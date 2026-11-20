// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/** Gmail notifications on the phone, for the glasses' mail widget (no Google sign-in needed). */
internal object MailFeed {
    const val PACKAGE = "com.google.android.gm"
    data class Mail(val key: String, val sender: String, val subject: String, val preview: String, val postedAt: Long, val account: String)
    private val mails = linkedMapOf<String, Mail>()
    @Volatile var version = 0; private set

    @Synchronized fun posted(item: StatusBarNotification) {
        val notification = item.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val subject = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (sender.isBlank() && subject.isBlank()) return
        // The expanded text is "subject\npreview".
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val preview = big.removePrefix(subject).trim().take(200)
        // Gmail puts the receiving account's address in the sub text; the official widget sends it as `sender`.
        val account = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        mails[item.key] = Mail(item.key, sender, subject, preview, item.postTime, account); version++
    }
    @Synchronized fun removed(key: String) { if (mails.remove(key) != null) version++ }
    @Synchronized fun reset(items: List<StatusBarNotification>) { mails.clear(); version++; items.forEach(::posted) }
    @Synchronized fun snapshot(): List<Mail> = mails.values.sortedByDescending { it.postedAt }
}

internal data class PhoneAlertSettings(val enabled: Boolean = false, val packages: Set<String> = emptySet(), val calls: Boolean = true,
    /** 0 fixed time, 1 dynamic (by length), as the official app's notification settings. */
    val dynamic: Boolean = false, val seconds: Int = 5) {
    /** 0x15 type 17, the official app's format. `notification` stays true: false would also silence the assistant's own notifications. */
    fun glassesConfig(): JSONObject = JSONObject().put("notification", true).put("callNotification", calls).put("avoidDuplicate", false)
        .put("displayTimeMode", if (dynamic) 1 else 0).put("displayTime", seconds).put("intervalTime", 2).put("filterUID", JSONArray())
}

/** Phone notifications forwarded to the glasses for the apps the user picked. */
internal object PhoneAlerts {
    data class Alert(val packageName: String, val appName: String, val title: String, val text: String)
    data class App(val packageName: String, val name: String)
    private const val PREFS = "phone-notifications"
    @Volatile var settings = PhoneAlertSettings(); private set
    /** Set by the bridge service while it runs; called on the listener's thread. */
    @Volatile var sink: ((Alert) -> Unit)? = null
    private val last = HashMap<String, String>()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        settings = PhoneAlertSettings(prefs.getBoolean("enabled", false), prefs.getStringSet("packages", emptySet()).orEmpty().toSet(),
            prefs.getBoolean("calls", true), prefs.getBoolean("dynamic", false), prefs.getInt("seconds", 5))
    }
    fun save(context: Context, next: PhoneAlertSettings) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", next.enabled)
            .putStringSet("packages", next.packages).putBoolean("calls", next.calls).putBoolean("dynamic", next.dynamic)
            .putInt("seconds", next.seconds).commit())
        settings = next
    }
    /** Apps with a launcher entry, for the source list. */
    fun apps(context: Context): List<App> {
        val manager = context.packageManager
        return manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(manager).toString()) }
            .filter { it.packageName != context.packageName }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
    }

    fun posted(context: Context, item: StatusBarNotification) {
        val current = settings
        if (!current.enabled || item.packageName !in current.packages || item.packageName == context.packageName) return
        val notification = item.notification
        if (notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        if (title.isBlank() && text.isBlank()) return
        // Apps re-post the same notification on every update; forward only a changed one.
        val signature = "$title\n$text"
        synchronized(last) { if (last[item.key] == signature) return; last[item.key] = signature; if (last.size > 200) last.clear() }
        val manager = context.packageManager
        val name = runCatching { manager.getApplicationLabel(manager.getApplicationInfo(item.packageName, 0)).toString() }.getOrDefault(item.packageName)
        sink?.invoke(Alert(item.packageName, name, title, text.take(500)))
    }
    fun removed(key: String) { synchronized(last) { last.remove(key) } }
}
