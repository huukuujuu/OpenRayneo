// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
import {z} from 'zod'

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
  return {key: JSON.stringify([input.phoneId, input.conversationKey, preset]), phoneId: input.phoneId, conversationKey: input.conversationKey, preset}
}

export async function nativeSession(ctx, opening, scope, signal) {
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
  const created = await ctx.sessionController.create(scope.preset ? {agentPreset: scope.preset} : {})
  await bindings.put(scope.key, {sessionId: created.sessionId, phoneId: scope.phoneId, conversationKey: scope.conversationKey, preset: scope.preset, instructed: false})
  signal.throwIfAborted()
  return {sessionId: created.sessionId, instructed: false}
}

export async function markInstructed(opening, scope, sessionId) {
  const bindings = (await opening).table('bindings')
  const current = bindings.get(scope.key)
  if (current?.sessionId === sessionId && !current.instructed) await bindings.put(scope.key, {...current, instructed: true})
}
