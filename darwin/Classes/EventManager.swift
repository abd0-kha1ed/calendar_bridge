import EventKit
import Foundation

class EventManager {
    private let eventStore: EKEventStore
    
    init(eventStore: EKEventStore) {
        self.eventStore = eventStore
    }
    
    private func eventIdString(_ event: EKEvent) -> String {
        #if os(iOS)
        return event.eventIdentifier
        #elseif os(macOS)
        return event.eventIdentifier ?? ""
        #endif
    }
    
    func retrieveEvents(calendarId: String, arguments: [String: Any]) throws -> [[String: Any]] {
        guard let calendar = eventStore.calendar(withIdentifier: calendarId) else {
            throw CalendarError.calendarNotFound(calendarId)
        }
        
        let startDate = extractDate(from: arguments["startDate"])
        let endDate = extractDate(from: arguments["endDate"])
        let eventIds = arguments["eventIds"] as? [String]
        
        var events: [EKEvent] = []
        
        if let eventIds = eventIds {
            // Retrieve specific events by ID
            for eventId in eventIds {
                if let event = eventStore.event(withIdentifier: eventId) {
                    events.append(event)
                }
            }
        } else {
            // Retrieve events by date range
            let predicate = eventStore.predicateForEvents(
                withStart: startDate ?? Date.distantPast,
                end: endDate ?? Date.distantFuture,
                calendars: [calendar]
            )
            events = eventStore.events(matching: predicate)
        }
        
        return events.map { event in
            return eventToDict(event: event)
        }
    }
    
    func createEvent(arguments: [String: Any]) throws -> String {
        guard let calendarId = arguments["calendarId"] as? String else {
            throw CalendarError.invalidArgument("Calendar ID is required")
        }
        
        guard let calendar = eventStore.calendar(withIdentifier: calendarId) else {
            throw CalendarError.calendarNotFound(calendarId)
        }
        
        guard calendar.allowsContentModifications else {
            throw CalendarError.invalidArgument("Cannot create event in read-only calendar")
        }
        
        let event = EKEvent(eventStore: eventStore)
        event.calendar = calendar
        
        try populateEventFromDict(event: event, dict: arguments)
        
        do {
            try eventStore.save(event, span: .thisEvent)
            return event.eventIdentifier
        } catch {
            throw CalendarError.platformError("Failed to create event: \(error.localizedDescription)")
        }
    }
    
    func updateEvent(arguments: [String: Any]) throws -> String {
        guard let eventId = arguments["eventId"] as? String else {
            throw CalendarError.invalidArgument("Event ID is required")
        }
        
        guard let event = eventStore.event(withIdentifier: eventId) else {
            throw CalendarError.eventNotFound(eventId)
        }
        
        guard event.calendar.allowsContentModifications else {
            throw CalendarError.invalidArgument("Cannot update event in read-only calendar")
        }
        
        try populateEventFromDict(event: event, dict: arguments)
        
        do {
            try eventStore.save(event, span: .thisEvent)
            return event.eventIdentifier
        } catch {
            throw CalendarError.platformError("Failed to update event: \(error.localizedDescription)")
        }
    }
    
    func deleteEvent(calendarId: String, eventId: String) throws -> Bool {
        print("🗑️ [DELETE] Starting deleteEvent - calendarId: \(calendarId), eventId: \(eventId)")

        guard let calendar = eventStore.calendar(withIdentifier: calendarId) else {
            print("❌ [DELETE] Calendar not found: \(calendarId)")
            throw CalendarError.calendarNotFound(calendarId)
        }
        print("✅ [DELETE] Calendar found: \(calendar.title)")

        // Extract master event ID if this is a composite ID
        let masterEventId: String
        if eventId.contains(":") {
            masterEventId = String(eventId.split(separator: ":")[0])
            print("ℹ️ [DELETE] Extracted master event ID: \(masterEventId)")
        } else {
            masterEventId = eventId
        }

        guard let event = eventStore.event(withIdentifier: masterEventId) else {
            print("❌ [DELETE] Event not found: \(masterEventId)")
            throw CalendarError.eventNotFound(masterEventId)
        }
        print("✅ [DELETE] Event found: \(event.title ?? "No title")")
        print("   - Has recurrence: \(event.hasRecurrenceRules)")

        guard calendar.allowsContentModifications else {
            print("❌ [DELETE] Calendar is read-only")
            throw CalendarError.invalidArgument("Cannot delete event from read-only calendar")
        }
        print("✅ [DELETE] Calendar allows modifications")

        do {
            // For recurring events, use .futureEvents to delete the entire series
            // For non-recurring events, use .thisEvent
            let span: EKSpan = event.hasRecurrenceRules ? .futureEvents : .thisEvent
            print("🔄 [DELETE] Attempting to remove event with commit=true, span=\(span == .futureEvents ? "futureEvents (entire series)" : "thisEvent")")
            try eventStore.remove(event, span: span, commit: true)
            print("✅ [DELETE] Event removed successfully!")
            return true
        } catch {
            print("❌ [DELETE] Failed to remove event: \(error.localizedDescription)")
            throw CalendarError.platformError("Failed to delete event: \(error.localizedDescription)")
        }
    }
    
    func deleteEventInstance(calendarId: String, eventId: String, startDate: Date, followingInstances: Bool) async throws -> Bool {
        print("🗑️ [DELETE_INSTANCE] Starting deleteEventInstance")
        print("   - calendarId: \(calendarId)")
        print("   - eventId: \(eventId)")
        print("   - startDate: \(startDate)")
        print("   - followingInstances: \(followingInstances)")

        guard let calendar = eventStore.calendar(withIdentifier: calendarId) else {
            print("❌ [DELETE_INSTANCE] Calendar not found: \(calendarId)")
            throw CalendarError.calendarNotFound(calendarId)
        }
        print("✅ [DELETE_INSTANCE] Calendar found: \(calendar.title)")

        guard calendar.allowsContentModifications else {
            print("❌ [DELETE_INSTANCE] Calendar is read-only")
            throw CalendarError.invalidArgument("Cannot delete event from read-only calendar")
        }
        print("✅ [DELETE_INSTANCE] Calendar allows modifications")

        // Strategy: Search for the event/occurrence by date instead of relying on master event ID
        // This is more reliable because occurrence IDs might not resolve correctly
        print("🔍 [DELETE_INSTANCE] Searching for event/occurrence at date: \(startDate)")

        // Create a time window to find the specific event/occurrence
        let currentCalendar = Calendar.current
        let startOfDay = currentCalendar.startOfDay(for: startDate)
        let endOfDay = currentCalendar.date(byAdding: .day, value: 1, to: startOfDay) ?? startDate

        print("   - Search window: \(startOfDay) to \(endOfDay)")

        let predicate = eventStore.predicateForEvents(withStart: startOfDay, end: endOfDay, calendars: [calendar])
        let occurrences = eventStore.events(matching: predicate)

        print("📋 [DELETE_INSTANCE] Found \(occurrences.count) event(s) in date range")

        // Log all events found for debugging
        for (index, occurrence) in occurrences.enumerated() {
            print("   [\(index)] \(occurrence.title ?? "No title")")
            print("        Start: \(occurrence.startDate)")
            print("        ID: \(eventIdString(occurrence))")
            print("        Calendar Item ID: \(occurrence.calendarItemIdentifier)")
        }

        // Extract master event ID from composite ID if present
        let masterEventId: String?
        if eventId.contains(":") {
            masterEventId = String(eventId.split(separator: ":")[0])
            print("ℹ️ [DELETE_INSTANCE] Extracted master event ID: \(masterEventId ?? "nil")")
        } else {
            masterEventId = eventId
            print("ℹ️ [DELETE_INSTANCE] Event ID (no colon): \(eventId)")
        }

        // Try to find the occurrence by multiple methods
        var targetOccurrence: EKEvent?

        // Method 1: Try exact event ID match
        if let found = occurrences.first(where: { eventIdString($0) == eventId }) {
            print("✅ [DELETE_INSTANCE] Found by exact event ID match")
            targetOccurrence = found
        }

        // Method 2: Try matching by start date (within tolerance)
        if targetOccurrence == nil {
            if let found = occurrences.first(where: { abs($0.startDate.timeIntervalSince(startDate)) < 60 }) {
                print("✅ [DELETE_INSTANCE] Found by start date match (within 60s tolerance)")
                targetOccurrence = found
            }
        }

        // Method 3: Try matching by calendar item identifier if we have a master ID
        if targetOccurrence == nil, let masterId = masterEventId {
            if let masterEvent = eventStore.event(withIdentifier: masterId) {
                print("ℹ️ [DELETE_INSTANCE] Found master event, searching for occurrence with same calendar item ID")
                if let found = occurrences.first(where: { $0.calendarItemIdentifier == masterEvent.calendarItemIdentifier }) {
                    print("✅ [DELETE_INSTANCE] Found by calendar item ID match")
                    targetOccurrence = found
                }
            } else {
                print("⚠️ [DELETE_INSTANCE] Master event not found with ID: \(masterId)")
            }
        }

        // If we found the target occurrence, delete it
        if let occurrence = targetOccurrence {
            print("✅ [DELETE_INSTANCE] Target event/occurrence identified:")
            print("   - Title: \(occurrence.title ?? "No title")")
            print("   - Start: \(occurrence.startDate)")
            print("   - Has recurrence: \(occurrence.hasRecurrenceRules)")

            return try await deleteEventWithSpan(occurrence, followingInstances: followingInstances)
        }

        // No occurrence found
        print("❌ [DELETE_INSTANCE] No matching event/occurrence found!")
        print("   - Searched for event ID: \(eventId)")
        print("   - Searched for start date: \(startDate)")
        throw CalendarError.eventNotFound("Event/occurrence not found")
    }

    private func deleteEventWithSpan(_ event: EKEvent, followingInstances: Bool) async throws -> Bool {
        do {
            let span: EKSpan = followingInstances ? .futureEvents : .thisEvent
            print("🔄 [DELETE_INSTANCE] Attempting to remove event '\(event.title ?? "No title")'")
            print("   - Start date: \(event.startDate)")
            print("   - Span: \(span == .futureEvents ? "futureEvents" : "thisEvent")")
            try eventStore.remove(event, span: span, commit: true)
            print("✅ [DELETE_INSTANCE] Event removed successfully!")
            return true
        } catch {
            print("❌ [DELETE_INSTANCE] Failed to remove: \(error.localizedDescription)")
            throw CalendarError.platformError("Failed to delete event instance: \(error.localizedDescription)")
        }
    }
    
    // MARK: - Helper Methods
    
    private func populateEventFromDict(event: EKEvent, dict: [String: Any]) throws {
        if let title = dict["title"] as? String {
            event.title = title
        }
        
        if let description = dict["description"] as? String {
            event.notes = description
        }
        
        if let location = dict["location"] as? String {
            event.location = location
        }
        
        if let url = dict["url"] as? String {
            event.url = URL(string: url)
        }
        
        if let isAllDay = dict["allDay"] as? Bool {
            event.isAllDay = isAllDay
        }
        
        if let startDate = extractDate(from: dict["start"]) {
            event.startDate = startDate
        }
        
        if let endDate = extractDate(from: dict["end"]) {
            event.endDate = endDate
        }
        
        // Handle attendees
        if let attendeesData = dict["attendees"] as? [[String: Any]] {
            setAttendees(attendeesData, event)
        }
        
        // Handle reminders
        if let remindersData = dict["reminders"] as? [[String: Any]] {
            let alarms = remindersData.compactMap { reminderDict -> EKAlarm? in
                guard let minutes = reminderDict["minutes"] as? Int else { return nil }
                return EKAlarm(relativeOffset: TimeInterval(-minutes * 60))
            }
            event.alarms = alarms
        }
        
        // Handle availability
        if let availabilityString = dict["availability"] as? String {
            switch availabilityString {
            case "busy":
                event.availability = .busy
            case "free":
                event.availability = .free
            case "tentative":
                event.availability = .tentative
            case "out-of-office":
                if #available(iOS 9.0, *) {
                    event.availability = .unavailable
                } else {
                    event.availability = .busy
                }
            default:
                event.availability = .busy
            }
        }
        
        // Handle recurrence rule
        if let rruleString = dict["recurrenceRule"] as? String {
            print("📥 Receiving RRULE from Flutter: \(rruleString)")
            if let rule = parseRRULEString(rruleString) {
                event.recurrenceRules = [rule]
                print("✅ Set recurrence rule on event")
            } else {
                print("❌ Failed to parse RRULE string")
            }
        }
    }
    
    private func eventToDict(event: EKEvent) -> [String: Any] {
        var dict: [String: Any] = [
            "eventId": eventIdString(event),
            "calendarId": event.calendar.calendarIdentifier,
            "title": event.title ?? "",
            "allDay": event.isAllDay
        ]
        
        if let description = event.notes {
            dict["description"] = description
        }
        
        if let location = event.location {
            dict["location"] = location
        }
        
        if let url = event.url {
            dict["url"] = url.absoluteString
        }
        
        dict["start"] = Int(event.startDate.timeIntervalSince1970 * 1000)
        dict["end"] = Int(event.endDate.timeIntervalSince1970 * 1000)
        
        // Handle attendees
        if let attendees = event.attendees {
            let attendeesData = attendees.map { attendee in
                #if os(iOS)
                var email = ""
                let url = attendee.url
                if url.scheme == "mailto" {
                    email = String(url.absoluteString.dropFirst(7)) 
                } else {
                    email = url.absoluteString
                }
                #elseif os(macOS)
                let email = attendee.url.absoluteString
                #endif
                return [
                    "email": email,
                    "name": attendee.name ?? "",
                    "role": attendeeRoleToString(attendee.participantRole),
                    "status": attendeeStatusToString(attendee.participantStatus)
                ]
            }
            dict["attendees"] = attendeesData
        }
        
        // Handle reminders
        if let alarms = event.alarms {
            let remindersData = alarms.compactMap { alarm -> [String: Any]? in
                #if os(iOS)
                guard let relativeOffset = alarm.relativeOffset as? TimeInterval else { return nil }
                #elseif os(macOS)
                let relativeOffset = alarm.relativeOffset
                #endif
                return ["minutes": Int(-relativeOffset / 60)]
            }
            dict["reminders"] = remindersData
        }
        
        // Handle recurrence rule
        if event.hasRecurrenceRules, let recurrenceRules = event.recurrenceRules, !recurrenceRules.isEmpty {
            // Get the first recurrence rule (iOS/macOS supports multiple but we use the first one)
            let rule = recurrenceRules[0]
            let rruleString = convertRecurrenceRuleToRRULE(rule)
            dict["recurrenceRule"] = rruleString
        }

        // Handle availability
        dict["availability"] = availabilityToString(event.availability)

        // Handle status
        dict["status"] = statusToString(event.status)

        var originalStartDate: Int64? = nil
        
        if let masterItem = eventStore.calendarItem(withIdentifier: event.calendarItemIdentifier) as? EKEvent {
            originalStartDate = Int64(masterItem.startDate.millisecondsSinceEpoch)
        } else {
            if event.hasRecurrenceRules {
                originalStartDate = Int64(event.startDate.millisecondsSinceEpoch)
            } else {
                originalStartDate = Int64(event.startDate.millisecondsSinceEpoch)
            }
        }
        
        if let originalStart = originalStartDate {
            dict["originalStart"] = originalStart
        }
        
        return dict
    }
    
    private func extractDate(from value: Any?) -> Date? {
        guard let timestamp = value as? NSNumber else { return nil }
        return Date(timeIntervalSince1970: timestamp.doubleValue / 1000.0)
    }
    
    private func attendeeRoleToString(_ role: EKParticipantRole) -> String {
        switch role {
        case .required:
            return "required"
        case .optional:
            return "optional"
        case .chair:
            return "chair"
        case .nonParticipant:
            return "non-participant"
        case .unknown:
            return "unknown"
        @unknown default:
            return "required"
        }
    }
    
    private func attendeeStatusToString(_ status: EKParticipantStatus) -> String {
        switch status {
        case .unknown:
            return "unknown"
        case .pending:
            return "pending"
        case .accepted:
            return "accepted"
        case .declined:
            return "declined"
        case .tentative:
            return "tentative"
        case .delegated:
            return "tentative"
        case .completed:
            return "accepted"
        case .inProcess:
            return "pending"
        @unknown default:
            return "unknown"
        }
    }
    
    private func availabilityToString(_ availability: EKEventAvailability) -> String {
        switch availability {
        case .notSupported:
            return "busy"
        case .busy:
            return "busy"
        case .free:
            return "free"
        case .tentative:
            return "tentative"
        case .unavailable:
            return "out-of-office"
        @unknown default:
            return "busy"
        }
    }
    
    private func statusToString(_ status: EKEventStatus) -> String {
        #if os(iOS)
        switch status {
        case .none:
            return "confirmed"
        case .confirmed:
            return "confirmed"
        case .tentative:
            return "tentative"
        case .canceled:
            return "cancelled"
        @unknown default:
            return "confirmed"
        }
        #elseif os(macOS)
        switch status {
        case .none:
            return "confirmed"
        case .confirmed:
            return "confirmed"
        case .tentative:
            return "tentative"
        case .canceled:
            return "cancelled"
        @unknown default:
            return "confirmed"
        }
        #endif
    }
    
    // MARK: - Recurrence Rule Handling
    
    private func createEKRecurrenceRules(_ recurrenceDict: [String: Any]) -> [EKRecurrenceRule]? {
        guard let frequency = recurrenceDict["freq"] as? String else { return nil }
        
        let interval = recurrenceDict["interval"] as? Int ?? 1
        let count = recurrenceDict["count"] as? Int
        let until = recurrenceDict["until"] as? String
        
        var ekFrequency: EKRecurrenceFrequency
        switch frequency.uppercased() {
        case "DAILY":
            ekFrequency = .daily
        case "WEEKLY":
            ekFrequency = .weekly
        case "MONTHLY":
            ekFrequency = .monthly
        case "YEARLY":
            ekFrequency = .yearly
        default:
            ekFrequency = .daily
        }
        
        var recurrenceEnd: EKRecurrenceEnd?
        if let count = count, count > 0 {
            recurrenceEnd = EKRecurrenceEnd(occurrenceCount: count)
        } else if let until = until {
            let dateFormatter = DateFormatter()
            dateFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
            if let endDate = dateFormatter.date(from: until) {
                recurrenceEnd = EKRecurrenceEnd(end: endDate)
            }
        }
        
        // Parse BYDAY values
        var daysOfWeek: [EKRecurrenceDayOfWeek]?
        if let byDayStrings = recurrenceDict["byday"] as? [String] {
            daysOfWeek = byDayStrings.compactMap { recurrenceDayOfWeekFromString($0) }
        }
        
        // Parse BYMONTHDAY values
        var daysOfMonth: [NSNumber]?
        if let byMonthDays = recurrenceDict["bymonthday"] as? [Int] {
            daysOfMonth = byMonthDays.map { NSNumber(value: $0) }
        }
        
        // Parse BYMONTH values
        var monthsOfYear: [NSNumber]?
        if let byMonths = recurrenceDict["bymonth"] as? [Int] {
            monthsOfYear = byMonths.map { NSNumber(value: $0) }
        }
        
        // Parse BYYEARDAY values
        var daysOfYear: [NSNumber]?
        if let byYearDays = recurrenceDict["byyearday"] as? [Int] {
            daysOfYear = byYearDays.map { NSNumber(value: $0) }
        }
        
        // Parse BYWEEKNO values
        var weeksOfYear: [NSNumber]?
        if let byWeeks = recurrenceDict["byweekno"] as? [Int] {
            weeksOfYear = byWeeks.map { NSNumber(value: $0) }
        }
        
        // Parse BYSETPOS values
        var setPositions: [NSNumber]?
        if let bySetPos = recurrenceDict["bysetpos"] as? [Int] {
            setPositions = bySetPos.map { NSNumber(value: $0) }
        }
        
        let rule = EKRecurrenceRule(
            recurrenceWith: ekFrequency,
            interval: interval,
            daysOfTheWeek: daysOfWeek,
            daysOfTheMonth: daysOfMonth,
            monthsOfTheYear: monthsOfYear,
            weeksOfTheYear: weeksOfYear,
            daysOfTheYear: daysOfYear,
            setPositions: setPositions,
            end: recurrenceEnd
        )
        
        return [rule]
    }
    
    private func convertRecurrenceRuleToRRULE(_ rule: EKRecurrenceRule) -> String {
        var components: [String] = []

        // Frequency
        let freqString: String
        switch rule.frequency {
        case .daily:
            freqString = "DAILY"
        case .weekly:
            freqString = "WEEKLY"
        case .monthly:
            freqString = "MONTHLY"
        case .yearly:
            freqString = "YEARLY"
        @unknown default:
            freqString = "DAILY"
        }
        components.append("FREQ=\(freqString)")

        // Interval
        if rule.interval > 1 {
            components.append("INTERVAL=\(rule.interval)")
        }

        // BYDAY
        if let daysOfWeek = rule.daysOfTheWeek, !daysOfWeek.isEmpty {
            let byDayStrings = daysOfWeek.map { recurrenceDayOfWeek -> String in
                let dayString: String
                switch recurrenceDayOfWeek.dayOfTheWeek {
                case .sunday: dayString = "SU"
                case .monday: dayString = "MO"
                case .tuesday: dayString = "TU"
                case .wednesday: dayString = "WE"
                case .thursday: dayString = "TH"
                case .friday: dayString = "FR"
                case .saturday: dayString = "SA"
                @unknown default: dayString = "MO"
                }

                if recurrenceDayOfWeek.weekNumber != 0 {
                    return "\(recurrenceDayOfWeek.weekNumber)\(dayString)"
                } else {
                    return dayString
                }
            }
            components.append("BYDAY=\(byDayStrings.joined(separator: ","))")
        }

        // BYMONTHDAY
        if let daysOfMonth = rule.daysOfTheMonth, !daysOfMonth.isEmpty {
            let days = daysOfMonth.map { "\($0)" }.joined(separator: ",")
            components.append("BYMONTHDAY=\(days)")
        }

        // BYMONTH
        if let monthsOfYear = rule.monthsOfTheYear, !monthsOfYear.isEmpty {
            let months = monthsOfYear.map { "\($0)" }.joined(separator: ",")
            components.append("BYMONTH=\(months)")
        }

        // BYYEARDAY
        if let daysOfYear = rule.daysOfTheYear, !daysOfYear.isEmpty {
            let days = daysOfYear.map { "\($0)" }.joined(separator: ",")
            components.append("BYYEARDAY=\(days)")
        }

        // BYWEEKNO
        if let weeksOfYear = rule.weeksOfTheYear, !weeksOfYear.isEmpty {
            let weeks = weeksOfYear.map { "\($0)" }.joined(separator: ",")
            components.append("BYWEEKNO=\(weeks)")
        }

        // BYSETPOS
        if let setPositions = rule.setPositions, !setPositions.isEmpty {
            let positions = setPositions.map { "\($0)" }.joined(separator: ",")
            components.append("BYSETPOS=\(positions)")
        }

        // End condition (COUNT or UNTIL)
        if let recurrenceEnd = rule.recurrenceEnd {
            let occurrenceCount = recurrenceEnd.occurrenceCount
            if occurrenceCount > 0 {
                components.append("COUNT=\(occurrenceCount)")
            } else if let endDate = recurrenceEnd.endDate {
                // Format as YYYYMMDDTHHMMSSZ
                let formatter = DateFormatter()
                formatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
                formatter.timeZone = TimeZone(identifier: "UTC")
                let untilString = formatter.string(from: endDate)
                components.append("UNTIL=\(untilString)")
            }
        }

        return "RRULE:" + components.joined(separator: ";")
    }

    private func parseRRULEString(_ rruleString: String) -> EKRecurrenceRule? {
        // Remove "RRULE:" prefix if present
        let cleanString = rruleString.hasPrefix("RRULE:") ? String(rruleString.dropFirst(6)) : rruleString

        // Parse components
        var frequency: EKRecurrenceFrequency = .daily
        var interval = 1
        var daysOfWeek: [EKRecurrenceDayOfWeek]?
        var daysOfMonth: [NSNumber]?
        var monthsOfYear: [NSNumber]?
        var daysOfYear: [NSNumber]?
        var weeksOfYear: [NSNumber]?
        var setPositions: [NSNumber]?
        var recurrenceEnd: EKRecurrenceEnd?

        // Split by semicolon
        let components = cleanString.components(separatedBy: ";")

        for component in components {
            let parts = component.components(separatedBy: "=")
            guard parts.count == 2 else { continue }

            let key = parts[0].trimmingCharacters(in: .whitespaces)
            let value = parts[1].trimmingCharacters(in: .whitespaces)

            switch key {
            case "FREQ":
                switch value {
                case "DAILY": frequency = .daily
                case "WEEKLY": frequency = .weekly
                case "MONTHLY": frequency = .monthly
                case "YEARLY": frequency = .yearly
                default: frequency = .daily
                }

            case "INTERVAL":
                interval = Int(value) ?? 1

            case "COUNT":
                if let count = Int(value), count > 0 {
                    recurrenceEnd = EKRecurrenceEnd(occurrenceCount: count)
                }

            case "UNTIL":
                // Parse UNTIL date (format: YYYYMMDDTHHMMSSZ)
                let formatter = DateFormatter()
                formatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
                formatter.timeZone = TimeZone(identifier: "UTC")
                if let endDate = formatter.date(from: value) {
                    recurrenceEnd = EKRecurrenceEnd(end: endDate)
                }

            case "BYDAY":
                let dayStrings = value.components(separatedBy: ",")
                daysOfWeek = dayStrings.compactMap { recurrenceDayOfWeekFromString($0) }

            case "BYMONTHDAY":
                let days = value.components(separatedBy: ",").compactMap { Int($0) }
                daysOfMonth = days.map { NSNumber(value: $0) }

            case "BYMONTH":
                let months = value.components(separatedBy: ",").compactMap { Int($0) }
                monthsOfYear = months.map { NSNumber(value: $0) }

            case "BYYEARDAY":
                let days = value.components(separatedBy: ",").compactMap { Int($0) }
                daysOfYear = days.map { NSNumber(value: $0) }

            case "BYWEEKNO":
                let weeks = value.components(separatedBy: ",").compactMap { Int($0) }
                weeksOfYear = weeks.map { NSNumber(value: $0) }

            case "BYSETPOS":
                let positions = value.components(separatedBy: ",").compactMap { Int($0) }
                setPositions = positions.map { NSNumber(value: $0) }

            default:
                break
            }
        }

        let rule = EKRecurrenceRule(
            recurrenceWith: frequency,
            interval: interval,
            daysOfTheWeek: daysOfWeek,
            daysOfTheMonth: daysOfMonth,
            monthsOfTheYear: monthsOfYear,
            weeksOfTheYear: weeksOfYear,
            daysOfTheYear: daysOfYear,
            setPositions: setPositions,
            end: recurrenceEnd
        )

        return rule
    }

    private func recurrenceDayOfWeekFromString(_ dayString: String) -> EKRecurrenceDayOfWeek? {
        // Parse strings like "MO", "TU", "1MO", "-1SU" etc.
        let pattern = "^(?:(\\+|-)?([0-9]{1,2}))?([A-Z]{2})$"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: []),
              let match = regex.firstMatch(in: dayString, range: NSRange(dayString.startIndex..., in: dayString)) else {
            return nil
        }
        
        var weekNumber: Int = 0
        var dayOfWeek: EKWeekday
        
        // Extract week number if present
        if match.range(at: 2).location != NSNotFound {
            let weekNumberString = String(dayString[Range(match.range(at: 2), in: dayString)!])
            weekNumber = Int(weekNumberString) ?? 0
            
            // Check for minus sign
            if match.range(at: 1).location != NSNotFound {
                let signString = String(dayString[Range(match.range(at: 1), in: dayString)!])
                if signString == "-" {
                    weekNumber = -weekNumber
                }
            }
        }
        
        // Extract day of week
        let dayString = String(dayString[Range(match.range(at: 3), in: dayString)!])
        switch dayString {
        case "SU": dayOfWeek = .sunday
        case "MO": dayOfWeek = .monday
        case "TU": dayOfWeek = .tuesday
        case "WE": dayOfWeek = .wednesday
        case "TH": dayOfWeek = .thursday
        case "FR": dayOfWeek = .friday
        case "SA": dayOfWeek = .saturday
        default: return nil
        }
        
        if weekNumber != 0 {
            return EKRecurrenceDayOfWeek(dayOfTheWeek: dayOfWeek, weekNumber: weekNumber)
        } else {
            return EKRecurrenceDayOfWeek(dayOfWeek)
        }
    }
    
    // MARK: - Attendee Handling
    
    private func setAttendees(_ attendeesData: [[String: Any]], _ event: EKEvent) {
        var attendees = [EKParticipant]()
        
        for attendeeDict in attendeesData {
            guard let email = attendeeDict["email"] as? String else { continue }
            let name = attendeeDict["name"] as? String ?? ""
            let role = attendeeDict["role"] as? Int ?? EKParticipantRole.required.rawValue
            
            // Check if attendee already exists
            if let existingAttendees = event.attendees {
                if let existingAttendee = existingAttendees.first(where: { participant in
                    return extractEmailFromParticipant(participant) == email
                }) {
                    attendees.append(existingAttendee)
                    continue
                }
            }
            
            // Create new participant
            if let participant = createParticipant(name: name, emailAddress: email, role: role) {
                attendees.append(participant)
            }
        }
        
        // Use KVC to set attendees (this is a workaround since attendees is normally read-only)
        event.setValue(attendees, forKey: "attendees")
    }
    
    private func createParticipant(name: String, emailAddress: String, role: Int) -> EKParticipant? {
        // This uses a private API approach similar to the reference implementation
        guard let ekAttendeeClass = NSClassFromString("EKAttendee") as? NSObject.Type else {
            return nil
        }
        
        let participant = ekAttendeeClass.init()
        participant.setValue(UUID().uuidString, forKey: "UUID")
        participant.setValue(name, forKey: "displayName")
        participant.setValue(emailAddress, forKey: "emailAddress")
        participant.setValue(role, forKey: "participantRole")
        
        return participant as? EKParticipant
    }
    
    private func extractEmailFromParticipant(_ participant: EKParticipant) -> String {
        #if os(iOS)
        let url = participant.url
        if url.scheme == "mailto" {
            return String(url.absoluteString.dropFirst(7))
        } else {
            return url.absoluteString
        }
        #elseif os(macOS)
        // For macOS, try to get email from the URL or use reflection to access emailAddress
        if let emailAddress = participant.value(forKey: "emailAddress") as? String {
            return emailAddress
        }
        return participant.url.absoluteString
        #endif
    }
}

// MARK: - Extensions

extension Date {
    var millisecondsSinceEpoch: Double {
        return self.timeIntervalSince1970 * 1000.0
    }
}