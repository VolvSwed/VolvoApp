import Foundation

extension Notification.Name {
    static let didReceiveAPNsToken = Notification.Name("VolvSwedBY.didReceiveAPNsToken")
    static let didOpenPushPath = Notification.Name("VolvSwedBY.didOpenPushPath")
}

enum PushNavigation {
    private static let key = "VolvSwedBY.pendingPushPath"

    static func store(_ path: String) {
        guard AppConfig.safePushFragment(path) != nil else { return }
        UserDefaults.standard.set(path, forKey: key)
        NotificationCenter.default.post(name: .didOpenPushPath, object: path)
    }

    static func consume() -> String? {
        let path = UserDefaults.standard.string(forKey: key)
        UserDefaults.standard.removeObject(forKey: key)
        guard let path, AppConfig.safePushFragment(path) != nil else { return nil }
        return path
    }
}
