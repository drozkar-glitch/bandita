package cz.mares.bandita

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

object AndroidContextHolder {
    var appContext: Context? = null
}

actual object PlatformServices {
    private const val PREFS_NAME = "bandita_mares_prefs"
    private const val KEY_CREDITS = "saved_credits"
    private const val KEY_HIGH_SCORE_PREFIX = "high_score_"
    private const val KEY_NICK = "player_nick"
    private const val KEY_DEVICE_ID = "device_id"
    private const val FIREBASE_BASE_URL = "https://mares-games-default-rtdb.europe-west1.firebasedatabase.app"

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun prefs() = AndroidContextHolder.appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    actual fun getSavedCredits(): Int = prefs()?.getInt(KEY_CREDITS, 200) ?: 200

    actual fun saveCredits(credits: Int) {
        prefs()?.edit()?.putInt(KEY_CREDITS, credits)?.apply()
    }

    actual fun getHighScore(gameId: String): Int {
        val p = prefs() ?: return 200
        val key = if (gameId == "bandita") "high_score" else "$KEY_HIGH_SCORE_PREFIX$gameId"
        return p.getInt(key, 200)
    }

    actual fun saveHighScore(score: Int, gameId: String) {
        val current = getHighScore(gameId)
        if (score > current) {
            val key = if (gameId == "bandita") "high_score" else "$KEY_HIGH_SCORE_PREFIX$gameId"
            prefs()?.edit()?.putInt(key, score)?.apply()
        }
    }

    actual fun getSavedNick(): String = prefs()?.getString(KEY_NICK, "") ?: ""

    actual fun saveNick(nick: String) {
        prefs()?.edit()?.putString(KEY_NICK, nick.trim())?.apply()
    }

    actual fun getDeviceId(): String {
        val p = prefs() ?: return "dev_android_fallback"
        val existing = p.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val generated = "dev_" + (1..20).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        p.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    actual fun playSound(sound: BanditaSound) {
        thread {
            try {
                when (sound) {
                    BanditaSound.BUTTON_CLICK -> synthArcadeTone(listOf(987.77 to 28, 1318.5 to 22))
                    BanditaSound.REEL_TICK -> synthArcadeTone(listOf(240.0 to 14), richHarmonics = false)
                    BanditaSound.REEL_STOP -> synthArcadeTone(listOf(155.0 to 45, 98.0 to 45), richHarmonics = false)
                    BanditaSound.HOLD_LOCK -> synthArcadeTone(listOf(587.33 to 40, 880.0 to 55))
                    BanditaSound.WHEEL_TICK -> synthArcadeTone(listOf(1174.66 to 22, 1567.98 to 18))
                    BanditaSound.SUPER_TRANSFER -> synthArcadeTone(
                        listOf(523.25 to 35, 659.25 to 35, 783.99 to 35, 1046.5 to 45, 1318.5 to 70)
                    )

                    // Chytlavy 90s rytmicky podkres pri blikani RISK 2x (stridavy 2-tonovy groove)
                    BanditaSound.RISK_BEAT_A -> synthArcadeTone(
                        listOf(330.0 to 38, 659.25 to 52)
                    )
                    BanditaSound.RISK_BEAT_B -> synthArcadeTone(
                        listOf(293.66 to 38, 587.33 to 52)
                    )

                    // Stupnujici se triumfalni trylky pri trefenem RISKU (1. az 5. krok o ton vys!)
                    BanditaSound.RISK_WIN_1 -> synthArcadeTone(
                        listOf(523.25 to 45, 659.25 to 45, 783.99 to 45, 659.25 to 40, 1046.5 to 120)
                    )
                    BanditaSound.RISK_WIN_2 -> synthArcadeTone(
                        listOf(587.33 to 45, 739.99 to 45, 880.0 to 45, 739.99 to 40, 1174.66 to 120)
                    )
                    BanditaSound.RISK_WIN_3 -> synthArcadeTone(
                        listOf(659.25 to 45, 830.61 to 45, 987.77 to 45, 830.61 to 40, 1318.51 to 130)
                    )
                    BanditaSound.RISK_WIN_4 -> synthArcadeTone(
                        listOf(698.46 to 45, 880.0 to 45, 1046.5 to 45, 880.0 to 40, 1396.91 to 140)
                    )
                    BanditaSound.RISK_WIN_5 -> synthArcadeTone(
                        listOf(
                            783.99 to 45, 987.77 to 45, 1174.66 to 45, 1567.98 to 70,
                            1174.66 to 45, 1567.98 to 190
                        )
                    )

                    BanditaSound.RISK_LOSE -> synthArcadeTone(
                        listOf(311.13 to 90, 277.18 to 90, 246.94 to 110, 185.0 to 230)
                    )

                    BanditaSound.WIN_SMALL -> synthArcadeTone(
                        listOf(523.25 to 60, 659.25 to 60, 783.99 to 60, 1046.5 to 140)
                    )
                    BanditaSound.WIN_BIG -> synthArcadeTone(
                        listOf(
                            523.25 to 55, 659.25 to 55, 783.99 to 55, 1046.5 to 80,
                            783.99 to 55, 1046.5 to 80, 1318.5 to 170
                        )
                    )
                    BanditaSound.JACKPOT_777 -> synthArcadeTone(
                        listOf(
                            523.25 to 60, 659.25 to 60, 783.99 to 60, 1046.5 to 95,
                            987.77 to 60, 1046.5 to 60, 1318.5 to 95, 1567.98 to 240
                        )
                    )
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Dvouhlasy retro syntezator ve stylu 90s automatovych cipu (YM2149 / OPL)
     */
    private fun synthArcadeTone(notes: List<Pair<Double, Int>>, richHarmonics: Boolean = true) {
        val sampleRate = 22050
        val totalMs = notes.sumOf { it.second }
        val totalSamples = (sampleRate * totalMs) / 1000
        if (totalSamples <= 0) return

        val pcm = ShortArray(totalSamples)
        var offset = 0
        for ((freq, durationMs) in notes) {
            val count = (sampleRate * durationMs) / 1000
            for (i in 0 until count) {
                if (offset + i >= totalSamples) break
                val attackSamples = (count / 10).coerceAtLeast(1)
                val env = if (i < attackSamples) {
                    i.toDouble() / attackSamples.toDouble()
                } else {
                    1.0 - ((i - attackSamples).toDouble() / (count - attackSamples).coerceAtLeast(1).toDouble()) * 0.65
                }

                val angle1 = 2.0 * PI * i * freq / sampleRate
                val sq1 = if (sin(angle1) >= 0.0) 0.55 else -0.55

                val sampleVal = if (richHarmonics) {
                    // Pridame oktavovou harmonickou a jemny FM dozvuk pro chytlavy zvonivy zvuk
                    val angle2 = 2.0 * PI * i * (freq * 2.0) / sampleRate
                    val sq2 = if (sin(angle2) >= 0.15) 0.28 else -0.28
                    val sub = sin(angle1 * 0.5) * 0.17
                    sq1 + sq2 + sub
                } else {
                    sq1
                }

                pcm[offset + i] = (sampleVal * env * 14500).toInt().coerceIn(-32000, 32000).toShort()
            }
            offset += count
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(totalMs.toLong() + 35L)
        track.release()
    }

    actual fun fetchTopScores(
        gameId: String,
        includeZeroScores: Boolean,
        onResult: (List<LeaderboardEntry>, String?) -> Unit
    ) {
        val myNick = getSavedNick()
        thread {
            try {
                val url = URL("$FIREBASE_BASE_URL/high_scores/$gameId.json")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val list = mutableListOf<LeaderboardEntry>()
                    if (body.isNotBlank() && body != "null") {
                        val json = JSONObject(body)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val nickKey = keys.next()
                            val item = json.optJSONObject(nickKey)
                            val sc = item?.optInt("score", 0) ?: json.optInt(nickKey, 0)
                            list.add(
                                LeaderboardEntry(
                                    nick = nickKey,
                                    score = sc,
                                    isCurrentUser = nickKey.equals(myNick, ignoreCase = true)
                                )
                            )
                        }
                    }
                    val sorted = list
                        .filter { includeZeroScores || it.score > 0 }
                        .sortedByDescending { it.score }
                        .take(50)
                    mainHandler.post { onResult(sorted, null) }
                } else {
                    mainHandler.post { onResult(emptyList(), "Chyba serveru ($code)") }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(emptyList(), "Nelze načíst žebříček: ${e.message ?: "offline"}") }
            }
        }
    }

    actual fun submitScore(
        gameId: String,
        nick: String,
        score: Int,
        onResult: (Boolean, String?) -> Unit
    ) {
        val cleanNick = nick.trim()
        if (cleanNick.length < 2) {
            onResult(false, "Přezdívka musí mít aspoň 2 znaky.")
            return
        }
        val devId = getDeviceId()
        thread {
            try {
                val encodedNick = URLEncoder.encode(cleanNick, "UTF-8").replace("+", "%20")
                val recordUrl = URL("$FIREBASE_BASE_URL/high_scores/$gameId/$encodedNick.json")

                val checkConn = (recordUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                var existingScore = -1
                if (checkConn.responseCode in 200..299) {
                    val existingBody = BufferedReader(InputStreamReader(checkConn.inputStream)).use { it.readText() }
                    if (existingBody.isNotBlank() && existingBody != "null") {
                        val obj = JSONObject(existingBody)
                        val ownerId = obj.optString("deviceId", "")
                        existingScore = obj.optInt("score", 0)
                        if (ownerId.isNotBlank() && ownerId != devId) {
                            mainHandler.post {
                                onResult(false, "Přezdívka '$cleanNick' už patří jinému hráči. Zvol si prosím jinou.")
                            }
                            return@thread
                        }
                    }
                }

                val finalScore = maxOf(score, existingScore, 0)
                if (existingScore >= 0 && finalScore <= existingScore) {
                    saveNick(cleanNick)
                    mainHandler.post { onResult(true, null) }
                    return@thread
                }

                val putConn = (recordUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                val payload = JSONObject().apply {
                    put("score", finalScore)
                    put("deviceId", devId)
                }.toString()

                OutputStreamWriter(putConn.outputStream, Charsets.UTF_8).use { it.write(payload) }
                val putCode = putConn.responseCode
                if (putCode in 200..299) {
                    saveNick(cleanNick)
                    mainHandler.post { onResult(true, null) }
                } else if (putCode == 401 || putCode == 403) {
                    mainHandler.post {
                        onResult(false, "Přezdívka '$cleanNick' je již obsazena jiným hráčem.")
                    }
                } else {
                    mainHandler.post { onResult(false, "Chyba uložení ($putCode)") }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, "Chyba spojení: ${e.message ?: "offline"}") }
            }
        }
    }
}
