#!/usr/bin/env node
/**
 * Extract one trimmed example of every live mux frame kind from the newest
 * work/probe/live-*.jsonl capture. Writes work/probe/LIVE-SAMPLES.md.
 *
 *   node scripts/extract-live.mjs
 */
import { readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

const DIR = resolve(process.cwd(), 'work', 'probe')
const files = readdirSync(DIR).filter((f) => f.startsWith('live-') && f.endsWith('.jsonl')).sort()
if (!files.length) { console.error('no live capture in', DIR); process.exit(1) }
const file = join(DIR, files[files.length - 1])
console.log('[extract] reading', file)

const lines = readFileSync(file, 'utf8').split('\n').filter(Boolean)
console.log('[extract] lines =', lines.length)

const trim = (value, depth = 0) => {
  if (value == null) return value
  if (typeof value === 'string') return value.length > 900 ? value.slice(0, 900) + `…(${value.length})` : value
  if (Array.isArray(value)) return value.slice(0, 6).map((v) => trim(v, depth + 1))
  if (typeof value === 'object') {
    const out = {}
    for (const [k, v] of Object.entries(value)) out[k] = depth > 6 ? '…' : trim(v, depth + 1)
    return out
  }
  return value
}

const kind = (stream, parsed) => {
  const v = parsed.value ?? parsed
  return `${stream}:${parsed.type}${v.type ? '/' + v.type : ''}${v.frame?.type ? ':' + v.frame.type : ''}${v.event?.type ? ':' + v.event.type : ''}`
}

const examples = new Map()
const counts = {}
for (const line of lines) {
  let row
  try { row = JSON.parse(line) } catch { continue }
  let parsed
  try { parsed = JSON.parse(row.text) } catch { continue }
  const k = kind(row.stream, parsed)
  counts[k] = (counts[k] ?? 0) + 1
  const key = k
  // Keep: every non-chunk kind once; every chunk sub-type once.
  const chunkType = parsed.value?.frame?.chunk?.type
  const dedupeKey = chunkType ? `${key}:${chunkType}` : key
  if (!examples.has(dedupeKey)) examples.set(dedupeKey, { row, parsed })
}

const out = []
const say = (s = '') => { out.push(s); console.log(s) }
say(`# Live mux frame samples — ${files[files.length - 1]}`)
say()
say(`Total lines: ${lines.length}`)
say()
say('| frame kind | count |')
say('| --- | --- |')
for (const [k, n] of Object.entries(counts).sort((a, b) => b[1] - a[1])) say(`| ${k} | ${n} |`)
say()
for (const [k, { parsed }] of examples.entries()) {
  say(`## ${k}`)
  say('```json')
  say(JSON.stringify(trim(parsed), null, 2))
  say('```')
  say()
}
writeFileSync(join(DIR, 'LIVE-SAMPLES.md'), out.join('\n'))
console.log('\n[extract] work/probe/LIVE-SAMPLES.md')
