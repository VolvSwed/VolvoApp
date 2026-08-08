import Foundation

enum AppConfig {
    static let loginRedirectScheme = "volvoclub"
    static let loginRedirectHost = "auth"

    static var webAppURL: URL {
        configuredURL(key: "VOLVO_WEB_APP_URL", fallback: "https://volvswed.site")
    }

    static var apiBaseURL: URL {
        configuredURL(key: "VOLVO_API_BASE_URL", fallback: "https://volvswed.site")
    }

    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"
    }

    static var loginStartURL: URL {
        var components = URLComponents(url: apiURL("mobile/auth/start"), resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "platform", value: "ios"),
            URLQueryItem(name: "redirect_uri", value: "\(loginRedirectScheme)://\(loginRedirectHost)")
        ]
        return components.url!
    }

    static func webURL(pushPath: String? = nil) -> URL {
        var components = URLComponents(url: webAppURL, resolvingAgainstBaseURL: false)!
        var queryItems = components.queryItems ?? []
        if !queryItems.contains(where: { $0.name == "platform" }) {
            queryItems.append(URLQueryItem(name: "platform", value: "ios"))
        }
        components.queryItems = queryItems

        if let pushPath, let fragment = safePushFragment(pushPath) {
            components.fragment = fragment
        }
        return components.url!
    }

    static func isTrustedWebURL(_ url: URL) -> Bool {
        guard url.scheme == "https", let host = url.host?.lowercased(),
              let configuredHost = webAppURL.host?.lowercased() else { return false }
        return host == configuredHost || host.hasSuffix(".\(configuredHost)")
    }

    static func apiURL(_ path: String) -> URL {
        path.split(separator: "/").reduce(apiBaseURL) { partial, component in
            partial.appendingPathComponent(String(component))
        }
    }

    static func safePushFragment(_ path: String) -> String? {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        let fragment: String
        if trimmed.hasPrefix("/#/") {
            fragment = String(trimmed.dropFirst(3))
        } else if trimmed.hasPrefix("#/") {
            fragment = String(trimmed.dropFirst(2))
        } else {
            return nil
        }
        guard !fragment.isEmpty,
              fragment.allSatisfy({ $0.isLetter || $0.isNumber || "/_-".contains($0) }) else { return nil }
        return "/\(fragment)"
    }

    private static func configuredURL(key: String, fallback: String) -> URL {
        let raw = (Bundle.main.object(forInfoDictionaryKey: key) as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let candidate = raw.flatMap(URL.init(string:)) ?? URL(string: fallback)!
        precondition(candidate.scheme == "https", "\(key) must use HTTPS")
        return candidate
    }
}
