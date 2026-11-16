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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

object AndroidContextHolder {
    var appContext: Context? = null
}

private data class CachedPcmSample(
    val pcm: ShortArray,
    val durationMs: Int
)

actual object PlatformServices {
    private const val PREFS_NAME = "bandita_mares_prefs"
    private const val KEY_CREDITS = "saved_credits"
    private const val KEY_HIGH_SCORE_PREFIX = "high_score_"
    private const val KEY_NICK = "player_nick"
    private const val KEY_DEVICE_ID = "device_id"
    private const val SAMPLE_RATE = 22050
    private const val FIREBASE_BASE_URL = "https://mares-games-default-rtdb.europe-west1.firebasedatabase.app"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val soundExecutor = Executors.newFixedThreadPool(6)
    private val activeAudioTracks = AtomicInteger(0)
    private val sampleCache = ConcurrentHashMap<BanditaSound, CachedPcmSample>()

    private fun prefs() = AndroidContextHolder.appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    actual fun getSavedCredits(gameId: String): Int {
        val p = prefs() ?: return 200
        val key = if (gameId == "bandita") KEY_CREDITS else "${KEY_CREDITS}_$gameId"
        return p.getInt(key, 200)
    }

    actual fun saveCredits(credits: Int, gameId: String) {
        val key = if (gameId == "bandita") KEY_CREDITS else "${KEY_CREDITS}_$gameId"
        prefs()?.edit()?.putInt(key, credits)?.apply()
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
        val isTransientTick = (
            sound == BanditaSound.REEL_TICK ||
            sound == BanditaSound.WHEEL_TICK ||
            sound == BanditaSound.RISK_BEAT_A ||
            sound == BanditaSound.RISK_BEAT_B
        )
        val limit = if (isTransientTick) 3 else 7
        if (activeAudioTracks.get() >= limit) return

        soundExecutor.execute {
            if (activeAudioTracks.incrementAndGet() > limit + 1) {
                activeAudioTracks.decrementAndGet()
                return@execute
            }
            var track: AudioTrack? = null
            try {
                val sample = sampleCache.getOrPut(sound) { buildSampleForSound(sound) }
                if (sample.pcm.isEmpty()) return@execute

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(sample.pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(sample.pcm, 0, sample.pcm.size)
                track.play()
                Thread.sleep(sample.durationMs.toLong() + 30L)
            } catch (_: Throwable) {
            } finally {
                try {
                    track?.release()
                } catch (_: Throwable) {
                }
                activeAudioTracks.decrementAndGet()
            }
        }
    }

    private fun buildSampleForSound(sound: BanditaSound): CachedPcmSample {
        return when (sound) {
            BanditaSound.BUTTON_CLICK -> generateArcadePcm(listOf(987.77 to 26, 1318.5 to 22))
            BanditaSound.REEL_TICK -> generateArcadePcm(listOf(235.0 to 13), richHarmonics = false)
            BanditaSound.REEL_STOP -> generateArcadePcm(listOf(155.0 to 42, 96.0 to 45), richHarmonics = false)
            BanditaSound.HOLD_LOCK -> generateArcadePcm(listOf(587.33 to 38, 880.0 to 52))
            BanditaSound.WHEEL_TICK -> generateArcadePcm(listOf(1174.66 to 20, 1567.98 to 16))
            BanditaSound.SUPER_TRANSFER -> generateArcadePcm(
                listOf(523.25 to 35, 659.25 to 35, 783.99 to 35, 1046.5 to 45, 1318.5 to 70)
            )
            BanditaSound.RISK_BEAT_A -> generateArcadePcm(
                listOf(330.0 to 36, 659.25 to 48)
            )
            BanditaSound.RISK_BEAT_B -> generateArcadePcm(
                listOf(293.66 to 36, 587.33 to 48)
            )
            BanditaSound.RISK_WIN_1 -> generateArcadePcm(
                listOf(523.25 to 45, 659.25 to 45, 783.99 to 45, 659.25 to 40, 1046.5 to 115)
            )
            BanditaSound.RISK_WIN_2 -> generateArcadePcm(
                listOf(587.33 to 45, 739.99 to 45, 880.0 to 45, 739.99 to 40, 1174.66 to 115)
            )
            BanditaSound.RISK_WIN_3 -> generateArcadePcm(
                listOf(659.25 to 45, 830.61 to 45, 987.77 to 45, 830.61 to 40, 1318.51 to 125)
            )
            BanditaSound.RISK_WIN_4 -> generateArcadePcm(
                listOf(698.46 to 45, 880.0 to 45, 1046.5 to 45, 880.0 to 40, 1396.91 to 135)
            )
            BanditaSound.RISK_WIN_5 -> generateArcadePcm(
                listOf(
                    783.99 to 45, 987.77 to 45, 1174.66 to 45, 1567.98 to 65,
                    1174.66 to 45, 1567.98 to 180
                )
            )
            BanditaSound.RISK_LOSE -> generateArcadePcm(
                listOf(311.13 to 85, 277.18 to 85, 246.94 to 105, 185.0 to 220)
            )
            BanditaSound.WIN_SMALL -> generateArcadePcm(
                listOf(523.25 to 58, 659.25 to 58, 783.99 to 58, 1046.5 to 135)
            )
            BanditaSound.WIN_BIG -> generateArcadePcm(
                listOf(
                    523.25 to 52, 659.25 to 52, 783.99 to 52, 1046.5 to 75,
                    783.99 to 52, 1046.5 to 75, 1318.5 to 165
                )
            )
            BanditaSound.JACKPOT_777 -> generateArcadePcm(
                listOf(
                    523.25 to 58, 659.25 to 58, 783.99 to 58, 1046.5 to 90,
                    987.77 to 58, 1046.5 to 58, 1318.5 to 90, 1567.98 to 230
                )
            )
        }
    }

    private fun generateArcadePcm(
        notes: List<Pair<Double, Int>>,
        richHarmonics: Boolean = true
    ): CachedPcmSample {
        val totalMs = notes.sumOf { it.second }
        val totalSamples = (SAMPLE_RATE * totalMs) / 1000
        if (totalSamples <= 0) return CachedPcmSample(ShortArray(0), 0)

        val pcm = ShortArray(totalSamples)
        var offset = 0
        for ((freq, durationMs) in notes) {
            val count = (SAMPLE_RATE * durationMs) / 1000
            for (i in 0 until count) {
                if (offset + i >= totalSamples) break
                val attackSamples = (count / 10).coerceAtLeast(1)
                val env = if (i < attackSamples) {
                    i.toDouble() / attackSamples.toDouble()
                } else {
                    1.0 - ((i - attackSamples).toDouble() / (count - attackSamples).coerceAtLeast(1).toDouble()) * 0.65
                }

                val angle1 = 2.0 * PI * i * freq / SAMPLE_RATE
                val sq1 = if (sin(angle1) >= 0.0) 0.55 else -0.55

                val sampleVal = if (richHarmonics) {
                    val angle2 = 2.0 * PI * i * (freq * 2.0) / SAMPLE_RATE
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

        return CachedPcmSample(pcm, totalMs)
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
