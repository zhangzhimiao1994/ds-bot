#!/usr/bin/env node
/**
 * Enumerate every session event type captured in work/probe/session-follow-frames.json
 * and print one trimmed example of each, so the Android protocol layer can be
 * written against real shapes.
 *
 *   node scripts/enumerate-events.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

const DIR = resolve(process.cwd(), 'work', 'probe')
const frames = JSON.parse(readFileSync(join(DIR, 'session-follow-frames.json'), 'utf8'))

const snapFrame = frames.find((f) => (f.value ?? f).type === 'snapshot')
const snap = snapFrame.value ?? snapFrame
const records = snap.records ?? []

const byType = new Map()
for (const r of records) {
  const ev = r.event ?? r
  const t = ev.type ?? '?'
  if (!byType.has(t)) byType.set(t, [])
  byType.get(t).push(ev)
}

const trim = (value, depth = 0) => {
  if (value == null) return value
  if (typeof value === 'string') return value.length > 600 ? value.slice(0, 600) + `…(${value.length})` : value
  if (Array.isArray(value)) return value.slice(0, 8).map((v) => trim(v, depth + 1))
  if (typeof value === 'object') {
    const out = {}
    for (const [k, v] of Object.entries(value)) {
      if (depth > 8) { out[k] = '…'; continue }
      out[k] = trim(v, depth + 1)
    }
    return out
  }
  return value
}

const lines = []
const say = (s = '') => { lines.push(s); console.log(s) }

say(`# Session event samples (${records.length} records, ${byType.size} types)`)
say()
say('| type | count |')
say('| --- | --- |')
for (const [t, list] of [...byType.entries()].sort((a, b) => b[1].length - a[1].length)) {
  say(`| ${t} | ${list.length} |`)
}
say()
for (const [t, list] of byType.entries()) {
  say(`## ${t}`)
  say('```json')
  say(JSON.stringify(trim(list[list.length - 1]), null, 2))
  say('```')
  say()
}

say('## assistantStream snapshot')
say('```json')
say(JSON.stringify(snap.assistantStream, null, 2))
say('```')
say()
say('## projections.values (trimmed)')
say('```json')
say(JSON.stringify(trim(snap.projections?.values ?? {}), null, 2))
say('```')

writeFileSync(join(DIR, 'EVENT-SAMPLES.md'), lines.join('\n'))
console.log('\n[events] work/probe/EVENT-SAMPLES.md')
