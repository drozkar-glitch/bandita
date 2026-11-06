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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private enum class BanditaScreen {
    CABINET,
    PAYTABLE,
    LEADERBOARD
}

@Composable
fun App() {
    MaterialTheme {
        val scope = rememberCoroutineScope()

        var currentScreen by remember { mutableStateOf(BanditaScreen.CABINET) }

        // Stav automatu
        var credits by remember { mutableStateOf(PlatformServices.getSavedCredits().coerceAtLeast(10)) }
        var highScore by remember { mutableStateOf(maxOf(PlatformServices.getHighScore(), credits)) }
        var betIndex by remember { mutableStateOf(2) } // vychozi sazka 5
        val currentBet = SlotMachineRules.BET_OPTIONS[betIndex]

        var pendingWin by remember { mutableStateOf(0) }
        var riskStepCount by remember { mutableStateOf(0) }
        var statusBanner by remember { mutableStateOf("ZATÁHNI ZA PÁKU NEBO STISKNI START!") }

        // Mrizka 3 valce x 3 symboly
        val reels = remember {
            mutableStateListOf(
                listOf(SlotSymbol.CHERRY, SlotSymbol.SEVEN, SlotSymbol.LEMON),
                listOf(SlotSymbol.ORANGE, SlotSymbol.SEVEN, SlotSymbol.PLUM),
                listOf(SlotSymbol.MELON, SlotSymbol.SEVEN, SlotSymbol.BELL)
            )
        }
        val spinningReels = remember { mutableStateListOf(false, false, false) }
        val reelPhaseOffsets = remember { mutableStateListOf(0f, 0f, 0f) }

        // HOLD (podrzeni valcu)
        var canHoldNow by remember { mutableStateOf(false) }
        val heldReels = remember { mutableStateListOf(false, false, false) }
        var usedHoldThisTurn by remember { mutableStateOf(false) }

        var winningLines by remember { mutableStateOf<List<WinningLineResult>>(emptyList()) }
        var leverPullProgress by remember { mutableStateOf(0f) }

        // Blikani zarovek a RISK lampicky
        var blinkPhase by remember { mutableStateOf(false) }
        var riskLampIsRed by remember { mutableStateOf(true) }

        // Online rekordy (Firebase)
        var savedNick by remember { mutableStateOf(PlatformServices.getSavedNick()) }
        var nickInput by remember { mutableStateOf("") }
        var leaderboardList by remember { mutableStateOf<List<LeaderboardEntry>>(emptyList()) }
        var leaderboardError by remember { mutableStateOf<String?>(null) }
        var isLoadingBoard by remember { mutableStateOf(false) }

        // Animacni smycka pro blikani zarovek a RISK 2x
        LaunchedEffect(Unit) {
            while (true) {
                delay(280L)
                blinkPhase = !blinkPhase
                riskLampIsRed = !riskLampIsRed
            }
        }

        // Smycka pro plynule otaceni valcu
        val anySpinning = spinningReels.any { it }
        LaunchedEffect(anySpinning) {
            var tickCounter = 0
            while (spinningReels.any { it }) {
                for (i in 0..2) {
                    if (spinningReels[i]) {
                        reelPhaseOffsets[i] = reelPhaseOffsets[i] + 0.36f
                    }
                }
                tickCounter++
                if (tickCounter % 5 == 0) {
                    PlatformServices.playSound(BanditaSound.REEL_TICK)
                }
                delay(24L)
            }
        }

        fun syncCreditsAndRecord(newCredits: Int) {
            credits = newCredits
            PlatformServices.saveCredits(newCredits)
            if (newCredits > highScore) {
                highScore = newCredits
                PlatformServices.saveHighScore(newCredits)
                if (savedNick.isNotBlank()) {
                    PlatformServices.submitScore(
                        gameId = "bandita",
                        nick = savedNick,
                        score = newCredits
                    ) { _, _ -> }
                }
            }
        }

        fun collectPendingWin() {
            if (pendingWin <= 0) return
            PlatformServices.playSound(BanditaSound.WIN_SMALL)
            val updated = credits + pendingWin
            statusBanner = "PŘIPSÁNO +$pendingWin DO KREDITU!"
            pendingWin = 0
            riskStepCount = 0
            syncCreditsAndRecord(updated)
        }

        fun triggerGambleRisk(chooseRed: Boolean) {
            if (pendingWin <= 0 || anySpinning) return
            val drawnRed = Random.nextBoolean()
            riskLampIsRed = drawnRed
            if (drawnRed == chooseRed) {
                pendingWin *= 2
                riskStepCount++
                PlatformServices.playSound(
                    if (riskStepCount % 2 == 1) BanditaSound.RISK_STEP_A else BanditaSound.RISK_STEP_B
                )
                statusBanner = "RISK VYŠEL! VÝHRA NÁSOBENA NA $pendingWin!"
                if (riskStepCount >= 5) {
                    val updated = credits + pendingWin
                    statusBanner = "MAX RISK BONUS! PŘIPSÁNO +$pendingWin!"
                    pendingWin = 0
                    riskStepCount = 0
                    syncCreditsAndRecord(updated)
                }
            } else {
                PlatformServices.playSound(BanditaSound.RISK_LOSE)
                pendingWin = 0
                riskStepCount = 0
                winningLines = emptyList()
                statusBanner = if (drawnRed) "PADLA ČERVENÁ ♥ – RISK NEVYŠEL!" else "PADLA ČERNÁ ♠ – RISK NEVYŠEL!"
            }
        }

        fun startSpin() {
            if (anySpinning) return

            // Pokud hrac mel nevybranou vyhru a stiskl START, nejprve ji pripiseme
            if (pendingWin > 0) {
                val updated = credits + pendingWin
                pendingWin = 0
                riskStepCount = 0
                syncCreditsAndRecord(updated)
            }

            if (credits < currentBet) {
                statusBanner = "MÁLO KREDITU PRO SÁZKU $currentBet! SNIŽ SÁZKU."
                PlatformServices.playSound(BanditaSound.RISK_LOSE)
                return
            }

            syncCreditsAndRecord(credits - currentBet)
            winningLines = emptyList()
            statusBanner = "VÁLCE SE TOČÍ... HODNĚ ŠTĚSTÍ!"

            scope.launch {
                for (step in 1..5) {
                    leverPullProgress = step / 5f
                    delay(22L)
                }
                for (step in 5 downTo 0) {
                    leverPullProgress = step / 5f
                    delay(22L)
                }
            }

            scope.launch {
                val activeHold = canHoldNow && heldReels.any { it }
                for (i in 0..2) {
                    spinningReels[i] = !(activeHold && heldReels[i])
                }

                // Postupne zastavovani valcu 1 -> 2 -> 3
                for (i in 0..2) {
                    if (spinningReels[i]) {
                        delay(if (i == 0) 480L else 380L)
                        reels[i] = SlotMachineRules.generateReelColumn()
                        spinningReels[i] = false
                        PlatformServices.playSound(BanditaSound.REEL_STOP)
                    } else {
                        delay(120L)
                    }
                }

                // Vyhodnoceni 5 vyhernich linii
                val results = SlotMachineRules.evaluateGrid(reels.toList(), currentBet)
                winningLines = results
                val totalWin = results.sumOf { it.winAmount }

                if (totalWin > 0) {
                    pendingWin = totalWin
                    riskStepCount = 0
                    canHoldNow = false
                    heldReels[0] = false
                    heldReels[1] = false
                    heldReels[2] = false
                    usedHoldThisTurn = false

                    val hasJackpot = results.any {
                        it.symbol == SlotSymbol.JOKER || it.symbol == SlotSymbol.SEVEN
                    }
                    when {
                        hasJackpot -> PlatformServices.playSound(BanditaSound.JACKPOT_777)
                        totalWin >= currentBet * 12 -> PlatformServices.playSound(BanditaSound.WIN_BIG)
                        else -> PlatformServices.playSound(BanditaSound.WIN_SMALL)
                    }

                    val winNames = results.joinToString(" + ") { "${it.symbol.titleCz} (${it.winAmount})" }
                    statusBanner = "VÝHRA $totalWin! ($winNames)"
                } else {
                    if (!activeHold && !usedHoldThisTurn && Random.nextFloat() < 0.65f) {
                        canHoldNow = true
                        val suggested = SlotMachineRules.suggestHoldReels(reels.toList())
                        for (i in 0..2) heldReels[i] = suggested[i]
                        usedHoldThisTurn = true
                        statusBanner = "PODRŽ VÁLCE (HOLD 1–3) PRO DALŠÍ TAH!"
                    } else {
                        canHoldNow = false
                        heldReels[0] = false
                        heldReels[1] = false
                        heldReels[2] = false
                        usedHoldThisTurn = false
                        statusBanner = "ZKUS ŠTĚSTÍ ZNOVU! STISKNI START."
                    }
                }
            }
        }

        fun refreshLeaderboard() {
            isLoadingBoard = true
            leaderboardError = null
            PlatformServices.fetchTopScores(gameId = "bandita", includeZeroScores = false) { list, err ->
                isLoadingBoard = false
                leaderboardList = list
                leaderboardError = err
            }
        }

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
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (currentScreen) {
                    BanditaScreen.CABINET -> {
                        // 1) Horni svitici rampa automatu s blikajicimi zarovkami
                        TopCabinetHeader(
                            blinkPhase = blinkPhase,
                            onOpenPaytable = {
                                PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
                                currentScreen = BanditaScreen.PAYTABLE
                            },
                            onOpenLeaderboard = {
                                PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
                                currentScreen = BanditaScreen.LEADERBOARD
                                refreshLeaderboard()
                            }
                        )

                        Spacer(modifier = Modifier.height(5.dp))

                        // 2) Digitalni 7-segmentova LED palubka (KREDIT, SÁZKA, VÝHRA, REKORD)
                        DigitalLedDashboard(
                            credits = credits,
                            currentBet = currentBet,
                            pendingWin = pendingWin,
                            highScore = highScore
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Dvouřádkový přehledný rozpis výher pod displejem (bez zalamování)
                        MiniPaytableStrip(currentBet = currentBet)

                        Spacer(modifier = Modifier.height(5.dp))

                        // 3) Hlavni okno 3 valcu s pakou
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            SlotMachineReelsCanvas(
                                reels = reels.toList(),
                                spinningReels = spinningReels.toList(),
                                reelPhaseOffsets = reelPhaseOffsets.toList(),
                                heldReels = heldReels.toList(),
                                winningLines = winningLines,
                                leverPullProgress = leverPullProgress,
                                blinkPhase = blinkPhase,
                                onLeverPulled = { startSpin() },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 4) Informacni LED pas s aktualni hlaskou
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF12071F), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = statusBanner,
                                color = if (pendingWin > 0 && blinkPhase) Color(0xFFFFEA00) else Color(0xFFFFCC80),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(5.dp))

                        // 5) Trojice podsvicenych tlacitek HOLD 1, HOLD 2, HOLD 3 zarovnana pod valci
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 30.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (idx in 0..2) {
                                val isHeld = heldReels[idx]
                                val bgColor = when {
                                    isHeld -> Color(0xFFFFAB00)
                                    canHoldNow && blinkPhase -> Color(0xFF6D4C41)
                                    else -> Color(0xFF2D1E3E)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(35.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(bgColor)
                                        .border(
                                            width = if (isHeld) 2.dp else 1.dp,
                                            color = if (isHeld) Color(0xFFFFF59D) else Color(0xFF8D6E63),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable(enabled = canHoldNow && !anySpinning) {
                                            val currentlyHeldCount = heldReels.count { it }
                                            if (!heldReels[idx] && currentlyHeldCount >= 2) {
                                                val firstHeld = heldReels.indexOfFirst { it }
                                                if (firstHeld >= 0) heldReels[firstHeld] = false
                                            }
                                            heldReels[idx] = !heldReels[idx]
                                            PlatformServices.playSound(BanditaSound.HOLD_LOCK)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (isHeld) "HOLD ${idx + 1} ●" else "HOLD ${idx + 1}",
                                        color = if (isHeld) Color(0xFF1A092B) else Color(0xFFE0E0E0),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(5.dp))

                        // 6) Panel RISK 2x (ČERVENÁ / ČERNÁ / VZÍT) pokud je aktivni vyhra
                        if (pendingWin > 0) {
                            RiskGambleControlRow(
                                pendingWin = pendingWin,
                                riskLampIsRed = riskLampIsRed,
                                onPickRed = { triggerGambleRisk(true) },
                                onPickBlack = { triggerGambleRisk(false) },
                                onCollect = { collectPendingWin() }
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                        }

                        // 7) Hlavni arkadovy pult (SÁZKA, DOPLNIT KREDIT, START)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Prepinac SÁZKA (BET) s kompaktnim contentPadding, aby se cislo nikdy neurizlo
                            Button(
                                onClick = {
                                    if (!anySpinning) {
                                        betIndex = (betIndex + 1) % SlotMachineRules.BET_OPTIONS.size
                                        PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
                                    }
                                },
                                enabled = !anySpinning,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "SÁZKA (BET)",
                                        fontSize = 9.sp,
                                        color = Color(0xFFBBDEFB),
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "$currentBet KREDITŮ",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                }
                            }

                            // Nouzove vhozeni mince, pokud hrac klesl pod 5 kreditu a nema vyhru
                            if (credits < 5 && pendingWin == 0) {
                                Button(
                                    onClick = {
                                        PlatformServices.playSound(BanditaSound.WIN_SMALL)
                                        credits = 100
                                        PlatformServices.saveCredits(100)
                                        statusBanner = "VHOZENA MINCE: +100 KREDITŮ DO HRY!"
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier
                                        .weight(0.95f)
                                        .height(48.dp)
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

                            // Velke zeleno-zlate tlacitko START
                            Button(
                                onClick = { startSpin() },
                                enabled = !anySpinning,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (pendingWin > 0) Color(0xFFFF8F00) else Color(0xFF2E7D32)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .weight(1.35f)
                                    .height(48.dp)
                                    .border(2.dp, Color(0xFFFFD700), RoundedCornerShape(10.dp))
                            ) {
                                Text(
                                    text = if (pendingWin > 0) "VZÍT & START" else "★ START ★",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    BanditaScreen.PAYTABLE -> {
                        PaytableScreen(
                            currentBet = currentBet,
                            onBack = {
                                PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
                                currentScreen = BanditaScreen.CABINET
                            }
                        )
                    }

                    BanditaScreen.LEADERBOARD -> {
                        LeaderboardScreen(
                            savedNick = savedNick,
                            nickInput = nickInput,
                            onNickInputChange = { if (it.length <= 14) nickInput = it },
                            highScore = highScore,
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
                                        gameId = "bandita",
                                        nick = trimmed,
                                        score = highScore
                                    ) { ok, msg ->
                                        if (ok) {
                                            savedNick = trimmed
                                            nickInput = ""
                                            refreshLeaderboard()
                                        } else {
                                            isLoadingBoard = false
                                            leaderboardError = msg
                                        }
                                    }
                                }
                            },
                            onRefresh = { refreshLeaderboard() },
                            onBack = {
                                PlatformServices.playSound(BanditaSound.BUTTON_CLICK)
                                currentScreen = BanditaScreen.CABINET
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))
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
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        // Rada blikajicich poutovych zarovicek
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (b in 0 until 12) {
                val lit = (b % 2 == 0) == blinkPhase
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (lit) Color(0xFFFFEA00) else Color(0xFF5D4037))
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

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
                    .padding(horizontal = 7.dp, vertical = 4.dp)
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
                    .padding(horizontal = 7.dp, vertical = 4.dp)
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
    }
}

@Composable
private fun DigitalLedDashboard(
    credits: Int,
    currentBet: Int,
    pendingWin: Int,
    highScore: Int
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
            label = "REKORD",
            value = highScore.toString().padStart(5, '0'),
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
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 1. radek: 5 nejvyssich symbolu
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
        // 2. radek: 5 ovocnych symbolu
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
private fun RiskGambleControlRow(
    pendingWin: Int,
    riskLampIsRed: Boolean,
    onPickRed: () -> Unit,
    onPickBlack: () -> Unit,
    onCollect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF260E3A), RoundedCornerShape(10.dp))
            .border(1.5.dp, Color(0xFFFFEA00), RoundedCornerShape(10.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // RISK ČERVENÁ 2x
        Button(
            onClick = onPickRed,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (riskLampIsRed) Color(0xFFD50000) else Color(0xFF6A0000)
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
        ) {
            Text(
                text = "♥ RISK 2× (${pendingWin * 2})",
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1
            )
        }

        // RISK ČERNÁ 2x
        Button(
            onClick = onPickBlack,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!riskLampIsRed) Color(0xFF37474F) else Color(0xFF1C262B)
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
        ) {
            Text(
                text = "♠ RISK 2× (${pendingWin * 2})",
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1
            )
        }

        // VZÍT VÝHRU
        Button(
            onClick = onCollect,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8F00)),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            modifier = Modifier
                .weight(0.9f)
                .height(40.dp)
        ) {
            Text(
                text = "VZÍT ($pendingWin)",
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1A092B),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PaytableScreen(
    currentBet: Int,
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
            text = "VÝPLATNÍ TABULKA (PŘI SÁZCE $currentBet)",
            color = Color(0xFFFFD700),
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "• Automat vyhodnocuje 5 výherních linií (3 vodorovné + 2 úhlopříčky).\n• Symbol JOKER ★ nahrazuje na linii jakýkoliv jiný symbol!\n• 2× TŘEŠNĚ zleva na linii vyplácí bonus 2× sázku (${currentBet * 2}).",
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
                        text = "3× ${sym.titleCz}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${sym.multiplier3x}× sázka = ${sym.multiplier3x * currentBet}",
                        color = Color(0xFFFFEA00),
                        fontSize = 13.sp,
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

        Spacer(modifier = Modifier.height(8.dp))

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
