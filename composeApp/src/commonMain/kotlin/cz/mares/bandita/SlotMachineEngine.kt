package cz.mares.bandita

import kotlin.random.Random

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

/**
 * 5 vyhernich linii na mrizce 3 valce x 3 rady (row: 0 = horni, 1 = stredni, 2 = dolni)
 */
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
    val isTwoCherryBonus: Boolean = false
)

object SlotMachineRules {
    val BET_OPTIONS = listOf(1, 2, 5, 10, 20, 50)

    val PAYLINES = listOf(
        PaylineDefinition(1, "STŘEDNÍ LINIE", listOf(1, 1, 1)),
        PaylineDefinition(2, "HORNÍ LINIE", listOf(0, 0, 0)),
        PaylineDefinition(3, "DOLNÍ LINIE", listOf(2, 2, 2)),
        PaylineDefinition(4, "ÚHLOPŘÍČKA ↘", listOf(0, 1, 2)),
        PaylineDefinition(5, "ÚHLOPŘÍČKA ↗", listOf(2, 1, 0))
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

    fun evaluateGrid(reels: List<List<SlotSymbol>>, bet: Int): List<WinningLineResult> {
        val results = mutableListOf<WinningLineResult>()

        for (line in PAYLINES) {
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
                        rowsPerReel = line.rowsPerReel
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
                        rowsPerReel = line.rowsPerReel
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
                        isTwoCherryBonus = true
                    )
                )
            }
        }

        return results
    }

    fun suggestHoldReels(reels: List<List<SlotSymbol>>): List<Boolean> {
        val m0 = reels[0][1]
        val m1 = reels[1][1]
        val m2 = reels[2][1]

        if (m0 == m1 || m0 == SlotSymbol.JOKER || m1 == SlotSymbol.JOKER) {
            return listOf(true, true, false)
        }
        if (m1 == m2 || m2 == SlotSymbol.JOKER) {
            return listOf(false, true, true)
        }
        if (m0 == m2) {
            return listOf(true, false, true)
        }
        for (r in 0..2) {
            if (reels[r].any { it == SlotSymbol.JOKER || it == SlotSymbol.SEVEN }) {
                return List(3) { idx -> idx == r }
            }
        }
        return listOf(false, false, false)
    }
}
