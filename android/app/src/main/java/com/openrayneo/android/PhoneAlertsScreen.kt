// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Phone notifications on the glasses, laid out like the official app's notification settings. */
@Composable
internal fun PhoneAlertsScreen(model: AppModel, actions: UiActions) {
    val context = LocalContext.current
    val settings = model.state.alerts
    // Listing every app's label is slow on a full phone: off the main thread.
    val apps by produceState(emptyList<PhoneAlerts.App>()) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PhoneAlerts.apps(context) } }
    var query by rememberSaveable { mutableStateOf("") }
    fun save(next: PhoneAlertSettings) = model.attempt { model.service().setPhoneAlerts(next) }
    val listening = model.state.notificationConnected
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionLabel("휴대폰 알림") }
        block {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("알림 허용", Modifier.weight(1f)); Switch(settings.enabled, { save(settings.copy(enabled = it)) })
            }
            Note("고른 앱의 새 알림을 안경에 보여 줍니다. 안경의 크라운을 누르면 지금 알림을 닫습니다.")
            if (!listening) Row(verticalAlignment = Alignment.CenterVertically) {
                Note("알림 접근 권한이 없어 알림을 읽을 수 없습니다.", Modifier.weight(1f))
                TextButton(onClick = actions.notificationAccess) { Text("허용하기") }
            }
        }
        item { SectionLabel("통화 알림") }
        block {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("통화 알림 수신", Modifier.weight(1f)); Switch(settings.calls, { save(settings.copy(calls = it)) })
            }
        }
        item { SectionLabel("알림 표시 시간") }
        block {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(!settings.dynamic, { save(settings.copy(dynamic = false)) })
                Column(Modifier.weight(1f)) { Text("고정 시간"); Note("모든 알림을 같은 시간 동안 보여 줍니다.") }
            }
            if (!settings.dynamic) Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5, 8, 10).forEach { seconds -> FilterChip(settings.seconds == seconds, { save(settings.copy(seconds = seconds)) }, label = { Text("${seconds}초") }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(settings.dynamic, { save(settings.copy(dynamic = true)) })
                Column(Modifier.weight(1f)) { Text("동적 시간"); Note("내용 길이에 따라 표시 시간을 자동으로 정합니다.") }
            }
        }
        block {
            SectionLabel("알림 출처 (${settings.packages.size}개 앱)")
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("앱 검색") }, singleLine = true)
        }
        val shown = apps.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .sortedByDescending { it.packageName in settings.packages }
        items(shown, key = { it.packageName }) { app ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.name, Modifier.weight(1f))
                Switch(app.packageName in settings.packages, { on ->
                    save(settings.copy(packages = if (on) settings.packages + app.packageName else settings.packages - app.packageName))
                })
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.block(content: @Composable ColumnScope.() -> Unit) =
    item { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
