package cz.mares.bandita

data class LeaderboardEntry(
    val nick: String,
    val score: Int,
    val isCurrentUser: Boolean = false
)

enum class BanditaSound {
    BUTTON_CLICK,
    REEL_TICK,
    REEL_STOP,
    HOLD_LOCK,
    WIN_SMALL,
    WIN_BIG,
    JACKPOT_777,
    RISK_BEAT_A,
    RISK_BEAT_B,
    RISK_WIN_1,
    RISK_WIN_2,
    RISK_WIN_3,
    RISK_WIN_4,
    RISK_WIN_5,
    RISK_LOSE,
    WHEEL_TICK,
    SUPER_TRANSFER
}

expect object PlatformServices {
    fun playSound(sound: BanditaSound)
    fun getSavedCredits(): Int
    fun saveCredits(credits: Int)
    fun getHighScore(gameId: String = "bandita"): Int
    fun saveHighScore(score: Int, gameId: String = "bandita")
    fun getSavedNick(): String
    fun saveNick(nick: String)
    fun getDeviceId(): String

    fun fetchTopScores(
        gameId: String = "bandita",
        includeZeroScores: Boolean = false,
        onResult: (List<LeaderboardEntry>, String?) -> Unit
    )

    fun submitScore(
        gameId: String = "bandita",
        nick: String,
        score: Int,
        onResult: (Boolean, String?) -> Unit
    )
}
