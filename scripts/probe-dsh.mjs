#!/usr/bin/env node
/**
 * DS Bot - read-only DSH protocol probe (dependency-free).
 *
 * Signs the DSH browser cookie from %USERPROFILE%\.dsh\.credentials.yaml and
 * captures the real wire shapes the app consumes:
 *   - unary: session/list, session/modelCatalog, skills/list, llm/listProviders
 *   - WS mux: session/follow snapshot + events (+ assistant-stream if running)
 *   - WS mux: $events ready frame
 *
 * Read-only: never prompts, cancels or mutates. Captures land in work/probe/.
 *
 * Run with the DSH bundled Node:
 *   & "E:\Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\node\bin\node.exe" scripts\probe-dsh.mjs
 */
import { createHash, createHmac, randomBytes, randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join, resolve } from 'node:path'
import http from 'node:http'

const AUTHORITY = process.env.DSH_AUTHORITY || '127.0.0.1:64005'
const [HOSTNAME, PORT_TEXT] = AUTHORITY.split(':')
const PORT = Number(PORT_TEXT || 80)
const OUT = resolve(process.cwd(), 'work', 'probe')
mkdirSync(OUT, { recursive: true })

const b64url = (buf) => Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

function readSecret() {
  const yamlPath = process.env.DSH_CREDENTIALS || join(process.env.USERPROFILE || homedir(), '.dsh', '.credentials.yaml')
  const text = readFileSync(yamlPath, 'utf8')
  const block = text.split('client-connection/browser-session:')[1]
  if (!block) throw new Error(`no browser-session record in ${yamlPath}`)
  const m = block.match(/secret:\s*([A-Za-z0-9_-]+)/)
  if (!m) throw new Error(`no secret field in ${yamlPath}`)
  return Buffer.from(m[1], 'base64url')
}

function cookieFor(authority, secret) {
  const name = 'dsh-auth-' + b64url(createHash('sha256').update(authority).digest())
  const now = Date.now()
  const payload = { version: 1, authority, issuedAt: now, expiresAt: now + 7 * 24 * 3600 * 1000 }
  const body = b64url(JSON.stringify(payload))
  const sig = b64url(createHmac('sha256', secret).update(body).digest())
  return `${name}=v1.${body}.${sig}`
}

const secret = readSecret()
const cookie = cookieFor(AUTHORITY, secret)
console.log('[probe] authority =', AUTHORITY, '| cookie =', cookie.split('=')[0])

function save(name, value) {
  const p = join(OUT, name + '.json')
  writeFileSync(p, JSON.stringify(value, null, 2))
  console.log(`[save] ${name}.json (${(readFileSync(p).length / 1024).toFixed(1)} KiB)`)
}

async function rpc(method, args) {
  const rpcId = randomUUID()
  const res = await fetch(`http://${AUTHORITY}/api/${method}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', cookie, host: AUTHORITY, accept: 'application/json' },
    body: JSON.stringify({ type: 'client-request', rpcId, method, payload: { args: args ?? {} } }),
  })
  const text = await res.text()
  let json
  try { json = JSON.parse(text) } catch { json = { raw: text.slice(0, 400) } }
  console.log(`[rpc ] ${method} -> HTTP ${res.status} ok=${json?.result?.ok ?? '?'}${json?.result?.ok === false ? ' code=' + json.result.error?.code : ''}`)
  return json
}

// ---------------------------------------------------------------------------
// Minimal WebSocket client (RFC 6455) - no npm dependencies.
// ---------------------------------------------------------------------------
class RawWs {
  constructor(socket, head) {
    this.socket = socket
    this.buffer = head && head.length ? Buffer.from(head) : Buffer.alloc(0)
    this.handlers = { message: [], close: [] }
    this.closed = false
    socket.on('data', (chunk) => { this.buffer = Buffer.concat([this.buffer, chunk]); this.pump() })
    socket.on('close', () => { this.closed = true; for (const h of this.handlers.close) h() })
    socket.on('error', () => {})
    if (this.buffer.length) this.pump()
  }

  on(event, fn) { this.handlers[event].push(fn) }

  pump() {
    while (this.buffer.length >= 2) {
      const b0 = this.buffer[0]
      const b1 = this.buffer[1]
      const fin = (b0 & 0x80) !== 0
      const opcode = b0 & 0x0f
      const masked = (b1 & 0x80) !== 0
      let len = b1 & 0x7f
      let offset = 2
      if (len === 126) { if (this.buffer.length < 4) return; len = this.buffer.readUInt16BE(2); offset = 4 }
      else if (len === 127) { if (this.buffer.length < 10) return; len = Number(this.buffer.readBigUInt64BE(2)); offset = 10 }
      const maskLen = masked ? 4 : 0
      if (this.buffer.length < offset + maskLen + len) return
      let payload = this.buffer.subarray(offset + maskLen, offset + maskLen + len)
      if (masked) {
        const mask = this.buffer.subarray(offset, offset + 4)
        payload = Buffer.from(payload.map((byte, i) => byte ^ mask[i % 4]))
      }
      this.buffer = this.buffer.subarray(offset + maskLen + len)
      if (opcode === 1 || opcode === 2) { for (const h of this.handlers.message) h(payload.toString('utf8')) }
      else if (opcode === 8) { this.close(); return }
      else if (opcode === 9) { this.sendFrame(payload, 10) }
      void fin
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

  close() {
    if (this.closed) return
    try { this.sendFrame(Buffer.alloc(0), 8) } catch {}
    this.closed = true
    try { this.socket.end() } catch {}
  }
}

function wsConnect(path) {
  return new Promise((resolveP, rejectP) => {
    const key = randomBytes(16).toString('base64')
    const req = http.request({
      host: HOSTNAME,
      port: PORT,
      path,
      method: 'GET',
      headers: {
        Connection: 'Upgrade',
        Upgrade: 'websocket',
        'Sec-WebSocket-Version': '13',
        'Sec-WebSocket-Key': key,
        cookie,
        host: AUTHORITY,
      },
    })
    req.on('upgrade', (res, socket, head) => resolveP(new RawWs(socket, head)))
    req.on('response', (res) => rejectP(new Error('upgrade rejected HTTP ' + res.statusCode)))
    req.on('error', rejectP)
    req.end()
  })
}

/** Collect WS messages for `seconds`, sending client frames from `onOpen`. */
async function collect(path, onOpen, seconds, maxFrames = 200) {
  const ws = await wsConnect(path)
  const frames = []
  const done = new Promise((resolveP) => {
    const timer = setTimeout(() => resolveP(), seconds * 1000)
    ws.on('message', (text) => {
      let parsed
      try { parsed = JSON.parse(text) } catch { parsed = { raw: text.slice(0, 300) } }
      frames.push(parsed)
      if (frames.length === 1) console.log(`[ws  ] ${path} first frame: ${JSON.stringify(parsed).slice(0, 260)}`)
      if (frames.length >= maxFrames) { clearTimeout(timer); resolveP() }
    })
    ws.on('close', () => { clearTimeout(timer); resolveP() })
  })
  if (onOpen) await onOpen(ws)
  await done
  ws.close()
  return frames
}

function summarizeFrames(frames) {
  const types = {}
  for (const f of frames) {
    const key = `${f.type ?? '?'}${f.item?.type ? '/' + f.item.type : ''}${f.frame?.type ? '/' + f.frame.type : ''}`
    types[key] = (types[key] ?? 0) + 1
  }
  return types
}

// ---------------------------------------------------------------------------
// 1. Unary reads
// ---------------------------------------------------------------------------
const sessionList = await rpc('session/list', { _request: {} })
save('session-list', sessionList)
save('model-catalog', await rpc('session/modelCatalog', {}))
save('llm-providers', await rpc('llm/listProviders', {}))
save('skills-list', await rpc('skills/list', {}))

const items = sessionList?.result?.value?.items ?? []
console.log('[info] sessions =', items.length)
for (const s of items.slice(0, 10)) {
  console.log('       ', s.sessionId.slice(0, 8), '| running =', s.running, '| parent =', (s.parentSessionId ?? '-').slice(0, 8), '| title =', s.projections?.values?.title ?? '(untitled)')
}

// ---------------------------------------------------------------------------
// 2. $events stream (approvals / host.home)
// ---------------------------------------------------------------------------
try {
  const events = await collect('/api/remote.mux', (ws) => {
    ws.send(JSON.stringify({ type: 'open', streamId: 'events-1', endpoint: '$events', payload: {} }))
  }, 6, 20)
  save('events-frames', events)
  console.log('[info] $events frame types:', summarizeFrames(events))
} catch (err) {
  console.warn('[warn] $events failed:', err.message)
}

// ---------------------------------------------------------------------------
// 3. session/follow - snapshot + live events (also captures assistant-stream
//    when the newest session is mid-turn)
// ---------------------------------------------------------------------------
const live = items.find((s) => s.running) || items[0]
if (live) {
  console.log('[info] following session', live.sessionId, '(running =', live.running, ')')
  try {
    const frames = await collect('/api/remote.mux', (ws) => {
      ws.send(JSON.stringify({
        type: 'open',
        streamId: 'follow-1',
        endpoint: 'session/follow',
        payload: { args: { request: { address: { kind: 'session', sessionId: live.sessionId }, maxMessages: 50, assistantStream: true } } },
      }))
    }, 15, 120)
    save('session-follow-frames', frames)
    console.log('[info] follow frame types:', summarizeFrames(frames))
    const snapshot = frames.find((f) => f.item?.type === 'snapshot' || f.type === 'snapshot')
    if (snapshot) {
      const snap = snapshot.item ?? snapshot
      console.log('[info] snapshot keys:', Object.keys(snap))
      const records = snap.records ?? snap.snapshot?.records ?? []
      console.log('[info] snapshot records =', records.length)
      const kinds = {}
      for (const r of records.slice(-40)) kinds[r.type ?? '?'] = (kinds[r.type ?? '?'] ?? 0) + 1
      console.log('[info] recent record types:', kinds)
    }
  } catch (err) {
    console.warn('[warn] follow failed:', err.message)
  }
}

console.log('[probe] done - captures in', OUT)

