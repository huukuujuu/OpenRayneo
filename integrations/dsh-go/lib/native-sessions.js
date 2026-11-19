// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
import {z} from 'zod'
import {mkdir} from 'node:fs/promises'
import {homedir} from 'node:os'
import {join, resolve} from 'node:path'

/** The absolute folder OpenRayneo sessions work in, or '' for the DSH default. Relative paths follow the folder DSH was started in. */
export function sessionFolder(config) {
  const folder = (config.sessionFolder ?? '').trim()
  if (!folder) return ''
  if (folder === '~' || folder.startsWith('~/') || folder.startsWith('~\\')) return resolve(join(homedir(), folder.slice(1)))
  return resolve(folder)
}

/** Options for a new OpenRayneo session: the configured preset and folder (created on first use). */
export async function sessionOptions(ctx, config, preset = config.agentPreset) {
  const options = preset ? {agentPreset: preset} : {}
  const cwd = sessionFolder(config)
  if (!cwd) return options
  try { await mkdir(cwd, {recursive: true}) }
  catch (error) {
    ctx.logger?.warn?.(`OpenRayneo 세션 폴더를 만들 수 없어 DSH 기본 폴더를 씁니다: ${cwd} (${error.message})`)
    return options
  }
  return {...options, cwd}
}

// Plugin-owned metadata only: no tokens, transcripts, or model credentials.
export const nativeSessionDomain = {
  name: 'openrayneo_native_sessions', version: 1,
  tables: {bindings: {valueSchema: z.object({sessionId: z.string(), phoneId: z.string(), conversationKey: z.string(), preset: z.string(),
    // Set once a turn carrying the RayNeo instructions was queued; bindings from 0.1.8 sent them every turn.
    instructed: z.boolean().optional()})}},
}

export function nativeScope(ctx, config, request) {
  const input = request.input
  if (request.kind !== 'task' || input?.source !== 'NATIVE' || input.backfill || !input.phoneId || !input.conversationKey) return
  const preset = config.agentPreset || ctx.get('agentPresets')?.defaultId || ''
  // A configured folder starts its own sessions there; an empty folder keeps the 0.1.10 key and its existing sessions.
  // The key holds the setting as typed, so a relative folder keeps its conversation when DSH starts elsewhere.
  const folder = (config.sessionFolder ?? '').trim()
  const key = JSON.stringify(folder ? [input.phoneId, input.conversationKey, preset, folder] : [input.phoneId, input.conversationKey, preset])
  return {key, phoneId: input.phoneId, conversationKey: input.conversationKey, preset, folder}
}

export async function nativeSession(ctx, config, opening, scope, signal) {
  const bindings = (await opening).table('bindings')
  signal.throwIfAborted()
  const current = bindings.get(scope.key)
  if (current) {
    try {
      // Cold-safe read. A temporary error never causes creation or prompt replay.
      await ctx.sessionController.state({sessionId: current.sessionId}, signal)
      return {sessionId: current.sessionId, instructed: current.instructed ?? true}
    } catch (error) {
      if (signal.aborted || error.code !== 'session/not-found') throw error
    }
  }
  const options = await sessionOptions(ctx, config, scope.preset)
  const created = await ctx.sessionController.create(options)
  // Created in the DSH default folder because the configured one failed: do not bind it, so the next wake tries the folder again.
  if (scope.folder && !options.cwd) { signal.throwIfAborted(); return {sessionId: created.sessionId, instructed: false} }
  await bindings.put(scope.key, {sessionId: created.sessionId, phoneId: scope.phoneId, conversationKey: scope.conversationKey, preset: scope.preset, instructed: false})
  signal.throwIfAborted()
  return {sessionId: created.sessionId, instructed: false}
}

export async function markInstructed(opening, scope, sessionId) {
  const bindings = (await opening).table('bindings')
  const current = bindings.get(scope.key)
  if (current?.sessionId === sessionId && !current.instructed) await bindings.put(scope.key, {...current, instructed: true})
}
