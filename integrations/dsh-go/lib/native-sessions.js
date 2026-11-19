// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
import {z} from 'zod'
import {mkdir} from 'node:fs/promises'
import {homedir} from 'node:os'
import {join, resolve} from 'node:path'

export const DEFAULT_SESSION_FOLDER = 'OpenRayneo'
/** The folder setting as typed; blank (e.g. a config saved before the field existed) means the default. */
export function folderSetting(config) { return (config.sessionFolder ?? '').trim() || DEFAULT_SESSION_FOLDER }
/** The absolute root folder of OpenRayneo sessions. Relative paths are under the home folder, not the engine's working folder. */
export function sessionFolder(config) {
  const folder = folderSetting(config)
  if (folder === '~' || folder.startsWith('~/') || folder.startsWith('~\\')) return resolve(join(homedir(), folder.slice(1)))
  return resolve(homedir(), folder)
}

/** Sub-folder (and DSH workspace) for each glasses feature, so DSH lists the sessions per feature. */
const FEATURE_FOLDERS = {
  NATIVE: 'RayNeo 깨우기', live_answers: 'RayNeo 실시간 답변', REALTIME: 'RayNeo 실시간 힌트',
  ALWAYS_ON: 'RayNeo 종일 기록', task: 'RayNeo 작업',
}
/** The feature of a request: the voice source, Live answers by its mode, anything else a plain task. */
export function featureOf(request, facts) {
  const source = request.input?.source
  if (source === 'REALTIME' && facts?.mode === 'live_answers') return 'live_answers'
  return source || 'task'
}
// Only known features get their own folder: a value sent by the app never becomes a path.
function featureFolder(feature) { return FEATURE_FOLDERS[feature] ?? FEATURE_FOLDERS.task }

/**
 * Options for a new OpenRayneo session: the configured preset, and the feature's folder (created on first use) as a DSH
 * workspace, so DSH Go lists it as a folder rather than relying on the cwd alone.
 */
export async function sessionOptions(ctx, config, preset = config.agentPreset, feature = 'task') {
  const options = preset ? {agentPreset: preset} : {}
  const base = sessionFolder(config)
  if (!base) return options
  const cwd = join(base, featureFolder(feature))
  try { await mkdir(cwd, {recursive: true}) }
  catch (error) {
    ctx.logger?.warn?.(`OpenRayneo 세션 폴더를 만들 수 없어 DSH 기본 폴더를 씁니다: ${cwd} (${error.message})`)
    return options
  }
  const registry = ctx.get('workspaceRegistry')
  if (!registry) { ctx.logger?.warn?.(`DSH 작업 공간 서비스가 없어 폴더만 지정합니다: ${cwd}`); return {...options, cwd} }
  try {
    const workspace = await registry.resolveByPath(cwd) ?? await registry.create(cwd)
    ctx.logger?.info?.(`OpenRayneo 세션 폴더: ${cwd} (작업 공간 ${workspace.id})`)
    return {...options, workspaceId: workspace.id}
  } catch (error) {
    ctx.logger?.warn?.(`OpenRayneo 폴더를 DSH 작업 공간으로 등록하지 못했습니다: ${cwd} (${error.message})`)
    return {...options, cwd}
  }
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
  // The key holds the setting as typed. 'workspace' keeps the 0.1.12+ wake sessions (own DSH workspace) apart from older
  // bindings; 'home' (0.1.15) apart from 0.1.12–0.1.14, whose relative folder followed the engine's working folder.
  const folder = folderSetting(config)
  const key = JSON.stringify([input.phoneId, input.conversationKey, preset, folder, 'workspace', 'home'])
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
  const options = await sessionOptions(ctx, config, scope.preset, 'NATIVE')
  const created = await ctx.sessionController.create(options)
  // Created in the DSH default folder because the configured one failed: do not bind it, so the next wake tries the folder again.
  if (scope.folder && !options.cwd && !options.workspaceId) { signal.throwIfAborted(); return {sessionId: created.sessionId, instructed: false} }
  await bindings.put(scope.key, {sessionId: created.sessionId, phoneId: scope.phoneId, conversationKey: scope.conversationKey, preset: scope.preset, instructed: false})
  signal.throwIfAborted()
  return {sessionId: created.sessionId, instructed: false}
}

export async function markInstructed(opening, scope, sessionId) {
  const bindings = (await opening).table('bindings')
  const current = bindings.get(scope.key)
  if (current?.sessionId === sessionId && !current.instructed) await bindings.put(scope.key, {...current, instructed: true})
}
