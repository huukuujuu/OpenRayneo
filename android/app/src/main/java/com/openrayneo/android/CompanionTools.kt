// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONArray
import org.json.JSONObject

internal object CompanionTools {
    private fun field(type: String, description: String) = JSONObject().put("type", type).put("description", description)
    private fun definition(name: String, description: String, required: List<String> = emptyList(), vararg properties: Pair<String, JSONObject>): JSONObject =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().apply { properties.forEach { put(it.first, it.second) }; if (mutates(name)) put("request_id", field("string", "같은 쓰기를 재시도할 때는 같은 ID 사용. 외부 Agent는 필수, 휴대폰 Agent는 자동 제공")) })
                .put("required", JSONArray(required)).put("additionalProperties", false)))
    fun definitions(): JSONArray = JSONArray().apply {
        put(definition("navigation_status", "Amap 알림 추종 상태 읽기. 경로는 추론하지 않음"))
        put(definition("stop_navigation_mirroring", "안경 추종 중지. Amap 내비게이션은 계속됨"))
        put(definition("show_notification", "안경에 짧은 알림 전송. 대기열 등록 성공이 표시를 보장하지 않음", listOf("title", "body"),
            "title" to field("string", "제목"), "body" to field("string", "본문")))
        put(definition("display_clear", "실시간 힌트의 답변을 지우고 진행 중인 음성 수신은 유지"))
        put(definition("device_read", "안경 상태와 설정을 직접 읽기"))
        put(definition("device_brightness", "밝기를 변경하고 재조회. 전송 성공을 기기 변경 완료라고 말하지 말 것", listOf("value"), "value" to field("integer", "밝기 1~17")))
        put(definition("device_display", "화면 높이와 거리를 변경하고 재조회", listOf("height", "distance"),
            "height" to field("integer", "높이 1~6"), "distance" to field("integer", "거리 1~3")))
        put(definition("todo_read", "휴대폰 할 일 목록 읽기(완료 항목 포함). 안경이 연결되면 안경 목록과 자동으로 맞춤"))
        put(definition("todo_add", "할 일 추가. 사용자가 명시적으로 요청한 경우에만", listOf("title"), "title" to field("string", "제목"), "important" to field("boolean", "중요 표시")))
        put(definition("todo_update", "조회로 얻은 ID로 할 일 수정. 완료 여부는 재조회 결과 기준", listOf("event_id"),
            "event_id" to field("integer", "실제 할 일 ID"), "title" to field("string", "새 제목"), "important" to field("boolean", "중요"), "completed" to field("boolean", "완료 또는 완료 취소")))
        put(definition("todo_delete", "사용자가 명시한 할 일 삭제. 먼저 읽어서 ID 확인", listOf("event_id"), "event_id" to field("integer", "실제 할 일 ID")))
        put(definition("calendar_read", "휴대폰 캘린더(구글 캘린더 포함)의 오늘부터 7일 일정 읽기. 읽기 전용이며 추가·수정은 구글 캘린더 앱에서 해야 함"))
        put(definition("schedule_read", "안경 일정 읽기"))
        val scheduleFields = arrayOf("event_id" to field("integer", "수정 또는 삭제 시 실제 ID 사용"), "title" to field("string", "제목"),
            "start_time" to field("integer", "명확한 시작 시각의 Unix 초, 현재 시간대 기준"), "end_time" to field("integer", "명확한 종료 시각의 Unix 초"))
        put(definition("schedule_add", "일정 추가. 날짜, 시작 또는 종료 시간이 없으면 먼저 확인하고 임의로 추측하지 말 것", listOf("title", "start_time", "end_time"), *scheduleFields.filter { it.first != "event_id" }.toTypedArray()))
        put(definition("schedule_update", "조회한 일정 수정", listOf("event_id"), *scheduleFields))
        put(definition("schedule_delete", "사용자가 명시한 일정 삭제", listOf("event_id"), "event_id" to field("integer", "실제 일정 ID")))
        put(definition("location_current", "휴대폰의 현재 위치, 정확도, 획득 시각 읽기. 위치가 지정되지 않으면 먼저 이 도구를 쓰고 실패하면 도시를 물을 것"))
        put(definition("weather_current", "휴대폰 현재 위치의 현재 날씨, 향후 시간별 및 3일 예보를 바로 가져옴. 사용자가 도시 지정 없이 오늘 날씨나 비 소식을 물으면 우선 호출. 매개변수 불필요, 안경 날씨 설정은 변경하지 않음"))
        put(definition("weather_search_cities", "사용자가 도시를 명시하면 좌표와 실제 도시 ID 조회. 도시 미지정 시 weather_current 우선", listOf("query"), "query" to field("string", "도시 이름")))
        put(definition("weather_get", "선택한 도시의 날씨를 가져오고 안경 날씨 도시도 설정. city는 검색 결과 원본 객체 사용", listOf("city"), "city" to field("object", "검색 결과의 id/name/latitude/longitude")))
        put(definition("weather_push", "가져온 날씨를 홈 온도와 날씨 카드에 함께 전송"))
        put(definition("dashboard_read", "안경 홈 모드와 위젯 순서 읽기"))
        put(definition("dashboard_update", "홈 모드와 순서 설정. 제거 요청이 없는 위젯은 유지. 날씨는 먼저 도시 설정", listOf("mode", "widgets"),
            "mode" to field("integer", "0 표준, 1 간결, 2 대각 분할"), "widgets" to field("array", "1 날씨, 2 할 일, 3 일정, 4 주식, 5 메일, 6 세계 시계, 1~5개").put("items", JSONObject().put("type", "integer"))))
        put(definition("record_query", "휴대폰의 전체 기록과 연결된 분석/태그/메모 읽기. 시간 범위나 원문으로 검색 가능", emptyList(),
            "recordId" to field("string", "지정 기록 ID"), "from" to field("integer", "시작 Unix 밀리초"), "to" to field("integer", "종료 Unix 밀리초"), "text" to field("string", "검색어")))
        put(definition("record_annotate", "분석, 태그, 메모를 원본 기록에 연결해 영구 저장하고 이후 컴퓨터로 동기화", listOf("recordId", "sourceRevision", "kind"),
            "recordId" to field("string", "조회한 기록 ID"), "sourceRevision" to field("integer", "조회 결과의 record.revision"),
            "kind" to field("string", "analysis/note/tags"), "text" to field("string", "본문"),
            "tags" to field("array", "태그").put("items", field("string", "태그")), "id" to field("string", "재시도용 고정 파생 ID"),
            "revision" to field("integer", "파생 항목 버전"), "agent" to field("string", "작성 Agent 이름")))
        put(definition("memory_search", "보존이 허용된 원문 발화와 출처가 있는 기억 검색. 인물 관계는 추론하지 않음", listOf("query"), "query" to field("string", "키워드")))
        put(definition("teleprompter_start", "사용자가 표시를 요청한 원고를 안경으로 전송하고 자동 스크롤 시작", listOf("title", "text"),
            "title" to field("string", "원고 이름"), "text" to field("string", "전문"), "speed" to field("integer", "스크롤 속도 1~10, 기본값 2")))
        put(definition("teleprompter_control", "이 앱이 시작한 프롬프터 제어", listOf("action"), "action" to field("string", "pause、resume、stop")))
    }
    fun mutates(name: String) = name in setOf("todo_add", "todo_update", "todo_delete", "schedule_add", "schedule_update", "schedule_delete")
}
