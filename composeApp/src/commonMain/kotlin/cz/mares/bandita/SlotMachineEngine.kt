package cz.mares.bandita

import kotlin.random.Random

/**
 * Dva herni automaty v jedne aplikaci:
 * 1) KLASIK 3V (3 valce + HOLD + RISK)
 * 2) SUPER 4V (4 valce + SUPERMETR + DRUHE KOLO STESTI NAHORE)
 */
enum class MachineMode(
    val titleCz: String,
    val reelCount: Int,
    val firebaseGameId: String
) {
    CLASSIC_3V("KLASIK 3 VÁLCE", 3, "bandita"),
    SUPER_4V("SUPER 4V + KOLO", 4, "bandita_super")
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
    val activeColumns: List<Int> = listOf(0, 1, 2)
)

/**
 * Segmenty horniho bonusoveho kola ("Druhe kolo" nad 4valcovym automatem)
 */
data class BonusWheelSlice(
    val index: Int,
    val label: String,
    val baseMultiplier: Int,
    val isMystery: Boolean = false,
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

    // 8 sviticich poli na hornim kole stesti
    val BONUS_WHEEL_SLICES = listOf(
        BonusWheelSlice(0, "20", 2, false, 0xFF00C853),
        BonusWheelSlice(1, "40", 4, false, 0xFF00B0FF),
        BonusWheelSlice(2, "★MYSTERY★", 10, true, 0xFFAA00FF),
        BonusWheelSlice(3, "30", 3, false, 0xFFFF9100),
        BonusWheelSlice(4, "100", 10, false, 0xFFFF1744),
        BonusWheelSlice(5, "20", 2, false, 0xFF00C853),
        BonusWheelSlice(6, "60", 6, false, 0xFF00E5FF),
        BonusWheelSlice(7, "JOKER 200", 20, false, 0xFFFFD600)
    )

    private val weightedStrip: List<SlotSymbol> by lazy {
        val list = mutableListOf<SlotSymbol>()
        for (sym in SlotSymbol.entries) {
            repeat(sym.weight) { list.add(sym) }
        }
        list.shuffled(Random(1994))
    }

    fun randomSymbol(): SlotSymbol {
        return weightedStrip[Random.nextInt(weightedStrip.size)]
    }

    fun generateReelColumn(): List<SlotSymbol> {
        val top = randomSymbol()
        var mid = randomSymbol()
        if (top == SlotSymbol.JOKER && mid == SlotSymbol.JOKER) {
            mid = SlotSymbol.CHERRY
        }
        var bot = randomSymbol()
        if ((mid == SlotSymbol.JOKER || top == SlotSymbol.JOKER) && bot == SlotSymbol.JOKER) {
            bot = SlotSymbol.LEMON
        }
        return listOf(top, mid, bot)
    }

    /**
     * Vyhodnoceni 3-valcoveho automatu
     */
    fun evaluateGrid3Reels(reels: List<List<SlotSymbol>>, bet: Int): List<WinningLineResult> {
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
                        activeColumns = listOf(0, 1, 2)
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

            val firstTwoAreCherryOrJoker =
                (s0 == SlotSymbol.CHERRY || s0 == SlotSymbol.JOKER) &&
                (s1 == SlotSymbol.CHERRY || s1 == SlotSymbol.JOKER) &&
                (s0 == SlotSymbol.CHERRY || s1 == SlotSymbol.CHERRY)

            if (firstTwoAreCherryOrJoker) {
                results.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = SlotSymbol.CHERRY,
                        winAmount = bet * 2,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1)
                    )
                )
            }
        }

        return results
    }

    /**
     * Vyhodnoceni 4-valcoveho automatu (4 v rade = 2.5x nasobek, 3 v rade zleva nebo zprava = 1x nasobek)
     */
    fun evaluateGrid4Reels(reels: List<List<SlotSymbol>>, bet: Int): List<WinningLineResult> {
        if (reels.size < 4) return emptyList()
        val results = mutableListOf<WinningLineResult>()

        for (line in PAYLINES_4V) {
            val s0 = reels[0][line.rowsPerReel[0]]
            val s1 = reels[1][line.rowsPerReel[1]]
            val s2 = reels[2][line.rowsPerReel[2]]
            val s3 = reels[3][line.rowsPerReel[3]]
            val quad = listOf(s0, s1, s2, s3)

            // 1) Kontrola 4 shodnych symbolu na linii
            val nonJokers4 = quad.filter { it != SlotSymbol.JOKER }
            val sym4 = nonJokers4.firstOrNull() ?: SlotSymbol.JOKER
            if (nonJokers4.all { it == sym4 }) {
                results.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = sym4,
                        winAmount = (bet * sym4.multiplier3x * 5) / 2,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1, 2, 3)
                    )
                )
                continue
            }

            // 2) Kontrola 3 shodnych symbolu ZLEVA (valce 0, 1, 2)
            val leftTrio = listOf(s0, s1, s2)
            val nonJokersLeft = leftTrio.filter { it != SlotSymbol.JOKER }
            val symLeft = nonJokersLeft.firstOrNull() ?: SlotSymbol.JOKER
            if (nonJokersLeft.all { it == symLeft }) {
                results.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = symLeft,
                        winAmount = bet * symLeft.multiplier3x,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(0, 1, 2)
                    )
                )
                continue
            }

            // 3) Kontrola 3 shodnych symbolu ZPRAVA (valce 1, 2, 3)
            val rightTrio = listOf(s1, s2, s3)
            val nonJokersRight = rightTrio.filter { it != SlotSymbol.JOKER }
            val symRight = nonJokersRight.firstOrNull() ?: SlotSymbol.JOKER
            if (nonJokersRight.all { it == symRight }) {
                results.add(
                    WinningLineResult(
                        lineId = line.id,
                        symbol = symRight,
                        winAmount = bet * symRight.multiplier3x,
                        rowsPerReel = line.rowsPerReel,
                        activeColumns = listOf(1, 2, 3)
                    )
                )
            }
        }

        return results
    }

    fun suggestHoldReels(reels: List<List<SlotSymbol>>, reelCount: Int): List<Boolean> {
        val result = MutableList(reelCount) { false }
        val midSymbols = List(reelCount) { idx -> reels.getOrNull(idx)?.getOrNull(1) ?: SlotSymbol.CHERRY }

        for (i in 0 until reelCount) {
            for (j in i + 1 until reelCount) {
                if (midSymbols[i] == midSymbols[j] || midSymbols[i] == SlotSymbol.JOKER || midSymbols[j] == SlotSymbol.JOKER) {
                    result[i] = true
                    result[j] = true
                    return result
                }
            }
        }
        for (r in 0 until reelCount) {
            if (reels.getOrNull(r)?.any { it == SlotSymbol.JOKER || it == SlotSymbol.SEVEN } == true) {
                result[r] = true
                return result
            }
        }
        return result
    }
}
