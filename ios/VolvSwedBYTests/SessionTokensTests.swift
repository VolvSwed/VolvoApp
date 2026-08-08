import XCTest
@testable import VolvSwedBY

final class SessionTokensTests: XCTestCase {
    func testFreshAccessAndRefresh() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let tokens = SessionTokens(
            accessToken: "access",
            refreshToken: "refresh",
            accessExpiresAt: Int64((now.timeIntervalSince1970 + 300) * 1000),
            refreshExpiresAt: Int64((now.timeIntervalSince1970 + 3600) * 1000)
        )
        XCTAssertTrue(tokens.accessIsFresh(now: now))
        XCTAssertTrue(tokens.canRefresh(now: now))
    }

    func testPushPathIsRestrictedToAppFragments() {
        XCTAssertEqual(AppConfig.safePushFragment("/#/chat"), "/chat")
        XCTAssertEqual(AppConfig.safePushFragment("/#/news/42"), "/news/42")
        XCTAssertNil(AppConfig.safePushFragment("https://example.com/#/chat"))
        XCTAssertNil(AppConfig.safePushFragment("/#/../../outside"))
    }
}
