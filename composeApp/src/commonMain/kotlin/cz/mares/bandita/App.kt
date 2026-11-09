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

private enum class BanditaScreen {
    CABINET,
    PAYTABLE,
    LEADERBOARD
}

/**
 * Reaktivni stavovy kontroler celeho automatu.
 * Eliminuje jakekoliv "zamrzle" promenne v Compose lambdach (napr. pri RISKU nebo zmene sazky).
 */
private class BanditaGameState(private val scope: CoroutineScope) {
    var machineMode by mutableStateOf(MachineMode.CLASSIC_3V)

    var credits by mutableStateOf(PlatformServices.getSavedCredits().coerceAtLeast(50))
    var highScore3V by mutableStateOf(maxOf(PlatformServices.getHighScore("bandita"), credits))
    var highScore4V by mutableStateOf(maxOf(PlatformServices.getHighScore("bandita_super"), credits))
    var highScore27 by mutableStateOf(maxOf(PlatformServices.getHighScore("bandita_crisscross"), credits))
    var highScore5V by mutableStateOf(maxOf(PlatformServices.getHighScore("bandita_vegas5"), credits))

    var betIndex by mutableStateOf(2) // vychozi sazka 5
    val currentBet: Int
        get() = SlotMachineRules.BET_OPTIONS[betIndex]

    var pendingWin by mutableStateOf(0)
    var supermeter by mutableStateOf(0)
    var freeSpinsRemaining by mutableStateOf(0)
    var riskStepCount by mutableStateOf(0)
    var isRiskBusy by mutableStateOf(false)
    val riskHistory = mutableStateListOf<Boolean>() // true = Cervena, false = Cerna

    var statusBanner by mutableStateOf("ZATÁHNI ZA PÁKU NEBO STISKNI START!")

    // Pozice zastaveni na pasu pro 5 valcu a viditelne symboly
    val reelStopPositions = mutableStateListOf(16, 9, 3, 21, 14)
    val reels = mutableStateListOf(
        SlotMachineRules.getVisibleReelSymbols(0, 16),
        SlotMachineRules.getVisibleReelSymbols(1, 9),
        SlotMachineRules.getVisibleReelSymbols(2, 3),
        SlotMachineRules.getVisibleReelSymbols(3, 21),
        SlotMachineRules.getVisibleReelSymbols(4, 14)
    )
    val spinningReels = mutableStateListOf(false, false, false, false, false)
    val reelPhaseOffsets = mutableStateListOf(16f, 9f, 3f, 21f, 14f)

    var canHoldNow by mutableStateOf(false)
    val heldReels = mutableStateListOf(false, false, false, false, false)
    val suggestedHoldReels = mutableStateListOf(false, false, false, false, false)
    var usedHoldThisTurn by mutableStateOf(false)

    var activeWheelIndex by mutableStateOf(0)
    var isWheelSpinning by mutableStateOf(false)

    var winningLines by mutableStateOf<List<WinningLineResult>>(emptyList())
    var leverPullProgress by mutableStateOf(0f)

    var blinkPhase by mutableStateOf(false)
    var riskLampIsRed by mutableStateOf(true)

    var savedNick by mutableStateOf(PlatformServices.getSavedNick())

    val anySpinning: Boolean
        get() = spinningReels.any { it } || isWheelSpinning || isRiskBusy

    fun getHighScoreForMode(mode: MachineMode): Int = when (mode) {
        MachineMode.CLASSIC_3V -> highScore3V
        MachineMode.SUPER_4V -> highScore4V
        MachineMode.CRISSCROSS_27 -> highScore27
        MachineMode.VEGAS_5V -> highScore5V
    }

    fun syncCreditsAndRecord(newCredits: Int) {
        credits = newCredits
        PlatformServices.saveCredits(newCredits)
        val totalWealth = newCredits + supermeter

        when (machineMode) {
            MachineMode.CLASSIC_3V -> if (totalWealth > highScore3V) {
                highScore3V = totalWealth
                PlatformServices.saveHighScore(totalWealth, "bandita")
                if (savedNick.isNotBlank()) {
                    PlatformServices.submitScore("bandita", savedNick, totalWealth) { _, _ -> }
                }
            }
            MachineMode.SUPER_4V -> if (totalWealth > highScore4V) {
                highScore4V = totalWealth
                PlatformServices.saveHighScore(totalWealth, "bandita_super")
                if (savedNick.isNotBlank()) {
                    PlatformServices.submitScore("bandita_super", savedNick, totalWealth) { _, _ -> }
                }
            }
            MachineMode.CRISSCROSS_27 -> if (totalWealth > highScore27) {
                highScore27 = totalWealth
                PlatformServices.saveHighScore(totalWealth, "bandita_crisscross")
                if (savedNick.isNotBlank()) {
                    PlatformServices.submitScore("bandita_crisscross", savedNick, totalWealth) { _, _ -> }
                }
            }
            MachineMode.VEGAS_5V -> if (totalWealth > highScore5V) {
                highScore5V = totalWealth
                PlatformServices.saveHighScore(totalWealth, "bandita_vegas5")
                if (savedNick.isNotBlank()) {
                    PlatformServices.submitScore("bandita_vegas5", savedNick, totalWealth) { _, _ -> }
                }
            }
        }
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
        val activeReelCount = machineMode.reelCount
        if (!canHoldNow || anySpinning || reelIdx !in 0 until activeReelCount) return
        val maxHoldAllowed = activeReelCount - 1
        val currentlyHeldCount = heldReels.take(activeReelCount).count { it }
        if (!heldReels[reelIdx] && currentlyHeldCount >= maxHoldAllowed) {
            val firstHeld = heldReels.take(activeReelCount).indexOfFirst { it }
            if (firstHeld >= 0) heldReels[firstHeld] = false
        }
        heldReels[reelIdx] = !heldReels[reelIdx]
        PlatformServices.playSound(BanditaSound.HOLD_LOCK)
    }

    fun switchMachineMode(newMode: MachineMode) {
        if (anySpinning || machineMode == newMode) return
        // Bezpecne ulozime nevybranou vyhru i Supermetr do kreditu
        val rescued = pendingWin + supermeter
        if (rescued > 0) {
            pendingWin = 0
            supermeter = 0
            riskStepCount = 0
            syncCreditsAndRecord(credits + rescued)
        }
        machineMode = newMode
        clearHoldState()
        winningLines = emptyList()
        PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
        statusBanner = when (newMode) {
            MachineMode.CLASSIC_3V -> "KLASIK 3V: 5 LINIÍ + HOLD + RISK 2×"
            MachineMode.SUPER_4V -> "SUPER 4V: 4 VÁLCE + SUPERMETR A DRUHÉ KOLO!"
            MachineMode.CRISSCROSS_27 -> "JOKER 27: 27 KŘÍŽOVÝCH CEST + JOKER NÁSOBIČ 2×/4×!"
            MachineMode.VEGAS_5V -> "VEGAS 5V: 5 VÁLCŮ, 9 LINIÍ + 3× ZVONEK = FREE SPINY!"
        }
    }

    fun cycleBet() {
        val betLockedByFreeSpins = (machineMode == MachineMode.VEGAS_5V && freeSpinsRemaining > 0)
        if (anySpinning || betLockedByFreeSpins) return
        betIndex = (betIndex + 1) % SlotMachineRules.BET_OPTIONS.size
        PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
    }

    fun addEmergencyCoin() {
        PlatformServices.playSound(BanditaSound.WIN_SMALL)
        credits += 100
        PlatformServices.saveCredits(credits)
        statusBanner = "VHOZENA MINCE: +100 KREDITŮ DO HRY!"
    }

    fun collectPendingWin() {
        if (pendingWin <= 0 || anySpinning) return
        PlatformServices.playSound(BanditaSound.WIN_SMALL)
        val won = pendingWin
        val updated = credits + won
        pendingWin = 0
        riskStepCount = 0
        statusBanner = "PŘIPSÁNO +$won DO KREDITU!"
        syncCreditsAndRecord(updated)
    }

    /**
     * Klasicka funkce PULIT (1/2): polovina vyhry jde rovnou do kreditu
     * a s druhou polovinou lze dale bezpecne riskovat!
     */
    fun halfPendingWin() {
        if (pendingWin < 2 || anySpinning) return
        val bankedHalf = pendingWin / 2
        val remainingRisk = pendingWin - bankedHalf
        PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
        pendingWin = remainingRisk
        syncCreditsAndRecord(credits + bankedHalf)
        statusBanner = "ULOŽENA 1/2 (+$bankedHalf DO KREDITU), V RISKU ZŮSTÁVÁ $remainingRisk!"
    }

    fun transferWinToSupermeter() {
        if (pendingWin <= 0 || anySpinning) return
        PlatformServices.playSound(BanditaSound.SUPER_TRANSFER)
        val transferred = pendingWin
        supermeter += transferred
        pendingWin = 0
        riskStepCount = 0
        statusBanner = "PŘEVEDENO +$transferred DO SUPERMETRU! ROZTOČ DRUHÉ KOLO!"
        syncCreditsAndRecord(credits)
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
        syncCreditsAndRecord(updated)
    }

    fun triggerGambleRisk(chooseRed: Boolean) {
        val currentWinToRisk = pendingWin
        if (currentWinToRisk <= 0 || anySpinning) return

        isRiskBusy = true
        scope.launch {
            // Rychle prebliknuti zarovky pred odhalenim barvy
            repeat(4) {
                riskLampIsRed = !riskLampIsRed
                PlatformServices.playSound(
                    if (riskLampIsRed) BanditaSound.RISK_BEAT_A else BanditaSound.RISK_BEAT_B
                )
                delay(55L)
            }

            val drawnRed = Random.nextBoolean()
            riskLampIsRed = drawnRed
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
                    delay(350L)
                    val updated = credits + doubledWin
                    pendingWin = 0
                    riskStepCount = 0
                    statusBanner = "MAX RISK 5/5 ($suitStr)! AUTOMATICKY PŘIPSÁNO +$doubledWin!"
                    syncCreditsAndRecord(updated)
                } else {
                    statusBanner = "PADLA $suitStr! VÝHRA ZDVOJNÁSOBENA NA $doubledWin (DALŠÍ 2× = ${doubledWin * 2})!"
                }
            } else {
                PlatformServices.playSound(BanditaSound.RISK_LOSE)
                pendingWin = 0
                riskStepCount = 0
                winningLines = emptyList()
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
        val bet = currentBet
        val wheelCost = bet * 2
        if (anySpinning) return

        // Pokud mel hrac nerozehranou vyhru v pendingWin, nejprve ji pripocteme do Supermetru
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
        statusBanner = "HORNÍ DRUHÉ KOLO SE TOČÍ..."

        scope.launch {
            val slices = SlotMachineRules.BONUS_WHEEL_SLICES
            val totalSteps = Random.nextInt(18, 29)
            for (step in 0 until totalSteps) {
                activeWheelIndex = (activeWheelIndex + 1) % slices.size
                PlatformServices.playSound(BanditaSound.WHEEL_TICK)
                val waitMs = when {
                    step < totalSteps - 8 -> 65L
                    step < totalSteps - 4 -> 130L
                    else -> 215L
                }
                delay(waitMs)
            }

            val landed = slices[activeWheelIndex]
            val wheelWin = if (landed.isMystery) {
                val mysteryMultipliers = listOf(4, 8, 12, 20, 30, 50)
                bet * mysteryMultipliers[Random.nextInt(mysteryMultipliers.size)]
            } else {
                bet * landed.baseMultiplier
            }

            isWheelSpinning = false
            pendingWin += wheelWin
            riskStepCount = 0

            if (landed.isJackpot || wheelWin >= bet * 15) {
                PlatformServices.playSound(BanditaSound.JACKPOT_777)
            } else {
                PlatformServices.playSound(BanditaSound.WIN_BIG)
            }

            statusBanner = when {
                landed.isMystery -> "★ MYSTERY BONUS ★ VYLOSOVÁNO +$wheelWin!"
                landed.isJackpot -> "★ JOKER JACKPOT KOLA (${landed.baseMultiplier}×) ★ VÝHRA +$wheelWin!"
                else -> "DRUHÉ KOLO (${landed.baseMultiplier}× SÁZKA): VÝHRA +$wheelWin!"
            }
        }
    }

    fun startSpin() {
        if (anySpinning) return
        val mode = machineMode
        val activeReelCount = mode.reelCount
        val bet = currentBet

        // Pokud mel hrac nevybranou vyhru a stiskl START, nejprve ji bezpecne pripiseme do kreditu
        if (pendingWin > 0) {
            val updated = credits + pendingWin
            pendingWin = 0
            riskStepCount = 0
            syncCreditsAndRecord(updated)
        }

        val isFreeSpinTurn = (mode == MachineMode.VEGAS_5V && freeSpinsRemaining > 0)

        if (!isFreeSpinTurn) {
            if (credits < bet) {
                statusBanner = "MÁLO KREDITU PRO SÁZKU $bet! SNIŽ SÁZKU NEBO VHOĎ MINCI."
                PlatformServices.playSound(BanditaSound.RISK_LOSE)
                return
            }
            syncCreditsAndRecord(credits - bet)
        } else {
            freeSpinsRemaining -= 1
        }

        winningLines = emptyList()
        statusBanner = if (isFreeSpinTurn) {
            "★ FREE SPIN (ZBÝVÁ $freeSpinsRemaining) – VŠECHNY VÝHRY 3×! ★"
        } else {
            "VÁLCE SE TOČÍ... HODNĚ ŠTĚSTÍ!"
        }

        scope.launch {
            for (step in 1..5) {
                leverPullProgress = step / 5f
                delay(20L)
            }
            for (step in 5 downTo 0) {
                leverPullProgress = step / 5f
                delay(20L)
            }
        }

        scope.launch {
            val activeHold = canHoldNow && !isFreeSpinTurn && heldReels.take(activeReelCount).any { it }
            for (i in 0 until activeReelCount) {
                spinningReels[i] = !(activeHold && heldReels[i])
            }

            // Postupne zastavovani valcu na novych pozicich pasu
            for (i in 0 until activeReelCount) {
                if (spinningReels[i]) {
                    delay(if (i == 0) 410L else 285L)
                    val newStop = SlotMachineRules.randomStopPosition()
                    reelStopPositions[i] = newStop
                    reelPhaseOffsets[i] = newStop.toFloat()
                    reels[i] = SlotMachineRules.getVisibleReelSymbols(i, newStop)
                    spinningReels[i] = false
                    PlatformServices.playSound(BanditaSound.REEL_STOP)
                } else {
                    delay(90L)
                }
            }

            val currentGrid = reels.take(activeReelCount)
            val evalSummary = SlotMachineRules.evaluateSpin(
                mode = mode,
                reels = currentGrid,
                bet = bet,
                freeSpinMultiplier = if (isFreeSpinTurn) 3 else 1
            )

            winningLines = evalSummary.winningLines
            val totalWin = evalSummary.totalWin

            if (evalSummary.awardedFreeSpins > 0) {
                freeSpinsRemaining += evalSummary.awardedFreeSpins
                PlatformServices.playSound(BanditaSound.JACKPOT_777)
            }

            if (totalWin > 0) {
                pendingWin = totalWin
                riskStepCount = 0
                clearHoldState()

                val hasJackpot = evalSummary.winningLines.any {
                    it.symbol == SlotSymbol.JOKER || it.symbol == SlotSymbol.SEVEN
                } || evalSummary.mysteryBonusWin > 0

                when {
                    hasJackpot -> PlatformServices.playSound(BanditaSound.JACKPOT_777)
                    totalWin >= bet * 12 -> PlatformServices.playSound(BanditaSound.WIN_BIG)
                    else -> PlatformServices.playSound(BanditaSound.WIN_SMALL)
                }

                statusBanner = when {
                    evalSummary.awardedFreeSpins > 0 ->
                        "★ BONUS +${evalSummary.awardedFreeSpins} FREE SPINŮ + VÝHRA $totalWin! ★"
                    evalSummary.mysteryBonusWin > 0 ->
                        "★ 3× JOKER MYSTERY (+${evalSummary.mysteryBonusWin}) ★ CELKEM VÝHRA $totalWin!"
                    else -> {
                        val winNames = evalSummary.winningLines.take(3).joinToString(" + ") {
                            val extra = if (it.bonusNote.isNotBlank()) " ${it.bonusNote}" else ""
                            "${it.symbol.titleCz}$extra (${it.winAmount})"
                        }
                        "VÝHRA $totalWin! ($winNames)"
                    }
                }
            } else if (evalSummary.awardedFreeSpins > 0) {
                clearHoldState()
                statusBanner = "★ 3× ZVONEK! VYHRÁVÁŠ +${evalSummary.awardedFreeSpins} FREE SPINŮ (VÝHRY ×3)! ★"
            } else {
                val suggested = SlotMachineRules.suggestHoldReels(currentGrid, mode)
                val hasGoodHoldSuggestion = suggested.any { it }
                if (!isFreeSpinTurn && !activeHold && !usedHoldThisTurn && hasGoodHoldSuggestion && Random.nextFloat() < 0.75f) {
                    canHoldNow = true
                    usedHoldThisTurn = true
                    for (i in 0 until activeReelCount) {
                        suggestedHoldReels[i] = suggested[i]
                        heldReels[i] = false
                    }
                    statusBanner = "TIP NA HOLD! KLEPNI NA VÁLCE NEBO TLAČÍTKA HOLD 1–$activeReelCount."
                } else {
                    clearHoldState()
                    statusBanner = if (freeSpinsRemaining > 0) {
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

        // Animacni smycka pro blikani zarovek + chytlavy 90s rytmicky zvuk pri aktivnim RISKU
        LaunchedEffect(currentScreen, game.pendingWin, game.anySpinning) {
            while (true) {
                delay(280L)
                game.blinkPhase = !game.blinkPhase
                if (!game.isRiskBusy) {
                    game.riskLampIsRed = !game.riskLampIsRed
                }
                if (currentScreen == BanditaScreen.CABINET && game.pendingWin > 0 && !game.anySpinning) {
                    PlatformServices.playSound(
                        if (game.riskLampIsRed) BanditaSound.RISK_BEAT_A else BanditaSound.RISK_BEAT_B
                    )
                }
            }
        }

        // Smycka pro plynule otaceni valcu po jejich mechanickem pasu
        val reelsSpinningNow = game.spinningReels.any { it }
        LaunchedEffect(reelsSpinningNow) {
            var tickCounter = 0
            while (game.spinningReels.any { it }) {
                for (i in 0..4) {
                    if (game.spinningReels[i]) {
                        val nextPhase = game.reelPhaseOffsets[i] - 0.38f
                        game.reelPhaseOffsets[i] = if (nextPhase < 0f) {
                            nextPhase + SlotMachineRules.STRIP_LENGTH.toFloat()
                        } else {
                            nextPhase
                        }
                    }
                }
                tickCounter++
                if (tickCounter % 5 == 0) {
                    PlatformServices.playSound(BanditaSound.REEL_TICK)
                }
                delay(24L)
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

                        // 6) Podsvicena tlacitka HOLD (3, 4 nebo 5 podle poctu valcu)
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
                                        fontSize = if (activeReelCount == 5) 9.sp else 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { game.cycleBet() },
                                enabled = !game.anySpinning && !betLockedByFreeSpins,
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

                            if (game.credits < 5 && game.pendingWin == 0 && game.supermeter == 0 && game.freeSpinsRemaining == 0) {
                                Button(
                                    onClick = { game.addEmergencyCoin() },
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
                                game.machineMode == MachineMode.VEGAS_5V && game.freeSpinsRemaining > 0 ->
                                    "★ FREE SPIN (${game.freeSpinsRemaining}) ★"
                                else -> "★ START ★"
                            }

                            Button(
                                onClick = { game.startSpin() },
                                enabled = !game.anySpinning,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        game.pendingWin > 0 -> Color(0xFFFF8F00)
                                        game.machineMode == MachineMode.VEGAS_5V && game.freeSpinsRemaining > 0 -> Color(0xFFAA00FF)
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
                                if (isLit) baseColor else baseColor.copy(alpha = 0.22f)
                            )
                            .border(
                                width = if (isLit) 2.dp else 1.dp,
                                color = if (isLit) Color.White else baseColor.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(5.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayText,
                            color = if (isLit) Color(0xFF12051E) else Color.White,
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
            text = "★ 27 KŘÍŽOVÝCH CEST (KAŽDÝ S KAŽDÝM) ★",
            color = if (blinkPhase) Color(0xFFFFEA00) else Color(0xFFFF80AB),
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
        Text(
            text = "1× JOKER = 2× VÝHRA  |  2× JOKER = 4× VÝHRA  |  3× JOKER KDEKOLIV = MYSTERY AŽ ${currentBet * 200}!",
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
                text = "★ AKTIVNÍ BONUS: ZBÝVÁ $freeSpinsRemaining FREE SPINŮ (VŠECHNY VÝHRY ×3)! ★",
                color = if (blinkPhase) Color(0xFFFFEA00) else Color(0xFF69F0AE),
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        } else {
            Text(
                text = "★ 5 VÁLCŮ / 9 LINIÍ — 3, 4 NEBO 5 SYMBOLŮ V ŘADĚ (5× 777 = ${currentBet * 600}) ★",
                color = Color(0xFF69F0AE),
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
        Text(
            text = "3× ZVONEK KDEKOLIV = 10 FREE SPINŮ   |   4× ZVONEK = 15   |   5× ZVONEK = 20 FREE SPINŮ!",
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
            MiniPayItem("★JOKER", 200 * currentBet, Color(0xFFFFEA00), Modifier.weight(1.15f))
            MiniPayItem("777", 100 * currentBet, Color(0xFFFF5252), Modifier.weight(0.95f))
            MiniPayItem("BAR", 50 * currentBet, Color(0xFFFFD54F), Modifier.weight(0.95f))
            MiniPayItem("ZVON", 30 * currentBet, Color(0xFF81C784), Modifier.weight(1f))
            MiniPayItem("MELOUN", 20 * currentBet, Color(0xFF4FC3F7), Modifier.weight(1.1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MiniPayItem("HROZNY", 12 * currentBet, Color(0xFFCE93D8), Modifier.weight(1.15f))
            MiniPayItem("ŠVESTKA", 8 * currentBet, Color(0xFFB39DDB), Modifier.weight(1.05f))
            MiniPayItem("POMER.", 6 * currentBet, Color(0xFFFFB74D), Modifier.weight(1f))
            MiniPayItem("CITRÓN", 4 * currentBet, Color(0xFFFFF176), Modifier.weight(1f))
            MiniPayItem("TŘEŠNĚ", 4 * currentBet, Color(0xFFFF8A80), Modifier.weight(1.05f))
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
        // Horni radek: Prehled aktualniho nasobeni + historie tazenych barev
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
                    text = "HISTORIE:",
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

        // Dolni radek: Tlacitka ČERVENÁ, ČERNÁ, PŮLIT 1/2, (SUPER), VZÍT
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ČERVENÁ 2x
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

            // ČERNÁ 2x
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

            // PŮLIT 1/2 (polovina do kreditu, polovina zustava v risku)
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

            // Prevod do SUPERMETRU v rezimu 4 valcu
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

            // VZÍT VÝHRU
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
                    "• 3 válce, 5 výherních linií (3 vodorovné + 2 úhlopříčky).\n• Symbol JOKER ★ nahrazuje jakýkoliv jiný symbol!\n• 2× TŘEŠNĚ zleva vyplácí 2× sázku (${currentBet * 2})."
                MachineMode.SUPER_4V ->
                    "• 4 válce, 5 linií. Vyhrávají 3 stejné zleva i zprava a 4 stejné vyplácí 2,5× více!\n• Na každé linii se vždy vyplácí nejvyšší dosažená kombinace.\n• Tlačítkem ▲ SUPER převedeš výhru do Supermetru pro HORNÍ DRUHÉ KOLO!"
                MachineMode.CRISSCROSS_27 ->
                    "• 3 válce, 27 křížových cest (každý řádek 1., 2. a 3. válce).\n• 1× JOKER v kombinaci násobí výhru 2×, 2× JOKER násobí výhru 4×!\n• 3× JOKER kdekoliv na válcích spouští MYSTERY BONUS (20× až 200× sázka)!"
                MachineMode.VEGAS_5V ->
                    "• 5 válců, 9 výherních linií. Vyhrávají 3, 4 nebo 5 stejných symbolů zleva.\n• 3, 4 nebo 5 ZVONKŮ kdekoliv na obrazovce spouští 10, 15 nebo 20 FREE SPINŮ zdarma, ve kterých se každá výhra násobí 3×!"
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
                val win3 = sym.multiplier3x * currentBet
                val win4 = (sym.multiplier3x * currentBet * 5) / 2
                val win5 = sym.multiplier3x * currentBet * 6
                val crissBase = maxOf(currentBet, (currentBet * sym.multiplier3x) / 2)

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
                        text = when (machineMode) {
                            MachineMode.CLASSIC_3V -> "3× = $win3"
                            MachineMode.SUPER_4V -> "3× = $win3  |  4× = $win4"
                            MachineMode.CRISSCROSS_27 ->
                                if (sym == SlotSymbol.JOKER) "3× = $win3"
                                else "3× = $crissBase (★=${crissBase * 2})"
                            MachineMode.VEGAS_5V -> "3×=$win3 | 4×=$win4 | 5×=$win5"
                        },
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

        // Prepinaci tlacitka pro vsechny 4 automaty
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
