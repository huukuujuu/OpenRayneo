// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The phone's calendars (Google accounts signed in on the phone sync into the system calendar provider), read only.
 * No Google API key or sign-in: the READ_CALENDAR permission is enough.
 */
internal object PhoneCalendar {
    data class Source(val id: Long, val name: String, val account: String, val color: Int)
    /** One occurrence; [key] stays the same for the same occurrence across reads. Times are epoch milliseconds, local for all-day. */
    data class Event(val key: String, val title: String, val begin: Long, val end: Long, val allDay: Boolean,
        val location: String, val calendar: String, val color: Int)

    fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun sources(context: Context): List<Source> {
        if (!allowed(context)) return emptyList()
        val columns = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR)
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columns,
            "${CalendarContract.Calendars.VISIBLE} = 1", null, CalendarContract.Calendars.ACCOUNT_NAME)?.use { c ->
            buildList { while (c.moveToNext()) add(Source(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3))) }
        }.orEmpty()
    }

    /** Occurrences overlapping [from, to) in the chosen calendars (null = every visible one), by start time. */
    fun events(context: Context, calendars: Set<Long>?, from: Long, to: Long): List<Event> {
        if (!allowed(context) || calendars?.isEmpty() == true) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to)
        }.build()
        val columns = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.STATUS, CalendarContract.Instances.VISIBLE)
        val selection = calendars?.let { "${CalendarContract.Instances.CALENDAR_ID} IN (${it.joinToString(",")})" }
        val zone = ZoneId.systemDefault()
        return context.contentResolver.query(uri, columns, selection, null, CalendarContract.Instances.BEGIN)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.getInt(10) == 0 || c.getInt(9) == CalendarContract.Instances.STATUS_CANCELED) continue
                    val allDay = c.getInt(4) == 1
                    // All-day occurrences are stored as UTC midnights: keep the date, move it to local midnight.
                    fun local(utc: Long) = if (!allDay) utc else
                        Instant.ofEpochMilli(utc).atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                    val begin = local(c.getLong(1)); val end = local(c.getLong(2)).coerceAtLeast(begin)
                    add(Event("${c.getLong(0)}:${c.getLong(1)}", c.getString(3).orEmpty().ifBlank { "(제목 없음)" }, begin, end, allDay,
                        c.getString(5).orEmpty(), c.getString(7).orEmpty(), c.getInt(8)))
                }
            }.filter { it.end > from && it.begin < to }
        }.orEmpty()
    }

    fun dayStart(date: LocalDate = LocalDate.now()): Long = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
