#!/usr/bin/env node
/**
 * Summarize the captures produced by scripts/probe-dsh.mjs into the compact
 * protocol notes the Android client needs (docs/PROTOCOL.md is written from
 * this output).
 *
 *   node scripts/summarize-probe.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

const DIR = resolve(process.cwd(), 'work', 'probe')
const read = (name) => JSON.parse(readFileSync(join(DIR, name + '.json'), 'utf8'))

const lines = []
const say = (s = '') => { lines.push(s); console.log(s) }

// --- model catalog ---------------------------------------------------------
try {
  const cat = read('model-catalog').result.value
  say('## modelCatalog')
  say(`default: ${JSON.stringify(cat.default)}`)
  say(`routableProviders: ${JSON.stringify(cat.routableProviders)}`)
  for (const g of cat.groups ?? []) {
    say(`group ${g.id} (${g.name}): ${(g.models ?? []).map((m) => m.id + (m.reasoning ? '[' + m.reasoning.efforts.map((e) => e.id).join('/') + ']' : '')).join(', ')}`)
  }
  say()
} catch (e) { say('[model-catalog] ' + e.message) }

// --- session list ---------------------------------------------------------
try {
  const list = read('session-list').result.value
  say('## session/list item shape')
  const s = (list.items ?? [])[0] ?? {}
  say('item keys: ' + Object.keys(s).join(', '))
  say('projections.values keys: ' + Object.keys(s.projections?.values ?? {}).join(', '))
  say('sample: ' + JSON.stringify({
    sessionId: s.sessionId, running: s.running, blank: s.blank,
    parentSessionId: s.parentSessionId ?? null, cwd: s.cwd,
    title: s.projections?.values?.title ?? null,
    lastPromptAt: s.projections?.values?.sessionListMetadata?.lastPromptAt ?? null,
  }))
  say()
} catch (e) { say('[session-list] ' + e.message) }

// --- follow frames --------------------------------------------------------
try {
  const frames = read('session-follow-frames')
  say(`## session/follow frames (${frames.length})`)
  const kinds = {}
  for (const f of frames) {
    const v = f.value ?? f
    const k = `${f.type}/${v.type}${v.event?.type ? ':' + v.event.type : ''}${v.frame?.type ? ':' + v.frame.type : ''}`
    kinds[k] = (kinds[k] ?? 0) + 1
  }
  say('frame kinds: ' + JSON.stringify(kinds, null, 2))

  const snapFrame = frames.find((f) => (f.value ?? f).type === 'snapshot')
  if (snapFrame) {
    const snap = snapFrame.value ?? snapFrame
    say('snapshot keys: ' + Object.keys(snap).join(', '))
    say('header: ' + JSON.stringify(snap.header))
    say('cursor: ' + JSON.stringify(snap.cursor))
    say('hasMore: ' + snap.hasMore + ' | records: ' + (snap.records ?? []).length)
    const types = {}
    for (const r of snap.records ?? []) types[r.type] = (types[r.type] ?? 0) + 1
    say('record types: ' + JSON.stringify(types, null, 2))
    say('assistantStream: ' + JSON.stringify(snap.assistantStream))
    // Show one example of every record type (trimmed).
    const seen = new Set()
    for (const r of snap.records ?? []) {
      if (seen.has(r.type)) continue
      seen.add(r.type)
      say(`\n### record ${r.type}\n` + JSON.stringify(r, null, 2).slice(0, 2500))
    }
    say('\n### projections.values\n' + JSON.stringify(snap.projections?.values, null, 2).slice(0, 4000))
  }

  // Every event frame type + one example each.
  const seenEvents = new Set()
  for (const f of frames) {
    const v = f.value ?? f
    if (v.type !== 'event') continue
    const t = v.event?.type
    if (!t || seenEvents.has(t)) continue
    seenEvents.add(t)
    say(`\n### event ${t}\n` + JSON.stringify(v.event, null, 2).slice(0, 2500))
  }

  // assistant-stream frames (live text deltas)
  const streamFrames = frames.filter((f) => (f.value ?? f).type === 'assistant-stream')
  say(`\n### assistant-stream frames: ${streamFrames.length}`)
  for (const f of streamFrames.slice(0, 3)) {
    const v = f.value ?? f
    say(JSON.stringify({ type: v.type, frame: v.frame }, null, 2).slice(0, 3000))
  }
  say()
} catch (e) { say('[follow] ' + e.message) }

// --- $events --------------------------------------------------------------
try {
  const ev = read('events-frames')
  say('## $events frames: ' + JSON.stringify(ev.map((f) => f.type)))
  for (const f of ev.slice(0, 3)) say(JSON.stringify(f, null, 2).slice(0, 2000))
  say()
} catch (e) { say('[events] ' + e.message) }

writeFileSync(join(DIR, 'PROTOCOL-SUMMARY.md'), lines.join('\n'))
console.log('\n[summary] work/probe/PROTOCOL-SUMMARY.md')
