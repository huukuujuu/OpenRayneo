// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
import z from '@deepseek-ai/schemastery'
import { defineTool } from '@deepseek-ai/dsh-tools'
import { setTimeout as delay } from 'node:timers/promises'
import { createApi } from './api.js'
import { startHarness } from './harness.js'
import {nativeSessionDomain, sessionFolder} from './native-sessions.js'
import pkg from '../package.json' with {type: 'json'}

export const name = 'openrayneo'
export const inject = ['tools', 'sessionController', 'agents', 'llm', 'agentDefaultModel', 'storageDomain']
export const Config = z.object({
  baseUrl: z.string().default('http://127.0.0.1:8766').description('OpenRayneo local API address / 로컬 엔드포인트 주소'),
  tokenEnv: z.string().role('credential-ref').default('OPENRAYNEO_TOKEN').volatile().description('OpenRayneo access token / 로컬 액세스 토큰 자격 증명 이름'),
  receiveVoiceTasks: z.boolean().default(true).description('Receive voice tasks / 안경 음성 작업 수신'),
  agentPreset: z.string().default('').description('Optional DSH agent preset / 선택 사항: DSH 프리셋'),
  sessionFolder: z.string().default('OpenRayneo').description('Folder the OpenRayneo DSH sessions work in; relative to the folder DSH was started in, ~ is home, empty keeps the DSH default / OpenRayneo DSH 세션 작업 폴더 (상대 경로는 DSH를 실행한 폴더 기준, ~는 홈, 비우면 DSH 기본)'),
})

function clip(text, length) { return Array.from(text).slice(0, length).join('') }
function toast(text) { return [{type: 'toast', text: clip(String(text).replace(/\s+/g, ' ').trim(), 40)}] }

// Bound the canonical result too, so neither model context nor native history stores an unlimited response.
function boundedResult(value) {
  const json = JSON.stringify(value)
  const total = Buffer.byteLength(json)
  if (total <= 8192) return value
  const chars = Array.from(json)
  const result = {truncated: true, total_bytes: total, preview: '', hint: '결과가 잘렸습니다. 조회 범위를 줄이세요. 표시되지 않은 내용을 이것으로 확정하지 마세요'}
  let low = 0, high = Math.min(chars.length, 8192)
  while (low < high) {
    const middle = Math.ceil((low + high) / 2)
    result.preview = chars.slice(0, middle).join('')
    if (Buffer.byteLength(JSON.stringify(result)) <= 8192) low = middle
    else high = middle - 1
  }
  result.preview = chars.slice(0, low).join('')
  return result
}

export async function apply(ctx, config) {
  const lifetime = new AbortController()
  const {api, base, tokenRef} = createApi(ctx, config, lifetime.signal)
  let uiBusy = false
  let deviceTail = Promise.resolve()

  async function callTool(toolName, args = {}, callerSignal) {
    const signal = AbortSignal.any([lifetime.signal, AbortSignal.timeout(50_000), ...(callerSignal ? [callerSignal] : [])])
    signal.throwIfAborted()
    const before = deviceTail
    const slot = Promise.withResolvers()
    deviceTail = before.then(() => slot.promise)
    const aborted = Promise.withResolvers()
    const onAbort = () => aborted.reject(signal.reason)
    signal.addEventListener('abort', onAbort, {once: true})
    try {
      await Promise.race([before, aborted.promise])
      signal.throwIfAborted()
      return await runTool(toolName, args, signal)
    } finally {
      slot.resolve()
      signal.removeEventListener('abort', onAbort)
    }
  }

  async function runTool(toolName, args, signal) {
    let task
    try {
      task = await api('/v1/tools/call', {body: {name: toolName, arguments: args}, signal})
    } catch (error) {
      if (error.transient) throw new Error('제출 결과가 확인되지 않았습니다. 먼저 상태를 조회하고 같은 작업을 다시 보내지 마세요')
      throw error
    }
    try {
      let failures = 0
      while (task.state === 'running') {
        await delay(300, undefined, {signal})
        try {
          task = await api(`/v1/tasks/${encodeURIComponent(task.id)}`, {signal})
          failures = 0
        } catch (error) {
          if (!error.transient || ++failures > 2) throw error
          await delay(500 * failures, undefined, {signal})
        }
      }
      if (task.state !== 'completed') throw new Error(task.error || `OpenRayneo task ${task.state}`)
      return task.result
    } catch (error) {
      if (task.id && task.state === 'running') {
        // Cancels remaining writes, never replays the original tool call.
        await api(`/v1/tasks/${encodeURIComponent(task.id)}/cancel`, {body: {}, timeoutMs: 2_000, cleanup: true}).catch(() => {})
      }
      throw error
    }
  }

  const output = {schema: {type: 'object', additionalProperties: true},
    render: (_args, value) => [{type: 'text', text: JSON.stringify(value)}]}
  ctx.tools.register(defineTool({
    name: 'rayneo_status', description: 'Read current OpenRayneo connection and activity. No audio or model keys.',
    parameters: {}, output, timeoutMs: 6_000, isConcurrencySafe: () => true,
    execute: (_args, exec) => api('/v1/status', {signal: exec.signal}),
  }))
  ctx.tools.register(defineTool({
    name: 'rayneo_tools', description: 'List actual OpenRayneo tool schemas before controlling glasses, todos, calendar or weather.',
    parameters: {}, output, timeoutMs: 6_000, isConcurrencySafe: () => true,
    execute: (_args, exec) => api('/v1/tools', {signal: exec.signal}),
  }))
  ctx.tools.register(defineTool({
    name: 'rayneo_call',
    description: 'Call a tool returned by rayneo_tools. Todo/calendar writes require a stable request_id. Never replay an uncertain write; read back first. Results are capped at 8 KiB; truncated=true means narrow the query. Only modify explicitly requested content. Queued results need device confirmation.',
    parameters: {
      name: {type: 'string', required: true, description: 'Exact tool name from rayneo_tools'},
      arguments: {type: 'object', required: true, additionalProperties: true, description: 'Arguments conforming to the selected tool schema'},
    }, output, timeoutMs: 55_000, isConcurrencySafe: () => false,
    execute: async (args, exec) => boundedResult(await callTool(args.name, args.arguments, exec.signal)),
  }))

  // Shows in the DSH engine log which plugin code is loaded and where its sessions go (the engine keeps old code until restarted).
  ctx.logger?.info?.(`OpenRayneo 플러그인 ${pkg.version} 시작 · 세션 폴더 ${sessionFolder(config) || 'DSH 기본'}`)
  const opening = config.receiveVoiceTasks ? await ctx.storageDomain.open(nativeSessionDomain) : undefined
  const harness = startHarness(ctx, config, api, lifetime.signal, opening)
  ctx.effect(() => () => {
    lifetime.abort()
    return harness.done.finally(async () => { if (opening) await (await opening).close() })
  })

  // All network actions acknowledge immediately; their work uses the bridge's deferred cancellation signal.
  // A single UI operation owns the device at a time; repeated taps return a valid short notice.
  function action(label, work, timeoutMs = 55_000) {
    return scope => {
      if (uiBusy) return toast('이전 작업을 처리 중입니다. 잠시 기다려 주세요')
      return {pending: {label, timeoutMs}, result: async signal => {
        if (uiBusy) return toast('이전 작업을 처리 중입니다. 잠시 기다려 주세요')
        uiBusy = true
        try {
          signal.throwIfAborted()
          return await work(scope, signal)
        } catch (error) {
          if (signal.aborted || lifetime.signal.aborted) return []
          return toast(error.message || '작업이 완료되지 않았습니다. 로컬 서비스를 확인하세요')
        } finally { uiBusy = false }
      }}
    }
  }

  ctx.inject(['dshGo'], scoped => {
    scoped.dshGo.on('connect', action('안경 연결', async (scope, signal) => {
      const copied = scope.clipboard?.trim() ?? ''
      const address = copied.match(/^http:\/\/\S+/m)?.[0]
      const token = copied.match(/Authorization:\s*Bearer\s+(\S+)/i)?.[1]
      if (!address || !token) return toast('먼저 OpenRayneo에서 주소와 액세스 토큰을 복사하세요')
      if (new URL(address).origin !== base.origin) return toast('주소가 일치하지 않습니다. 먼저 플러그인의 Service URL을 수정하세요')
      const credentials = ctx.get('credentials')
      if (!credentials) throw new Error('현재 DSH에 쓰기 가능한 자격 증명 서비스가 없습니다')
      signal.throwIfAborted()
      await credentials.set(tokenRef(), token)
      signal.throwIfAborted()
      const state = await api('/v1/status', {signal})
      return toast(state.link === 'FULL' ? 'OpenRayneo 연결됨, 안경 완전 연결' : 'OpenRayneo 연결됨, 안경은 아직 완전히 연결되지 않음')
    }, 15_000))
    scoped.dshGo.on('status', action('안경 상태 읽기', async (_scope, signal) => {
      const state = await api('/v1/status', {signal})
      const link = {FULL: '완전 연결', DISCONNECTED: '미연결', BLE_ONLY: 'BLE 전용 연결',
        SCANNING: '스캔 중', CONNECTING: '연결 중', RETRY_WAIT: '재연결 대기', CLOSING: '연결 해제 중'}[state.link] ?? '상태 알 수 없음'
      return toast(`v${pkg.version} · 안경 ${link} · ${state.conversationRunning ? '음성 실행 중' : '음성 대기'}`)
    }, 10_000))
    scoped.dshGo.on('send-glasses', action('안경으로 보내기', async (scope, signal) => {
      const text = typeof scope.message === 'string' ? scope.message : ''
      if (!text.trim()) return toast('보낼 답변 본문이 없습니다')
      await callTool('show_notification', {title: 'DSH Go', body: clip(text, 500)}, signal)
      return toast('안경 알림을 보냈습니다. 안경을 확인하세요')
    }))
    scoped.dshGo.on('clear-glasses', action('안경 화면 지우기', async (_scope, signal) => {
      await callTool('display_clear', {}, signal)
      return toast('화면 지우기 요청을 보냄')
    }))
  })
}
