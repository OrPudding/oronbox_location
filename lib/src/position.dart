class Position {
  Position({
    required this.latitude,
    required this.longitude,
    required this.timestamp,
    required this.accuracy,
    this.hasAccuracy = true,
    int? ageMilliseconds,
    this.altitude = 0,
    this.hasAltitude = false,
    this.speed,
    this.bearing,
    this.verticalAccuracy,
    this.satellitesUsed,
    this.provider,
  }) : _ageMilliseconds = ageMilliseconds;

  factory Position.fromMap(Map<Object?, Object?> value) => Position(
    latitude: (value['latitude'] as num).toDouble(),
    longitude: (value['longitude'] as num).toDouble(),
    timestamp: DateTime.fromMillisecondsSinceEpoch(
      (value['timestamp'] as num).toInt(),
      isUtc: true,
    ),
    accuracy: (value['accuracy'] as num?)?.toDouble() ?? 0,
    hasAccuracy: value['accuracy'] != null,
    ageMilliseconds: (value['ageMilliseconds'] as num?)?.toInt(),
    altitude: (value['altitude'] as num?)?.toDouble() ?? 0,
    hasAltitude: value['altitude'] != null,
    speed: (value['speed'] as num?)?.toDouble(),
    bearing: (value['bearing'] as num?)?.toDouble(),
    verticalAccuracy: (value['verticalAccuracy'] as num?)?.toDouble(),
    satellitesUsed: (value['satellitesUsed'] as num?)?.toInt(),
    provider: value['provider'] as String?,
  );

  final double latitude, longitude, accuracy, altitude;
  final DateTime timestamp;
  final bool hasAccuracy, hasAltitude;
  final int? _ageMilliseconds;
  final Stopwatch _ageClock = Stopwatch()..start();
  int? get ageMilliseconds => _ageMilliseconds == null
      ? null
      : _ageMilliseconds + _ageClock.elapsedMilliseconds;
  final double? speed, bearing, verticalAccuracy;
  final int? satellitesUsed;
  final String? provider;
}
