import ConnectIQ
import Flutter
import UIKit

public class SwiftWatchConnectivityGarminPlugin: NSObject, FlutterPlugin, IQDeviceEventDelegate, IQAppMessageDelegate {
    private static let deviceIdsKey = "watch_connectivity_garmin/deviceIds"

    let channel: FlutterMethodChannel
    let connectIQ = ConnectIQ.sharedInstance()!
    let defaults = UserDefaults.standard
    var applicationId: String?
    var urlScheme: String?

    init(channel: FlutterMethodChannel) {
        self.channel = channel

        super.init()
    }

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "watch_connectivity_garmin", binaryMessenger: registrar.messenger())
        let instance = SwiftWatchConnectivityGarminPlugin(channel: channel)
        registrar.addMethodCallDelegate(instance, channel: channel)
        registrar.addApplicationDelegate(instance)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        // Getters
        case "isSupported":
            isSupported(result)
        case "isPaired":
            isPaired(result)
        case "isReachable":
            isReachable(result)

        // Methods
        case "initialize":
            initialize(call, result)
        case "showDeviceSelection":
            showDeviceSelection(result)
        case "openUrl":
            openUrl(call, result)
        case "sendMessage":
            sendMessage(call, result)

        // Not implemented
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    public func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        guard url.scheme == urlScheme else {
            return false
        }

        let devices = connectIQ.parseDeviceSelectionResponse(from: url) as? [IQDevice]
        guard devices != nil else {
            return false
        }

        defaults.set(devices!.map { $0.uuid.uuidString }, forKey: Self.deviceIdsKey)
        registerForEvents()

        return true
    }

    public func openUrl(_ call: FlutterMethodCall, _ result: FlutterResult) {
        let args = call.arguments as! [String: Any]

        guard let url = args["url"] as? String, let urlEncoded = url.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            result(false)
            return
        }

        let urlObject = URL(string: urlEncoded)

        guard urlObject?.scheme == urlScheme else {
            result(false)
            return
        }

        let devices = connectIQ.parseDeviceSelectionResponse(from: urlObject) as? [IQDevice]

        guard devices != nil else {
            result(false)
            return
        }

        defaults.set(devices!.map { $0.uuid.uuidString }, forKey: Self.deviceIdsKey)
        registerForEvents()

        result(true)
    }

    private func getCachedDevices() -> [IQDevice] {
        return defaults.stringArray(forKey: Self.deviceIdsKey)?.map { IQDevice(id: UUID(uuidString: $0), modelName: "", friendlyName: "")! } ?? []
    }

    private func createApps() -> [IQApp] {
        return getCachedDevices().map { IQApp(uuid: UUID(uuidString: applicationId!)!, store: UUID(), device: $0) }
    }

    private func registerForEvents() {
        connectIQ.unregister(forAllAppMessages: self)
        connectIQ.unregister(forAllDeviceEvents: self)

        let apps = createApps()
        for app in apps {
            connectIQ.register(forDeviceEvents: app.device, delegate: self)
            connectIQ.register(forAppMessages: app, delegate: self)
        }
    }

    private func initialize(_ call: FlutterMethodCall, _ result: FlutterResult) {
        let args = call.arguments as! [String: Any]
        applicationId = args["applicationId"] as? String
        urlScheme = args["urlScheme"] as? String

        let autoUI = args["autoUI"] as? Bool ?? false

        connectIQ.initialize(withUrlScheme: urlScheme, uiOverrideDelegate: autoUI ? nil : IQUIOverrideDelegateStub())

        registerForEvents()

        result(nil)
    }

    private func showDeviceSelection(_ result: FlutterResult) {
        connectIQ.showDeviceSelection()
        result(nil)
    }

    private func isSupported(_ result: FlutterResult) {
        result(UIApplication.shared.canOpenURL(URL(string: "gcm-ciq://stub")!))
    }

    private func isPaired(_ result: FlutterResult) {
        result(getCachedDevices().isEmpty == false)
    }

    private func isReachable(_ result: @escaping FlutterResult) {
        Task { await isReachableAsync(result) }
    }

    private func isReachableAsync(_ result: FlutterResult) async {
        let apps = createApps()
        for app in apps {
            let installed = await withCheckedContinuation { continuation in
                connectIQ.getAppStatus(app) { status in
                    continuation.resume(returning: status?.isInstalled == true)
                }
            }
            if installed {
                result(true)
                return
            }
        }

        result(false)
    }

    private func sendMessage(_ call: FlutterMethodCall, _ result: FlutterResult) {
        guard applicationId != nil else {
            result(FlutterError(code: "Not Initialized", message: nil, details: nil))
            return
        }

        let connectedApps = createApps().filter { connectIQ.getDeviceStatus($0.device) == .connected }
        
        guard !connectedApps.isEmpty else {
            result(nil)
            return
        }

        var errors = [String]()
        for app in connectedApps {
            connectIQ.sendMessage(call.arguments, to: app, progress: { _, _ in }) { result in
                if result != .success {
                    errors.append("\(result)")
                }
            }
        }

        if errors.isEmpty {
            result(nil)
        } else {
            result(FlutterError(code: "Error sending message", message: errors.joined(separator: ", "), details: nil))
        }
    }
    
    public func deviceStatusChanged(_ device: IQDevice!, status: IQDeviceStatus) {
        // Don't care
    }
    
    public func receivedMessage(_ message: Any!, from app: IQApp!) {
        channel.invokeMethod("didReceiveMessage", arguments: message)
    }
}

class IQUIOverrideDelegateStub: NSObject, IQUIOverrideDelegate {}
