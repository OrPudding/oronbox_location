# oronbox_location

A lightweight Flutter plugin providing native location support for OronBox

Android and iOS, using Android LocationManager and Apple CoreLocation. No Google Play Services, location SDK, network upload or location history. Desktop and Web are not implemented.

## API

- `checkPermission`, `isPreciseLocationEnabled`, `isLocationServiceEnabled`
- `requestPermission`: foreground only
- `requestBackgroundPermission`: separate, explicit request after foreground authorization
- `openLocationSettings`: open the system location settings
- `openSettings`: recovery after permanent denial or reduced accuracy
- `getCurrentPosition`, `cancelCurrentPosition`
- `positions`, `startUpdates`, `stopUpdates`: one native workout session, shared by the host application

Subscribe before starting updates. The host serializes start/stop and releases the native session after its last consumer leaves. Unsubscribing alone does not stop a session; call `stopUpdates`. Engine detachment releases requests and subscriptions.

Position timestamps are UTC milliseconds. Android freshness uses elapsed realtime rather than wall-clock subtraction. Missing accuracy, altitude, speed, bearing and vertical accuracy remain distinguishable from valid zero values. Android includes provider and the number of satellites used in the fix; iOS does not fabricate a satellite count.

## Android

Minimum API 24. The plugin declares fine/coarse/background location, foreground service and location foreground-service permissions. Request foreground permission from a visible Activity. Android 11+ background authorization is granted separately in application settings; re-check permission after the app resumes. Approximate permission does not authorize precise workout tracking.

Service startup has a 10-second deadline; unexpected service termination and disabled GPS are reported to session consumers. The native foreground service exists only during a location session and uses host-supplied localized notification text. Android may restrict starting a location foreground service from the background even when permissions are present. The plugin reports `BACKGROUND_START_RESTRICTED` and can post a host-supplied recovery notification. Notification permission remains the host's responsibility; notification denial cannot bypass OS restrictions. No boot receiver, process-restart tracking or automatic background-permission escalation is installed.

Single fixes can use GPS or the system network provider. Continuous workout updates use GPS at a requested 1-second interval; delivery timing is controlled by Android. Freshness, permission, disabled services, busy requests, cancellation and timeouts are reported separately. No stale fallback is returned on timeout.

## iOS

The host must supply `NSLocationWhenInUseUsageDescription`, `NSLocationAlwaysAndWhenInUseUsageDescription`, and `location` in `UIBackgroundModes` for continuous sessions. CoreLocation may defer Always authorization; the request returns the current state and the host must re-check on resume. Background sessions show the system location indicator. iOS retains control over background execution.

## Development

Use a local path while developing. Consumers should pin a Git tag after a verified release. No published tag is assumed by this initial implementation.
