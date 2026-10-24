#!/usr/bin/env node
/**
 * Harvest the DSH desktop web UI design tokens so the Android app can match it.
 *
 * Fetches `/` (with a signed DSH cookie), follows local asset links (CSS/JS),
 * and extracts CSS custom properties, font stacks and radius/shadow values into
 * work/probe/WEB-UI-TOKENS.md. Read-only.
 *
 *   node scripts/fetch-web-ui.mjs
 */
import { createHash, createHmac } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join, resolve } from 'node:path'

const AUTHORITY = process.env.DSH_AUTHORITY || '127.0.0.1:64005'
const BASE = `http://${AUTHORITY}`
const OUT = resolve(process.cwd(), 'work', 'probe', 'web')
mkdirSync(OUT, { recursive: true })

const b64url = (buf) => Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

function cookieValue() {
  const yamlPath = join(process.env.USERPROFILE || homedir(), '.dsh', '.credentials.yaml')
  const text = readFileSync(yamlPath, 'utf8')
  const block = text.split('client-connection/browser-session:')[1]
  const m = block.match(/secret:\s*([A-Za-z0-9_-]+)/)
  const secret = Buffer.from(m[1], 'base64url')
  const name = 'dsh-auth-' + b64url(createHash('sha256').update(AUTHORITY).digest())
  const now = Date.now()
  const payload = { version: 1, authority: AUTHORITY, issuedAt: now, expiresAt: now + 7 * 24 * 3600 * 1000 }
  const body = b64url(JSON.stringify(payload))
  const sig = b64url(createHmac('sha256', secret).update(body).digest())
  return `${name}=v1.${body}.${sig}`
}

const cookie = cookieValue()

async function get(path) {
  const url = path.startsWith('http') ? path : BASE + path
  const res = await fetch(url, { headers: { cookie, host: AUTHORITY } })
  const text = await res.text()
  return { status: res.status, type: res.headers.get('content-type') || '', text }
}

const index = await get('/')
console.log('[web] / ->', index.status, index.type, index.text.length, 'bytes')
writeFileSync(join(OUT, 'index.html'), index.text)

const assets = new Set()
for (const m of index.text.matchAll(/(?:src|href)="([^"]+)"/g)) {
  const href = m[1]
  if (href.endsWith('.js') || href.endsWith('.css') || href.includes('/assets/')) assets.add(href)
}
console.log('[web] assets:', [...assets].join(', '))

const cssTexts = []
const jsTexts = []
for (const href of [...assets].slice(0, 40)) {
  try {
    const path = href.startsWith('./') ? href.slice(1) : href
    const r = await get(path)
    const name = path.split('/').pop().split('?')[0]
    writeFileSync(join(OUT, name), r.text)
    if (r.type.includes('css') || name.endsWith('.css')) cssTexts.push(r.text)
    else if (name.endsWith('.js')) jsTexts.push(r.text)
    console.log('[web]', name, r.status, r.text.length)
  } catch (e) { console.warn('[web] asset failed', href, e.message) }
}

// Some apps inject the stylesheet from JS; also probe the boot payload.
const boot = index.text.match(/__DSH_BOOT__\s*=\s*(\{.*?\});/s)
if (boot) writeFileSync(join(OUT, 'boot.json'), boot[1].slice(0, 200000))
writeFileSync(join(OUT, 'all-css.txt'), cssTexts.join('\n\n'))
writeFileSync(join(OUT, 'all-js.txt'), jsTexts.join('\n\n'))

const allCss = cssTexts.join('\n')
const allText = allCss + '\n' + jsTexts.join('\n')

const vars = new Map()
for (const m of allText.matchAll(/(--[a-zA-Z0-9-_]+)\s*:\s*([^;{}"'`]+)/g)) {
  const key = m[1]
  const value = m[2].trim().slice(0, 120)
  if (!vars.has(key)) vars.set(key, value)
}
const interesting = [...vars.entries()].filter(([k]) =>
  /color|bg|background|fg|text|accent|primary|surface|border|radius|font|shadow|spacing|gap|sidebar|panel|code|link|danger|warn|success/i.test(k))

const fonts = new Set()
for (const m of allText.matchAll(/font-family\s*:\s*([^;{}]+)/g)) fonts.add(m[1].trim().slice(0, 160))
const radii = new Set()
for (const m of allText.matchAll(/border-radius\s*:\s*([^;{}]+)/g)) radii.add(m[1].trim().slice(0, 60))
const shadows = new Set()
for (const m of allText.matchAll(/box-shadow\s*:\s*([^;{}]+)/g)) shadows.add(m[1].trim().slice(0, 120))

const out = []
const say = (s = '') => { out.push(s); console.log(s) }
say('# DSH web UI design tokens (harvested)')
say()
say(`source: ${BASE}/ (HTTP ${index.status}), assets: ${assets.size}`)
say()
say('## CSS custom properties')
say('```css')
for (const [k, v] of interesting.sort()) say(`  ${k}: ${v};`)
say('```')
say()
say('## font-family values')
say('```')
for (const f of [...fonts].slice(0, 30)) say(f)
say('```')
say()
say('## border-radius values')
say('```')
for (const r of [...radii].slice(0, 40)) say(r)
say('```')
say()
say('## box-shadow values')
say('```')
for (const s of [...shadows].slice(0, 25)) say(s)
say('```')
say()
say('## html/body/heading snippets from index')
const styleBits = [...index.text.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]).join('\n')
say('```css')
say((styleBits || '(no inline style)').slice(0, 6000))
say('```')

writeFileSync(join(OUT, 'TOKENS.md'), out.join('\n'))
console.log('\n[web] tokens ->', join(OUT, 'TOKENS.md'))

