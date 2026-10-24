#!/usr/bin/env node
/**
 * DS Bot — end-to-end protocol test against the *real* host bridge.
 *
 * Exercises exactly the same wire contract the Android app uses:
 *   1. rejected without / with wrong token (401)
 *   2. forbidden admin method (settings/describe → 403)
 *   3. $events ready frame → host.home
 *   4. session/create → session/list → session/prompt
 *   5. session/follow: snapshot + live assistant-stream + assistant/message
 *   6. prints the assistant answer (real model call, one tiny prompt)
 *
 * Usage:
 *   node scripts/e2e-app-protocol.mjs --bridge http://127.0.0.1:8788 --token <token> [--no-prompt]
 *
 * Read-only except one throwaway session + one short prompt (unless --no-prompt).
 */
import { randomUUID, randomBytes } from 'node:crypto'
import http from 'node:http'

const args = process.argv.slice(2)
const opt = (name, fallback = null) => {
  const i = args.indexOf('--' + name)
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : fallback
}
const BRIDGE = (opt('bridge', 'http://127.0.0.1:8788')).replace(/\/$/, '')
const TOKEN = opt('token')
const NO_PROMPT = args.includes('--no-prompt')
if (!TOKEN) { console.error('missing --token'); process.exit(2) }

const url = new URL(BRIDGE)
const HOSTNAME = url.hostname
const PORT = Number(url.port || 80)
const AUTH = { authorization: 'Bearer ' + TOKEN }
let failures = 0
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
  if (!ok) failures++
}

async function rpc(method, argsObj, { auth = AUTH } = {}) {
  const res = await fetch(`${BRIDGE}/api/${method}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth },
    body: JSON.stringify({ type: 'client-request', rpcId: randomUUID(), method, payload: { args: argsObj ?? {} } }),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch {}
  return { status: res.status, json, text }
}

// --- raw WS (same framing the app's OkHttp client speaks) -------------------
class RawWs {
  constructor(socket, head, onMessage, onClose) {
    this.socket = socket
    this.buffer = head?.length ? Buffer.from(head) : Buffer.alloc(0)
    this.onMessage = onMessage
    this.onClose = onClose
    this.closed = false
    socket.on('data', (chunk) => { this.buffer = Buffer.concat([this.buffer, chunk]); this.pump() })
    socket.on('close', () => { this.closed = true; onClose?.() })
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
      const masked = (b1 & 0x80) !== 0; const maskLen = masked ? 4 : 0
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
  close() { if (this.closed) return; this.closed = true; try { this.socket.end() } catch {} }
}

function wsConnect(onMessage, onClose) {
  return new Promise((resolveP, rejectP) => {
    const req = http.request({
      host: HOSTNAME, port: PORT, path: '/api/remote.mux', method: 'GET',
      headers: {
        Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Version': '13',
        'Sec-WebSocket-Key': randomBytes(16).toString('base64'),
        host: `${HOSTNAME}:${PORT}`,
        authorization: 'Bearer ' + TOKEN,
      },
    })
    req.on('upgrade', (_res, socket, head) => resolveP(new RawWs(socket, head, onMessage, onClose)))
    req.on('response', (res) => rejectP(new Error('WS upgrade rejected HTTP ' + res.statusCode)))
    req.on('error', rejectP)
    req.end()
  })
}

// --- 1. auth ---------------------------------------------------------------
{
  const noAuth = await rpc('session/list', { _request: {} }, { auth: {} })
  check('no token → 401', noAuth.status === 401, 'HTTP ' + noAuth.status)
  const badAuth = await rpc('session/list', { _request: {} }, { auth: { authorization: 'Bearer wrong-token' } })
  check('wrong token rejected', badAuth.status === 401 || badAuth.status === 429, 'HTTP ' + badAuth.status)
}

// --- 2. method allowlist ---------------------------------------------------
{
  const forbidden = await rpc('settings/describe', {})
  check('settings/describe → 403', forbidden.status === 403, 'HTTP ' + forbidden.status + ' ' + forbidden.text.slice(0, 120))
  const creds = await rpc('credentials/describe', { refs: [] })
  check('credentials/describe → 403', creds.status === 403, 'HTTP ' + creds.status)
}

// --- 3. $events ready ------------------------------------------------------
let hostHome = null
let ws = null
{
  const ready = new Promise((resolveP) => {
    const timer = setTimeout(() => resolveP(false), 8000)
    ws = null
    wsConnect((text) => {
      const frame = JSON.parse(text)
      const value = frame.value
      if (value?.type === 'ready') {
        hostHome = value.host?.home ?? null
        clearTimeout(timer)
        resolveP(true)
      }
    }, () => {}).then((socket) => {
      ws = socket
      socket.send(JSON.stringify({ type: 'open', streamId: 'events', endpoint: '$events', payload: { args: {} } }))
    }).catch(() => resolveP(false))
  })
  const ok = await ready
  check('$events ready frame', ok, 'host.home=' + hostHome)
}

// --- 4. create + prompt ----------------------------------------------------
let sessionId = null
if (!NO_PROMPT) {
  const created = await rpc('session/create', { request: hostHome ? { cwd: hostHome } : {} })
  sessionId = created.json?.result?.value?.sessionId ?? null
  check('session/create', created.status === 200 && !!sessionId, created.text.slice(0, 160))

  if (sessionId) {
    await rpc('session/rename', { request: { sessionId, title: 'DS Bot E2E ' + new Date().toISOString() } })
    const requestId = randomUUID()
    const prompt = await rpc('session/prompt', {
      request: {
        requestId, sessionId, mode: 'queue',
        content: [{ type: 'text', text: '请只回答两个字：你好' }],
      },
    })
    check('session/prompt accepted', prompt.status === 200 && prompt.json?.result?.ok === true, prompt.text.slice(0, 160))

    // --- 5. follow and capture the reply -----------------------------------
    const streamed = { text: '', reasoning: '', events: 0, assistant: '', finished: false }
    const done = new Promise((resolveP) => {
      const timer = setTimeout(() => resolveP('timeout'), 200_000)
      wsConnect((text) => {
        const frame = JSON.parse(text)
        if (frame.type === 'end' && frame.streamId === 'follow') { clearTimeout(timer); resolveP('end') }
        const value = frame.value
        if (!value) return
        if (value.type === 'assistant-stream') {
          const f = value.frame
          if (f?.type === 'chunk') {
            if (f.chunk?.type === 'text-delta') streamed.text += f.chunk.text
            if (f.chunk?.type === 'reasoning-delta') streamed.reasoning += f.chunk.text
            if (f.chunk?.type === 'block-end' && f.chunk.block?.type === 'text') streamed.text = f.chunk.block.text
          }
          if (f?.type === 'end') streamed.finished = true
        }
        if (value.type === 'event') {
          streamed.events++
          const ev = value.event
          if (ev?.type === 'assistant/message') {
            const parts = (ev.data?.message?.content ?? []).filter((b) => b.type === 'text').map((b) => b.text)
            if (parts.length) streamed.assistant = parts.join('\n')
          }
          if (ev?.type === 'turn/end') { clearTimeout(timer); resolveP('turn-end') }
        }
      }, () => {}).then((socket) => {
        socket.send(JSON.stringify({
          type: 'open', streamId: 'follow', endpoint: 'session/follow',
          payload: { args: { request: { address: { kind: 'session', sessionId }, maxMessages: 20, assistantStream: true } } },
        }))
      }).catch(() => resolveP('ws-error'))
    })
    const reason = await done
    const reply = (streamed.assistant || streamed.text).trim()
    check('follow streamed a reply', reply.length > 0, `reason=${reason} streamed=${streamed.text.length}B final=${streamed.assistant.length}B events=${streamed.events}`)
    console.log('--- assistant reply ---')
    console.log(reply || '(empty)')
    console.log('-----------------------')
  }
}

ws?.close()
console.log(failures === 0 ? '\nE2E_PASSED' : `\nE2E_FAILED (${failures} failures)`)
process.exit(failures === 0 ? 0 : 1)
