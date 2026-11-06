@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cz.mares.bandita

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.random.Random

actual object PlatformServices {
    private const val KEY_CREDITS = "bandita_saved_credits"
    private const val KEY_HIGH_SCORE = "bandita_high_score"
    private const val KEY_NICK = "bandita_player_nick"
    private const val KEY_DEVICE_ID = "bandita_device_id"
    private const val FIREBASE_BASE_URL = "https://mares-games-default-rtdb.europe-west1.firebasedatabase.app"

    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun getSavedCredits(): Int {
        val obj = defaults.objectForKey(KEY_CREDITS)
        return if (obj != null) defaults.integerForKey(KEY_CREDITS).toInt() else 200
    }

    actual fun saveCredits(credits: Int) {
        defaults.setInteger(credits.toLong(), forKey = KEY_CREDITS)
    }

    actual fun getHighScore(gameId: String): Int {
        val key = if (gameId == "bandita") KEY_HIGH_SCORE else "${KEY_HIGH_SCORE}_$gameId"
        val obj = defaults.objectForKey(key)
        return if (obj != null) defaults.integerForKey(key).toInt() else 200
    }

    actual fun saveHighScore(score: Int, gameId: String) {
        val key = if (gameId == "bandita") KEY_HIGH_SCORE else "${KEY_HIGH_SCORE}_$gameId"
        if (score > getHighScore(gameId)) {
            defaults.setInteger(score.toLong(), forKey = key)
        }
    }

    actual fun getSavedNick(): String = defaults.stringForKey(KEY_NICK) ?: ""

    actual fun saveNick(nick: String) {
        defaults.setObject(nick.trim(), forKey = KEY_NICK)
    }

    actual fun getDeviceId(): String {
        val existing = defaults.stringForKey(KEY_DEVICE_ID)
        if (!existing.isNullOrBlank()) return existing
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val generated = "dev_" + (1..20).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        defaults.setObject(generated, forKey = KEY_DEVICE_ID)
        return generated
    }

    actual fun playSound(sound: BanditaSound) {
        val soundId: UInt = when (sound) {
            BanditaSound.BUTTON_CLICK -> 1104u
            BanditaSound.REEL_TICK -> 1103u
            BanditaSound.REEL_STOP -> 1105u
            BanditaSound.HOLD_LOCK -> 1057u
            BanditaSound.WHEEL_TICK -> 1104u
            BanditaSound.SUPER_TRANSFER -> 1025u
            BanditaSound.RISK_BEAT_A -> 1052u
            BanditaSound.RISK_BEAT_B -> 1054u
            BanditaSound.RISK_WIN_1 -> 1025u
            BanditaSound.RISK_WIN_2 -> 1026u
            BanditaSound.RISK_WIN_3 -> 1027u
            BanditaSound.RISK_WIN_4 -> 1028u
            BanditaSound.RISK_WIN_5 -> 1028u
            BanditaSound.RISK_LOSE -> 1073u
            BanditaSound.WIN_SMALL -> 1025u
            BanditaSound.WIN_BIG -> 1026u
            BanditaSound.JACKPOT_777 -> 1028u
        }
        AudioServicesPlaySystemSound(soundId)
    }

    private fun encodeUrlPart(input: String): String {
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val sb = StringBuilder()
        for (ch in input.encodeToByteArray()) {
            val c = ch.toInt().toChar()
            if (c in allowed) {
                sb.append(c)
            } else {
                val hex = (ch.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                sb.append('%').append(hex)
            }
        }
        return sb.toString()
    }

    actual fun fetchTopScores(
        gameId: String,
        includeZeroScores: Boolean,
        onResult: (List<LeaderboardEntry>, String?) -> Unit
    ) {
        val myNick = getSavedNick()
        val nsUrl = NSURL.URLWithString("$FIREBASE_BASE_URL/high_scores/$gameId.json")
        if (nsUrl == null) {
            onResult(emptyList(), "Neplatná URL")
            return
        }
        val getReq = NSMutableURLRequest(uRL = nsUrl).apply {
            setHTTPMethod("GET")
        }
        val task = NSURLSession.sharedSession.dataTaskWithRequest(getReq) { data: NSData?, _, error: NSError? ->
            if (error != null || data == null) {
                dispatch_async(dispatch_get_main_queue()) {
                    onResult(emptyList(), error?.localizedDescription ?: "Chyba sítě")
                }
                return@dataTaskWithRequest
            }
            val parsed = NSJSONSerialization.JSONObjectWithData(data, 0u, null) as? kotlin.collections.Map<*, *>
            val entries = mutableListOf<LeaderboardEntry>()
            if (parsed != null) {
                for ((k, valueObj) in parsed) {
                    val nickKey = k as? String ?: continue
                    val scoreVal = when (valueObj) {
                        is kotlin.collections.Map<*, *> -> (valueObj["score"] as? Number)?.toInt() ?: 0
                        is Number -> valueObj.toInt()
                        else -> 0
                    }
                    entries.add(
                        LeaderboardEntry(
                            nick = nickKey,
                            score = scoreVal,
                            isCurrentUser = nickKey.equals(myNick, ignoreCase = true)
                        )
                    )
                }
            }
            val sorted = entries
                .filter { includeZeroScores || it.score > 0 }
                .sortedByDescending { it.score }
                .take(50)
            dispatch_async(dispatch_get_main_queue()) {
                onResult(sorted, null)
            }
        }
        task.resume()
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
        val encoded = encodeUrlPart(cleanNick)
        val nsUrl = NSURL.URLWithString("$FIREBASE_BASE_URL/high_scores/$gameId/$encoded.json")
        if (nsUrl == null) {
            onResult(false, "Neplatná URL")
            return
        }

        val checkReq = NSMutableURLRequest(uRL = nsUrl).apply {
            setHTTPMethod("GET")
        }
        val checkTask = NSURLSession.sharedSession.dataTaskWithRequest(checkReq) { data: NSData?, _, error: NSError? ->
            if (error != null) {
                dispatch_async(dispatch_get_main_queue()) {
                    onResult(false, error.localizedDescription)
                }
                return@dataTaskWithRequest
            }
            var existingScore = -1
            if (data != null) {
                val existingDict = NSJSONSerialization.JSONObjectWithData(data, 0u, null) as? kotlin.collections.Map<*, *>
                if (existingDict != null) {
                    val ownerId = existingDict["deviceId"] as? String ?: ""
                    existingScore = (existingDict["score"] as? Number)?.toInt() ?: 0
                    if (ownerId.isNotBlank() && ownerId != devId) {
                        dispatch_async(dispatch_get_main_queue()) {
                            onResult(false, "Přezdívka '$cleanNick' už patří jinému hráči. Zvol si prosím jinou.")
                        }
                        return@dataTaskWithRequest
                    }
                }
            }

            val finalScore = maxOf(score, existingScore, 0)
            if (existingScore >= 0 && finalScore <= existingScore) {
                saveNick(cleanNick)
                dispatch_async(dispatch_get_main_queue()) {
                    onResult(true, null)
                }
                return@dataTaskWithRequest
            }

            val jsonStr = "{\"score\":$finalScore,\"deviceId\":\"$devId\"}"
            val bodyData = NSString.create(string = jsonStr).dataUsingEncoding(NSUTF8StringEncoding)
            val putReq = NSMutableURLRequest(uRL = nsUrl).apply {
                setHTTPMethod("PUT")
                setValue("application/json", forHTTPHeaderField = "Content-Type")
                setHTTPBody(bodyData)
            }

            val putTask = NSURLSession.sharedSession.dataTaskWithRequest(putReq) { _, response: NSURLResponse?, putErr: NSError? ->
                val httpResp = response as? NSHTTPURLResponse
                val statusCode = httpResp?.statusCode?.toInt() ?: 0
                dispatch_async(dispatch_get_main_queue()) {
                    if (putErr == null && statusCode in 200..299) {
                        saveNick(cleanNick)
                        onResult(true, null)
                    } else if (statusCode == 401 || statusCode == 403) {
                        onResult(false, "Přezdívka '$cleanNick' je již obsazena.")
                    } else {
                        onResult(false, putErr?.localizedDescription ?: "Chyba uložení ($statusCode)")
                    }
                }
            }
            putTask.resume()
        }
        checkTask.resume()
    }
}
