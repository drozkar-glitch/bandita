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
    private const val KEY_HIGH_SCORE = "high_score"
    private const val KEY_NICK = "player_nick"
    private const val KEY_DEVICE_ID = "device_id"
    private const val FIREBASE_BASE_URL = "https://mares-games-default-rtdb.europe-west1.firebasedatabase.app"

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun prefs() = AndroidContextHolder.appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    actual fun getSavedCredits(): Int = prefs()?.getInt(KEY_CREDITS, 200) ?: 200

    actual fun saveCredits(credits: Int) {
        prefs()?.edit()?.putInt(KEY_CREDITS, credits)?.apply()
    }

    actual fun getHighScore(): Int = prefs()?.getInt(KEY_HIGH_SCORE, 200) ?: 200

    actual fun saveHighScore(score: Int) {
        val current = getHighScore()
        if (score > current) {
            prefs()?.edit()?.putInt(KEY_HIGH_SCORE, score)?.apply()
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
                    BanditaSound.BUTTON_CLICK -> synthSequence(listOf(880.0 to 35))
                    BanditaSound.REEL_TICK -> synthSequence(listOf(280.0 to 16))
                    BanditaSound.REEL_STOP -> synthSequence(listOf(140.0 to 55, 95.0 to 40))
                    BanditaSound.HOLD_LOCK -> synthSequence(listOf(660.0 to 45, 990.0 to 55))
                    BanditaSound.RISK_STEP_A -> synthSequence(listOf(523.25 to 75))
                    BanditaSound.RISK_STEP_B -> synthSequence(listOf(659.25 to 75))
                    BanditaSound.RISK_LOSE -> synthSequence(listOf(220.0 to 140, 146.8 to 240))
                    BanditaSound.WIN_SMALL -> synthSequence(
                        listOf(523.25 to 70, 659.25 to 70, 783.99 to 70, 1046.5 to 150)
                    )
                    BanditaSound.WIN_BIG -> synthSequence(
                        listOf(
                            523.25 to 65, 659.25 to 65, 783.99 to 65, 1046.5 to 90,
                            783.99 to 65, 1046.5 to 180
                        )
                    )
                    BanditaSound.JACKPOT_777 -> synthSequence(
                        listOf(
                            523.25 to 70, 659.25 to 70, 783.99 to 70, 1046.5 to 110,
                            987.77 to 70, 1046.5 to 70, 1318.5 to 110, 1567.98 to 260
                        )
                    )
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun synthSequence(notes: List<Pair<Double, Int>>) {
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
                val env = if (i < count / 8) i.toDouble() / (count / 8).coerceAtLeast(1)
                else (count - i).toDouble() / count.toDouble()
                val angle = 2.0 * PI * i * freq / sampleRate
                val rawWave = if (sin(angle) >= 0.0) 0.7 else -0.7
                pcm[offset + i] = (rawWave * env * 14000).toInt().toShort()
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
        Thread.sleep(totalMs.toLong() + 40L)
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
