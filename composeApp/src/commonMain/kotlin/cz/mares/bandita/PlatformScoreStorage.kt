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
    WIN_SMALL,
    WIN_BIG,
    JACKPOT_777,
    RISK_STEP_A,
    RISK_STEP_B,
    RISK_LOSE,
    HOLD_LOCK
}

expect object PlatformServices {
    fun playSound(sound: BanditaSound)
    fun getSavedCredits(): Int
    fun saveCredits(credits: Int)
    fun getHighScore(): Int
    fun saveHighScore(score: Int)
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
