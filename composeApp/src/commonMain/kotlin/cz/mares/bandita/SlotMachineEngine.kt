package cz.mares.bandita

import kotlin.random.Random

/**
 * Kolekce 4 hernich automatu z 90. let v jedne aplikaci:
 * 1) KLASIK 3V (3 valce, 5 linii, HOLD + RISK)
 * 2) SUPER 4V (4 valce, 5 linii zleva/zprava/4x + SUPERMETR a DRUHE KOLO)
 * 3) JOKER 27 (3 valce, 27 krizovych cest Criss-Cross, 2x PLATNO + 3x ZVONEK MYSTERY)
 * 4) VEGAS 5V (5 valcu, 9 linii, 3x/4x/5x v rade + FREE SPINY s 2x nasobicem)
 */
enum class MachineMode(
    val shortTabCz: String,
    val titleCz: String,
    val reelCount: Int,
    val allowHold: Boolean,
    val firebaseGameId: String
) {
    CLASSIC_3V("KLASIK 3V", "KLASIK 3 VÁLCE (5 LINIÍ)", 3, true, "bandita"),
    SUPER_4V("SUPER 4V", "SUPER 4V + DRUHÉ KOLO", 4, true, "bandita_super"),
    CRISSCROSS_27("JOKER 27", "JOKER 27 CRISS-CROSS", 3, false, "bandita_crisscross"),
    VEGAS_5V("VEGAS 5V", "VEGAS 5V + FREE SPINY", 5, false, "bandita_vegas5")
}

/**
 * Symboly klasickeho ceskeho 90s automatu "Ovoce / Jednoruky Bandita"
 */
enum class SlotSymbol(
    val titleCz: String,
    val isFruit: Boolean
) {
    CHERRY("TŘEŠNĚ", true),
    LEMON("CITRÓN", true),
    ORANGE("POMERANČ", true),
    PLUM("ŠVESTKA", true),
    GRAPES("HROZNY", true),
    MELON("MELOUN", true),
    BELL("ZVONEK", false),
    BAR("BAR", false),
    SEVEN("SEDMIČKA 7", false),
    JOKER("JOKER ★", false);
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
    val mysteryBonusWin: Int = 0,
    val isFullFruitDouble: Boolean = false,
    val highlightCells: List<Pair<Int, Int>> = emptyList()
)

/**
 * Segmenty horniho bonusoveho kola ("Druhe kolo" nad 4valcovym automatem)
 * Vcetne prohernich poli STOP (0), aby kolo melo realnou matematiku (~98 % RTP).
 */
data class BonusWheelSlice(
    val index: Int,
    val baseMultiplier: Int,
    val weight: Int,
    val isMystery: Boolean = false,
    val isJackpot: Boolean = false,
    val isLose: Boolean = false,
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

    // 8 sviticich poli na hornim kole stesti (s poctivymi vahami pro cenu kola 2x sazka)
    val BONUS_WHEEL_SLICES = listOf(
        BonusWheelSlice(0, baseMultiplier = 0, weight = 26, isLose = true, colorHex = 0xFF455A64),
        BonusWheelSlice(1, baseMultiplier = 2, weight = 22, colorHex = 0xFF00C853),
        BonusWheelSlice(2, baseMultiplier = 10, weight = 6, isMystery = true, colorHex = 0xFFAA00FF),
        BonusWheelSlice(3, baseMultiplier = 0, weight = 24, isLose = true, colorHex = 0xFF455A64),
        BonusWheelSlice(4, baseMultiplier = 6, weight = 8, colorHex = 0xFF00B0FF),
        BonusWheelSlice(5, baseMultiplier = 3, weight = 14, colorHex = 0xFFFF9100),
        BonusWheelSlice(6, baseMultiplier = 0, weight = 20, isLose = true, colorHex = 0xFF455A64),
        BonusWheelSlice(7, baseMultiplier = 25, weight = 2, isJackpot = true, colorHex = 0xFFFFD600)
    )

    fun pickWeightedWheelSliceIndex(): Int {
        val totalWeight = BONUS_WHEEL_SLICES.sumOf { it.weight }
        var roll = Random.nextInt(totalWeight)
        for (slice in BONUS_WHEEL_SLICES) {
            roll -= slice.weight
            if (roll < 0) return slice.index
        }
        return 0
    }

    fun rollMysteryWheelMultiplier(): Int {
        val options = listOf(4, 6, 8, 12, 16, 25)
        return options[Random.nextInt(options.size)]
    }

    /**
     * Sestavi deterministicky, ale pro kazdy valec zcela unikatni pas symbolu,
     * kde zadne dva stejne symboly nelezi ve vzdalenosti <= 2 (ani pres konec pasu dokola).
     */
    private fun buildDistinctReelStrip(counts: List<Pair<SlotSymbol, Int>>, seed: Int): List<SlotSymbol> {
        val totalLen = counts.sumOf { it.second }

        for (attempt in 0 until 80) {
            val rng = Random(seed + attempt * 97)
            val remaining = LinkedHashMap<SlotSymbol, Int>()
            for ((sym, cnt) in counts) remaining[sym] = cnt

            val result = ArrayList<SlotSymbol>(totalLen)
            var success = true

            for (pos in 0 until totalLen) {
                val forbidden = HashSet<SlotSymbol>()
                if (pos >= 1) forbidden.add(result[pos - 1])
                if (pos >= 2) forbidden.add(result[pos - 2])
                if (pos == totalLen - 2 && result.isNotEmpty()) {
                    forbidden.add(result[0])
                }
                if (pos == totalLen - 1 && result.size >= 2) {
                    forbidden.add(result[0])
                    forbidden.add(result[1])
                }

                val candidates = remaining.entries.filter { it.value > 0 && it.key !in forbidden }
                if (candidates.isEmpty()) {
                    success = false
                    break
                }

                // Uprednostnime symboly s nejvyssim zbyvajicim poctem + nahodny vyber, aby se nezablokoval konec
                val maxRem = candidates.maxOf { it.value }
                val pool = if (maxRem * 3 >= (totalLen - pos) + 1) {
                    candidates.filter { it.value == maxRem }
                } else {
                    candidates
                }

                val weightSum = pool.sumOf { it.value }
                var pickRoll = rng.nextInt(weightSum)
                var chosen = pool.first().key
                for (entry in pool) {
                    pickRoll -= entry.value
                    if (pickRoll < 0) {
                        chosen = entry.key
                        break
                    }
                }

                result.add(chosen)
                remaining[chosen] = (remaining[chosen] ?: 1) - 1
            }

            if (success && result.size == totalLen) {
                return result
            }
        }

        // Zaruceny fallback rozlozeni po 3 drahach (kdyby nahodny pokus nevyšel)
        val flat = ArrayList<SlotSymbol>(totalLen)
        for ((sym, cnt) in counts.sortedByDescending { it.second }) {
            repeat(cnt) { flat.add(sym) }
        }
        val fallback = Array(totalLen) { SlotSymbol.CHERRY }
        var idx = 0
        for (lane in 0..2) {
            var p = lane
            while (p < totalLen) {
                fallback[p] = flat[idx++]
                p += 3
            }
        }
        return fallback.toList()
    }

    // 40-polozkove unikatni pasy pro 5-liniove a 9-liniove automaty (KLASIK 3V, SUPER 4V, VEGAS 5V)
    private val COUNTS_LINE_MACHINES = listOf(
        SlotSymbol.CHERRY to 7,
        SlotSymbol.LEMON to 7,
        SlotSymbol.ORANGE to 7,
        SlotSymbol.PLUM to 6,
        SlotSymbol.GRAPES to 4,
        SlotSymbol.MELON to 3,
        SlotSymbol.BELL to 2,
        SlotSymbol.BAR to 2,
        SlotSymbol.SEVEN to 1,
        SlotSymbol.JOKER to 1
    )

    // 45-polozkove vyvazene pasy pro 27-cestny automat JOKER 27 CRISS-CROSS
    private val COUNTS_JOKER27 = listOf(
        SlotSymbol.CHERRY to 6,
        SlotSymbol.LEMON to 6,
        SlotSymbol.ORANGE to 6,
        SlotSymbol.PLUM to 6,
        SlotSymbol.GRAPES to 5,
        SlotSymbol.MELON to 5,
        SlotSymbol.BELL to 4,
        SlotSymbol.BAR to 4,
        SlotSymbol.SEVEN to 2,
        SlotSymbol.JOKER to 1
    )

    private val STRIPS_LINE_MACHINES: List<List<SlotSymbol>> by lazy {
        List(5) { reelIdx -> buildDistinctReelStrip(COUNTS_LINE_MACHINES, seed = 1993 + reelIdx * 503) }
    }

    private val STRIPS_JOKER27: List<List<SlotSymbol>> by lazy {
        List(3) { reelIdx -> buildDistinctReelStrip(COUNTS_JOKER27, seed = 2026 + reelIdx * 733) }
    }

    private fun stripsForMode(mode: MachineMode): List<List<SlotSymbol>> {
        return if (mode == MachineMode.CRISSCROSS_27) STRIPS_JOKER27 else STRIPS_LINE_MACHINES
    }

    fun getStripLength(mode: MachineMode): Int {
        return stripsForMode(mode).first().size
    }

    fun getSymbolOnStrip(mode: MachineMode, reelIndex: Int, position: Int): SlotSymbol {
        val strips = stripsForMode(mode)
        val strip = strips.getOrElse(reelIndex) { strips.first() }
        val safeIdx = ((position % strip.size) + strip.size) % strip.size
        return strip[safeIdx]
    }

    fun getVisibleReelSymbols(mode: MachineMode, reelIndex: Int, stopPosition: Int): List<SlotSymbol> {
        return listOf(
            getSymbolOnStrip(mode, reelIndex, stopPosition),
            getSymbolOnStrip(mode, reelIndex, stopPosition + 1),
            getSymbolOnStrip(mode, reelIndex, stopPosition + 2)
        )
    }

    fun randomStopPosition(mode: MachineMode): Int {
        return Random.nextInt(getStripLength(mode))
    }

    // --- VYPLATNI TABULKY JEDNOTLIVYCH AUTOMATU ---

    fun getClassic3VMultiplier(symbol: SlotSymbol): Int = when (symbol) {
        SlotSymbol.CHERRY -> 2
        SlotSymbol.LEMON -> 2
        SlotSymbol.ORANGE -> 4
        SlotSymbol.PLUM -> 6
        SlotSymbol.GRAPES -> 10
        SlotSymbol.MELON -> 16
        SlotSymbol.BELL -> 24
        SlotSymbol.BAR -> 40
        SlotSymbol.SEVEN -> 100
        SlotSymbol.JOKER -> 200
    }

    private fun getSuper4VMultiplier3x(symbol: SlotSymbol): Int = when (symbol) {
        SlotSymbol.CHERRY -> 2
        SlotSymbol.LEMON -> 2
        SlotSymbol.ORANGE -> 3
        SlotSymbol.PLUM -> 4
        SlotSymbol.GRAPES -> 6
        SlotSymbol.MELON -> 10
        SlotSymbol.BELL -> 16
        SlotSymbol.BAR -> 25
        SlotSymbol.SEVEN -> 60
        SlotSymbol.JOKER -> 120
    }

    private fun getSuper4VMultiplier4x(symbol: SlotSymbol): Int = when (symbol) {
        SlotSymbol.CHERRY -> 6
        SlotSymbol.LEMON -> 6
        SlotSymbol.ORANGE -> 10
        SlotSymbol.PLUM -> 14
        SlotSymbol.GRAPES -> 20
        SlotSymbol.MELON -> 35
        SlotSymbol.BELL -> 50
        SlotSymbol.BAR -> 80
        SlotSymbol.SEVEN -> 200
        SlotSymbol.JOKER -> 400
    }

    private fun getCrissCross27Multiplier(symbol: SlotSymbol): Int = when (symbol) {
        SlotSymbol.CHERRY -> 1
        SlotSymbol.LEMON -> 1
        SlotSymbol.ORANGE -> 1
        SlotSymbol.PLUM -> 1
        SlotSymbol.GRAPES -> 2
        SlotSymbol.MELON -> 2
        SlotSymbol.BELL -> 4
        SlotSymbol.BAR -> 6
        SlotSymbol.SEVEN -> 16
        SlotSymbol.JOKER -> 50
    }

    private fun getVegas5VMultiplier(symbol: SlotSymbol, matchLen: Int): Int {
        val m3: Int
        val m4: Int
        val m5: Int
        when (symbol) {
            SlotSymbol.CHERRY -> { m3 = 1; m4 = 3; m5 = 10 }
            SlotSymbol.LEMON -> { m3 = 1; m4 = 3; m5 = 10 }
            SlotSymbol.ORANGE -> { m3 = 1; m4 = 4; m5 = 12 }
            SlotSymbol.PLUM -> { m3 = 2; m4 = 5; m5 = 15 }
            SlotSymbol.GRAPES -> { m3 = 3; m4 = 8; m5 = 25 }
            SlotSymbol.MELON -> { m3 = 4; m4 = 12; m5 = 40 }
            SlotSymbol.BELL -> { m3 = 5; m4 = 15; m5 = 50 }
            SlotSymbol.BAR -> { m3 = 8; m4 = 25; m5 = 80 }
            SlotSymbol.SEVEN -> { m3 = 15; m4 = 50; m5 = 150 }
            SlotSymbol.JOKER -> { m3 = 30; m4 = 100; m5 = 300 }
        }
        return when (matchLen) {
            3 -> m3
            4 -> m4
            else -> m5
        }
    }

    fun getPaytableMultipliersText(mode: MachineMode, symbol: SlotSymbol, bet: Int): String {
        return when (mode) {
            MachineMode.CLASSIC_3V -> "3× = ${getClassic3VMultiplier(symbol) * bet}"
            MachineMode.SUPER_4V ->
                "3× = ${getSuper4VMultiplier3x(symbol) * bet}  |  4× = ${getSuper4VMultiplier4x(symbol) * bet}"
            MachineMode.CRISSCROSS_27 -> {
                val base = getCrissCross27Multiplier(symbol) * bet
                if (symbol.isFruit) "3× = $base (Plátno = ${base * 2})" else "3× = $base"
            }
            MachineMode.VEGAS_5V -> {
                val w3 = getVegas5VMultiplier(symbol, 3) * bet
                val w4 = getVegas5VMultiplier(symbol, 4) * bet
                val w5 = getVegas5VMultiplier(symbol, 5) * bet
                "3×=$w3 | 4×=$w4 | 5×=$w5"
            }
        }
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
    private fun evaluateGrid3Reels(reels: List<List<SlotSymbol>>, bet: Int): List<WinningLineResult> {
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
                        winAmount = bet * getClassic3VMultiplier(SlotSymbol.JOKER),
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
                        winAmount = bet * getClassic3VMultiplier(targetSymbol),
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1, 2)
                    )
                )
                continue
            }

            // Bonus 2x TRESNE zleva (vraceni 1x sazky)
            if (s0 == SlotSymbol.CHERRY && s1 == SlotSymbol.CHERRY) {
                results.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = SlotSymbol.CHERRY,
                        winAmount = bet,
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
     * Na kazde linii zkontroluje 4 v rade, 3 zleva a 3 zprava a vybere nejvyssi kombinaci.
     */
    private fun evaluateGrid4Reels(reels: List<List<SlotSymbol>>, bet: Int): List<WinningLineResult> {
        if (reels.size < 4) return emptyList()
        val results = mutableListOf<WinningLineResult>()

        for (line in PAYLINES_4V) {
            val s0 = reels[0][line.rowsPerReel[0]]
            val s1 = reels[1][line.rowsPerReel[1]]
            val s2 = reels[2][line.rowsPerReel[2]]
            val s3 = reels[3][line.rowsPerReel[3]]

            val candidates = mutableListOf<WinningLineResult>()

            // A: 4 stejne v rade (0, 1, 2, 3)
            val quad = listOf(s0, s1, s2, s3)
            val nonJokers4 = quad.filter { it != SlotSymbol.JOKER }
            val sym4 = nonJokers4.firstOrNull() ?: SlotSymbol.JOKER
            if (nonJokers4.all { it == sym4 }) {
                candidates.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = sym4,
                        winAmount = bet * getSuper4VMultiplier4x(sym4),
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1, 2, 3),
                        bonusNote = "4× V ŘADĚ"
                    )
                )
            }

            // B: 3 stejne ZLEVA (0, 1, 2)
            val leftTrio = listOf(s0, s1, s2)
            val nonJokersLeft = leftTrio.filter { it != SlotSymbol.JOKER }
            val symLeft = nonJokersLeft.firstOrNull() ?: SlotSymbol.JOKER
            if (nonJokersLeft.all { it == symLeft }) {
                candidates.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = symLeft,
                        winAmount = bet * getSuper4VMultiplier3x(symLeft),
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1, 2),
                        bonusNote = "3× ZLEVA"
                    )
                )
            }

            // C: 3 stejne ZPRAVA (1, 2, 3)
            val rightTrio = listOf(s1, s2, s3)
            val nonJokersRight = rightTrio.filter { it != SlotSymbol.JOKER }
            val symRight = nonJokersRight.firstOrNull() ?: SlotSymbol.JOKER
            if (nonJokersRight.all { it == symRight }) {
                candidates.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = symRight,
                        winAmount = bet * getSuper4VMultiplier3x(symRight),
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
     * 3) Vyhodnoceni JOKER 27 CRISS-CROSS (27 cest na 3 valcich):
     * - JOKER nahrazuje jakykoliv symbol na 27 cestach.
     * - Pokud vsech 9 poli na obrazovce tvori OVOCE (nebo ovoce + JOKER), vsechny vyhry se nasobi 2x (PLATNO 2x)!
     * - Pokud na kazdem ze 3 valcu padne ZVONEK (3x ZVONEK kdekoliv), spusti se navic MYSTERY VYHRA (8x az 50x sazka)!
     */
    private fun evaluateCrissCross27(reels: List<List<SlotSymbol>>, bet: Int): SpinEvaluationSummary {
        if (reels.size < 3) return SpinEvaluationSummary(emptyList(), 0)

        val allNineAreFruitOrJoker = (0..2).all { c ->
            (0..2).all { r ->
                val s = reels[c][r]
                s.isFruit || s == SlotSymbol.JOKER
            }
        }
        val platnoMultiplier = if (allNineAreFruitOrJoker) 2 else 1

        // Nejvyssi ne-Joker symbol na kazdem valci pro pripad 2x JOKER na lince
        val highestPerReel = List(3) { c ->
            reels[c].filter { it != SlotSymbol.JOKER }.maxByOrNull { getCrissCross27Multiplier(it) }
        }

        val results = mutableListOf<WinningLineResult>()
        for (line in PAYLINES_CRISSCROSS_27) {
            val s0 = reels[0][line.rowsPerReel[0]]
            val s1 = reels[1][line.rowsPerReel[1]]
            val s2 = reels[2][line.rowsPerReel[2]]
            val trio = listOf(s0, s1, s2)
            val jokerCount = trio.count { it == SlotSymbol.JOKER }

            if (jokerCount == 3) {
                results.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = SlotSymbol.JOKER,
                        winAmount = bet * getCrissCross27Multiplier(SlotSymbol.JOKER),
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
                if (jokerCount == 2) {
                    val onlyRealCol = trio.indexOfFirst { it != SlotSymbol.JOKER }
                    if (targetSymbol != highestPerReel[onlyRealCol]) continue
                }
                val win = bet * getCrissCross27Multiplier(targetSymbol) * platnoMultiplier
                results.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = targetSymbol,
                        winAmount = win,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1, 2),
                        bonusNote = if (platnoMultiplier > 1) "PLÁTNO ×2" else ""
                    )
                )
            }
        }

        // Scatter 3x ZVONEK -> MYSTERY VYHRA (8x az 50x sazka)
        val bellPositions = mutableListOf<Pair<Int, Int>>()
        for (c in 0..2) {
            for (r in 0..2) {
                if (reels[c][r] == SlotSymbol.BELL) {
                    bellPositions.add(c to r)
                }
            }
        }
        val mysteryWin = if (bellPositions.size >= 3) {
            val mysteryMults = listOf(8, 12, 16, 25, 35, 50)
            bet * mysteryMults[Random.nextInt(mysteryMults.size)]
        } else {
            0
        }

        val highlighted = mutableListOf<Pair<Int, Int>>()
        if (mysteryWin > 0) highlighted.addAll(bellPositions)
        if (allNineAreFruitOrJoker && results.isNotEmpty()) {
            for (c in 0..2) for (r in 0..2) highlighted.add(c to r)
        }

        val totalWin = results.sumOf { it.winAmount } + mysteryWin
        return SpinEvaluationSummary(
            winningLines = results,
            totalWin = totalWin,
            mysteryBonusWin = mysteryWin,
            isFullFruitDouble = allNineAreFruitOrJoker && results.isNotEmpty(),
            highlightCells = highlighted.distinct()
        )
    }

    /**
     * 4) Vyhodnoceni VEGAS 5V (5 valcu, 9 linii + Scatter ZVONEK pro 8/12/18 FREE SPINU s 2x nasobicem).
     */
    private fun evaluateVegas5Reels(
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
                    val mult = getVegas5VMultiplier(candidateSym, len)
                    val multipliedWin = bet * mult * freeSpinMultiplier
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

        // Scatter kontrola: 3, 4 nebo 5 ZVONKU kdekoliv na 5 valcich spousti 8, 12 nebo 18 FREE SPINU
        val bellPositions = mutableListOf<Pair<Int, Int>>()
        for (c in 0..4) {
            for (r in 0..2) {
                if (reels[c][r] == SlotSymbol.BELL) {
                    bellPositions.add(c to r)
                }
            }
        }

        val freeSpinsWon = when {
            bellPositions.size >= 5 -> 18
            bellPositions.size == 4 -> 12
            bellPositions.size == 3 -> 8
            else -> 0
        }

        val totalWin = results.sumOf { it.winAmount }
        return SpinEvaluationSummary(
            winningLines = results,
            totalWin = totalWin,
            awardedFreeSpins = freeSpinsWon,
            highlightCells = if (freeSpinsWon > 0) bellPositions else emptyList()
        )
    }

    /**
     * Prohleda VSECH 5 vyhernich linii (vodorovne i uhlopricky) v rezimech KLASIK 3V a SUPER 4V
     * a najde nejvyhodnejsi dvojici valcu (nebo valec s JOKEREM / 777), kterou stoji za to podrzet (HOLD).
     */
    fun suggestHoldReels(reels: List<List<SlotSymbol>>, mode: MachineMode): List<Boolean> {
        val reelCount = mode.reelCount
        val emptyMask = List(reelCount) { false }
        if (!mode.allowHold || reels.size < reelCount) return emptyMask

        var bestScore = 0
        var bestCols: List<Int> = emptyList()

        if (mode == MachineMode.CLASSIC_3V) {
            val pairs = listOf(0 to 1, 1 to 2, 0 to 2)
            for (line in PAYLINES_3V) {
                for ((c1, c2) in pairs) {
                    val sym1 = reels[c1][line.rowsPerReel[c1]]
                    val sym2 = reels[c2][line.rowsPerReel[c2]]
                    val pairScore = scoreHoldPair(sym1, sym2, isClassic = true)
                    if (pairScore > bestScore) {
                        bestScore = pairScore
                        bestCols = listOf(c1, c2)
                    }
                }
            }
        } else if (mode == MachineMode.SUPER_4V) {
            // Kontrola dvojic uvnitr 3-valcoveho okna zleva (0,1,2) a zprava (1,2,3) na vsech 5 liniich
            val validPairs = listOf(0 to 1, 1 to 2, 0 to 2, 2 to 3, 1 to 3)
            for (line in PAYLINES_4V) {
                for ((c1, c2) in validPairs) {
                    val sym1 = reels[c1][line.rowsPerReel[c1]]
                    val sym2 = reels[c2][line.rowsPerReel[c2]]
                    val pairScore = scoreHoldPair(sym1, sym2, isClassic = false)
                    if (pairScore > bestScore) {
                        bestScore = pairScore
                        bestCols = listOf(c1, c2)
                    }
                }
            }
        }

        if (bestCols.isNotEmpty()) {
            return List(reelCount) { idx -> idx in bestCols }
        }

        // Pokud neni dvojice na linii, ale na nekterem valci je JOKER nebo SEDMICKA, nabidneme ten valec
        val singleJokerCol = (0 until reelCount).firstOrNull { c -> reels[c].any { it == SlotSymbol.JOKER } }
        if (singleJokerCol != null) {
            return List(reelCount) { idx -> idx == singleJokerCol }
        }
        val singleSevenCol = (0 until reelCount).firstOrNull { c -> reels[c].any { it == SlotSymbol.SEVEN } }
        if (singleSevenCol != null) {
            return List(reelCount) { idx -> idx == singleSevenCol }
        }

        return emptyMask
    }

    private fun scoreHoldPair(sym1: SlotSymbol, sym2: SlotSymbol, isClassic: Boolean): Int {
        return when {
            sym1 == SlotSymbol.JOKER && sym2 == SlotSymbol.JOKER -> 500
            sym1 == sym2 -> {
                if (isClassic) getClassic3VMultiplier(sym1) * 2 else getSuper4VMultiplier3x(sym1) * 2
            }
            sym1 == SlotSymbol.JOKER -> {
                val m = if (isClassic) getClassic3VMultiplier(sym2) else getSuper4VMultiplier3x(sym2)
                if (m >= 10) m + 15 else 0 // Slaby symbol vedle Jokera nedoporucujeme drzet
            }
            sym2 == SlotSymbol.JOKER -> {
                val m = if (isClassic) getClassic3VMultiplier(sym1) else getSuper4VMultiplier3x(sym1)
                if (m >= 10) m + 15 else 0
            }
            else -> 0
        }
    }
}
