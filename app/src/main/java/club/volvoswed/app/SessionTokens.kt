package club.volvoswed.app

data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long
) {
    fun accessIsFresh(now: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && accessExpiresAt > now + 60_000L

    fun canRefresh(now: Long = System.currentTimeMillis()): Boolean =
        refreshToken.isNotBlank() && refreshExpiresAt > now + 60_000L
}
