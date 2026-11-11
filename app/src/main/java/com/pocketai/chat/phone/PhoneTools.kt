package com.pocketai.chat.phone

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.pocketai.chat.ReplyService
import com.pocketai.chat.data.ToolEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phone actions (2.20 preview): alarms and timers through the phone's Clock app, calendar events added to and
 * read from the phone's calendar (CalendarContract — the same calendars Samsung/Google Calendar show).
 * Each action leaves a card in the chat (a [ToolEvent]); calendar events added here can be undone from it.
 */
class PhoneTools(private val context: Context) {

    /** What the model gets back, and the card shown in the chat. */
    class Outcome(val text: String, val event: ToolEvent? = null)

    /**
     * [visible]: a Pocket AI screen is showing. Android won't let an app open the Clock app from the
     * background, so then the alarm is offered as a notification to tap instead.
     */
    suspend fun run(name: String, args: JsonObject, visible: () -> Boolean): Outcome = try {
        when (name) {
            "set_alarm" -> setAlarm(args, visible())
            "set_timer" -> setTimer(args, visible())
            "add_calendar_event" -> addEvent(args, visible)
            "list_calendar_events" -> listEvents(args, visible)
            else -> Outcome("Error: unknown tool $name.")
        }
    } catch (e: IllegalArgumentException) {
        Outcome("Error: ${e.message}")
    } catch (e: SecurityException) {
        Outcome("The phone refused: ${e.message}")
    }

    // ------------------------------------------------------------------ clock

    private fun setAlarm(args: JsonObject, visible: Boolean): Outcome {
        val now = LocalDateTime.now()
        val inMinutes = args.int("in_minutes")
        val time = when {
            inMinutes != null -> now.plusMinutes(inMinutes.toLong()).toLocalTime()
            else -> parseTime(args.str("time") ?: throw IllegalArgumentException("give 'time' (HH:MM, 24-hour) or 'in_minutes'."))
        }
        val label = args.str("label")
        val days = args.list("days").mapNotNull { dayOf(it) }.distinct().sorted()
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            // Clock apps take java.util.Calendar day numbers (Sunday = 1).
            if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { it.value % 7 + 1 }))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        val hhmm = "%02d:%02d".format(time.hour, time.minute)
        val dayText = daysText(days)
        val card = listOfNotNull("Alarm $hhmm", dayText, label).joinToString(" · ")
        val how = launch(i, visible, "Tap to set alarm", card) ?: return Outcome("No clock app on this phone accepts alarms.")
        val nowText = "Current time: ${now.format(CLOCK)}."
        return Outcome(
            "Alarm ${if (how == Launch.NOTIFICATION) "offered as a notification (the app was in the background, so the user has to tap it)" else "set in the Clock app"}: " +
                "$hhmm${dayText?.let { ", repeats $it" } ?: ""}${label?.let { ", label \"$it\"" } ?: ""}. $nowText",
            ToolEvent(kind = "alarm", label = card + if (how == Launch.NOTIFICATION) " (tap the notification)" else ""),
        )
    }

    private fun setTimer(args: JsonObject, visible: Boolean): Outcome {
        val seconds = (args.int("minutes") ?: 0) * 60 + (args.int("seconds") ?: 0)
        require(seconds in 1..86_400) { "the timer must be between 1 second and 24 hours ('minutes' and/or 'seconds')." }
        val label = args.str("label")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        val len = buildString {
            if (seconds >= 3600) append("${seconds / 3600} h ")
            if (seconds % 3600 >= 60) append("${seconds % 3600 / 60} min ")
            if (seconds % 60 > 0) append("${seconds % 60} s")
        }.trim()
        val card = listOfNotNull("Timer $len", label).joinToString(" · ")
        val how = launch(i, visible, "Tap to start timer", card) ?: return Outcome("No clock app on this phone accepts timers.")
        return Outcome(
            if (how == Launch.NOTIFICATION) "Timer offered as a notification (the app was in the background, so it starts when the user taps it): $len."
            else "Timer started: $len${label?.let { " ($it)" } ?: ""}. It ends at ${LocalTime.now().plusSeconds(seconds.toLong()).format(HHMM)}.",
            ToolEvent(kind = "timer", label = card + if (how == Launch.NOTIFICATION) " (tap the notification)" else ""),
        )
    }

    private enum class Launch { OPENED, NOTIFICATION }

    private fun launch(i: Intent, visible: Boolean, tapTitle: String, text: String): Launch? {
        if (i.resolveActivity(context.packageManager) == null) return null
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (visible) {
            try {
                context.startActivity(i)
                return Launch.OPENED
            } catch (_: Exception) {
            }
        }
        ReplyService.notifyTapToRun(context, tapTitle, text, i)
        return Launch.NOTIFICATION
    }

    // ------------------------------------------------------------------ calendar

    private suspend fun calendarAllowed(visible: () -> Boolean): Boolean =
        PhonePermissions.ensure(context, CALENDAR_PERMISSIONS, visible)

    private suspend fun addEvent(args: JsonObject, visible: () -> Boolean): Outcome {
        val title = args.str("title") ?: throw IllegalArgumentException("missing 'title'.")
        val zone = ZoneId.systemDefault()
        val allDay = args.bool("all_day") == true || (args.str("start")?.let { DATE_ONLY.matches(it.trim()) } == true)
        val start: LocalDateTime = args.int("in_minutes")?.let { LocalDateTime.now().plusMinutes(it.toLong()).withSecond(0).withNano(0) }
            ?: parseDateTime(args.str("start") ?: throw IllegalArgumentException("missing 'start' (YYYY-MM-DDTHH:MM)."))
        val end: LocalDateTime = args.str("end")?.let { parseDateTime(it) }
            ?: if (allDay) start.toLocalDate().plusDays(1).atStartOfDay()
            else start.plusMinutes((args.int("duration_minutes") ?: 60).toLong())
        require(!end.isBefore(start)) { "'end' is before 'start'." }
        if (!calendarAllowed(visible)) return Outcome(NO_CALENDAR)
        val cal = pickCalendar() ?: return Outcome("There's no calendar on this phone that can be written to (add a Google account in Samsung Calendar first).")

        val (dtStart, dtEnd, tz) = if (allDay) {
            // All-day events are stored as UTC midnights; the end date is exclusive.
            val s = start.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            // 'end' for an all-day event is read as the last day (inclusive).
            val lastDay = args.str("end")?.let { parseDateTime(it).toLocalDate() }?.takeIf { !it.isBefore(start.toLocalDate()) }
                ?: start.toLocalDate()
            Triple(s, lastDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), "UTC")
        } else {
            Triple(start.atZone(zone).toInstant().toEpochMilli(), end.atZone(zone).toInstant().toEpochMilli(), zone.id)
        }
        val reminder = args.int("reminder_minutes") ?: if (allDay) null else 30
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal.id)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, dtStart)
            put(CalendarContract.Events.DTEND, dtEnd)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            args.str("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            args.str("notes")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            put(CalendarContract.Events.HAS_ALARM, if (reminder != null && reminder >= 0) 1 else 0)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return Outcome("The calendar didn't accept the event.")
        val id = ContentUris.parseId(uri)
        if (reminder != null && reminder >= 0) {
            runCatching {
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id)
                    put(CalendarContract.Reminders.MINUTES, reminder)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }
        }
        val whenText = if (allDay) {
            val last = Instant.ofEpochMilli(dtEnd).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
            start.toLocalDate().format(DAY) + (if (last != start.toLocalDate()) " – ${last.format(DAY)}" else "") + " (all day)"
        } else {
            start.format(DAY_TIME) + "–" + (if (end.toLocalDate() == start.toLocalDate()) end.format(HHMM) else end.format(DAY_TIME))
        }
        val remindText = when {
            reminder == null || reminder < 0 -> null
            reminder == 0 -> "reminder at the start"
            else -> "reminder ${minutesText(reminder)} before"
        }
        return Outcome(
            "Added to the calendar \"${cal.name}\": $title, $whenText" +
                (args.str("location")?.let { ", at $it" } ?: "") + (remindText?.let { ", $it" } ?: "") +
                ". The user can undo it from the card in the chat.",
            ToolEvent(kind = "calendar", label = "$title · $whenText", ref = id),
        )
    }

    private suspend fun listEvents(args: JsonObject, visible: () -> Boolean): Outcome {
        val zone = ZoneId.systemDefault()
        val from = args.str("from")?.let { parseDate(it) } ?: LocalDate.now()
        val to = (args.str("to")?.let { parseDate(it) } ?: from).let { if (it.isBefore(from)) from else it }
        require(!to.isAfter(from.plusDays(62))) { "ask for at most two months at a time." }
        if (!calendarAllowed(visible)) return Outcome(NO_CALENDAR)
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .let { ContentUris.appendId(it, begin); ContentUris.appendId(it, endMs); it }.build()
        val lines = mutableListOf<String>()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            ),
            "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext() && lines.size < MAX_EVENTS) {
                val title = c.getString(0)?.ifBlank { null } ?: "(no title)"
                val allDay = c.getInt(3) == 1
                val text = if (allDay) {
                    val s = Instant.ofEpochMilli(c.getLong(1)).atZone(ZoneOffset.UTC).toLocalDate()
                    val e = Instant.ofEpochMilli(c.getLong(2)).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                    s.format(DAY) + (if (e.isAfter(s)) " – ${e.format(DAY)}" else "") + ", all day"
                } else {
                    val s = Instant.ofEpochMilli(c.getLong(1)).atZone(zone).toLocalDateTime()
                    val e = Instant.ofEpochMilli(c.getLong(2)).atZone(zone).toLocalDateTime()
                    s.format(DAY_TIME) + "–" + (if (e.toLocalDate() == s.toLocalDate()) e.format(HHMM) else e.format(DAY_TIME))
                }
                val where = c.getString(4)?.ifBlank { null }?.let { " @ $it" } ?: ""
                lines += "- $text: $title$where [${c.getString(5).orEmpty()}]"
            }
        }
        val range = if (to == from) from.format(DAY) else "${from.format(DAY)} – ${to.format(DAY)}"
        val head = "Current time: ${LocalDateTime.now().format(CLOCK)} (${zone.id}).\nCalendar for $range:"
        return Outcome(
            head + "\n" + (if (lines.isEmpty()) "No events." else lines.joinToString("\n")) +
                (if (lines.size >= MAX_EVENTS) "\n(only the first $MAX_EVENTS shown)" else ""),
            ToolEvent(kind = "agenda", label = range, ref = begin),
        )
    }

    /** Undo from the chat card: removes an event this chat added. */
    fun deleteEvent(id: Long): Boolean = runCatching {
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
    }.getOrDefault(false)

    private class Cal(val id: Long, val name: String)

    /** The phone's main writable calendar: a Google account's primary one when there is one. */
    private fun pickCalendar(): Cal? {
        val found = mutableListOf<Pair<Cal, Int>>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.VISIBLE, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            ),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                var score = 0
                if (c.getString(2) == "com.google") score += 4
                if (c.getInt(3) == 1) score += 2
                if (c.getInt(4) == 1) score += 1
                found += Cal(c.getLong(0), c.getString(1).orEmpty()) to score
            }
        }
        return found.maxByOrNull { it.second }?.first
    }

    companion object {
        val CALENDAR_PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val NAMES = setOf("set_alarm", "set_timer", "add_calendar_event", "list_calendar_events")
        private const val MAX_EVENTS = 60
        private const val NO_CALENDAR = "Pocket AI isn't allowed to use the calendar (the user declined, or wasn't in the app " +
            "to be asked). Tell the user to allow Calendar for Pocket AI and try again."
        private val DATE_ONLY = Regex("""\d{4}-\d{2}-\d{2}""")
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)
        private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.US)
        private val CLOCK = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.US)

        /** Added to the system prompt when phone actions are on. */
        const val PROMPT = "\nYou can also do simple things on the user's phone: set_alarm, set_timer, add_calendar_event " +
            "and list_calendar_events. When the user asks for an alarm, a timer, a reminder or a calendar entry, do it " +
            "with the tool instead of explaining how; a reminder is a calendar event with a reminder. Look at the calendar " +
            "before saying when the user is free or busy. Turn relative days ('Thursday', 'tomorrow') into real dates from " +
            "today's date. You don't know the current clock time, so for times counted from now ('in 20 minutes') use " +
            "in_minutes. If something you need is really unclear (which day, morning or evening), ask one short question " +
            "first; otherwise take the obvious reading. Don't ask for confirmation: afterwards say in one short sentence " +
            "what you did with the exact day and time; the chat shows a card the user can undo."

        fun parseTime(s: String): LocalTime {
            val m = Regex("""^\s*(\d{1,2})[:.h]?(\d{2})?\s*(am|pm|a\.m\.|p\.m\.)?\s*$""", RegexOption.IGNORE_CASE).find(s)
                ?: throw IllegalArgumentException("can't read the time \"$s\"; use HH:MM (24-hour).")
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = m.groupValues[3].lowercase()
            if (ap.startsWith("p") && h < 12) h += 12
            if (ap.startsWith("a") && h == 12) h = 0
            require(h in 0..23 && min in 0..59) { "\"$s\" isn't a valid time." }
            return LocalTime.of(h, min)
        }

        fun parseDate(s: String): LocalDate = runCatching { LocalDate.parse(s.trim().take(10)) }
            .getOrElse { throw IllegalArgumentException("can't read the date \"$s\"; use YYYY-MM-DD.") }

        /** "2026-11-13T15:00", "2026-11-13 15:00", "2026-11-13T15:00:00+08:00" or just "2026-11-13". */
        fun parseDateTime(s: String): LocalDateTime {
            val t = s.trim()
            if (DATE_ONLY.matches(t)) return parseDate(t).atStartOfDay()
            val m = Regex("""^(\d{4}-\d{2}-\d{2})[T ](\d{1,2}:\d{2})""").find(t)
                ?: throw IllegalArgumentException("can't read \"$s\"; use YYYY-MM-DDTHH:MM in the user's time zone.")
            return parseDate(m.groupValues[1]).atTime(parseTime(m.groupValues[2]))
        }

        fun dayOf(s: String): DayOfWeek? = when (s.trim().lowercase().take(3)) {
            "mon" -> DayOfWeek.MONDAY
            "tue" -> DayOfWeek.TUESDAY
            "wed" -> DayOfWeek.WEDNESDAY
            "thu" -> DayOfWeek.THURSDAY
            "fri" -> DayOfWeek.FRIDAY
            "sat" -> DayOfWeek.SATURDAY
            "sun" -> DayOfWeek.SUNDAY
            else -> null
        }

        fun daysText(days: List<DayOfWeek>): String? {
            if (days.isEmpty()) return null
            if (days.size == 7) return "every day"
            val sorted = days.sorted()
            if (sorted == DayOfWeek.entries.take(5)) return "Mon–Fri"
            if (sorted == DayOfWeek.entries.drop(5)) return "weekends"
            return sorted.joinToString(", ") { it.name.take(1) + it.name.drop(1).take(2).lowercase() }
        }

        private fun minutesText(m: Int) = when {
            m % 1440 == 0 -> "${m / 1440} day" + if (m / 1440 > 1) "s" else ""
            m % 60 == 0 -> "${m / 60} hour" + if (m / 60 > 1) "s" else ""
            else -> "$m minutes"
        }

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
        private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull()?.toInt() }
        private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
        private fun JsonObject.list(k: String): List<String> = when (val v = this[k]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> v.contentOrNull?.split(',', ' ')?.filter { it.isNotBlank() }.orEmpty()
            else -> emptyList()
        }

        /** Opens the card's item: the event, the day in the calendar, or the Clock app. */
        fun openIntent(e: ToolEvent): Intent? = when (e.kind) {
            "alarm" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "timer" -> if (android.os.Build.VERSION.SDK_INT >= 26) Intent(AlarmClock.ACTION_SHOW_TIMERS) else null
            "calendar" -> e.ref?.let { Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it)) }
            "agenda" -> e.ref?.let {
                Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(it.toString()).build())
            }
            else -> null
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val TOOL_DEFS: JsonArray = buildJsonArray {
            fun tool(name: String, description: String, required: List<String>, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", name)
                        put("description", description)
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties", props)
                            putJsonArray("required") { required.forEach { add(it) } }
                        }
                    }
                }
            fun kotlinx.serialization.json.JsonObjectBuilder.p(name: String, type: String, description: String) =
                putJsonObject(name) { put("type", type); put("description", description) }

            tool("set_alarm", "Set an alarm in the phone's Clock app.", emptyList()) {
                p("time", "string", "Alarm time, HH:MM 24-hour (e.g. \"06:30\").")
                p("in_minutes", "integer", "Instead of time: ring this many minutes from now.")
                p("label", "string", "Optional short label.")
                putJsonObject("days") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Repeat days, e.g. [\"mon\",\"tue\",\"wed\",\"thu\",\"fri\"]. Omit for a one-time alarm.")
                }
            }
            tool("set_timer", "Start a countdown timer in the phone's Clock app.", emptyList()) {
                p("minutes", "integer", "Minutes.")
                p("seconds", "integer", "Extra seconds.")
                p("label", "string", "Optional short label.")
            }
            tool(
                "add_calendar_event",
                "Add an event to the user's calendar (also used for reminders: it notifies before the start).",
                listOf("title"),
            ) {
                p("title", "string", "Event title.")
                p("start", "string", "Start in the user's time zone: YYYY-MM-DDTHH:MM, or YYYY-MM-DD for an all-day event.")
                p("in_minutes", "integer", "Instead of start: start this many minutes from now.")
                p("end", "string", "Optional end, same format. Default: 1 hour after the start.")
                p("duration_minutes", "integer", "Optional length instead of end.")
                p("all_day", "boolean", "All-day event.")
                p("location", "string", "Optional place.")
                p("notes", "string", "Optional notes.")
                p("reminder_minutes", "integer", "Notify this many minutes before (0 = at the start). Default 30 for timed events.")
            }
            tool("list_calendar_events", "Read the user's calendar for a day or a range of days. The result starts with the current time.", emptyList()) {
                p("from", "string", "First day, YYYY-MM-DD. Default today.")
                p("to", "string", "Last day, YYYY-MM-DD (inclusive). Default the same day.")
            }
        }
    }
}
