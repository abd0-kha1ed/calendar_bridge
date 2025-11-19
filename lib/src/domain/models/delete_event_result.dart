import 'package:flutter/foundation.dart';

/// Result of a delete event operation, particularly for recurring events
///
/// When deleting recurring events with "this and following instances" option,
/// the eventId may change because Android recreates the event with updated RRULE.
@immutable
class DeleteEventResult {
  /// Whether the delete operation succeeded
  final bool success;

  /// The event ID after the operation
  ///
  /// For simple deletes: null (event is deleted)
  /// For "delete this instance": returns original eventId (exception created)
  /// For "delete this and following": may return new eventId (event recreated)
  final String? eventId;

  const DeleteEventResult({
    required this.success,
    this.eventId,
  });

  /// Creates result from platform method channel response
  factory DeleteEventResult.fromMap(Map<dynamic, dynamic> map) {
    return DeleteEventResult(
      success: map['success'] as bool? ?? false,
      eventId: map['eventId'] as String?,
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;

    return other is DeleteEventResult &&
        other.success == success &&
        other.eventId == eventId;
  }

  @override
  int get hashCode => Object.hash(success, eventId);

  @override
  String toString() =>
      'DeleteEventResult(success: $success, eventId: $eventId)';
}
