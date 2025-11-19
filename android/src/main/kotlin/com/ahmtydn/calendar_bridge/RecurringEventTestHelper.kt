package com.ahmtydn.calendar_bridge

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * Helper class to test different approaches for updating recurring events
 * Use this to verify which method works correctly on different Android versions
 */
class RecurringEventTestHelper(private val context: Context) {

    private val TAG = "RecurringEventTest"

    /**
     * Approach 1: Simple UPDATE on Events table
     *
     * Pros: Keeps eventId, simple, fast
     * Cons: Instances table may not refresh immediately
     */
    fun updateRRuleDirectly(eventId: String, newRRule: String): Boolean {
        Log.d(TAG, "═══ Testing UPDATE approach ═══")
        Log.d(TAG, "EventId: $eventId")
        Log.d(TAG, "New RRULE: $newRRule")

        val values = ContentValues().apply {
            put(CalendarContract.Events.RRULE, newRRule)
        }

        val updatedRows = context.contentResolver.update(
            CalendarContract.Events.CONTENT_URI,
            values,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId)
        )

        Log.d(TAG, "Updated rows: $updatedRows")

        // Verify the update
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.RRULE),
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId),
            null
        )

        var verified = false
        cursor?.use {
            if (it.moveToFirst()) {
                val storedRRule = it.getString(0)
                verified = storedRRule == newRRule
                Log.d(TAG, "Stored RRULE: $storedRRule")
                Log.d(TAG, "Verified: $verified")
            }
        }

        return verified
    }

    /**
     * Approach 2: UPDATE with sync adapter flag
     *
     * This tells Android that we're a sync adapter, which may trigger
     * instance expansion immediately
     */
    fun updateRRuleWithSyncFlag(eventId: String, newRRule: String): Boolean {
        Log.d(TAG, "═══ Testing UPDATE + SYNC_ADAPTER approach ═══")
        Log.d(TAG, "EventId: $eventId")
        Log.d(TAG, "New RRULE: $newRRule")

        val values = ContentValues().apply {
            put(CalendarContract.Events.RRULE, newRRule)
        }

        val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "local")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, "LOCAL")
            .build()

        val updatedRows = context.contentResolver.update(
            uri,
            values,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId)
        )

        Log.d(TAG, "Updated rows: $updatedRows")

        // Trigger notification
        val eventUri = ContentUris.withAppendedId(
            CalendarContract.Events.CONTENT_URI,
            eventId.toLong()
        )
        context.contentResolver.notifyChange(eventUri, null)

        return updatedRows > 0
    }

    /**
     * Approach 3: DELETE + INSERT (current implementation)
     *
     * Pros: Guaranteed to work, instances refresh immediately
     * Cons: EventId changes, loses references
     */
    fun deleteAndRecreate(
        calendarId: String,
        eventId: String,
        newRRule: String,
        eventData: Map<String, Any?>
    ): String? {
        Log.d(TAG, "═══ Testing DELETE + INSERT approach ═══")
        Log.d(TAG, "EventId: $eventId")
        Log.d(TAG, "New RRULE: $newRRule")

        // Delete old event
        val deletedRows = context.contentResolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId)
        )

        Log.d(TAG, "Deleted rows: $deletedRows")

        if (deletedRows == 0) {
            Log.e(TAG, "Failed to delete event")
            return null
        }

        // Create new event
        val newEventValues = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId.toLong())
            put(CalendarContract.Events.TITLE, eventData["title"] as String)
            put(CalendarContract.Events.DTSTART, eventData["dtstart"] as Long)
            put(CalendarContract.Events.DTEND, eventData["dtend"] as Long)
            put(CalendarContract.Events.ALL_DAY, if (eventData["allDay"] as Boolean) 1 else 0)
            put(CalendarContract.Events.AVAILABILITY, eventData["availability"] as Int)
            put(CalendarContract.Events.STATUS, eventData["status"] as Int)
            put(CalendarContract.Events.EVENT_TIMEZONE, eventData["timezone"] as String)
            put(CalendarContract.Events.RRULE, newRRule)

            eventData["description"]?.let {
                put(CalendarContract.Events.DESCRIPTION, it as String)
            }
            eventData["location"]?.let {
                put(CalendarContract.Events.EVENT_LOCATION, it as String)
            }
        }

        val uri = context.contentResolver.insert(
            CalendarContract.Events.CONTENT_URI,
            newEventValues
        )

        val newEventId = uri?.lastPathSegment
        Log.d(TAG, "New EventId: $newEventId")

        return newEventId
    }

    /**
     * Verify that instances are correctly expanded after RRULE update
     *
     * @param eventId The event to check
     * @param expectedCount Expected number of instances in the next 30 days
     * @return true if instance count matches expected
     */
    fun verifyInstancesExpanded(eventId: String, expectedCount: Int): Boolean {
        Log.d(TAG, "═══ Verifying instances expansion ═══")

        val now = System.currentTimeMillis()
        val in30Days = now + (30L * 24 * 60 * 60 * 1000)

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, now)
            ContentUris.appendId(this, in30Days)
        }.build()

        val cursor = context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN),
            "${CalendarContract.Instances.EVENT_ID} = ?",
            arrayOf(eventId),
            "${CalendarContract.Instances.BEGIN} ASC"
        )

        var actualCount = 0
        cursor?.use {
            actualCount = it.count
            Log.d(TAG, "Instances found in next 30 days: $actualCount")

            var index = 0
            while (it.moveToNext()) {
                val begin = it.getLong(1)
                val date = Date(begin)
                Log.d(TAG, "  Instance ${++index}: $date")
            }
        }

        val matches = actualCount == expectedCount
        Log.d(TAG, "Expected: $expectedCount, Actual: $actualCount, Match: $matches")

        return matches
    }

    /**
     * Get current RRULE from event
     */
    fun getCurrentRRule(eventId: String): String? {
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.RRULE),
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId),
            null
        )

        var rrule: String? = null
        cursor?.use {
            if (it.moveToFirst()) {
                rrule = it.getString(0)
            }
        }

        return rrule
    }

    /**
     * Add UNTIL to existing RRULE
     */
    fun addUntilToRRule(currentRRule: String, untilDate: Long): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val untilString = dateFormat.format(Date(untilDate))

        // Remove existing UNTIL if present
        val rruleWithoutUntil = currentRRule
            .replace(Regex(";UNTIL=[^;]*"), "")
            .replace(Regex("UNTIL=[^;]*;?"), "")

        // Add new UNTIL (without Z suffix for Android)
        return "$rruleWithoutUntil;UNTIL=$untilString"
    }
}