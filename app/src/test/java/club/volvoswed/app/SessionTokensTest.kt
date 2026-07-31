package club.volvoswed.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTokensTest {
    @Test
    fun freshAccessTokenIsAccepted() {
        val now = 1_000_000L
        val tokens = SessionTokens("access", "refresh", now + 120_000L, now + 300_000L)

        assertTrue(tokens.accessIsFresh(now))
        assertTrue(tokens.canRefresh(now))
    }

    @Test
    fun tokenInsideSafetyWindowMustBeRefreshed() {
        val now = 1_000_000L
        val tokens = SessionTokens("access", "refresh", now + 30_000L, now + 300_000L)

        assertFalse(tokens.accessIsFresh(now))
        assertTrue(tokens.canRefresh(now))
    }

    @Test
    fun expiredRefreshTokenIsRejected() {
        val now = 1_000_000L
        val tokens = SessionTokens("access", "refresh", now - 1L, now - 1L)

        assertFalse(tokens.accessIsFresh(now))
        assertFalse(tokens.canRefresh(now))
    }
}
