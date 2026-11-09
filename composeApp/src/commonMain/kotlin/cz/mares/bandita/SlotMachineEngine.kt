package cz.mares.bandita

import kotlin.random.Random

/**
 * Kolekce 4 hernich automatu z 90. let v jedne aplikaci:
 * 1) KLASIK 3V (3 valce, 5 linii, HOLD + RISK)
 * 2) SUPER 4V (4 valce, 5 linii zleva/zprava/4x + SUPERMETR a DRUHE KOLO)
 * 3) JOKER 27 (3 valce, 27 krizovych cest Criss-Cross, Joker 2x/4x nasobic + Mystery)
 * 4) VEGAS 5V (5 valcu, 9 linii, 3x/4x/5x v rade + 10 FREE SPINU s 3x nasobicem)
 */
enum class MachineMode(
    val shortTabCz: String,
    val titleCz: String,
    val reelCount: Int,
    val firebaseGameId: String
) {
    CLASSIC_3V("KLASIK 3V", "KLASIK 3 VÁLCE (5 LINIÍ)", 3, "bandita"),
    SUPER_4V("SUPER 4V", "SUPER 4V + DRUHÉ KOLO", 4, "bandita_super"),
    CRISSCROSS_27("JOKER 27", "JOKER 27 CRISS-CROSS", 3, "bandita_crisscross"),
    VEGAS_5V("VEGAS 5V", "VEGAS 5V + FREE SPINY", 5, "bandita_vegas5")
}

/**
 * Symboly klasickeho ceskeho 90s automatu "Ovoce / Jednoruky Bandita"
 */
enum class SlotSymbol(
    val titleCz: String,
    val multiplier3x: Int,
    val weight: Int
) {
    CHERRY("TŘEŠNĚ", 4, 22),
    LEMON("CITRÓN", 4, 20),
    ORANGE("POMERANČ", 6, 17),
    PLUM("ŠVESTKA", 8, 14),
    GRAPES("HROZNY", 12, 11),
    MELON("MELOUN", 20, 8),
    BELL("ZVONEK", 30, 6),
    BAR("BAR", 50, 4),
    SEVEN("SEDMIČKA 7", 100, 3),
    JOKER("JOKER ★", 200, 2);
}

data class PaylineDefinition(
    val id: Int,
    val nameCz: String,
    val rowsPerReel: List<Int>
)

data class WinningLineResult(
    val lineId: Int,
    val symbol: SlotSymbol,
    val winAmount: Int,
    val rowsPerReel: List<Int>,
    val activeColumns: List<Int> = listOf(0, 1, 2),
    val bonusNote: String = ""
)

data class SpinEvaluationSummary(
    val winningLines: List<WinningLineResult>,
    val totalWin: Int,
    val awardedFreeSpins: Int = 0,
    val mysteryBonusWin: Int = 0
)

/**
 * Segmenty horniho bonusoveho kola ("Druhe kolo" nad 4valcovym automatem)
 */
data class BonusWheelSlice(
    val index: Int,
    val baseMultiplier: Int,
    val isMystery: Boolean = false,
    val isJackpot: Boolean = false,
    val colorHex: Long
)

object SlotMachineRules {
    val BET_OPTIONS = listOf(1, 2, 5, 10, 20, 50)

    // 5 vyhernich linii pro 3-valcovy automat
    val PAYLINES_3V = listOf(
        PaylineDefinition(1, "STŘEDNÍ LINIE", listOf(1, 1, 1)),
        PaylineDefinition(2, "HORNÍ LINIE", listOf(0, 0, 0)),
        PaylineDefinition(3, "DOLNÍ LINIE", listOf(2, 2, 2)),
        PaylineDefinition(4, "ÚHLOPŘÍČKA ↘", listOf(0, 1, 2)),
        PaylineDefinition(5, "ÚHLOPŘÍČKA ↗", listOf(2, 1, 0))
    )

    // 5 vyhernich linii pro 4-valcovy automat
    val PAYLINES_4V = listOf(
        PaylineDefinition(1, "STŘEDNÍ LINIE", listOf(1, 1, 1, 1)),
        PaylineDefinition(2, "HORNÍ LINIE", listOf(0, 0, 0, 0)),
        PaylineDefinition(3, "DOLNÍ LINIE", listOf(2, 2, 2, 2)),
        PaylineDefinition(4, "KŘÍŽOVÁ ↘", listOf(0, 1, 1, 2)),
        PaylineDefinition(5, "KŘÍŽOVÁ ↗", listOf(2, 1, 1, 0))
    )

    // 9 vyhernich linii pro 5-valcovy automat VEGAS 5V
    val PAYLINES_5V = listOf(
        PaylineDefinition(1, "STŘEDNÍ LINIE", listOf(1, 1, 1, 1, 1)),
        PaylineDefinition(2, "HORNÍ LINIE", listOf(0, 0, 0, 0, 0)),
        PaylineDefinition(3, "DOLNÍ LINIE", listOf(2, 2, 2, 2, 2)),
        PaylineDefinition(4, "KLÍN V ↘↗", listOf(0, 1, 2, 1, 0)),
        PaylineDefinition(5, "STŘECHA Λ ↗↘", listOf(2, 1, 0, 1, 2)),
        PaylineDefinition(6, "HORNÍ OBLOUK", listOf(0, 0, 1, 0, 0)),
        PaylineDefinition(7, "DOLNÍ OBLOUK", listOf(2, 2, 1, 2, 2)),
        PaylineDefinition(8, "VLNA DOLŮ", listOf(1, 2, 2, 2, 1)),
        PaylineDefinition(9, "VLNA NAHORU", listOf(1, 0, 0, 0, 1))
    )

    // 27 krizovych cest Criss-Cross pro 3 valce (kazdy radek s kazdym)
    val PAYLINES_CRISSCROSS_27: List<PaylineDefinition> by lazy {
        val list = mutableListOf<PaylineDefinition>()
        var idCounter = 1
        for (r0 in 0..2) {
            for (r1 in 0..2) {
                for (r2 in 0..2) {
                    list.add(
                        PaylineDefinition(
                            id = idCounter++,
                            nameCz = "CESTA ${r0 + 1}-${r1 + 1}-${r2 + 1}",
                            rowsPerReel = listOf(r0, r1, r2)
                        )
                    )
                }
            }
        }
        list
    }

    // 8 sviticich poli na hornim kole stesti (nasobky aktualni sazky)
    val BONUS_WHEEL_SLICES = listOf(
        BonusWheelSlice(0, 2, isMystery = false, isJackpot = false, colorHex = 0xFF00C853),
        BonusWheelSlice(1, 4, isMystery = false, isJackpot = false, colorHex = 0xFF00B0FF),
        BonusWheelSlice(2, 10, isMystery = true, isJackpot = false, colorHex = 0xFFAA00FF),
        BonusWheelSlice(3, 3, isMystery = false, isJackpot = false, colorHex = 0xFFFF9100),
        BonusWheelSlice(4, 10, isMystery = false, isJackpot = false, colorHex = 0xFFFF1744),
        BonusWheelSlice(5, 2, isMystery = false, isJackpot = false, colorHex = 0xFF00C853),
        BonusWheelSlice(6, 6, isMystery = false, isJackpot = false, colorHex = 0xFF00E5FF),
        BonusWheelSlice(7, 25, isMystery = false, isJackpot = true, colorHex = 0xFFFFD600)
    )

    /**
     * Pevne mechanicke pasy pro 5 valcu (kazdy pas ma 30 symbolu poskladanych tak,
     * aby zadne 3 sousedni symboly na valci nebyly stejne - pri zastaveni valce
     * jsou tedy v okne vzdy 3 ruzne symboly a animace presne odpovida vysledku).
     */
    private val BASE_STRIP = listOf(
        SlotSymbol.CHERRY, SlotSymbol.LEMON, SlotSymbol.ORANGE,
        SlotSymbol.PLUM, SlotSymbol.CHERRY, SlotSymbol.GRAPES,
        SlotSymbol.LEMON, SlotSymbol.MELON, SlotSymbol.ORANGE,
        SlotSymbol.CHERRY, SlotSymbol.BELL, SlotSymbol.PLUM,
        SlotSymbol.LEMON, SlotSymbol.BAR, SlotSymbol.ORANGE,
        SlotSymbol.GRAPES, SlotSymbol.CHERRY, SlotSymbol.SEVEN,
        SlotSymbol.LEMON, SlotSymbol.PLUM, SlotSymbol.MELON,
        SlotSymbol.ORANGE, SlotSymbol.CHERRY, SlotSymbol.BELL,
        SlotSymbol.GRAPES, SlotSymbol.LEMON, SlotSymbol.JOKER,
        SlotSymbol.PLUM, SlotSymbol.ORANGE, SlotSymbol.MELON
    )

    val REEL_STRIPS: List<List<SlotSymbol>> = listOf(
        BASE_STRIP,
        BASE_STRIP.drop(7) + BASE_STRIP.take(7),
        BASE_STRIP.drop(13) + BASE_STRIP.take(13),
        BASE_STRIP.drop(19) + BASE_STRIP.take(19),
        BASE_STRIP.drop(23) + BASE_STRIP.take(23)
    )

    val STRIP_LENGTH: Int = BASE_STRIP.size

    fun getSymbolOnStrip(reelIndex: Int, position: Int): SlotSymbol {
        val strip = REEL_STRIPS.getOrElse(reelIndex) { BASE_STRIP }
        val safeIdx = ((position % strip.size) + strip.size) % strip.size
        return strip[safeIdx]
    }

    /**
     * Vrati 3 viditelne symboly (horni, stredni, dolni) pro dany valec a index zastaveni na pasu.
     */
    fun getVisibleReelSymbols(reelIndex: Int, stopPosition: Int): List<SlotSymbol> {
        return listOf(
            getSymbolOnStrip(reelIndex, stopPosition),
            getSymbolOnStrip(reelIndex, stopPosition + 1),
            getSymbolOnStrip(reelIndex, stopPosition + 2)
        )
    }

    fun randomStopPosition(): Int {
        return Random.nextInt(STRIP_LENGTH)
    }

    fun generateReelColumn(reelIndex: Int = Random.nextInt(5)): List<SlotSymbol> {
        return getVisibleReelSymbols(reelIndex, randomStopPosition())
    }

    /**
     * Hlavni rozcestnik vyhodnoceni podle aktivniho automatu
     */
    fun evaluateSpin(
        mode: MachineMode,
        reels: List<List<SlotSymbol>>,
        bet: Int,
        freeSpinMultiplier: Int = 1
    ): SpinEvaluationSummary {
        return when (mode) {
            MachineMode.CLASSIC_3V -> {
                val lines = evaluateGrid3Reels(reels, bet)
                SpinEvaluationSummary(
                    winningLines = lines,
                    totalWin = lines.sumOf { it.winAmount }
                )
            }
            MachineMode.SUPER_4V -> {
                val lines = evaluateGrid4Reels(reels, bet)
                SpinEvaluationSummary(
                    winningLines = lines,
                    totalWin = lines.sumOf { it.winAmount }
                )
            }
            MachineMode.CRISSCROSS_27 -> {
                evaluateCrissCross27(reels, bet)
            }
            MachineMode.VEGAS_5V -> {
                evaluateVegas5Reels(reels, bet, freeSpinMultiplier)
            }
        }
    }

    /**
     * 1) Vyhodnoceni KLASIK 3V (3 valce, 5 linii)
     */
    fun evaluateGrid3Reels(reels: List<List<SlotSymbol>>, bet: Int): List<WinningLineResult> {
        if (reels.size < 3) return emptyList()
        val results = mutableListOf<WinningLineResult>()

        for (line in PAYLINES_3V) {
            val s0 = reels[0][line.rowsPerReel[0]]
            val s1 = reels[1][line.rowsPerReel[1]]
            val s2 = reels[2][line.rowsPerReel[2]]
            val trio = listOf(s0, s1, s2)

            if (trio.all { it == SlotSymbol.JOKER }) {
                results.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = SlotSymbol.JOKER,
                        winAmount = bet * SlotSymbol.JOKER.multiplier3x,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1, 2),
                        bonusNote = "3× JOKER"
                    )
                )
                continue
            }

            val nonJokers = trio.filter { it != SlotSymbol.JOKER }
            val targetSymbol = nonJokers.first()
            if (nonJokers.all { it == targetSymbol }) {
                results.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = targetSymbol,
                        winAmount = bet * targetSymbol.multiplier3x,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1, 2)
                    )
                )
                continue
            }

            // Klasicky bonus 2x TRESNE zleva (pouze dve skutecne tresne na 1. a 2. valci)
            if (s0 == SlotSymbol.CHERRY && s1 == SlotSymbol.CHERRY) {
                results.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = SlotSymbol.CHERRY,
                        winAmount = bet * 2,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1),
                        bonusNote = "2× TŘEŠNĚ"
                    )
                )
            }
        }

        return results
    }

    /**
     * 2) Vyhodnoceni SUPER 4V (4 valce, 5 linii).
     * Na kazde linii vyhodnoti 4 v rade, 3 zleva a 3 zprava a vybere NEJVYSSI vyhru.
     */
    fun evaluateGrid4Reels(reels: List<List<SlotSymbol>>, bet: Int): List<WinningLineResult> {
        if (reels.size < 4) return emptyList()
        val results = mutableListOf<WinningLineResult>()

        for (line in PAYLINES_4V) {
            val s0 = reels[0][line.rowsPerReel[0]]
            val s1 = reels[1][line.rowsPerReel[1]]
            val s2 = reels[2][line.rowsPerReel[2]]
            val s3 = reels[3][line.rowsPerReel[3]]

            val candidates = mutableListOf<WinningLineResult>()

            // A: Vsechny 4 valce (0, 1, 2, 3) -> 2.5x nasobek trojice
            val quad = listOf(s0, s1, s2, s3)
            val nonJokers4 = quad.filter { it != SlotSymbol.JOKER }
            val sym4 = nonJokers4.firstOrNull() ?: SlotSymbol.JOKER
            if (nonJokers4.all { it == sym4 }) {
                candidates.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = sym4,
                        winAmount = (bet * sym4.multiplier3x * 5) / 2,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1, 2, 3),
                        bonusNote = "4× V ŘADĚ"
                    )
                )
            }

            // B: 3 valce ZLEVA (0, 1, 2)
            val leftTrio = listOf(s0, s1, s2)
            val nonJokersLeft = leftTrio.filter { it != SlotSymbol.JOKER }
            val symLeft = nonJokersLeft.firstOrNull() ?: SlotSymbol.JOKER
            if (nonJokersLeft.all { it == symLeft }) {
                candidates.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = symLeft,
                        winAmount = bet * symLeft.multiplier3x,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1, 2),
                        bonusNote = "3× ZLEVA"
                    )
                )
            }

            // C: 3 valce ZPRAVA (1, 2, 3)
            val rightTrio = listOf(s1, s2, s3)
            val nonJokersRight = rightTrio.filter { it != SlotSymbol.JOKER }
            val symRight = nonJokersRight.firstOrNull() ?: SlotSymbol.JOKER
            if (nonJokersRight.all { it == symRight }) {
                candidates.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = symRight,
                        winAmount = bet * symRight.multiplier3x,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(1, 2, 3),
                        bonusNote = "3× ZPRAVA"
                    )
                )
            }

            val bestOnLine = candidates.maxByOrNull { it.winAmount }
            if (bestOnLine != null) {
                results.add(bestOnLine)
            }
        }

        return results
    }

    /**
     * 3) Vyhodnoceni JOKER 27 CRISS-CROSS (27 cest na 3 valcich).
     * OPRAVENO:
     * - Aby 2 Jokeri na dvou valcich nevyplatili vsechny 3 ruzne symboly na zbylemu valci najednou,
     *   pri kombinaci se 2 Jokery vyhrava na tretim valci pouze symbol s NEJVYSSI hodnotou.
     * - Kazdy JOKER v kombinaci nasobi vyhru 2x (1 Joker = 2x, 2 Jokeri = 4x).
     */
    fun evaluateCrissCross27(reels: List<List<SlotSymbol>>, bet: Int): SpinEvaluationSummary {
        if (reels.size < 3) return SpinEvaluationSummary(emptyList(), 0)
        val rawResults = mutableListOf<WinningLineResult>()

        // Nejvyssi symbol na kazdem valci pro pripad 2x JOKER + 1 symbol
        val highestNonJokerPerReel = List(3) { r ->
            reels[r].filter { it != SlotSymbol.JOKER }.maxByOrNull { it.multiplier3x }
        }

        for (line in PAYLINES_CRISSCROSS_27) {
            val s0 = reels[0][line.rowsPerReel[0]]
            val s1 = reels[1][line.rowsPerReel[1]]
            val s2 = reels[2][line.rowsPerReel[2]]
            val trio = listOf(s0, s1, s2)
            val jokerCount = trio.count { it == SlotSymbol.JOKER }

            if (jokerCount == 3) {
                rawResults.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = SlotSymbol.JOKER,
                        winAmount = bet * SlotSymbol.JOKER.multiplier3x,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1, 2),
                        bonusNote = "3× JOKER"
                    )
                )
                continue
            }

            val nonJokers = trio.filter { it != SlotSymbol.JOKER }
            val targetSymbol = nonJokers.first()
            if (nonJokers.all { it == targetSymbol }) {
                // Pokud jsou v ceste 2 Jokeri a jen 1 bezny symbol, zapocitame pouze nejvyssi symbol daneho valce
                if (jokerCount == 2) {
                    val nonJokerReelIdx = trio.indexOfFirst { it != SlotSymbol.JOKER }
                    if (targetSymbol != highestNonJokerPerReel[nonJokerReelIdx]) {
                        continue
                    }
                }

                val baseWin = maxOf(bet, (bet * targetSymbol.multiplier3x) / 2)
                val jokerMultiplier = when (jokerCount) {
                    1 -> 2
                    2 -> 4
                    else -> 1
                }
                val finalWin = baseWin * jokerMultiplier
                val note = if (jokerMultiplier > 1) "JOKER ×$jokerMultiplier" else ""
                rawResults.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = targetSymbol,
                        winAmount = finalWin,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1, 2),
                        bonusNote = note
                    )
                )
            }
        }

        // Mystery bonus: pokud na obrazovce padnou 3 symboly JOKER (a netvori uz primou linii 3x JOKER)
        val totalJokersOnScreen = reels.take(3).sumOf { col -> col.count { it == SlotSymbol.JOKER } }
        val hasDirectJokerLine = rawResults.any { it.symbol == SlotSymbol.JOKER }
        val mysteryWin = if (totalJokersOnScreen >= 3 && !hasDirectJokerLine) {
            val mysteryMults = listOf(20, 40, 60, 100, 150, 200)
            bet * mysteryMults[Random.nextInt(mysteryMults.size)]
        } else {
            0
        }

        val totalWin = rawResults.sumOf { it.winAmount } + mysteryWin
        return SpinEvaluationSummary(
            winningLines = rawResults,
            totalWin = totalWin,
            mysteryBonusWin = mysteryWin
        )
    }

    /**
     * 4) Vyhodnoceni VEGAS 5V (5 valcu, 9 linii + Scatter ZVONEK pro FREE SPINY s 3x nasobicem).
     * OPRAVENO: Na kazde linii vyhodnoti delky 3, 4 i 5 a vybere tu s NEJVYSSI vyhrou!
     */
    fun evaluateVegas5Reels(
        reels: List<List<SlotSymbol>>,
        bet: Int,
        freeSpinMultiplier: Int = 1
    ): SpinEvaluationSummary {
        if (reels.size < 5) return SpinEvaluationSummary(emptyList(), 0)
        val results = mutableListOf<WinningLineResult>()

        for (line in PAYLINES_5V) {
            val lineSyms = List(5) { c -> reels[c][line.rowsPerReel[c]] }
            val candidates = mutableListOf<WinningLineResult>()

            for (len in 3..5) {
                val sub = lineSyms.take(len)
                val nonJokers = sub.filter { it != SlotSymbol.JOKER }
                val candidateSym = nonJokers.firstOrNull() ?: SlotSymbol.JOKER
                if (nonJokers.all { it == candidateSym }) {
                    val lengthFactor = when (len) {
                        3 -> 1.0
                        4 -> 2.5
                        else -> 6.0
                    }
                    val rawWin = (bet * candidateSym.multiplier3x * lengthFactor).toInt()
                    val multipliedWin = rawWin * freeSpinMultiplier
                    candidates.add(
                        WinningLineResult(
                            lineId = line.id,
                            symbol = candidateSym,
                            winAmount = multipliedWin,
                            rowsPerReel = line.rowsPerReel,
                            activeColumns = (0 until len).toList(),
                            bonusNote = if (freeSpinMultiplier > 1) {
                                "${len}× (BONUS ×$freeSpinMultiplier)"
                            } else {
                                "${len}× V ŘADĚ"
                            }
                        )
                    )
                }
            }

            val bestOnLine = candidates.maxByOrNull { it.winAmount }
            if (bestOnLine != null) {
                results.add(bestOnLine)
            }
        }

        // Scatter kontrola: 3 nebo vice symbolu ZVONEK kdekoliv na 5 valcich spousti FREE SPINY
        val bellCount = reels.take(5).sumOf { col -> col.count { it == SlotSymbol.BELL } }
        val freeSpinsWon = when {
            bellCount >= 5 -> 20
            bellCount == 4 -> 15
            bellCount == 3 -> 10
            else -> 0
        }

        val totalWin = results.sumOf { it.winAmount }
        return SpinEvaluationSummary(
            winningLines = results,
            totalWin = totalWin,
            awardedFreeSpins = freeSpinsWon
        )
    }

    /**
     * Chytry navrh HOLD pro 3, 4 nebo 5 valcu:
     * Vraci masku doporucenych valcu (TIP), aniz by je nutil hraci natvrdo.
     */
    fun suggestHoldReels(reels: List<List<SlotSymbol>>, mode: MachineMode): List<Boolean> {
        val reelCount = mode.reelCount
        val result = MutableList(reelCount) { false }
        if (reels.size < reelCount) return result

        // 1) Pokud je na nekterem valci JOKER, doporucime podrzet valce s JOKEREM
        val jokerCols = (0 until reelCount).filter { r -> reels[r].any { it == SlotSymbol.JOKER } }
        if (jokerCols.isNotEmpty() && jokerCols.size < reelCount) {
            for (c in jokerCols.take(reelCount - 1)) {
                result[c] = true
            }
            return result
        }

        // 2) Sousedici dvojice shodnych symbolu na prostredni linii
        val midRow = List(reelCount) { r -> reels[r][1] }
        for (i in 0 until reelCount - 1) {
            if (midRow[i] == midRow[i + 1]) {
                result[i] = true
                result[i + 1] = true
                return result
            }
        }

        // U 3valcove hry take 1. a 3. valec na prostredni linii
        if (reelCount == 3 && midRow[0] == midRow[2]) {
            result[0] = true
            result[2] = true
            return result
        }

        // 3) Valec se SEDMICKOU na prostrednim radku
        for (r in 0 until reelCount) {
            if (reels[r][1] == SlotSymbol.SEVEN) {
                result[r] = true
                return result
            }
        }

        return result
    }
}
