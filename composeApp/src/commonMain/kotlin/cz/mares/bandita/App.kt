package cz.mares.bandita

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.TimeSource

private enum class BanditaScreen {
    CABINET,
    PAYTABLE,
    LEADERBOARD
}

/**
 * Reaktivni stavovy kontroler cele sady 4 automatu.
 * Kazdy automat ma vlastni penezenku kreditu, vlastni rekord i vlastni pozice pasu valcu.
 */
private class BanditaGameState(private val scope: CoroutineScope) {
    var machineMode by mutableStateOf(MachineMode.CLASSIC_3V)

    // Oddelene kredity a rekordy pro kazdy ze 4 automatu (indexovane pres mode.ordinal)
    val modeCredits = mutableStateListOf(
        PlatformServices.getSavedCredits(MachineMode.CLASSIC_3V.firebaseGameId).coerceAtLeast(50),
        PlatformServices.getSavedCredits(MachineMode.SUPER_4V.firebaseGameId).coerceAtLeast(50),
        PlatformServices.getSavedCredits(MachineMode.CRISSCROSS_27.firebaseGameId).coerceAtLeast(50),
        PlatformServices.getSavedCredits(MachineMode.VEGAS_5V.firebaseGameId).coerceAtLeast(50)
    )

    val modeHighScores = mutableStateListOf(
        maxOf(PlatformServices.getHighScore(MachineMode.CLASSIC_3V.firebaseGameId), modeCredits[0]),
        maxOf(PlatformServices.getHighScore(MachineMode.SUPER_4V.firebaseGameId), modeCredits[1]),
        maxOf(PlatformServices.getHighScore(MachineMode.CRISSCROSS_27.firebaseGameId), modeCredits[2]),
        maxOf(PlatformServices.getHighScore(MachineMode.VEGAS_5V.firebaseGameId), modeCredits[3])
    )

    val credits: Int
        get() = modeCredits[machineMode.ordinal]

    var betIndex by mutableStateOf(2) // vychozi sazka 5
    var freeSpinLockedBet by mutableStateOf(5)

    val currentBet: Int
        get() = if (machineMode == MachineMode.VEGAS_5V && freeSpinsRemaining > 0) {
            freeSpinLockedBet
        } else {
            SlotMachineRules.BET_OPTIONS[betIndex]
        }

    var pendingWin by mutableStateOf(0)
    var supermeter by mutableStateOf(0)
    var freeSpinsRemaining by mutableStateOf(0)
    var riskStepCount by mutableStateOf(0)
    var isRiskBusy by mutableStateOf(false)
    var riskRevealHoldTicks by mutableStateOf(0)
    val riskHistory = mutableStateListOf<Boolean>()

    var statusBanner by mutableStateOf("ZATÁHNI ZA PÁKU NEBO STISKNI START!")

    // Ulozene indexy zastaveni valcu pro kazdy rezim zvlast
    private val savedStopsPerMode = Array(MachineMode.entries.size) { modeOrdinal ->
        val m = MachineMode.entries[modeOrdinal]
        val len = SlotMachineRules.getStripLength(m)
        IntArray(5) { r -> (r * 9 + 5) % len }
    }

    val reels = mutableStateListOf(
        SlotMachineRules.getVisibleReelSymbols(MachineMode.CLASSIC_3V, 0, savedStopsPerMode[0][0]),
        SlotMachineRules.getVisibleReelSymbols(MachineMode.CLASSIC_3V, 1, savedStopsPerMode[0][1]),
        SlotMachineRules.getVisibleReelSymbols(MachineMode.CLASSIC_3V, 2, savedStopsPerMode[0][2]),
        SlotMachineRules.getVisibleReelSymbols(MachineMode.CLASSIC_3V, 3, savedStopsPerMode[0][3]),
        SlotMachineRules.getVisibleReelSymbols(MachineMode.CLASSIC_3V, 4, savedStopsPerMode[0][4])
    )
    val spinningReels = mutableStateListOf(false, false, false, false, false)
    val reelPhaseOffsets = mutableStateListOf(
        savedStopsPerMode[0][0].toFloat(),
        savedStopsPerMode[0][1].toFloat(),
        savedStopsPerMode[0][2].toFloat(),
        savedStopsPerMode[0][3].toFloat(),
        savedStopsPerMode[0][4].toFloat()
    )

    var canHoldNow by mutableStateOf(false)
    val heldReels = mutableStateListOf(false, false, false, false, false)
    val suggestedHoldReels = mutableStateListOf(false, false, false, false, false)
    var usedHoldThisTurn by mutableStateOf(false)

    var activeWheelIndex by mutableStateOf(0)
    var isWheelSpinning by mutableStateOf(false)

    var winningLines by mutableStateOf<List<WinningLineResult>>(emptyList())
    var highlightCells by mutableStateOf<List<Pair<Int, Int>>>(emptyList())
    var leverPullProgress by mutableStateOf(0f)

    var blinkPhase by mutableStateOf(false)
    var riskLampIsRed by mutableStateOf(true)

    var savedNick by mutableStateOf(PlatformServices.getSavedNick())

    val anySpinning: Boolean
        get() = spinningReels.any { it } || isWheelSpinning || isRiskBusy

    fun getHighScoreForMode(mode: MachineMode): Int = modeHighScores[mode.ordinal]

    private fun updateCreditsForMode(targetMode: MachineMode, newCredits: Int) {
        val idx = targetMode.ordinal
        modeCredits[idx] = newCredits
        PlatformServices.saveCredits(newCredits, targetMode.firebaseGameId)

        val extraSuper = if (targetMode == MachineMode.SUPER_4V && machineMode == MachineMode.SUPER_4V) supermeter else 0
        val totalWealth = newCredits + extraSuper
        if (totalWealth > modeHighScores[idx]) {
            modeHighScores[idx] = totalWealth
            PlatformServices.saveHighScore(totalWealth, targetMode.firebaseGameId)
            if (savedNick.isNotBlank()) {
                PlatformServices.submitScore(targetMode.firebaseGameId, savedNick, totalWealth) { _, _ -> }
            }
        }
    }

    private fun syncActiveCredits(newCredits: Int) {
        updateCreditsForMode(machineMode, newCredits)
    }

    fun clearHoldState() {
        canHoldNow = false
        usedHoldThisTurn = false
        for (i in 0..4) {
            heldReels[i] = false
            suggestedHoldReels[i] = false
        }
    }

    fun toggleReelHold(reelIdx: Int) {
        val mode = machineMode
        val activeReelCount = mode.reelCount
        if (!mode.allowHold || !canHoldNow || anySpinning || reelIdx !in 0 until activeReelCount) return
        // Na klasickem 3V i 4V automatu lze drzet nejvyse 2 valce soucasne
        val maxHoldAllowed = 2
        val currentlyHeldCount = heldReels.take(activeReelCount).count { it }
        if (!heldReels[reelIdx] && currentlyHeldCount >= maxHoldAllowed) {
            val firstHeld = heldReels.take(activeReelCount).indexOfFirst { it }
            if (firstHeld >= 0) heldReels[firstHeld] = false
        }
        heldReels[reelIdx] = !heldReels[reelIdx]
        PlatformServices.playSound(BanditaSound.HOLD_LOCK)
    }

    fun switchMachineMode(newMode: MachineMode) {
        val oldMode = machineMode
        if (anySpinning || oldMode == newMode) return

        // Pripadnou nevybranou vyhru nebo Supermetr ulozime do penezenky PUVODNIHO automatu
        val rescued = pendingWin + supermeter
        if (rescued > 0) {
            pendingWin = 0
            supermeter = 0
            riskStepCount = 0
            updateCreditsForMode(oldMode, modeCredits[oldMode.ordinal] + rescued)
        }

        machineMode = newMode
        clearHoldState()
        winningLines = emptyList()
        highlightCells = emptyList()

        // Nacteme pozice pasu pro novy automat
        val stops = savedStopsPerMode[newMode.ordinal]
        for (r in 0..4) {
            reelPhaseOffsets[r] = stops[r].toFloat()
            reels[r] = SlotMachineRules.getVisibleReelSymbols(newMode, r, stops[r])
        }

        PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
        statusBanner = when (newMode) {
            MachineMode.CLASSIC_3V -> "KLASIK 3V: 5 LINIÍ + HOLD + RISK 2×"
            MachineMode.SUPER_4V -> "SUPER 4V: 4 VÁLCE + SUPERMETR A DRUHÉ KOLO!"
            MachineMode.CRISSCROSS_27 -> "JOKER 27: 27 CEST + PLÁTNO ×2 + 3× ZVONEK MYSTERY!"
            MachineMode.VEGAS_5V -> "VEGAS 5V: 9 LINIÍ + 3× ZVONEK = FREE SPINY (VÝHRY ×2)!"
        }
    }

    fun cycleBet() {
        val betLocked = anySpinning ||
            pendingWin > 0 ||
            (machineMode == MachineMode.VEGAS_5V && freeSpinsRemaining > 0)
        if (betLocked) return

        betIndex = (betIndex + 1) % SlotMachineRules.BET_OPTIONS.size
        PlatformServices.playSound(BanditaSound.BUTTON_CLICK)

        // Zmena sazky vzdy zrusi pripadny HOLD z predchoziho kola (ochrana proti exploitu sazky)
        if (canHoldNow || heldReels.any { it }) {
            clearHoldState()
            statusBanner = "SÁZKA ZMĚNĚNA NA $currentBet – PŘEDCHOZÍ HOLD ZRUŠEN."
        } else {
            statusBanner = "SÁZKA NASTAVENA NA $currentBet KREDITŮ."
        }
    }

    fun addEmergencyCoin() {
        if (anySpinning) return
        PlatformServices.playSound(BanditaSound.WIN_SMALL)
        val updated = credits + 100
        modeCredits[machineMode.ordinal] = updated
        PlatformServices.saveCredits(updated, machineMode.firebaseGameId)
        statusBanner = "VHOZENA MINCE: +100 KREDITŮ DO AUTOMATU ${machineMode.shortTabCz}!"
    }

    fun collectPendingWin() {
        if (pendingWin <= 0 || anySpinning) return
        PlatformServices.playSound(BanditaSound.WIN_SMALL)
        val won = pendingWin
        val updated = credits + won
        pendingWin = 0
        riskStepCount = 0
        statusBanner = "PŘIPSÁNO +$won DO KREDITU!"
        syncActiveCredits(updated)
    }

    fun halfPendingWin() {
        if (pendingWin < 2 || anySpinning) return
        val bankedHalf = pendingWin / 2
        val remainingRisk = pendingWin - bankedHalf
        PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
        pendingWin = remainingRisk
        syncActiveCredits(credits + bankedHalf)
        statusBanner = "ULOŽENA 1/2 (+$bankedHalf DO KREDITU), V RISKU ZŮSTÁVÁ $remainingRisk!"
    }

    fun transferWinToSupermeter() {
        if (pendingWin <= 0 || anySpinning || machineMode != MachineMode.SUPER_4V) return
        PlatformServices.playSound(BanditaSound.SUPER_TRANSFER)
        val transferred = pendingWin
        supermeter += transferred
        pendingWin = 0
        riskStepCount = 0
        statusBanner = "PŘEVEDENO +$transferred DO SUPERMETRU! ROZTOČ DRUHÉ KOLO!"
        syncActiveCredits(credits)
    }

    fun cashOutSupermeter() {
        if ((supermeter <= 0 && pendingWin <= 0) || anySpinning) return
        PlatformServices.playSound(BanditaSound.WIN_BIG)
        val amount = supermeter + pendingWin
        supermeter = 0
        pendingWin = 0
        riskStepCount = 0
        val updated = credits + amount
        statusBanner = "VYPLACENO +$amount ZPĚT DO KREDITU!"
        syncActiveCredits(updated)
    }

    fun triggerGambleRisk(chooseRed: Boolean) {
        val currentWinToRisk = pendingWin
        if (currentWinToRisk <= 0 || anySpinning) return

        isRiskBusy = true
        scope.launch {
            repeat(4) {
                riskLampIsRed = !riskLampIsRed
                PlatformServices.playSound(
                    if (riskLampIsRed) BanditaSound.RISK_BEAT_A else BanditaSound.RISK_BEAT_B
                )
                delay(55L)
            }

            val drawnRed = Random.nextBoolean()
            riskLampIsRed = drawnRed
            // Podrzime rozsvicenou tazenou barvu ~1.1 sekundy, aby hrac jasne videl, co padlo
            riskRevealHoldTicks = 4

            riskHistory.add(0, drawnRed)
            if (riskHistory.size > 6) {
                riskHistory.removeAt(riskHistory.lastIndex)
            }

            if (drawnRed == chooseRed) {
                val doubledWin = currentWinToRisk * 2
                val nextStep = riskStepCount + 1
                pendingWin = doubledWin
                riskStepCount = nextStep

                val winSound = when (nextStep) {
                    1 -> BanditaSound.RISK_WIN_1
                    2 -> BanditaSound.RISK_WIN_2
                    3 -> BanditaSound.RISK_WIN_3
                    4 -> BanditaSound.RISK_WIN_4
                    else -> BanditaSound.RISK_WIN_5
                }
                PlatformServices.playSound(winSound)

                val suitStr = if (drawnRed) "ČERVENÁ ♥" else "ČERNÁ ♠"
                if (nextStep >= 5) {
                    delay(400L)
                    val updated = credits + doubledWin
                    pendingWin = 0
                    riskStepCount = 0
                    statusBanner = "MAX RISK 5/5 ($suitStr)! AUTOMATICKY PŘIPSÁNO +$doubledWin!"
                    syncActiveCredits(updated)
                } else {
                    statusBanner = "PADLA $suitStr! VÝHRA $doubledWin (DALŠÍ 2× = ${doubledWin * 2})!"
                }
            } else {
                PlatformServices.playSound(BanditaSound.RISK_LOSE)
                pendingWin = 0
                riskStepCount = 0
                winningLines = emptyList()
                highlightCells = emptyList()
                statusBanner = if (drawnRed) {
                    "PADLA ČERVENÁ ♥ – RISK NEVYŠEL!"
                } else {
                    "PADLA ČERNÁ ♠ – RISK NEVYŠEL!"
                }
            }
            isRiskBusy = false
        }
    }

    fun spinUpperBonusWheel() {
        if (anySpinning || machineMode != MachineMode.SUPER_4V) return
        val bet = currentBet
        val wheelCost = bet * 2

        if (pendingWin > 0) {
            supermeter += pendingWin
            pendingWin = 0
            riskStepCount = 0
        }

        if (supermeter < wheelCost) {
            statusBanner = "PRO DRUHÉ KOLO POTŘEBUJEŠ V SUPERMETRU ASPOŇ $wheelCost!"
            PlatformServices.playSound(BanditaSound.RISK_LOSE)
            return
        }

        supermeter -= wheelCost
        isWheelSpinning = true
        winningLines = emptyList()
        highlightCells = emptyList()
        statusBanner = "HORNÍ DRUHÉ KOLO SE TOČÍ..."

        scope.launch {
            val slices = SlotMachineRules.BONUS_WHEEL_SLICES
            val slicesCount = slices.size
            val targetSliceIdx = SlotMachineRules.pickWeightedWheelSliceIndex()
            val delta = ((targetSliceIdx - activeWheelIndex) % slicesCount + slicesCount) % slicesCount
            val totalSteps = slicesCount * 3 + delta

            for (step in 1..totalSteps) {
                activeWheelIndex = (activeWheelIndex + 1) % slicesCount
                PlatformServices.playSound(BanditaSound.WHEEL_TICK)
                val remaining = totalSteps - step
                val waitMs = when {
                    remaining >= 8 -> 62L
                    remaining >= 4 -> 125L
                    else -> 210L
                }
                delay(waitMs)
            }

            val landed = slices[activeWheelIndex]
            isWheelSpinning = false

            if (landed.isLose) {
                PlatformServices.playSound(BanditaSound.RISK_LOSE)
                statusBanner = "DRUHÉ KOLO: ✖ NIC – ZKUS ŠTĚSTÍ ZNOVU!"
            } else {
                val mult = if (landed.isMystery) {
                    SlotMachineRules.rollMysteryWheelMultiplier()
                } else {
                    landed.baseMultiplier
                }
                val wheelWin = bet * mult
                pendingWin += wheelWin
                riskStepCount = 0

                if (landed.isJackpot || mult >= 16) {
                    PlatformServices.playSound(BanditaSound.JACKPOT_777)
                } else {
                    PlatformServices.playSound(BanditaSound.WIN_BIG)
                }

                statusBanner = when {
                    landed.isMystery -> "★ MYSTERY BONUS (${mult}×) ★ VÝHRA +$wheelWin!"
                    landed.isJackpot -> "★ JACKPOT DRUHÉHO KOLA (${mult}×) ★ VÝHRA +$wheelWin!"
                    else -> "DRUHÉ KOLO (${mult}× SÁZKA): VÝHRA +$wheelWin!"
                }
            }
        }
    }

    fun startSpin() {
        if (anySpinning) return
        val mode = machineMode
        val activeReelCount = mode.reelCount

        // Pripadnou nevybranou vyhru nejprve bezpecne pripiseme do kreditu
        if (pendingWin > 0) {
            val updated = credits + pendingWin
            pendingWin = 0
            riskStepCount = 0
            syncActiveCredits(updated)
        }

        val isFreeSpinTurn = (mode == MachineMode.VEGAS_5V && freeSpinsRemaining > 0)
        val bet = if (isFreeSpinTurn) freeSpinLockedBet else SlotMachineRules.BET_OPTIONS[betIndex]

        if (!isFreeSpinTurn) {
            if (credits < bet) {
                statusBanner = "MÁLO KREDITU PRO SÁZKU $bet! SNIŽ SÁZKU NEBO VHOĎ MINCI."
                PlatformServices.playSound(BanditaSound.RISK_LOSE)
                return
            }
            syncActiveCredits(credits - bet)
        } else {
            freeSpinsRemaining -= 1
        }

        winningLines = emptyList()
        highlightCells = emptyList()
        statusBanner = if (isFreeSpinTurn) {
            "★ FREE SPIN (ZBÝVÁ $freeSpinsRemaining) – VŠECHNY VÝHRY ×2! ★"
        } else {
            "VÁLCE SE TOČÍ... HODNĚ ŠTĚSTÍ!"
        }

        // Animace zatazeni paky
        scope.launch {
            for (step in 1..5) {
                leverPullProgress = step / 5f
                delay(18L)
            }
            for (step in 5 downTo 0) {
                leverPullProgress = step / 5f
                delay(18L)
            }
        }

        // Plynule mechanicke roztoceni a presne zastaveni valcu bez skoku symbolu
        scope.launch {
            val activeHold = mode.allowHold && canHoldNow && !isFreeSpinTurn && heldReels.take(activeReelCount).any { it }
            val stripLen = SlotMachineRules.getStripLength(mode)
            val modeStops = savedStopsPerMode[mode.ordinal]

            val startStops = IntArray(activeReelCount) { i -> modeStops[i] }
            val targetStops = IntArray(activeReelCount) { i -> modeStops[i] }
            val totalStepsPerReel = IntArray(activeReelCount) { 0 }
            val durationMsPerReel = LongArray(activeReelCount) { 0L }

            var spinOrder = 0
            for (i in 0 until activeReelCount) {
                val shouldSpin = !(activeHold && heldReels[i])
                spinningReels[i] = shouldSpin
                if (shouldSpin) {
                    val chosenStop = SlotMachineRules.randomStopPosition(mode)
                    targetStops[i] = chosenStop
                    val delta = ((startStops[i] - chosenStop) % stripLen + stripLen) % stripLen
                    totalStepsPerReel[i] = stripLen + delta
                    durationMsPerReel[i] = 620L + spinOrder * 250L
                    spinOrder++
                }
            }

            if (spinOrder > 0) {
                val startMark = TimeSource.Monotonic.markNow()
                var lastTickMs = 0L

                while (spinningReels.take(activeReelCount).any { it }) {
                    val elapsedMs = startMark.elapsedNow().inWholeMilliseconds

                    if (elapsedMs - lastTickMs >= 95L) {
                        lastTickMs = elapsedMs
                        PlatformServices.playSound(BanditaSound.REEL_TICK)
                    }

                    for (i in 0 until activeReelCount) {
                        if (spinningReels[i]) {
                            val dur = durationMsPerReel[i]
                            if (elapsedMs >= dur) {
                                val finalStop = targetStops[i]
                                modeStops[i] = finalStop
                                reelPhaseOffsets[i] = finalStop.toFloat()
                                reels[i] = SlotMachineRules.getVisibleReelSymbols(mode, i, finalStop)
                                spinningReels[i] = false
                                PlatformServices.playSound(BanditaSound.REEL_STOP)
                            } else {
                                val progress = (elapsedMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                                // Plynule zpomaleni (cubic ease-out) presne na cilovou pozici pasu
                                val inv = 1f - progress
                                val eased = 1f - inv * inv * inv
                                val rawPhase = startStops[i].toFloat() - eased * totalStepsPerReel[i].toFloat()
                                val wrapped = ((rawPhase % stripLen.toFloat()) + stripLen.toFloat()) % stripLen.toFloat()
                                reelPhaseOffsets[i] = wrapped
                            }
                        }
                    }
                    delay(16L)
                }
            } else {
                delay(120L)
            }

            val currentGrid = reels.take(activeReelCount)
            val evalSummary = SlotMachineRules.evaluateSpin(
                mode = mode,
                reels = currentGrid,
                bet = bet,
                freeSpinMultiplier = if (isFreeSpinTurn) 2 else 1
            )

            winningLines = evalSummary.winningLines
            highlightCells = evalSummary.highlightCells
            val totalWin = evalSummary.totalWin

            if (evalSummary.awardedFreeSpins > 0) {
                if (freeSpinsRemaining == 0 && !isFreeSpinTurn) {
                    freeSpinLockedBet = bet
                }
                freeSpinsRemaining += evalSummary.awardedFreeSpins
                PlatformServices.playSound(BanditaSound.JACKPOT_777)
            }

            if (totalWin > 0) {
                pendingWin = totalWin
                riskStepCount = 0
                clearHoldState()

                val hasJackpot = evalSummary.winningLines.any {
                    it.symbol == SlotSymbol.JOKER || it.symbol == SlotSymbol.SEVEN
                } || evalSummary.mysteryBonusWin >= bet * 20

                when {
                    hasJackpot -> PlatformServices.playSound(BanditaSound.JACKPOT_777)
                    totalWin >= bet * 10 -> PlatformServices.playSound(BanditaSound.WIN_BIG)
                    else -> PlatformServices.playSound(BanditaSound.WIN_SMALL)
                }

                statusBanner = when {
                    evalSummary.awardedFreeSpins > 0 ->
                        "★ +${evalSummary.awardedFreeSpins} FREE SPINŮ + VÝHRA $totalWin! ★"
                    evalSummary.mysteryBonusWin > 0 && evalSummary.winningLines.isEmpty() ->
                        "★ 3× ZVONEK MYSTERY BONUS: VÝHRA +$totalWin! ★"
                    evalSummary.mysteryBonusWin > 0 ->
                        "★ MYSTERY (+${evalSummary.mysteryBonusWin}) ★ CELKEM VÝHRA $totalWin!"
                    evalSummary.isFullFruitDouble ->
                        "★ PLÁTNO OVOCE ×2! CELKEM VÝHRA $totalWin! ★"
                    else -> {
                        val winNames = evalSummary.winningLines.take(2).joinToString(" + ") {
                            val extra = if (it.bonusNote.isNotBlank()) " ${it.bonusNote}" else ""
                            "${it.symbol.titleCz}$extra (${it.winAmount})"
                        }
                        val moreCount = evalSummary.winningLines.size - 2
                        val suffix = if (moreCount > 0) " +$moreCount dal." else ""
                        "VÝHRA $totalWin! ($winNames$suffix)"
                    }
                }
            } else if (evalSummary.awardedFreeSpins > 0) {
                clearHoldState()
                statusBanner = "★ ZVONKY! VYHRÁVÁŠ +${evalSummary.awardedFreeSpins} FREE SPINŮ (VÝHRY ×2)! ★"
            } else {
                val suggested = SlotMachineRules.suggestHoldReels(currentGrid, mode)
                val hasGoodHoldSuggestion = mode.allowHold && suggested.any { it }
                if (!isFreeSpinTurn && !activeHold && !usedHoldThisTurn && hasGoodHoldSuggestion) {
                    canHoldNow = true
                    usedHoldThisTurn = true
                    for (i in 0 until activeReelCount) {
                        suggestedHoldReels[i] = suggested[i]
                        heldReels[i] = false
                    }
                    statusBanner = "TIP NA HOLD! KLEPNI NA VÁLCE NEBO TLAČÍTKA HOLD 1–$activeReelCount."
                } else {
                    clearHoldState()
                    statusBanner = if (mode == MachineMode.VEGAS_5V && freeSpinsRemaining > 0) {
                        "FREE SPIN DOKONČEN – ZBÝVÁ $freeSpinsRemaining VOLNÝCH ZATOČENÍ!"
                    } else {
                        "ZKUS ŠTĚSTÍ ZNOVU! STISKNI START NEBO ZATÁHNI ZA PÁKU."
                    }
                }
            }
        }
    }
}

@Composable
fun App() {
    MaterialTheme {
        val scope = rememberCoroutineScope()
        val game = remember(scope) { BanditaGameState(scope) }

        var currentScreen by remember { mutableStateOf(BanditaScreen.CABINET) }

        // Online rekordy (Firebase)
        var leaderboardCategory by remember { mutableStateOf(MachineMode.CLASSIC_3V) }
        var nickInput by remember { mutableStateOf("") }
        var leaderboardList by remember { mutableStateOf<List<LeaderboardEntry>>(emptyList()) }
        var leaderboardError by remember { mutableStateOf<String?>(null) }
        var isLoadingBoard by remember { mutableStateOf(false) }

        // Animacni smycka zarovek + chytlavy 90s rytmicky zvuk pri aktivnim RISKU
        LaunchedEffect(currentScreen, game.pendingWin, game.anySpinning) {
            while (true) {
                delay(280L)
                game.blinkPhase = !game.blinkPhase
                if (!game.isRiskBusy) {
                    if (game.riskRevealHoldTicks > 0) {
                        game.riskRevealHoldTicks -= 1
                    } else {
                        game.riskLampIsRed = !game.riskLampIsRed
                        if (currentScreen == BanditaScreen.CABINET && game.pendingWin > 0 && !game.anySpinning) {
                            PlatformServices.playSound(
                                if (game.riskLampIsRed) BanditaSound.RISK_BEAT_A else BanditaSound.RISK_BEAT_B
                            )
                        }
                    }
                }
            }
        }

        fun refreshLeaderboard(mode: MachineMode = leaderboardCategory) {
            isLoadingBoard = true
            leaderboardError = null
            PlatformServices.fetchTopScores(gameId = mode.firebaseGameId, includeZeroScores = false) { list, err ->
                isLoadingBoard = false
                leaderboardList = list
                leaderboardError = err
            }
        }

        val currentHighScore = game.getHighScoreForMode(game.machineMode)

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0E051A)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1E0A38), Color(0xFF0E051A), Color(0xFF160828))
                        )
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (currentScreen) {
                    BanditaScreen.CABINET -> {
                        // 1) Horni svitici rampa s prepinacem 4 hernich automatu
                        TopCabinetHeader(
                            blinkPhase = game.blinkPhase,
                            machineMode = game.machineMode,
                            onSelectMode = { newMode -> game.switchMachineMode(newMode) },
                            onOpenPaytable = {
                                PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
                                currentScreen = BanditaScreen.PAYTABLE
                            },
                            onOpenLeaderboard = {
                                PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
                                leaderboardCategory = game.machineMode
                                currentScreen = BanditaScreen.LEADERBOARD
                                refreshLeaderboard(game.machineMode)
                            }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // 2) Digitalni 7-segmentova LED palubka
                        val fourthLabel = when (game.machineMode) {
                            MachineMode.SUPER_4V -> "SUPERMETR"
                            MachineMode.VEGAS_5V -> if (game.freeSpinsRemaining > 0) "FREE SPINY" else "REKORD"
                            else -> "REKORD"
                        }
                        val fourthValue = when (game.machineMode) {
                            MachineMode.SUPER_4V -> game.supermeter
                            MachineMode.VEGAS_5V -> if (game.freeSpinsRemaining > 0) game.freeSpinsRemaining else currentHighScore
                            else -> currentHighScore
                        }

                        DigitalLedDashboard(
                            credits = game.credits,
                            currentBet = game.currentBet,
                            pendingWin = game.pendingWin,
                            fourthLabel = fourthLabel,
                            fourthValue = fourthValue
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // 3) Horni panel podle vybraneho automatu
                        when (game.machineMode) {
                            MachineMode.SUPER_4V -> {
                                UpperBonusWheelSection(
                                    currentBet = game.currentBet,
                                    supermeter = game.supermeter,
                                    activeSliceIndex = game.activeWheelIndex,
                                    isWheelSpinning = game.isWheelSpinning,
                                    blinkPhase = game.blinkPhase,
                                    onSpinWheel = { game.spinUpperBonusWheel() },
                                    onCashOutSupermeter = { game.cashOutSupermeter() }
                                )
                            }
                            MachineMode.CRISSCROSS_27 -> {
                                CrissCrossInfoBanner(currentBet = game.currentBet, blinkPhase = game.blinkPhase)
                            }
                            MachineMode.VEGAS_5V -> {
                                VegasFreeSpinsBanner(
                                    currentBet = game.currentBet,
                                    freeSpinsRemaining = game.freeSpinsRemaining,
                                    blinkPhase = game.blinkPhase
                                )
                            }
                            MachineMode.CLASSIC_3V -> {
                                MiniPaytableStrip(currentBet = game.currentBet)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 4) Hlavni okno 3, 4 nebo 5 valcu s pakou
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            SlotMachineReelsCanvas(
                                machineMode = game.machineMode,
                                reels = game.reels.toList(),
                                spinningReels = game.spinningReels.toList(),
                                reelPhaseOffsets = game.reelPhaseOffsets.toList(),
                                heldReels = game.heldReels.toList(),
                                suggestedHoldReels = game.suggestedHoldReels.toList(),
                                canHoldNow = game.canHoldNow,
                                winningLines = game.winningLines,
                                highlightCells = game.highlightCells,
                                leverPullProgress = game.leverPullProgress,
                                blinkPhase = game.blinkPhase,
                                onReelTapped = { idx -> game.toggleReelHold(idx) },
                                onLeverPulled = { game.startSpin() },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 5) Informacni LED pas s aktualni hlaskou
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF12071F), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = game.statusBanner,
                                color = if ((game.pendingWin > 0 || game.freeSpinsRemaining > 0) && game.blinkPhase) {
                                    Color(0xFFFFEA00)
                                } else {
                                    Color(0xFFFFCC80)
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 6) Podsvicena tlacitka HOLD (pouze u 3V a 4V automatu, ktere maji mechanicky HOLD)
                        if (game.machineMode.allowHold) {
                            val activeReelCount = game.machineMode.reelCount
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 28.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                for (idx in 0 until activeReelCount) {
                                    val isHeld = game.heldReels[idx]
                                    val isSuggested = game.canHoldNow && game.suggestedHoldReels[idx]
                                    val bgColor = when {
                                        isHeld -> Color(0xFFFFAB00)
                                        isSuggested && game.blinkPhase -> Color(0xFF2E7D32)
                                        game.canHoldNow -> Color(0xFF4E342E)
                                        else -> Color(0xFF2D1E3E)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(31.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(bgColor)
                                            .border(
                                                width = if (isHeld || isSuggested) 2.dp else 1.dp,
                                                color = when {
                                                    isHeld -> Color(0xFFFFF59D)
                                                    isSuggested -> Color(0xFF69F0AE)
                                                    else -> Color(0xFF8D6E63)
                                                },
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable(enabled = game.canHoldNow && !game.anySpinning) {
                                                game.toggleReelHold(idx)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val btnLabel = when {
                                            isHeld -> "DRŽÍ ${idx + 1}"
                                            isSuggested -> "TIP ${idx + 1}"
                                            else -> "HOLD ${idx + 1}"
                                        }
                                        Text(
                                            text = btnLabel,
                                            color = if (isHeld) Color(0xFF1A092B) else Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        // 7) Prehledny RISK 2x monitor s historii barev a tlacitkem PULIT (1/2)
                        if (game.pendingWin > 0) {
                            RiskGambleControlPanel(
                                pendingWin = game.pendingWin,
                                riskStepCount = game.riskStepCount,
                                riskLampIsRed = game.riskLampIsRed,
                                isRiskBusy = game.isRiskBusy,
                                riskHistory = game.riskHistory.toList(),
                                showSupermeterTransfer = (game.machineMode == MachineMode.SUPER_4V),
                                onPickRed = { game.triggerGambleRisk(true) },
                                onPickBlack = { game.triggerGambleRisk(false) },
                                onHalfWin = { game.halfPendingWin() },
                                onTransferSuper = { game.transferWinToSupermeter() },
                                onCollect = { game.collectPendingWin() }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        // 8) Hlavni arkadovy pult (SÁZKA, DOPLNIT KREDIT, START)
                        val betLockedByFreeSpins = (game.machineMode == MachineMode.VEGAS_5V && game.freeSpinsRemaining > 0)
                        val canChangeBet = !game.anySpinning && game.pendingWin == 0 && !betLockedByFreeSpins
                        val activeModeFreeSpins = if (game.machineMode == MachineMode.VEGAS_5V) game.freeSpinsRemaining else 0

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { game.cycleBet() },
                                enabled = canChangeBet,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = if (betLockedByFreeSpins) "SÁZKA (BONUS)" else "SÁZKA (BET)",
                                        fontSize = 9.sp,
                                        color = Color(0xFFBBDEFB),
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${game.currentBet} KREDITŮ",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                }
                            }

                            if (game.credits < game.currentBet && game.pendingWin == 0 && game.supermeter == 0 && activeModeFreeSpins == 0) {
                                Button(
                                    onClick = { game.addEmergencyCoin() },
                                    enabled = !game.anySpinning,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier
                                        .weight(0.95f)
                                        .height(46.dp)
                                ) {
                                    Text(
                                        text = "+100 KREDIT",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFFFEA00),
                                        maxLines = 1
                                    )
                                }
                            }

                            val startButtonText = when {
                                game.pendingWin > 0 -> "VZÍT (${game.pendingWin}) & START"
                                activeModeFreeSpins > 0 -> "★ FREE SPIN ($activeModeFreeSpins) ★"
                                else -> "★ START ★"
                            }

                            Button(
                                onClick = { game.startSpin() },
                                enabled = !game.anySpinning,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        game.pendingWin > 0 -> Color(0xFFFF8F00)
                                        activeModeFreeSpins > 0 -> Color(0xFFAA00FF)
                                        else -> Color(0xFF2E7D32)
                                    }
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .weight(1.35f)
                                    .height(46.dp)
                                    .border(2.dp, Color(0xFFFFD700), RoundedCornerShape(10.dp))
                            ) {
                                Text(
                                    text = startButtonText,
                                    fontSize = if (game.pendingWin > 0) 12.sp else 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    BanditaScreen.PAYTABLE -> {
                        PaytableScreen(
                            currentBet = game.currentBet,
                            machineMode = game.machineMode,
                            onBack = {
                                PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
                                currentScreen = BanditaScreen.CABINET
                            }
                        )
                    }

                    BanditaScreen.LEADERBOARD -> {
                        val selectedBest = game.getHighScoreForMode(leaderboardCategory)
                        LeaderboardScreen(
                            selectedCategory = leaderboardCategory,
                            onSelectCategory = { cat ->
                                leaderboardCategory = cat
                                PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
                                refreshLeaderboard(cat)
                            },
                            savedNick = game.savedNick,
                            nickInput = nickInput,
                            onNickInputChange = { if (it.length <= 14) nickInput = it },
                            highScore = selectedBest,
                            entries = leaderboardList,
                            errorText = leaderboardError,
                            isLoading = isLoadingBoard,
                            onSaveNick = {
                                val trimmed = nickInput.trim()
                                if (trimmed.length < 2) {
                                    leaderboardError = "Přezdívka musí mít 2 až 14 znaků."
                                } else {
                                    isLoadingBoard = true
                                    leaderboardError = null
                                    PlatformServices.submitScore(
                                        gameId = leaderboardCategory.firebaseGameId,
                                        nick = trimmed,
                                        score = selectedBest
                                    ) { ok, msg ->
                                        if (ok) {
                                            game.savedNick = trimmed
                                            nickInput = ""
                                            refreshLeaderboard(leaderboardCategory)
                                        } else {
                                            isLoadingBoard = false
                                            leaderboardError = msg
                                        }
                                    }
                                }
                            },
                            onRefresh = { refreshLeaderboard(leaderboardCategory) },
                            onBack = {
                                PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
                                currentScreen = BanditaScreen.CABINET
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "© 2026 Created by Pavel Mareš",
                    color = Color(0xFF9E86B8),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun TopCabinetHeader(
    blinkPhase: Boolean,
    machineMode: MachineMode,
    onSelectMode: (MachineMode) -> Unit,
    onOpenPaytable: () -> Unit,
    onOpenLeaderboard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF4A148C), Color(0xFF880E4F), Color(0xFF4A148C))
                ),
                RoundedCornerShape(12.dp)
            )
            .border(2.dp, Color(0xFFFFD700), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (b in 0 until 12) {
                val lit = (b % 2 == 0) == blinkPhase
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (lit) Color(0xFFFFEA00) else Color(0xFF5D4037))
                )
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2D124D))
                    .border(1.dp, Color(0xFFFFD54F), RoundedCornerShape(6.dp))
                    .clickable { onOpenPaytable() }
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "VÝPLATY",
                    color = Color(0xFFFFF59D),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Text(
                text = "★ JEDNORUKÝ BANDITA ★",
                color = Color(0xFFFFD700),
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2D124D))
                    .border(1.dp, Color(0xFFFFD54F), RoundedCornerShape(6.dp))
                    .clickable { onOpenLeaderboard() }
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "TOP 50",
                    color = Color(0xFF80D8FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (mode in MachineMode.entries) {
                val selected = (mode == machineMode)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Color(0xFFFFB300) else Color(0xFF240B36))
                        .border(
                            width = 1.dp,
                            color = if (selected) Color.White else Color(0xFF7B1FA2),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable { onSelectMode(mode) }
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.shortTabCz,
                        color = if (selected) Color(0xFF1A062B) else Color(0xFFE1BEE7),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun UpperBonusWheelSection(
    currentBet: Int,
    supermeter: Int,
    activeSliceIndex: Int,
    isWheelSpinning: Boolean,
    blinkPhase: Boolean,
    onSpinWheel: () -> Unit,
    onCashOutSupermeter: () -> Unit
) {
    val slices = SlotMachineRules.BONUS_WHEEL_SLICES
    val wheelCost = currentBet * 2
    val canSpinWheel = supermeter >= wheelCost && !isWheelSpinning

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B0930), RoundedCornerShape(8.dp))
            .border(1.5.dp, Color(0xFFFFD700), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (rowIdx in 0..1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (colIdx in 0..3) {
                    val idx = rowIdx * 4 + colIdx
                    val slice = slices[idx]
                    val isLit = (idx == activeSliceIndex)
                    val baseColor = Color(slice.colorHex)
                    val displayText = when {
                        slice.isLose -> "✖ NIC"
                        slice.isMystery -> "★MYSTERY★"
                        slice.isJackpot -> "★${slice.baseMultiplier * currentBet}★"
                        else -> "+${slice.baseMultiplier * currentBet}"
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(23.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                if (isLit) baseColor else baseColor.copy(alpha = 0.24f)
                            )
                            .border(
                                width = if (isLit) 2.dp else 1.dp,
                                color = if (isLit) Color.White else baseColor.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(5.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayText,
                            color = when {
                                isLit && !slice.isLose -> Color(0xFF12051E)
                                slice.isLose && !isLit -> Color(0xFFB0BEC5)
                                else -> Color.White
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1.35f)
                    .height(27.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            canSpinWheel && blinkPhase -> Color(0xFFFFEA00)
                            canSpinWheel -> Color(0xFFFF9100)
                            else -> Color(0xFF3E2723)
                        }
                    )
                    .border(1.dp, Color(0xFFFFD54F), RoundedCornerShape(6.dp))
                    .clickable(enabled = canSpinWheel) { onSpinWheel() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "★ TOČIT DRUHÉ KOLO (-$wheelCost) ★",
                    color = if (canSpinWheel) Color(0xFF1A062B) else Color(0xFF9E9E9E),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }

            if (supermeter > 0) {
                Box(
                    modifier = Modifier
                        .weight(0.85f)
                        .height(27.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF00897B))
                        .border(1.dp, Color(0xFF80CBC4), RoundedCornerShape(6.dp))
                        .clickable(enabled = !isWheelSpinning) { onCashOutSupermeter() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "VYBRAT ($supermeter)",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun CrissCrossInfoBanner(currentBet: Int, blinkPhase: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF280B3A), RoundedCornerShape(7.dp))
            .border(1.dp, Color(0xFFFF80AB), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "★ 27 KŘÍŽOVÝCH CEST — 9× OVOCE NA OBRAZOVCE = PLÁTNO ×2! ★",
            color = if (blinkPhase) Color(0xFFFFEA00) else Color(0xFFFF80AB),
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "3× ★JOKER = ${currentBet * 50}   |   3× ZVONEK KDEKOLIV = MYSTERY VÝHRA AŽ ${currentBet * 50}!",
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VegasFreeSpinsBanner(
    currentBet: Int,
    freeSpinsRemaining: Int,
    blinkPhase: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (freeSpinsRemaining > 0) Color(0xFF311B92) else Color(0xFF0D291E),
                RoundedCornerShape(7.dp)
            )
            .border(
                1.dp,
                if (freeSpinsRemaining > 0) Color(0xFFFFEA00) else Color(0xFF00E676),
                RoundedCornerShape(7.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (freeSpinsRemaining > 0) {
            Text(
                text = "★ AKTIVNÍ BONUS: ZBÝVÁ $freeSpinsRemaining FREE SPINŮ (VŠECHNY VÝHRY ×2)! ★",
                color = if (blinkPhase) Color(0xFFFFEA00) else Color(0xFF69F0AE),
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        } else {
            Text(
                text = "★ 5 VÁLCŮ / 9 LINIÍ — 5× ★JOKER = ${currentBet * 300}  |  5× 777 = ${currentBet * 150} ★",
                color = Color(0xFF69F0AE),
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
        Text(
            text = "3× ZVONEK = 8 FREE SPINŮ   |   4× ZVONEK = 12   |   5× ZVONEK = 18 FREE SPINŮ!",
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DigitalLedDashboard(
    credits: Int,
    currentBet: Int,
    pendingWin: Int,
    fourthLabel: String,
    fourthValue: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LedBox(
            label = "KREDIT",
            value = credits.toString().padStart(5, '0'),
            valueColor = Color(0xFFFF1744),
            modifier = Modifier.weight(1.15f)
        )
        LedBox(
            label = "SÁZKA",
            value = currentBet.toString().padStart(2, '0'),
            valueColor = Color(0xFF00E676),
            modifier = Modifier.weight(0.75f)
        )
        LedBox(
            label = "VÝHRA",
            value = pendingWin.toString().padStart(5, '0'),
            valueColor = Color(0xFFFFEA00),
            modifier = Modifier.weight(1.1f)
        )
        LedBox(
            label = fourthLabel,
            value = fourthValue.toString().padStart(5, '0'),
            valueColor = Color(0xFF40C4FF),
            modifier = Modifier.weight(1.1f)
        )
    }
}

@Composable
private fun LedBox(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF09030E), RoundedCornerShape(8.dp))
            .border(1.5.dp, Color(0xFF795548), RoundedCornerShape(8.dp))
            .padding(vertical = 3.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = Color(0xFFBDBDBD),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

@Composable
private fun MiniPaytableStrip(currentBet: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A0B2E), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF4A148C), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MiniPayItem("★JOKER", SlotMachineRules.getClassic3VMultiplier(SlotSymbol.JOKER) * currentBet, Color(0xFFFFEA00), Modifier.weight(1.15f))
            MiniPayItem("777", SlotMachineRules.getClassic3VMultiplier(SlotSymbol.SEVEN) * currentBet, Color(0xFFFF5252), Modifier.weight(0.95f))
            MiniPayItem("BAR", SlotMachineRules.getClassic3VMultiplier(SlotSymbol.BAR) * currentBet, Color(0xFFFFD54F), Modifier.weight(0.95f))
            MiniPayItem("ZVON", SlotMachineRules.getClassic3VMultiplier(SlotSymbol.BELL) * currentBet, Color(0xFF81C784), Modifier.weight(1f))
            MiniPayItem("MELOUN", SlotMachineRules.getClassic3VMultiplier(SlotSymbol.MELON) * currentBet, Color(0xFF4FC3F7), Modifier.weight(1.1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MiniPayItem("HROZNY", SlotMachineRules.getClassic3VMultiplier(SlotSymbol.GRAPES) * currentBet, Color(0xFFCE93D8), Modifier.weight(1.15f))
            MiniPayItem("ŠVESTKA", SlotMachineRules.getClassic3VMultiplier(SlotSymbol.PLUM) * currentBet, Color(0xFFB39DDB), Modifier.weight(1.05f))
            MiniPayItem("POMER.", SlotMachineRules.getClassic3VMultiplier(SlotSymbol.ORANGE) * currentBet, Color(0xFFFFB74D), Modifier.weight(1f))
            MiniPayItem("CITRÓN", SlotMachineRules.getClassic3VMultiplier(SlotSymbol.LEMON) * currentBet, Color(0xFFFFF176), Modifier.weight(1f))
            MiniPayItem("TŘEŠNĚ", SlotMachineRules.getClassic3VMultiplier(SlotSymbol.CHERRY) * currentBet, Color(0xFFFF8A80), Modifier.weight(1.05f))
        }
    }
}

@Composable
private fun MiniPayItem(
    label: String,
    payout: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = "$label:$payout",
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier
    )
}

@Composable
private fun RiskGambleControlPanel(
    pendingWin: Int,
    riskStepCount: Int,
    riskLampIsRed: Boolean,
    isRiskBusy: Boolean,
    riskHistory: List<Boolean>,
    showSupermeterTransfer: Boolean,
    onPickRed: () -> Unit,
    onPickBlack: () -> Unit,
    onHalfWin: () -> Unit,
    onTransferSuper: () -> Unit,
    onCollect: () -> Unit
) {
    val doubledTarget = pendingWin * 2
    val canHalf = pendingWin >= 2 && !isRiskBusy

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF260E3A), RoundedCornerShape(10.dp))
            .border(1.5.dp, Color(0xFFFFEA00), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "RISK (${riskStepCount}/5): $pendingWin ➔ 2× = $doubledTarget",
                color = Color(0xFFFFEA00),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TAŽENO:",
                    color = Color(0xFFB39DDB),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                if (riskHistory.isEmpty()) {
                    Text("—", color = Color.Gray, fontSize = 9.sp)
                } else {
                    for (wasRed in riskHistory) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (wasRed) Color(0xFFD50000) else Color(0xFF263238))
                                .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (wasRed) "♥" else "♠",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onPickRed,
                enabled = !isRiskBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (riskLampIsRed) Color(0xFFD50000) else Color(0xFF7F0000)
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                modifier = Modifier
                    .weight(1.05f)
                    .height(36.dp)
                    .border(
                        width = if (riskLampIsRed) 2.dp else 1.dp,
                        color = if (riskLampIsRed) Color(0xFFFFEA00) else Color(0xFFFF8A80),
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                Text(
                    text = "♥ ČERVENÁ ($doubledTarget)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 1
                )
            }

            Button(
                onClick = onPickBlack,
                enabled = !isRiskBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!riskLampIsRed) Color(0xFF37474F) else Color(0xFF1C262B)
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                modifier = Modifier
                    .weight(1.05f)
                    .height(36.dp)
                    .border(
                        width = if (!riskLampIsRed) 2.dp else 1.dp,
                        color = if (!riskLampIsRed) Color(0xFFFFEA00) else Color(0xFF90A4AE),
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                Text(
                    text = "♠ ČERNÁ ($doubledTarget)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 1
                )
            }

            if (canHalf) {
                Button(
                    onClick = onHalfWin,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    modifier = Modifier
                        .weight(0.72f)
                        .height(36.dp)
                ) {
                    Text(
                        text = "1/2 (${pendingWin / 2})",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFFFF59D),
                        maxLines = 1
                    )
                }
            }

            if (showSupermeterTransfer) {
                Button(
                    onClick = onTransferSuper,
                    enabled = !isRiskBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    modifier = Modifier
                        .weight(0.75f)
                        .height(36.dp)
                ) {
                    Text(
                        text = "▲ SUPER",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF09030E),
                        maxLines = 1
                    )
                }
            }

            Button(
                onClick = onCollect,
                enabled = !isRiskBusy,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8F00)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                modifier = Modifier
                    .weight(0.75f)
                    .height(36.dp)
            ) {
                Text(
                    text = "VZÍT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF1A092B),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PaytableScreen(
    currentBet: Int,
    machineMode: MachineMode,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A0B2E), RoundedCornerShape(12.dp))
            .border(2.dp, Color(0xFFFFD700), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = "VÝPLATY – ${machineMode.titleCz} (SÁZKA $currentBet)",
            color = Color(0xFFFFD700),
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = when (machineMode) {
                MachineMode.CLASSIC_3V ->
                    "• 3 válce, 5 výherních linií (3 vodorovné + 2 úhlopříčky).\n• Symbol JOKER ★ nahrazuje jakýkoliv jiný symbol! 2× TŘEŠNĚ zleva vrací sázku ($currentBet).\n• Po nevýherním kole můžeš podržet až 2 válce pomocí HOLD."
                MachineMode.SUPER_4V ->
                    "• 4 válce, 5 linií. Vyhrávají 3 stejné zleva, 3 stejné zprava i 4 stejné v řadě!\n• Tlačítkem ▲ SUPER převedeš výhru do Supermetru a točíš HORNÍ DRUHÉ KOLO (výhry až 25× sázka a ★MYSTERY★, pozor na pole ✖ NIC)."
                MachineMode.CRISSCROSS_27 ->
                    "• 3 válce, 27 křížových cest (každý řádek 1., 2. a 3. válce).\n• Devět ovocných symbolů na obrazovce násobí všechny výhry 2× (PLÁTNO ×2)!\n• 3× ZVONEK kdekoliv na válcích vyplácí MYSTERY BONUS (8× až 50× sázka)!"
                MachineMode.VEGAS_5V ->
                    "• 5 válců, 9 výherních linií. Vyhrávají 3, 4 nebo 5 stejných symbolů zleva.\n• 3, 4 nebo 5 ZVONKŮ kdekoliv spouští 8, 12 nebo 18 FREE SPINŮ zdarma s uzamčenou sázkou a 2× násobičem všech výher!"
            },
            color = Color(0xFFE0E0E0),
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val reversedSyms = SlotSymbol.entries.reversed()
            itemsIndexed(reversedSyms) { _, sym ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF281244), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = sym.titleCz,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = SlotMachineRules.getPaytableMultipliersText(machineMode, sym, currentBet),
                        color = Color(0xFFFFEA00),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ZPĚT K AUTOMATU", fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
    }
}

@Composable
private fun LeaderboardScreen(
    selectedCategory: MachineMode,
    onSelectCategory: (MachineMode) -> Unit,
    savedNick: String,
    nickInput: String,
    onNickInputChange: (String) -> Unit,
    highScore: Int,
    entries: List<LeaderboardEntry>,
    errorText: String?,
    isLoading: Boolean,
    onSaveNick: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A0B2E), RoundedCornerShape(12.dp))
            .border(2.dp, Color(0xFFFFD700), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        // Horni lista: vlevo nadpis 12.sp s weight(1f), vpravo pouze OBNOVIT
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ONLINE REKORDY (TOP 50)",
                color = Color(0xFFFFD700),
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1565C0))
                    .clickable { onRefresh() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "OBNOVIT",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Prepinaci tlacitka kategorii pro vsechny 4 automaty
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (cat in MachineMode.entries) {
                val active = (cat == selectedCategory)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) Color(0xFFFFB300) else Color(0xFF281244))
                        .border(
                            width = 1.dp,
                            color = if (active) Color.White else Color(0xFF7B1FA2),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable { onSelectCategory(cat) }
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = cat.shortTabCz,
                        color = if (active) Color(0xFF1A062B) else Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Uzamceny prouzek hrace (11.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A1447), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "HRÁČ: ${if (savedNick.isBlank()) "(nezadáno)" else savedNick}",
                color = Color(0xFF80D8FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "OSOBNÍ: $highScore",
                color = Color(0xFFFFEA00),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        // Zadavaci pole pro prezdivku POUZE pokud hrac jeste nema ulozeny nick
        if (savedNick.isBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .background(Color(0xFF0E051A), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (nickInput.isEmpty()) {
                        Text(
                            text = "Zadej přezdívku (2–14 znaků)...",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    BasicTextField(
                        value = nickInput,
                        onValueChange = onNickInputChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        cursorBrush = SolidColor(Color(0xFFFFD700)),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2E7D32))
                        .clickable { onSaveNick() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ULOŽIT",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        if (!errorText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = errorText,
                color = Color(0xFFFF5252),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tabulka TOP 50
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF120621), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF4A148C), RoundedCornerShape(8.dp))
                .padding(6.dp)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Načítám online žebříček...", color = Color(0xFFFFD54F), fontSize = 13.sp)
                }
            } else if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Zatím žádné záznamy. Buď první!", color = Color.LightGray, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(entries) { idx, item ->
                        val isMe = item.isCurrentUser || (savedNick.isNotBlank() && item.nick.equals(savedNick, ignoreCase = true))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isMe) Color(0xFF4A148C) else Color(0xFF1F0D38),
                                    RoundedCornerShape(6.dp)
                                )
                                .border(
                                    width = if (isMe) 1.dp else 0.dp,
                                    color = if (isMe) Color(0xFFFFD700) else Color.Transparent,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${idx + 1}.",
                                color = when (idx) {
                                    0 -> Color(0xFFFFD700)
                                    1 -> Color(0xFFE0E0E0)
                                    2 -> Color(0xFFFFB74D)
                                    else -> Color(0xFFB39DDB)
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.width(32.dp)
                            )
                            Text(
                                text = item.nick,
                                color = if (isMe) Color(0xFFFFF59D) else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isMe) FontWeight.ExtraBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${item.score}",
                                color = Color(0xFF00E676),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "ZPĚT DO MENU",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}
