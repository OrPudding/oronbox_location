import CoreLocation
import Flutter
import UIKit

public class OronboxLocationPlugin: NSObject, FlutterPlugin, FlutterStreamHandler, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var sink: FlutterEventSink?
    private var permissionResult: FlutterResult?
    private var currentResult: FlutterResult?
    private var timer: Timer?
    private var requestedAt: Date?
    private var maximumAge: TimeInterval = 0
    private var streaming = false

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = OronboxLocationPlugin()
        registrar.addMethodCallDelegate(instance, channel: FlutterMethodChannel(name: "org.zxor.oronbox/location", binaryMessenger: registrar.messenger()))
        FlutterEventChannel(name: "org.zxor.oronbox/location/positions", binaryMessenger: registrar.messenger()).setStreamHandler(instance)
    }
    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .fitness
        manager.pausesLocationUpdatesAutomatically = false
    }
    private var authorization: CLAuthorizationStatus {
        if #available(iOS 14.0, *) { return manager.authorizationStatus }
        return CLLocationManager.authorizationStatus()
    }
    private var permission: Int {
        switch authorization {
        case .authorizedAlways: return 3
        case .authorizedWhenInUse: return 2
        case .restricted, .denied: return 1
        default: return 0
        }
    }
    private var precise: Bool {
        if #available(iOS 14.0, *) { return permission >= 2 && manager.accuracyAuthorization == .fullAccuracy }
        return permission >= 2
    }
    private func error(_ code: String) -> FlutterError { FlutterError(code: code, message: "Location operation unavailable", details: nil) }
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let args = call.arguments as? [String: Any] ?? [:]
        switch call.method {
        case "serviceEnabled": result(CLLocationManager.locationServicesEnabled())
        case "preciseEnabled": result(precise)
        case "permission": result(permission)
        case "requestPermission", "requestBackgroundPermission":
            guard permissionResult == nil else { result(error("BUSY")); return }
            let background = call.method == "requestBackgroundPermission"
            if background && permission < 2 { result(error("FOREGROUND_PERMISSION_REQUIRED")); return }
            if (!background && permission != 0) || (background && permission == 3) { result(permission); return }
            let key = background ? "NSLocationAlwaysAndWhenInUseUsageDescription" : "NSLocationWhenInUseUsageDescription"
            guard Bundle.main.object(forInfoDictionaryKey: key) != nil else { result(error("MISSING_USAGE_DESCRIPTION")); return }
            if background {
                // iOS can defer the Always prompt. Re-check authorization on resume.
                manager.requestAlwaysAuthorization(); result(permission)
            } else { permissionResult = result; manager.requestWhenInUseAuthorization() }
        case "openSettings", "openLocationSettings":
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            result(nil)
        case "current":
            guard currentResult == nil else { result(error("BUSY")); return }
            guard ready(result) else { return }
            maximumAge = max(0, (args["maximumAgeMs"] as? NSNumber)?.doubleValue ?? 0) / 1000
            requestedAt = Date()
            if maximumAge > 0, let cached = manager.location, valid(cached) { result(encode(cached)); return }
            currentResult = result
            timer = Timer.scheduledTimer(withTimeInterval: max(1, min(120, ((args["timeoutMs"] as? NSNumber)?.doubleValue ?? 30000) / 1000)), repeats: false) { [weak self] _ in
                self?.finishCurrent(nil, "TIMEOUT")
            }
            manager.startUpdatingLocation()
        case "cancelCurrent": finishCurrent(nil, "CANCELLED"); result(nil)
        case "start":
            if UIApplication.shared.applicationState != .active && permission != 3 {
                result(error("BACKGROUND_START_RESTRICTED")); return
            }
            guard ready(result) else { return }
            let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
            guard modes.contains("location") else { result(error("MISSING_BACKGROUND_MODE")); return }
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = true
            streaming = true; manager.startUpdatingLocation(); result(nil)
        case "stop":
            streaming = false; manager.allowsBackgroundLocationUpdates = false
            if currentResult == nil { manager.stopUpdatingLocation() }
            result(nil)
        default: result(FlutterMethodNotImplemented)
        }
    }
    private func ready(_ result: FlutterResult) -> Bool {
        if !CLLocationManager.locationServicesEnabled() { result(error("SERVICE_DISABLED")); return false }
        if !precise { result(error("PRECISE_PERMISSION_REQUIRED")); return false }
        return true
    }
    private func valid(_ location: CLLocation) -> Bool {
        let age = -location.timestamp.timeIntervalSinceNow
        guard location.horizontalAccuracy >= 0 && age >= -1 else { return false }
        return maximumAge > 0 ? age <= maximumAge : location.timestamp >= (requestedAt ?? Date())
    }
    private func encode(_ location: CLLocation) -> [String: Any] {
        var result: [String: Any] = ["provider": "core_location", "latitude": location.coordinate.latitude, "longitude": location.coordinate.longitude,
            "timestamp": location.timestamp.timeIntervalSince1970 * 1000,
            "ageMilliseconds": max(0, -location.timestamp.timeIntervalSinceNow * 1000)]
        if location.horizontalAccuracy >= 0 { result["accuracy"] = location.horizontalAccuracy }
        if location.verticalAccuracy >= 0 { result["altitude"] = location.altitude; result["verticalAccuracy"] = location.verticalAccuracy }
        if location.speed >= 0 { result["speed"] = location.speed }
        if location.course >= 0 { result["bearing"] = location.course }
        return result
    }
    private func finishCurrent(_ location: CLLocation?, _ code: String?) {
        let callback = currentResult; currentResult = nil
        timer?.invalidate(); timer = nil
        if !streaming { manager.stopUpdatingLocation() }
        if let location = location { callback?(encode(location)) }
        else if let code = code { callback?(error(code)) }
    }
    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for location in locations {
            if currentResult != nil && valid(location) { finishCurrent(location, nil) }
            let age = -location.timestamp.timeIntervalSinceNow
            if streaming && location.horizontalAccuracy >= 0 && age >= -1 && age <= 10 { sink?(encode(location)) }
        }
    }
    public func locationManager(_ manager: CLLocationManager, didFailWithError failure: Error) {
        if (failure as? CLError)?.code == .locationUnknown { return }
        let code = (failure as? CLError)?.code == .denied ? "PERMISSION_DENIED" : "LOCATION_ERROR"
        finishCurrent(nil, code)
        if streaming { sink?(error(code)) }
    }
    private func authorizationChanged() {
        if let callback = permissionResult, authorization != .notDetermined { permissionResult = nil; callback(permission) }
        if permission < 2 {
            finishCurrent(nil, "PERMISSION_DENIED")
            if streaming { sink?(error("PERMISSION_DENIED")); streaming = false; manager.stopUpdatingLocation() }
        }
    }
    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { authorizationChanged() }
    public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) { authorizationChanged() }
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? { sink = events; return nil }
    public func onCancel(withArguments arguments: Any?) -> FlutterError? { sink = nil; return nil }
    public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
        streaming = false; finishCurrent(nil, "CANCELLED"); manager.stopUpdatingLocation()
        permissionResult?(error("CANCELLED")); permissionResult = nil; sink = nil; manager.delegate = nil
    }
}
