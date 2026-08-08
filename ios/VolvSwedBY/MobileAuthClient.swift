import Foundation

struct MobileAuthClient {
    enum ClientError: LocalizedError {
        case invalidResponse
        case server(String, Int)

        var errorDescription: String? {
            switch self {
            case .invalidResponse:
                return "Сервер вернул некорректный ответ."
            case let .server(message, _):
                return message
            }
        }
    }

    private let session: URLSession

    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        session = URLSession(configuration: configuration)
    }

    func exchange(code: String) async throws -> SessionTokens {
        try await postSession(path: "mobile/auth/exchange", body: ["code": code])
    }

    func refresh(refreshToken: String) async throws -> SessionTokens {
        try await postSession(path: "mobile/auth/refresh", body: ["refresh_token": refreshToken])
    }

    func registerPush(accessToken: String, deviceToken: String, environment: String) async throws {
        let body: [String: Any] = [
            "platform": "ios",
            "device_token": deviceToken,
            "environment": environment,
            "app_version": AppConfig.version
        ]
        var request = request(path: "mobile/push/register", method: "POST")
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        _ = try await perform(request)
    }

    func unregisterPush(accessToken: String, deviceToken: String) async throws {
        var request = request(path: "mobile/push/register", method: "DELETE")
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["device_token": deviceToken])
        _ = try await perform(request)
    }

    func logout(accessToken: String) async {
        var request = request(path: "mobile/auth/logout", method: "POST")
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        _ = try? await perform(request)
    }

    private func postSession(path: String, body: [String: String]) async throws -> SessionTokens {
        var request = request(path: path, method: "POST")
        request.httpBody = try JSONEncoder().encode(body)
        let data = try await perform(request)
        return try JSONDecoder().decode(SessionTokens.self, from: data)
    }

    private func request(path: String, method: String) -> URLRequest {
        var request = URLRequest(url: AppConfig.apiURL(path))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        return request
    }

    private func perform(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw ClientError.invalidResponse }
        guard (200...299).contains(http.statusCode) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw ClientError.server(message ?? "Ошибка сервера \(http.statusCode)", http.statusCode)
        }
        return data
    }
}
