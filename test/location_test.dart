import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:oronbox_location/oronbox_location.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const methods = MethodChannel('org.zxor.oronbox/location');
  final calls = <MethodCall>[];
  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methods, (call) async {
          calls.add(call);
          return switch (call.method) {
            'requestPermission' => 2,
            'requestBackgroundPermission' => 3,
            'current' => {
              'latitude': 0.0,
              'longitude': 0.0,
              'timestamp': 1000,
              'accuracy': 0.0,
            },
            _ => null,
          };
        });
  });
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methods, null),
  );
  test(
    'foreground authorization never requests background implicitly',
    () async {
      final location = OronboxLocation();
      expect(await location.requestPermission(), LocationPermission.whileInUse);
      expect(calls.map((call) => call.method), ['requestPermission']);
      expect(
        await location.requestBackgroundPermission(),
        LocationPermission.always,
      );
    },
  );
  test(
    'valid zero coordinates and precision survive, missing altitude stays missing',
    () async {
      final position = await OronboxLocation().getCurrentPosition();
      expect(position.latitude, 0);
      expect(position.longitude, 0);
      expect(position.hasAccuracy, isTrue);
      expect(position.accuracy, 0);
      expect(position.hasAltitude, isFalse);
    },
  );
  test(
    'single-fix timeout and age limits are forwarded in milliseconds',
    () async {
      await OronboxLocation().getCurrentPosition(
        timeout: const Duration(seconds: 8),
        maximumAge: const Duration(seconds: 2),
      );
      expect(calls.single.arguments, {'timeoutMs': 8000, 'maximumAgeMs': 2000});
    },
  );
  test(
    'last position listener cancels the native event subscription',
    () async {
      const events = MethodChannel('org.zxor.oronbox/location/positions');
      final lifecycle = <String>[];
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(events, (call) async {
        lifecycle.add(call.method);
        return null;
      });
      final first = OronboxLocation().positions.listen((_) {});
      final second = OronboxLocation().positions.listen((_) {});
      await Future<void>.delayed(Duration.zero);
      expect(lifecycle, ['listen']);
      await first.cancel();
      expect(lifecycle, ['listen']);
      await second.cancel();
      expect(lifecycle, ['listen', 'cancel']);
      messenger.setMockMethodCallHandler(events, null);
    },
  );
  test('native fix age keeps advancing while queued in Dart', () async {
    final position = Position.fromMap({
      'latitude': 0.0,
      'longitude': 0.0,
      'timestamp': 1000,
      'accuracy': 5.0,
      'ageMilliseconds': 100,
    });
    final initial = position.ageMilliseconds!;
    await Future<void>.delayed(const Duration(milliseconds: 15));
    expect(position.ageMilliseconds, greaterThan(initial));
  });
}
