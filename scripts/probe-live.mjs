#!/usr/bin/env node
/**
 * DS Bot — long-running live capture.
 *
 * Keeps two mux streams open against the running DSH host and appends every
 * frame as JSONL, so the assistant-stream shape (live deltas) and the $events
 * waterfall can be inspected afterwards:
 *
 *   work/probe/live-<label>-<ts>.jsonl
 *
 * Usage:
 *   node scripts/probe-live.mjs [seconds] [label]
 *
 * Read-only: never prompts or answers anything.
 */
import { createHash, createHmac, randomBytes } from 'node:crypto'
import { createWriteStream, mkdirSync, readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join, resolve } from 'node:path'
import http from 'node:http'

const AUTHORITY = process.env.DSH_AUTHORITY || '127.0.0.1:64005'
const [HOSTNAME, PORT_TEXT] = AUTHORITY.split(':')
const PORT = Number(PORT_TEXT || 80)
const SECONDS = Number(process.argv[2] || 300)
const LABEL = process.argv[3] || 'live'
const OUT = resolve(process.cwd(), 'work', 'probe')
mkdirSync(OUT, { recursive: true })
const STAMP = new Date().toISOString().replace(/[:.]/g, '-')
const FILE = join(OUT, `live-${LABEL}-${STAMP}.jsonl`)
const out = createWriteStream(FILE, { flags: 'a' })

const b64url = (buf) => Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

function cookieValue() {
  const yamlPath = join(process.env.USERPROFILE || homedir(), '.dsh', '.credentials.yaml')
  const text = readFileSync(yamlPath, 'utf8')
  const block = text.split('client-connection/browser-session:')[1]
  const m = block.match(/secret:\s*([A-Za-z0-9_-]+)/)
  const secret = Buffer.from(m[1], 'base64url')
  const authority = AUTHORITY
  const name = 'dsh-auth-' + b64url(createHash('sha256').update(authority).digest())
  const now = Date.now()
  const payload = { version: 1, authority, issuedAt: now, expiresAt: now + 7 * 24 * 3600 * 1000 }
  const body = b64url(JSON.stringify(payload))
  const sig = b64url(createHmac('sha256', secret).update(body).digest())
  return `${name}=v1.${body}.${sig}`
}

const cookie = cookieValue()

class RawWs {
  constructor(socket, head, onMessage) {
    this.socket = socket
    this.buffer = head && head.length ? Buffer.from(head) : Buffer.alloc(0)
    this.onMessage = onMessage
    this.closed = false
    socket.on('data', (chunk) => { this.buffer = Buffer.concat([this.buffer, chunk]); this.pump() })
    socket.on('close', () => { this.closed = true })
    socket.on('error', () => {})
    if (this.buffer.length) this.pump()
  }
  pump() {
    while (this.buffer.length >= 2) {
      const b0 = this.buffer[0]; const b1 = this.buffer[1]
      const opcode = b0 & 0x0f
      let len = b1 & 0x7f; let offset = 2
      if (len === 126) { if (this.buffer.length < 4) return; len = this.buffer.readUInt16BE(2); offset = 4 }
      else if (len === 127) { if (this.buffer.length < 10) return; len = Number(this.buffer.readBigUInt64BE(2)); offset = 10 }
      const masked = (b1 & 0x80) !== 0
      const maskLen = masked ? 4 : 0
      if (this.buffer.length < offset + maskLen + len) return
      let payload = this.buffer.subarray(offset + maskLen, offset + maskLen + len)
      if (masked) { const mask = this.buffer.subarray(offset, offset + 4); payload = Buffer.from(payload.map((b, i) => b ^ mask[i % 4])) }
      this.buffer = this.buffer.subarray(offset + maskLen + len)
      if (opcode === 1) this.onMessage(payload.toString('utf8'))
      else if (opcode === 8) { this.close(); return }
      else if (opcode === 9) this.sendFrame(payload, 10)
    }
  }
  sendFrame(payload, opcode = 1) {
    if (this.closed) return
    const data = Buffer.isBuffer(payload) ? payload : Buffer.from(payload, 'utf8')
    const mask = randomBytes(4)
    let header
    if (data.length < 126) { header = Buffer.alloc(2); header[1] = 0x80 | data.length }
    else if (data.length < 65536) { header = Buffer.alloc(4); header[1] = 0x80 | 126; header.writeUInt16BE(data.length, 2) }
    else { header = Buffer.alloc(10); header[1] = 0x80 | 127; header.writeBigUInt64BE(BigInt(data.length), 2) }
    header[0] = 0x80 | opcode
    const masked = Buffer.alloc(data.length)
    for (let i = 0; i < data.length; i++) masked[i] = data[i] ^ mask[i % 4]
    this.socket.write(Buffer.concat([header, mask, masked]))
  }
  send(text) { this.sendFrame(Buffer.from(text, 'utf8'), 1) }
  close() { if (this.closed) return; this.closed = true; try { this.sendFrame(Buffer.alloc(0), 8) } catch {}; try { this.socket.end() } catch {} }
}

function wsConnect(onMessage) {
  return new Promise((resolveP, rejectP) => {
    const req = http.request({
      host: HOSTNAME, port: PORT, path: '/api/remote.mux', method: 'GET',
      headers: {
        Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Version': '13',
        'Sec-WebSocket-Key': randomBytes(16).toString('base64'), cookie, host: AUTHORITY,
      },
    })
    req.on('upgrade', (_res, socket, head) => resolveP(new RawWs(socket, head, onMessage)))
    req.on('response', (res) => rejectP(new Error('upgrade rejected HTTP ' + res.statusCode)))
    req.on('error', rejectP)
    req.end()
  })
}

async function rpc(method, args) {
  const res = await fetch(`http://${AUTHORITY}/api/${method}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', cookie, host: AUTHORITY },
    body: JSON.stringify({ type: 'client-request', rpcId: `probe-${Date.now()}`, method, payload: { args: args ?? {} } }),
  })
  return res.json()
}

const counts = {}
function record(stream, text) {
  const line = JSON.stringify({ t: Date.now(), stream, text })
  out.write(line + '\n')
  try {
    const parsed = JSON.parse(text)
    const v = parsed.value ?? parsed
    const key = `${stream}:${parsed.type}${v.type ? '/' + v.type : ''}${v.event?.type ? ':' + v.event.type : ''}${v.frame?.type ? ':' + v.frame.type : ''}`
    counts[key] = (counts[key] ?? 0) + 1
    if ((counts[key] ?? 0) <= 2) console.log(`[capture] ${key}`)
  } catch {}
}

console.log(`[live] authority=${AUTHORITY} seconds=${SECONDS} file=${FILE}`)

const list = await rpc('session/list', { _request: {} })
const items = list?.result?.value?.items ?? []
const running = items.find((s) => s.running)
const followTarget = running || items[0]
console.log('[live] running session =', running?.sessionId ?? '(none)', '| follow target =', followTarget?.sessionId)

const eventsWs = await wsConnect((text) => record('events', text))
eventsWs.send(JSON.stringify({ type: 'open', streamId: 'events-1', endpoint: '$events', payload: { args: {} } }))

let followWs = null
if (followTarget) {
  followWs = await wsConnect((text) => record('follow', text))
  followWs.send(JSON.stringify({
    type: 'open', streamId: 'follow-1', endpoint: 'session/follow',
    payload: { args: { request: { address: { kind: 'session', sessionId: followTarget.sessionId }, maxMessages: 20, assistantStream: true } } },
  }))
}

const timer = setInterval(() => {
  console.log('[live] frames so far:', JSON.stringify(counts))
}, 30000)

setTimeout(() => {
  clearInterval(timer)
  console.log('[live] done. counts =', JSON.stringify(counts, null, 2))
  console.log('[live] file =', FILE)
  try { followWs?.close() } catch {}
  try { eventsWs.close() } catch {}
  out.end()
  process.exit(0)
}, SECONDS * 1000)
