package com.ahmtydn.calendar_bridge

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class EventManager(private val context: Context) {

    suspend fun retrieveEvents(calendarId: String, arguments: Map<String, Any>): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        val events = mutableListOf<Map<String, Any>>()
        
        val startDate = arguments["startDate"] as? Long
        val endDate = arguments["endDate"] as? Long
        val eventIds = arguments["eventIds"] as? List<String>

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.AVAILABILITY,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.DTSTART
        )

        var selection: String
        var selectionArgs: Array<String>

        if (eventIds != null && eventIds.isNotEmpty()) {
            // Query specific events by ID
            val placeholders = eventIds.joinToString(",") { "?" }
            selection = "${CalendarContract.Instances.EVENT_ID} IN ($placeholders)"
            selectionArgs = eventIds.toTypedArray()
        } else {
            // Query by calendar and date range using Instances
            val selectionParts = mutableListOf("${CalendarContract.Events.CALENDAR_ID} = ?")
            val argsList = mutableListOf(calendarId)

            if (startDate != null) {
                selectionParts.add("${CalendarContract.Instances.END} >= ?")
                argsList.add(startDate.toString())
            }

            if (endDate != null) {
                selectionParts.add("${CalendarContract.Instances.BEGIN} <= ?")
                argsList.add(endDate.toString())
            }

            selection = selectionParts.joinToString(" AND ")
            selectionArgs = argsList.toTypedArray()
        }


        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, startDate ?: 0L)
            ContentUris.appendId(this, endDate ?: Long.MAX_VALUE)
        }.build()

        val cursor: Cursor? = context.contentResolver.query(
            instancesUri,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Instances.BEGIN} ASC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val eventId = it.getLong(0).toString()
                val eventCalendarId = it.getLong(1).toString()
                val title = it.getString(2) ?: ""
                val description = it.getString(3)
                val startTime = it.getLong(4)
                val endTime = it.getLong(5)
                val allDay = it.getInt(6) == 1
                val location = it.getString(7)
                val availability = it.getInt(8)
                val status = it.getInt(9)
                val rrule = it.getString(10)
                val originalStartTime = it.getLong(11)

                val eventMap = mutableMapOf<String, Any>(
                    "eventId" to eventId,
                    "calendarId" to eventCalendarId,
                    "title" to title,
                    "start" to startTime,
                    "end" to endTime,
                    "allDay" to allDay,
                    "availability" to availabilityToString(availability),
                    "status" to statusToString(status)
                )

                description?.let { eventMap["description"] = it }
                location?.let { eventMap["location"] = it }
                rrule?.let {
                    // Add 'Z' suffix back to UNTIL for consistency with iOS
                    val rruleWithZ = it.replace(Regex("(UNTIL=\\d{8}T\\d{6})(?!Z)"), "$1Z")
                    // Add 'RRULE:' prefix for Dart RecurrenceRule.fromString()
                    eventMap["recurrenceRule"] = "RRULE:$rruleWithZ"
                }
  
                if (rrule != null) {
                    eventMap["originalStart"] = originalStartTime
                }

                // Get attendees
                val attendees = getEventAttendees(eventId)
                if (attendees.isNotEmpty()) {
                    eventMap["attendees"] = attendees
                }

                // Get reminders
                val reminders = getEventReminders(eventId)
                if (reminders.isNotEmpty()) {
                    eventMap["reminders"] = reminders
                }

                events.add(eventMap)
            }
        }

        return@withContext events
    }

    suspend fun createEvent(arguments: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val calendarId = arguments["calendarId"] as? String
            ?: throw CalendarException.InvalidArgument("Calendar ID is required")

        val title = arguments["title"] as? String
            ?: throw CalendarException.InvalidArgument("Event title is required")

        val startTime = arguments["start"] as? Long
            ?: throw CalendarException.InvalidArgument("Event start time is required")

        val endTime = arguments["end"] as? Long
            ?: throw CalendarException.InvalidArgument("Event end time is required")

        if (startTime >= endTime) {
            throw CalendarException.InvalidArgument("Event start time must be before end time")
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId.toLong())
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startTime)
            put(CalendarContract.Events.DTEND, endTime)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.ALL_DAY, if (arguments["allDay"] as? Boolean == true) 1 else 0)
            
            arguments["description"]?.let { 
                put(CalendarContract.Events.DESCRIPTION, it as String)
            }
            
            arguments["location"]?.let { 
                put(CalendarContract.Events.EVENT_LOCATION, it as String)
            }
            
            arguments["availability"]?.let { 
                put(CalendarContract.Events.AVAILABILITY, availabilityFromString(it as String))
            }
            
            arguments["status"]?.let { 
                put(CalendarContract.Events.STATUS, statusFromString(it as String))
            }
            
            arguments["recurrenceRule"]?.let {
                // Android requires UNTIL without 'Z' suffix and without 'RRULE:' prefix
                var rrule = (it as String).replace(Regex("(UNTIL=\\d{8}T\\d{6})Z"), "$1")
                // Remove 'RRULE:' prefix if present
                rrule = rrule.removePrefix("RRULE:")
                put(CalendarContract.Events.RRULE, rrule)
            }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw CalendarException.PlatformError("Failed to create event")

        val eventId = uri.lastPathSegment
            ?: throw CalendarException.PlatformError("Failed to get event ID")

        // Add attendees if provided
        arguments["attendees"]?.let { attendees ->
            addEventAttendees(eventId, attendees as List<Map<String, Any>>)
        }

        // Add reminders if provided
        arguments["reminders"]?.let { reminders ->
            addEventReminders(eventId, reminders as List<Map<String, Any>>)
        }

        return@withContext eventId
    }

    suspend fun updateEvent(arguments: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val eventId = arguments["eventId"] as? String
            ?: throw CalendarException.InvalidArgument("Event ID is required")

        // Check if event exists
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId),
            null
        )

        cursor?.use {
            if (!it.moveToFirst()) {
                throw CalendarException.EventNotFound(eventId)
            }
        } ?: throw CalendarException.EventNotFound(eventId)

        val values = ContentValues()
        
        arguments["title"]?.let { 
            values.put(CalendarContract.Events.TITLE, it as String)
        }
        
        arguments["description"]?.let { 
            values.put(CalendarContract.Events.DESCRIPTION, it as String)
        }
        
        arguments["location"]?.let { 
            values.put(CalendarContract.Events.EVENT_LOCATION, it as String)
        }
        
        arguments["start"]?.let { 
            values.put(CalendarContract.Events.DTSTART, it as Long)
        }
        
        arguments["end"]?.let { 
            values.put(CalendarContract.Events.DTEND, it as Long)
        }
        
        arguments["allDay"]?.let { 
            values.put(CalendarContract.Events.ALL_DAY, if (it as Boolean) 1 else 0)
        }
        
        arguments["availability"]?.let { 
            values.put(CalendarContract.Events.AVAILABILITY, availabilityFromString(it as String))
        }
        
        arguments["status"]?.let { 
            values.put(CalendarContract.Events.STATUS, statusFromString(it as String))
        }
        
        arguments["recurrenceRule"]?.let {
            // Android requires UNTIL without 'Z' suffix and without 'RRULE:' prefix
            var rrule = (it as String).replace(Regex("(UNTIL=\\d{8}T\\d{6})Z"), "$1")
            // Remove 'RRULE:' prefix if present
            rrule = rrule.removePrefix("RRULE:")
            values.put(CalendarContract.Events.RRULE, rrule)
        }

        val updatedRows = context.contentResolver.update(
            CalendarContract.Events.CONTENT_URI,
            values,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId)
        )

        if (updatedRows == 0) {
            throw CalendarException.PlatformError("Failed to update event")
        }

        // Update attendees if provided
        arguments["attendees"]?.let { attendees ->
            // Delete existing attendees
            context.contentResolver.delete(
                CalendarContract.Attendees.CONTENT_URI,
                "${CalendarContract.Attendees.EVENT_ID} = ?",
                arrayOf(eventId)
            )
            // Add new attendees
            addEventAttendees(eventId, attendees as List<Map<String, Any>>)
        }

        // Update reminders if provided
        arguments["reminders"]?.let { reminders ->
            // Delete existing reminders
            context.contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId)
            )
            // Add new reminders
            addEventReminders(eventId, reminders as List<Map<String, Any>>)
        }

        return@withContext eventId
    }

    suspend fun deleteEvent(calendarId: String, eventId: String): Boolean = withContext(Dispatchers.IO) {
        android.util.Log.d("CalendarBridge", "[Android EventManager] deleteEvent called")
        android.util.Log.d("CalendarBridge", "[Android EventManager]   calendarId: $calendarId")
        android.util.Log.d("CalendarBridge", "[Android EventManager]   eventId: $eventId")

        // First check if calendar is writable
        val calendarCursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars._ID} = ?",
            arrayOf(calendarId),
            null
        )

        calendarCursor?.use {
            if (!it.moveToFirst()) {
                android.util.Log.e("CalendarBridge", "[Android EventManager] Calendar not found: $calendarId")
                throw CalendarException.CalendarNotFound(calendarId)
            }
            val accessLevel = it.getInt(0)
            android.util.Log.d("CalendarBridge", "[Android EventManager]   Calendar access level: $accessLevel")
            if (accessLevel < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                android.util.Log.e("CalendarBridge", "[Android EventManager] Calendar is read-only")
                throw CalendarException.InvalidArgument("Cannot delete event from read-only calendar")
            }
        } ?: throw CalendarException.CalendarNotFound(calendarId)

        // Verify event exists in the specified calendar
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID),
            "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ?",
            arrayOf(eventId, calendarId),
            null
        )

        cursor?.use {
            if (!it.moveToFirst()) {
                android.util.Log.e("CalendarBridge", "[Android EventManager] Event not found: $eventId")
                throw CalendarException.EventNotFound(eventId)
            }
            android.util.Log.d("CalendarBridge", "[Android EventManager]   Event found in calendar")
        } ?: throw CalendarException.EventNotFound(eventId)

        android.util.Log.d("CalendarBridge", "[Android EventManager] Deleting event...")
        val deletedRows = context.contentResolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId)
        )

        android.util.Log.d("CalendarBridge", "[Android EventManager] Deleted rows: $deletedRows")
        return@withContext deletedRows > 0
    }

    suspend fun deleteEventInstance(calendarId: String, eventId: String, startDate: Long, followingInstances: Boolean): Boolean = withContext(Dispatchers.IO) {
        android.util.Log.d("CalendarBridge", "[Android EventManager] deleteEventInstance called")
        android.util.Log.d("CalendarBridge", "[Android EventManager]   calendarId: $calendarId")
        android.util.Log.d("CalendarBridge", "[Android EventManager]   eventId: $eventId")
        android.util.Log.d("CalendarBridge", "[Android EventManager]   startDate: $startDate (${Date(startDate)})")
        android.util.Log.d("CalendarBridge", "[Android EventManager]   followingInstances: $followingInstances")

        // First check if calendar is writable
        val calendarCursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars._ID} = ?",
            arrayOf(calendarId),
            null
        )

        calendarCursor?.use {
            if (!it.moveToFirst()) {
                android.util.Log.e("CalendarBridge", "[Android EventManager] Calendar not found: $calendarId")
                throw CalendarException.CalendarNotFound(calendarId)
            }
            val accessLevel = it.getInt(0)
            android.util.Log.d("CalendarBridge", "[Android EventManager]   Calendar access level: $accessLevel")
            if (accessLevel < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                android.util.Log.e("CalendarBridge", "[Android EventManager] Calendar is read-only")
                throw CalendarException.InvalidArgument("Cannot delete event from read-only calendar")
            }
        } ?: throw CalendarException.CalendarNotFound(calendarId)

        // Verify event exists in the specified calendar and get RRULE
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.RRULE, CalendarContract.Events.DTSTART),
            "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ?",
            arrayOf(eventId, calendarId),
            null
        )

        var isRecurring = false
        var currentRRule: String? = null
        var masterStartDate: Long? = null
        cursor?.use {
            if (!it.moveToFirst()) {
                android.util.Log.e("CalendarBridge", "[Android EventManager] Event not found: $eventId")
                throw CalendarException.EventNotFound(eventId)
            }
            currentRRule = it.getString(2)
            masterStartDate = it.getLong(3)
            isRecurring = !currentRRule.isNullOrEmpty()
            android.util.Log.d("CalendarBridge", "[Android EventManager]   Event found - isRecurring: $isRecurring")
            android.util.Log.d("CalendarBridge", "[Android EventManager]   RRULE: $currentRRule")
            android.util.Log.d("CalendarBridge", "[Android EventManager]   Master start date: $masterStartDate (${masterStartDate?.let { Date(it) }})")
        } ?: throw CalendarException.EventNotFound(eventId)

        // For non-recurring events, just delete normally
        if (!isRecurring) {
            android.util.Log.d("CalendarBridge", "[Android EventManager] Non-recurring event, deleting normally")
            return@withContext deleteEvent(calendarId, eventId)
        }

        // For recurring events
        if (followingInstances) {
            android.util.Log.d("CalendarBridge", "[Android EventManager] Deleting this and following instances")

            // Check if we're deleting from the first occurrence or before
            // If so, delete the entire event instead of updating RRULE
            if (startDate <= masterStartDate!!) {
                android.util.Log.d("CalendarBridge", "[Android EventManager]   Deleting from first occurrence, removing entire event")
                return@withContext deleteEvent(calendarId, eventId)
            }

            // Delete this and all following instances by updating RRULE with UNTIL
            // Android requires UNTIL without 'Z' suffix
            val dateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            // Set UNTIL to one millisecond before the instance to delete
            val untilDate = Date(startDate - 1)
            val untilString = dateFormat.format(untilDate)
            android.util.Log.d("CalendarBridge", "[Android EventManager]   UNTIL date: $untilString")

            // Check if there's already an UNTIL that ends at or before our target date
            // We'll proceed anyway to recreate the event (Android might not be applying RRULE correctly)
            val existingUntilMatch = Regex("UNTIL=(\\d{8}T\\d{6}Z?)").find(currentRRule ?: "")
            if (existingUntilMatch != null) {
                val existingUntilStr = existingUntilMatch.groupValues[1].replace(Regex("Z$"), "")
                try {
                    val existingUntilDate = dateFormat.parse(existingUntilStr)
                    if (existingUntilDate != null && existingUntilDate.time <= startDate) {
                        android.util.Log.w("CalendarBridge", "[Android EventManager]   UNTIL already exists: $existingUntilStr (same or before target)")
                        android.util.Log.w("CalendarBridge", "[Android EventManager]   But we'll recreate the event anyway to force Android to apply it")
                        // Don't return - continue to recreate the event
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CalendarBridge", "[Android EventManager]   Could not parse existing UNTIL: $existingUntilStr")
                }
            }

            // Update the RRULE to add UNTIL
            val updatedRRule = if (currentRRule != null) {
                // Remove existing UNTIL if present
                val rruleWithoutUntil = currentRRule!!.replace(Regex(";UNTIL=[^;]*"), "")
                    .replace(Regex("UNTIL=[^;]*;?"), "")
                // Add new UNTIL (without Z suffix for Android)
                if (rruleWithoutUntil.contains(";")) {
                    "$rruleWithoutUntil;UNTIL=$untilString"
                } else {
                    "$rruleWithoutUntil;UNTIL=$untilString"
                }
            } else {
                android.util.Log.e("CalendarBridge", "[Android EventManager] Current RRULE is null, cannot update")
                return@withContext false
            }

            android.util.Log.d("CalendarBridge", "[Android EventManager]   Updated RRULE: $updatedRRule")

            // Get all event details before deleting
            val eventCursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.AVAILABILITY,
                    CalendarContract.Events.STATUS,
                    CalendarContract.Events.EVENT_TIMEZONE
                ),
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId),
                null
            )

            var eventData: Map<String, Any?>? = null
            eventCursor?.use {
                if (it.moveToFirst()) {
                    eventData = mapOf(
                        "title" to it.getString(0),
                        "description" to it.getString(1),
                        "location" to it.getString(2),
                        "dtstart" to it.getLong(3),
                        "dtend" to it.getLong(4),
                        "allDay" to (it.getInt(5) == 1),
                        "availability" to it.getInt(6),
                        "status" to it.getInt(7),
                        "timezone" to it.getString(8)
                    )
                }
            }

            if (eventData == null) {
                android.util.Log.e("CalendarBridge", "[Android EventManager] Failed to get event data")
                return@withContext false
            }

            android.util.Log.d("CalendarBridge", "[Android EventManager]   Deleting old event and creating new one with updated RRULE")

            // Delete old event
            val deletedRows = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId)
            )

            if (deletedRows == 0) {
                android.util.Log.e("CalendarBridge", "[Android EventManager] Failed to delete old event")
                return@withContext false
            }

            // Create new event with updated RRULE
            val newEventValues = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId.toLong())
                put(CalendarContract.Events.TITLE, eventData["title"] as String)
                put(CalendarContract.Events.DTSTART, eventData["dtstart"] as Long)
                put(CalendarContract.Events.DTEND, eventData["dtend"] as Long)
                put(CalendarContract.Events.ALL_DAY, if (eventData["allDay"] as Boolean) 1 else 0)
                put(CalendarContract.Events.AVAILABILITY, eventData["availability"] as Int)
                put(CalendarContract.Events.STATUS, eventData["status"] as Int)
                put(CalendarContract.Events.EVENT_TIMEZONE, eventData["timezone"] as String)
                put(CalendarContract.Events.RRULE, updatedRRule)

                eventData["description"]?.let {
                    put(CalendarContract.Events.DESCRIPTION, it as String)
                }
                eventData["location"]?.let {
                    put(CalendarContract.Events.EVENT_LOCATION, it as String)
                }
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, newEventValues)

            if (uri != null) {
                val newEventId = uri.lastPathSegment
                android.util.Log.d("CalendarBridge", "[Android EventManager]   New event created with ID: $newEventId")

                // Copy attendees from old event
                val attendees = getEventAttendees(eventId)
                if (attendees.isNotEmpty()) {
                    android.util.Log.d("CalendarBridge", "[Android EventManager]   Copying ${attendees.size} attendees")
                    addEventAttendees(newEventId!!, attendees)
                }

                // Copy reminders from old event
                val reminders = getEventReminders(eventId)
                if (reminders.isNotEmpty()) {
                    android.util.Log.d("CalendarBridge", "[Android EventManager]   Copying ${reminders.size} reminders")
                    addEventReminders(newEventId!!, reminders)
                }

                return@withContext true
            } else {
                android.util.Log.e("CalendarBridge", "[Android EventManager]   Failed to create new event")
                return@withContext false
            }
        } else {
            android.util.Log.d("CalendarBridge", "[Android EventManager] Deleting only this instance by creating exception")

            // Get event details for the exception
            val eventCursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.EVENT_TIMEZONE
                ),
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId),
                null
            )

            var title: String? = null
            var dtStart: Long? = null
            var dtEnd: Long? = null
            var allDay: Int = 0
            var timezone: String? = null

            eventCursor?.use {
                if (it.moveToFirst()) {
                    title = it.getString(0)
                    dtStart = it.getLong(1)
                    dtEnd = it.getLong(2)
                    allDay = it.getInt(3)
                    timezone = it.getString(4)
                }
            }

            if (title == null || dtStart == null || dtEnd == null) {
                android.util.Log.e("CalendarBridge", "[Android EventManager] Failed to get event details for exception")
                return@withContext false
            }

            // Calculate the duration to maintain it for the exception
            val duration = dtEnd!! - dtStart!!

            // Create exception event with all required fields
            val exceptionValues = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.ORIGINAL_ID, eventId)
                put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, startDate)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startDate)
                put(CalendarContract.Events.DTEND, startDate + duration)
                put(CalendarContract.Events.ALL_DAY, allDay)
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                timezone?.let { put(CalendarContract.Events.EVENT_TIMEZONE, it) }
            }

            android.util.Log.d("CalendarBridge", "[Android EventManager]   Creating exception event...")
            android.util.Log.d("CalendarBridge", "[Android EventManager]   Original ID: $eventId, Instance time: $startDate")
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, exceptionValues)
            val success = uri != null
            android.util.Log.d("CalendarBridge", "[Android EventManager]   Exception created: $success (URI: $uri)")
            return@withContext success
        }
    }

    private fun getEventAttendees(eventId: String): List<Map<String, Any>> {
        val attendees = mutableListOf<Map<String, Any>>()
        val projection = arrayOf(
            CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_NAME,
            CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
            CalendarContract.Attendees.ATTENDEE_STATUS
        )

        val cursor = context.contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            projection,
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventId),
            null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val email = it.getString(0) ?: ""
                val name = it.getString(1)
                val relationship = it.getInt(2)
                val status = it.getInt(3)

                attendees.add(mapOf(
                    "email" to email,
                    "name" to (name ?: email),
                    "role" to attendeeRoleToString(relationship),
                    "status" to attendeeStatusToString(status)
                ))
            }
        }

        return attendees
    }

    private fun getEventReminders(eventId: String): List<Map<String, Any>> {
        val reminders = mutableListOf<Map<String, Any>>()
        val projection = arrayOf(CalendarContract.Reminders.MINUTES)

        val cursor = context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            projection,
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId),
            null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val minutes = it.getInt(0)
                reminders.add(mapOf("minutes" to minutes))
            }
        }

        return reminders
    }

    private fun addEventAttendees(eventId: String, attendees: List<Map<String, Any>>) {
        attendees.forEach { attendee ->
            val email = attendee["email"] as? String ?: return@forEach
            val name = attendee["name"] as? String ?: email
            val role = attendee["role"] as? String ?: "required"
            val status = attendee["status"] as? String ?: "pending"
            
            val values = ContentValues().apply {
                put(CalendarContract.Attendees.EVENT_ID, eventId.toLong())
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                put(CalendarContract.Attendees.ATTENDEE_NAME, name)
                put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, attendeeRoleFromString(role))
                put(CalendarContract.Attendees.ATTENDEE_STATUS, attendeeStatusFromString(status))
                put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
            }

            context.contentResolver.insert(CalendarContract.Attendees.CONTENT_URI, values)
        }
    }

    private fun addEventReminders(eventId: String, reminders: List<Map<String, Any>>) {
        reminders.forEach { reminder ->
            val minutes = reminder["minutes"] as? Int ?: return@forEach
            
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId.toLong())
                put(CalendarContract.Reminders.MINUTES, minutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }

            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
        }
    }

    private fun availabilityToString(availability: Int): String {
        return when (availability) {
            CalendarContract.Events.AVAILABILITY_BUSY -> "busy"
            CalendarContract.Events.AVAILABILITY_FREE -> "free"
            CalendarContract.Events.AVAILABILITY_TENTATIVE -> "tentative"
            else -> "busy"
        }
    }

    private fun availabilityFromString(availability: String): Int {
        return when (availability.lowercase()) {
            "free" -> CalendarContract.Events.AVAILABILITY_FREE
            "tentative" -> CalendarContract.Events.AVAILABILITY_TENTATIVE
            else -> CalendarContract.Events.AVAILABILITY_BUSY
        }
    }

    private fun statusToString(status: Int): String {
        return when (status) {
            CalendarContract.Events.STATUS_CONFIRMED -> "confirmed"
            CalendarContract.Events.STATUS_TENTATIVE -> "tentative"
            CalendarContract.Events.STATUS_CANCELED -> "cancelled"
            else -> "confirmed"
        }
    }

    private fun statusFromString(status: String): Int {
        return when (status.lowercase()) {
            "tentative" -> CalendarContract.Events.STATUS_TENTATIVE
            "cancelled" -> CalendarContract.Events.STATUS_CANCELED
            else -> CalendarContract.Events.STATUS_CONFIRMED
        }
    }

    private fun attendeeRoleToString(relationship: Int): String {
        return when (relationship) {
            CalendarContract.Attendees.RELATIONSHIP_ATTENDEE -> "required"
            CalendarContract.Attendees.RELATIONSHIP_ORGANIZER -> "chair"
            CalendarContract.Attendees.RELATIONSHIP_PERFORMER -> "required"
            CalendarContract.Attendees.RELATIONSHIP_SPEAKER -> "required"
            else -> "required"
        }
    }

    private fun attendeeStatusToString(status: Int): String {
        return when (status) {
            CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> "accepted"
            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> "declined"
            CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> "pending"
            CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> "tentative"
            else -> "unknown"
        }
    }

    private fun attendeeRoleFromString(role: String): Int {
        return when (role.lowercase()) {
            "chair" -> CalendarContract.Attendees.RELATIONSHIP_ORGANIZER
            "required" -> CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
            "optional" -> CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
            else -> CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
        }
    }

    private fun attendeeStatusFromString(status: String): Int {
        return when (status.lowercase()) {
            "accepted" -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
            "declined" -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
            "tentative" -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
            "pending" -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
            else -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
        }
    }
}