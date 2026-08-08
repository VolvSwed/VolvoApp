import SwiftUI

struct RootView: View {
    @ObservedObject var state: AppState
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch state.phase {
            case .loading:
                ZStack {
                    Color(red: 0.055, green: 0.078, blue: 0.086).ignoresSafeArea()
                    ProgressView().tint(.white)
                }
            case let .loggedOut(message):
                LoginView(message: message, action: state.startTelegramLogin)
            case let .authenticated(tokens):
                ClubWebView(accessToken: tokens.accessToken, expiresAt: tokens.accessExpiryDate) {
                    state.sessionExpired()
                }
                .ignoresSafeArea(.container, edges: .bottom)
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { state.refreshIfNeeded() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .didReceiveAPNsToken)) { notification in
            guard let token = notification.userInfo?["token"] as? String,
                  let environment = notification.userInfo?["environment"] as? String else { return }
            state.acceptAPNsToken(token, environment: environment)
        }
    }
}

private struct LoginView: View {
    let message: String?
    let action: () -> Void

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.055, green: 0.078, blue: 0.086), Color(red: 0.11, green: 0.14, blue: 0.15)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 20) {
                Image(systemName: "car.side.fill")
                    .font(.system(size: 56, weight: .medium))
                    .foregroundStyle(.white)
                Text("VolvSwedBY")
                    .font(.system(size: 34, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                Text(message ?? "Войдите через Telegram, чтобы открыть клубное приложение.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white.opacity(0.72))
                    .padding(.horizontal, 28)
                Button(action: action) {
                    Text("Войти через Telegram")
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color(red: 0.20, green: 0.51, blue: 0.70))
                .padding(.horizontal, 28)
            }
        }
    }
}
