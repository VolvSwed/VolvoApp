import SwiftUI
import UIKit
import WebKit

struct ClubWebView: UIViewRepresentable {
    let accessToken: String
    let expiresAt: Date
    let onSessionExpired: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onSessionExpired: onSessionExpired)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.applicationNameForUserAgent = "VolvoClubIOS/\(AppConfig.version)"

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.contentInsetAdjustmentBehavior = .automatic
        context.coordinator.webView = webView
        context.coordinator.installPushObserver()

        setSessionCookie(in: webView) {
            let pendingPath = PushNavigation.consume()
            webView.load(URLRequest(url: AppConfig.webURL(pushPath: pendingPath)))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    private func setSessionCookie(in webView: WKWebView, completion: @escaping () -> Void) {
        let hosts = Set([AppConfig.webAppURL.host, AppConfig.apiBaseURL.host].compactMap { $0 })
        guard !hosts.isEmpty else { completion(); return }
        let group = DispatchGroup()
        for host in hosts {
            let properties: [HTTPCookiePropertyKey: Any] = [
                .name: "volvo_mobile_session",
                .value: accessToken,
                .domain: host,
                .path: "/",
                .secure: "TRUE",
                .expires: expiresAt,
                HTTPCookiePropertyKey("HttpOnly"): "TRUE",
                HTTPCookiePropertyKey("SameSite"): "Lax"
            ]
            guard let cookie = HTTPCookie(properties: properties) else { continue }
            group.enter()
            webView.configuration.websiteDataStore.httpCookieStore.setCookie(cookie) { group.leave() }
        }
        group.notify(queue: .main, execute: completion)
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKDownloadDelegate {
        weak var webView: WKWebView?
        private let onSessionExpired: () -> Void
        private var pushObserver: NSObjectProtocol?
        private var downloadDestinations: [ObjectIdentifier: URL] = [:]

        init(onSessionExpired: @escaping () -> Void) {
            self.onSessionExpired = onSessionExpired
        }

        deinit {
            if let pushObserver { NotificationCenter.default.removeObserver(pushObserver) }
        }

        func installPushObserver() {
            guard pushObserver == nil else { return }
            pushObserver = NotificationCenter.default.addObserver(
                forName: .didOpenPushPath,
                object: nil,
                queue: .main
            ) { [weak self] notification in
                let path = PushNavigation.consume() ?? (notification.object as? String)
                guard let path,
                      let webView = self?.webView else { return }
                webView.load(URLRequest(url: AppConfig.webURL(pushPath: path)))
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            preferences: WKWebpagePreferences,
            decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void
        ) {
            if navigationAction.shouldPerformDownload {
                decisionHandler(.download, preferences)
                return
            }
            guard let url = navigationAction.request.url else {
                decisionHandler(.cancel, preferences)
                return
            }
            if AppConfig.isTrustedWebURL(url) {
                decisionHandler(.allow, preferences)
                return
            }
            decisionHandler(.cancel, preferences)
            DispatchQueue.main.async { UIApplication.shared.open(url) }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationResponse: WKNavigationResponse,
            decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
        ) {
            if let response = navigationResponse.response as? HTTPURLResponse,
               response.statusCode == 401 {
                decisionHandler(.cancel)
                DispatchQueue.main.async { self.onSessionExpired() }
                return
            }
            if !navigationResponse.canShowMIMEType {
                decisionHandler(.download)
                return
            }
            decisionHandler(.allow)
        }

        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            guard navigationAction.targetFrame == nil,
                  let url = navigationAction.request.url else { return nil }
            if AppConfig.isTrustedWebURL(url) {
                webView.load(URLRequest(url: url))
            } else {
                UIApplication.shared.open(url)
            }
            return nil
        }

        func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
            download.delegate = self
        }

        func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
            download.delegate = self
        }

        func download(
            _ download: WKDownload,
            decideDestinationUsing response: URLResponse,
            suggestedFilename: String,
            completionHandler: @escaping (URL?) -> Void
        ) {
            let safeName = suggestedFilename.replacingOccurrences(of: "/", with: "-")
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
                .appendingPathComponent(safeName)
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            downloadDestinations[ObjectIdentifier(download)] = url
            completionHandler(url)
        }

        func downloadDidFinish(_ download: WKDownload) {
            guard let url = downloadDestinations.removeValue(forKey: ObjectIdentifier(download)) else { return }
            DispatchQueue.main.async {
                guard let presenter = Self.topViewController() else { return }
                let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
                controller.popoverPresentationController?.sourceView = presenter.view
                presenter.present(controller, animated: true)
            }
        }

        func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
            downloadDestinations.removeValue(forKey: ObjectIdentifier(download))
            print("Download failed: \(error.localizedDescription)")
        }

        private static func topViewController() -> UIViewController? {
            let root = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first(where: { $0.isKeyWindow })?.rootViewController
            var current = root
            while let presented = current?.presentedViewController { current = presented }
            return current
        }
    }
}
