import Foundation

struct SessionTokens: Codable, Equatable {
    let accessToken: String
    let refreshToken: String
    let accessExpiresAt: Int64
    let refreshExpiresAt: Int64

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case accessExpiresAt = "access_expires_at"
        case refreshExpiresAt = "refresh_expires_at"
    }

    func accessIsFresh(now: Date = Date()) -> Bool {
        accessExpiresAt > Int64(now.timeIntervalSince1970 * 1000) + 60_000
    }

    func canRefresh(now: Date = Date()) -> Bool {
        refreshExpiresAt > Int64(now.timeIntervalSince1970 * 1000) + 60_000
    }

    var accessExpiryDate: Date {
        Date(timeIntervalSince1970: TimeInterval(accessExpiresAt) / 1000)
    }
}
