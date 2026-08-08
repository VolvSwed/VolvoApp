import AuthenticationServices
import SwiftUI
import UserNotifications

@MainActor
final class AppState: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    enum Phase {
        case loading
        case loggedOut(String?)
        case authenticated(SessionTokens)
    }

    @Published private(set) var phase: Phase = .loading

    private let store = KeychainSessionStore()
    private let client = MobileAuthClient()
    private var authenticationSession: ASWebAuthenticationSession?
    private var lastPushToken: (token: String, environment: String)?

    override init() {
        super.init()
        Task { await restoreSession() }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: { $0.isKeyWindow }) ?? ASPresentationAnchor()
    }

    func startTelegramLogin() {
        phase = .loading
        let session = ASWebAuthenticationSession(
            url: AppConfig.loginStartURL,
            callbackURLScheme: AppConfig.loginRedirectScheme
        ) { [weak self] callbackURL, error in
            Task { @MainActor in
                guard let self else { return }
                self.authenticationSession = nil
                if let callbackURL {
                    await self.finishTelegramLogin(callbackURL)
                } else {
                    let message = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
                        ? nil
                        : error?.localizedDescription
                    self.phase = .loggedOut(message)
                }
            }
        }
        session.presentationContextProvider = self
        session.prefersEphemeralWebBrowserSession = false
        authenticationSession = session
        if !session.start() {
            authenticationSession = nil
            phase = .loggedOut("Не удалось открыть вход через Telegram.")
        }
    }

    func refreshIfNeeded() {
        guard case let .authenticated(tokens) = phase, !tokens.accessIsFresh() else { return }
        Task { await refresh(tokens) }
    }

    func acceptAPNsToken(_ token: String, environment: String) {
        guard !token.isEmpty else { return }
        lastPushToken = (token, environment)
        guard case let .authenticated(tokens) = phase else { return }
        Task { await syncPushToken(tokens: tokens) }
    }

    func sessionExpired() {
        guard case let .authenticated(tokens) = phase else { return }
        if tokens.canRefresh() {
            Task { await refresh(tokens) }
        } else {
            clearSession(message: "Сессия приложения закончилась. Войдите снова через Telegram.")
        }
    }

    func logout() {
        guard case let .authenticated(tokens) = phase else {
            clearSession(message: nil)
            return
        }
        let push = lastPushToken
        store.clear()
        phase = .loggedOut(nil)
        Task {
            if let push {
                try? await client.unregisterPush(accessToken: tokens.accessToken, deviceToken: push.token)
            }
            await client.logout(accessToken: tokens.accessToken)
        }
    }

    private func restoreSession() async {
        guard let tokens = store.read() else {
            phase = .loggedOut(nil)
            return
        }
        if tokens.accessIsFresh() {
            await accept(tokens)
        } else if tokens.canRefresh() {
            await refresh(tokens)
        } else {
            clearSession(message: nil)
        }
    }

    private func finishTelegramLogin(_ url: URL) async {
        guard url.scheme == AppConfig.loginRedirectScheme, url.host == AppConfig.loginRedirectHost else {
            phase = .loggedOut("Приложение получило неизвестный адрес возврата.")
            return
        }
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let items = components?.queryItems ?? []
        if let message = items.first(where: { $0.name == "error" })?.value, !message.isEmpty {
            phase = .loggedOut(message)
            return
        }
        guard let code = items.first(where: { $0.name == "code" })?.value, !code.isEmpty else {
            phase = .loggedOut("Telegram не вернул код входа.")
            return
        }
        do {
            let tokens = try await client.exchange(code: code)
            await accept(tokens)
        } catch {
            phase = .loggedOut(error.localizedDescription)
        }
    }

    private func refresh(_ tokens: SessionTokens) async {
        guard tokens.canRefresh() else {
            clearSession(message: "Сессия приложения закончилась. Войдите снова через Telegram.")
            return
        }
        do {
            let updated = try await client.refresh(refreshToken: tokens.refreshToken)
            await accept(updated)
        } catch {
            clearSession(message: "Не удалось обновить сессию. Войдите снова через Telegram.")
        }
    }

    private func accept(_ tokens: SessionTokens) async {
        do {
            try store.save(tokens)
            phase = .authenticated(tokens)
            requestPushPermission()
            await syncPushToken(tokens: tokens)
        } catch {
            clearSession(message: "Не удалось безопасно сохранить сессию на iPhone.")
        }
    }

    private func requestPushPermission() {
        Task {
            let center = UNUserNotificationCenter.current()
            let settings = await center.notificationSettings()
            var authorized = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
            if settings.authorizationStatus == .notDetermined {
                authorized = (try? await center.requestAuthorization(options: [.alert, .badge, .sound])) == true
            }
            if authorized {
                await MainActor.run { UIApplication.shared.registerForRemoteNotifications() }
            }
        }
    }

    private func syncPushToken(tokens: SessionTokens) async {
        guard let push = lastPushToken else { return }
        try? await client.registerPush(
            accessToken: tokens.accessToken,
            deviceToken: push.token,
            environment: push.environment
        )
    }

    private func clearSession(message: String?) {
        store.clear()
        phase = .loggedOut(message)
    }
}
