package cz.mares.bandita

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun SlotMachineReelsCanvas(
    reels: List<List<SlotSymbol>>,
    spinningReels: List<Boolean>,
    reelPhaseOffsets: List<Float>,
    heldReels: List<Boolean>,
    winningLines: List<WinningLineResult>,
    leverPullProgress: Float,
    blinkPhase: Boolean,
    onLeverPulled: () -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { tapOffset ->
                if (tapOffset.x > size.width * 0.88f) {
                    onLeverPulled()
                }
            }
        }
    ) {
        val w = size.width
        val h = size.height

        // Vyhradime 90 % sirky pro okno valcu a 10 % vpravo pro mechanickou paku
        val cabinetWidth = w * 0.90f
        val pad = min(cabinetWidth, h) * 0.035f
        val windowLeft = pad * 1.4f
        val windowTop = pad
        val windowWidth = cabinetWidth - pad * 2.8f
        val windowHeight = h - pad * 2f

        // 1) Kovovy a zlaty vnejsi ram okna valcu
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFFD54F), Color(0xFFB28900), Color(0xFFFFF176), Color(0xFF8D6E63))
            ),
            topLeft = Offset(windowLeft - 6f, windowTop - 6f),
            size = Size(windowWidth + 12f, windowHeight + 12f),
            cornerRadius = CornerRadius(18f, 18f)
        )

        drawRoundRect(
            color = Color(0xFF12081F),
            topLeft = Offset(windowLeft, windowTop),
            size = Size(windowWidth, windowHeight),
            cornerRadius = CornerRadius(14f, 14f)
        )

        val reelGap = windowWidth * 0.02f
        val reelWidth = (windowWidth - reelGap * 4f) / 3f
        val reelHeight = windowHeight - reelGap * 2f
        val cellHeight = reelHeight / 3f

        // 2) Vykresleni 3 valcu
        for (reelIdx in 0..2) {
            val rx = windowLeft + reelGap + reelIdx * (reelWidth + reelGap)
            val ry = windowTop + reelGap

            // Podklad valce (bilo-kremovy pergamen jako na klasickem automatu)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFBEB6A8),
                        Color(0xFFFFFDF7),
                        Color(0xFFFFFFFF),
                        Color(0xFFFFFDF7),
                        Color(0xFFADA597)
                    ),
                    startY = ry,
                    endY = ry + reelHeight
                ),
                topLeft = Offset(rx, ry),
                size = Size(reelWidth, reelHeight),
                cornerRadius = CornerRadius(10f, 10f)
            )

            // Orez pro symboly uvnitr valce
            clipRect(left = rx, top = ry, right = rx + reelWidth, bottom = ry + reelHeight) {
                val isSpinning = spinningReels.getOrElse(reelIdx) { false }
                val phase = reelPhaseOffsets.getOrElse(reelIdx) { 0f }

                if (isSpinning) {
                    val shiftY = (phase % 1f) * cellHeight
                    val allSyms = SlotSymbol.entries
                    val baseIndex = (phase.toInt() + reelIdx * 3) % allSyms.size
                    for (row in -1..3) {
                        val symIdx = (baseIndex + row + allSyms.size) % allSyms.size
                        val sym = allSyms[symIdx]
                        val cy = ry + row * cellHeight + shiftY + cellHeight * 0.5f
                        val cx = rx + reelWidth * 0.5f
                        drawSlotSymbol(
                            symbol = sym,
                            center = Offset(cx, cy),
                            boxSize = min(reelWidth, cellHeight) * 0.80f,
                            isHighlighted = false
                        )
                    }
                } else {
                    val colSyms = reels.getOrElse(reelIdx) {
                        listOf(SlotSymbol.SEVEN, SlotSymbol.SEVEN, SlotSymbol.SEVEN)
                    }
                    for (row in 0..2) {
                        val sym = colSyms.getOrElse(row) { SlotSymbol.CHERRY }
                        val cy = ry + row * cellHeight + cellHeight * 0.5f
                        val cx = rx + reelWidth * 0.5f

                        val isWinningCell = winningLines.any { win ->
                            win.rowsPerReel.getOrElse(reelIdx) { -1 } == row &&
                                (!win.isTwoCherryBonus || reelIdx < 2)
                        }

                        if (isWinningCell) {
                            drawRoundRect(
                                color = if (blinkPhase) Color(0x55FFD700) else Color(0x33FF5252),
                                topLeft = Offset(rx + 4f, ry + row * cellHeight + 4f),
                                size = Size(reelWidth - 8f, cellHeight - 8f),
                                cornerRadius = CornerRadius(8f, 8f)
                            )
                        }

                        drawSlotSymbol(
                            symbol = sym,
                            center = Offset(cx, cy),
                            boxSize = min(reelWidth, cellHeight) * 0.82f,
                            isHighlighted = isWinningCell && blinkPhase
                        )

                        // Jemna delici linka mezi policky na valci
                        if (row > 0) {
                            drawLine(
                                color = Color(0x335D4037),
                                start = Offset(rx + 6f, ry + row * cellHeight),
                                end = Offset(rx + reelWidth - 6f, ry + row * cellHeight),
                                strokeWidth = 1.5f
                            )
                        }
                    }
                }

                // 3D zakriveny stin valce nahore a dole (efekt plastoveho bubnu)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0x99000000), Color.Transparent),
                        startY = ry,
                        endY = ry + reelHeight * 0.18f
                    ),
                    topLeft = Offset(rx, ry),
                    size = Size(reelWidth, reelHeight * 0.18f)
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0x99000000)),
                        startY = ry + reelHeight * 0.82f,
                        endY = ry + reelHeight
                    ),
                    topLeft = Offset(rx, ry + reelHeight * 0.82f),
                    size = Size(reelWidth, reelHeight * 0.18f)
                )
            }

            // Ramecek kolem valce (zvyrazneny oranzove/zlate pokud je aktivni HOLD)
            val isHeld = heldReels.getOrElse(reelIdx) { false }
            drawRoundRect(
                color = if (isHeld) Color(0xFFFFAB00) else Color(0xFF4E342E),
                topLeft = Offset(rx, ry),
                size = Size(reelWidth, reelHeight),
                cornerRadius = CornerRadius(10f, 10f),
                style = Stroke(width = if (isHeld) 4.5f else 2f)
            )
        }

        // 3) Vodicí linky 5 vyhernich linii na okrajich + zvyrazneni vyher
        val rowCentersY = List(3) { r -> windowTop + reelGap + r * cellHeight + cellHeight * 0.5f }
        val reelCentersX = List(3) { c -> windowLeft + reelGap + c * (reelWidth + reelGap) + reelWidth * 0.5f }

        // Jemna vodici linka uprostred (hlavni linie 1)
        drawLine(
            color = Color(0x44FF1744),
            start = Offset(windowLeft, rowCentersY[1]),
            end = Offset(windowLeft + windowWidth, rowCentersY[1]),
            strokeWidth = 2f
        )

        // Znaky linii 1..5 na levem okraji
        val badgeRadius = min(pad * 0.65f, 14f)
        val lineBadgePositions = listOf(
            1 to Offset(windowLeft - badgeRadius * 0.5f, rowCentersY[1]),
            2 to Offset(windowLeft - badgeRadius * 0.5f, rowCentersY[0]),
            3 to Offset(windowLeft - badgeRadius * 0.5f, rowCentersY[2]),
            4 to Offset(windowLeft - badgeRadius * 0.5f, windowTop + badgeRadius),
            5 to Offset(windowLeft - badgeRadius * 0.5f, windowTop + windowHeight - badgeRadius)
        )
        for ((lineNo, pos) in lineBadgePositions) {
            val isLineWon = winningLines.any { it.lineId == lineNo }
            drawCircle(
                color = if (isLineWon && blinkPhase) Color(0xFFFFEA00) else Color(0xFFD50000),
                radius = badgeRadius,
                center = pos
            )
            drawCircle(
                color = Color.White,
                radius = badgeRadius,
                center = pos,
                style = Stroke(width = 1.5f)
            )
        }

        // Zvyraznene laserove paprsky pro vyhodnocene vyherni linie
        for (win in winningLines) {
            val lineColor = when (win.lineId) {
                1 -> Color(0xFFFF1744)
                2 -> Color(0xFF00E676)
                3 -> Color(0xFF00B0FF)
                4 -> Color(0xFFFFEA00)
                else -> Color(0xFFE040FB)
            }
            val p0 = Offset(reelCentersX[0], rowCentersY[win.rowsPerReel[0]])
            val p1 = Offset(reelCentersX[1], rowCentersY[win.rowsPerReel[1]])
            val p2 = Offset(reelCentersX[2], rowCentersY[win.rowsPerReel[2]])

            val alpha = if (blinkPhase) 1f else 0.45f
            drawLine(
                color = lineColor.copy(alpha = alpha * 0.45f),
                start = p0,
                end = p1,
                strokeWidth = 14f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = lineColor.copy(alpha = alpha),
                start = p0,
                end = p1,
                strokeWidth = 5.5f,
                cap = StrokeCap.Round
            )
            if (!win.isTwoCherryBonus) {
                drawLine(
                    color = lineColor.copy(alpha = alpha * 0.45f),
                    start = p1,
                    end = p2,
                    strokeWidth = 14f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = lineColor.copy(alpha = alpha),
                    start = p1,
                    end = p2,
                    strokeWidth = 5.5f,
                    cap = StrokeCap.Round
                )
            }
        }

        // 4) Mechanicka paka "Jednorukeho bandity" vpravo
        drawBanditaLever(
            areaLeft = cabinetWidth,
            areaWidth = w - cabinetWidth,
            areaHeight = h,
            pullProgress = leverPullProgress
        )
    }
}

private fun DrawScope.drawBanditaLever(
    areaLeft: Float,
    areaWidth: Float,
    areaHeight: Float,
    pullProgress: Float
) {
    val pivotX = areaLeft + areaWidth * 0.35f
    val pivotY = areaHeight * 0.62f

    // Kovovy kloub u tela automatu
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF9E9E9E), Color(0xFF424242), Color(0xFF212121))
        ),
        topLeft = Offset(areaLeft - 2f, pivotY - 22f),
        size = Size(areaWidth * 0.48f, 44f),
        cornerRadius = CornerRadius(8f, 8f)
    )

    // Rameno paky (pri zatazeni se kulicka posune z horni uvrati dolu)
    val topY = areaHeight * 0.14f
    val bottomY = areaHeight * 0.84f
    val knobY = topY + (bottomY - topY) * pullProgress.coerceIn(0f, 1f)
    val knobX = areaLeft + areaWidth * 0.62f

    // Ocelova tyc
    drawLine(
        color = Color(0xFFBDBDBD),
        start = Offset(pivotX, pivotY),
        end = Offset(knobX, knobY),
        strokeWidth = 9f,
        cap = StrokeCap.Round
    )
    drawLine(
        color = Color(0xFFFFFFFF),
        start = Offset(pivotX - 1.5f, pivotY),
        end = Offset(knobX - 1.5f, knobY),
        strokeWidth = 2.5f,
        cap = StrokeCap.Round
    )

    // Klasicka cervena kulova hlavice paky s leskem
    val radius = (areaWidth * 0.34f).coerceIn(11f, 22f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFF8A80), Color(0xFFD50000), Color(0xFF7F0000)),
            center = Offset(knobX - radius * 0.3f, knobY - radius * 0.3f),
            radius = radius * 1.3f
        ),
        radius = radius,
        center = Offset(knobX, knobY)
    )
}

/**
 * Vektorove vykresleni 10 klasickych symbolu automatu
 */
fun DrawScope.drawSlotSymbol(
    symbol: SlotSymbol,
    center: Offset,
    boxSize: Float,
    isHighlighted: Boolean
) {
    val r = boxSize * 0.46f
    if (isHighlighted) {
        drawCircle(
            color = Color(0x44FFD700),
            radius = r * 1.15f,
            center = center
        )
    }

    when (symbol) {
        SlotSymbol.CHERRY -> drawCherrySymbol(center, r)
        SlotSymbol.LEMON -> drawLemonSymbol(center, r)
        SlotSymbol.ORANGE -> drawOrangeSymbol(center, r)
        SlotSymbol.PLUM -> drawPlumSymbol(center, r)
        SlotSymbol.GRAPES -> drawGrapesSymbol(center, r)
        SlotSymbol.MELON -> drawMelonSymbol(center, r)
        SlotSymbol.BELL -> drawBellSymbol(center, r)
        SlotSymbol.BAR -> drawBarSymbol(center, r)
        SlotSymbol.SEVEN -> drawSevenSymbol(center, r)
        SlotSymbol.JOKER -> drawJokerSymbol(center, r)
    }
}

private fun DrawScope.drawCherrySymbol(c: Offset, r: Float) {
    val topStem = Offset(c.x + r * 0.1f, c.y - r * 0.78f)
    val leftCherry = Offset(c.x - r * 0.36f, c.y + r * 0.32f)
    val rightCherry = Offset(c.x + r * 0.36f, c.y + r * 0.40f)

    // Stopky
    val stemPath = Path().apply {
        moveTo(leftCherry.x, leftCherry.y - r * 0.2f)
        quadraticBezierTo(c.x - r * 0.25f, c.y - r * 0.45f, topStem.x, topStem.y)
        moveTo(rightCherry.x, rightCherry.y - r * 0.2f)
        quadraticBezierTo(c.x + r * 0.30f, c.y - r * 0.35f, topStem.x, topStem.y)
    }
    drawPath(stemPath, color = Color(0xFF2E7D32), style = Stroke(width = r * 0.11f, cap = StrokeCap.Round))

    // Zeleny listek nahore
    val leafPath = Path().apply {
        moveTo(topStem.x, topStem.y)
        quadraticBezierTo(topStem.x + r * 0.45f, topStem.y - r * 0.18f, topStem.x + r * 0.55f, topStem.y + r * 0.15f)
        quadraticBezierTo(topStem.x + r * 0.2f, topStem.y + r * 0.22f, topStem.x, topStem.y)
        close()
    }
    drawPath(leafPath, color = Color(0xFF43A047))

    // Dve tresne s leskem
    val cr = r * 0.42f
    for (pos in listOf(leftCherry, rightCherry)) {
        drawCircle(color = Color(0xFF8E0000), radius = cr + 2f, center = pos)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFFF5252), Color(0xFFD50000), Color(0xFF8E0000)),
                center = Offset(pos.x - cr * 0.3f, pos.y - cr * 0.3f),
                radius = cr * 1.3f
            ),
            radius = cr,
            center = pos
        )
        drawCircle(
            color = Color(0xCCFFFFFF),
            radius = cr * 0.22f,
            center = Offset(pos.x - cr * 0.32f, pos.y - cr * 0.32f)
        )
    }
}

private fun DrawScope.drawLemonSymbol(c: Offset, r: Float) {
    val path = Path().apply {
        moveTo(c.x - r * 0.85f, c.y)
        quadraticBezierTo(c.x - r * 0.45f, c.y - r * 0.72f, c.x, c.y - r * 0.62f)
        quadraticBezierTo(c.x + r * 0.45f, c.y - r * 0.72f, c.x + r * 0.85f, c.y)
        quadraticBezierTo(c.x + r * 0.45f, c.y + r * 0.72f, c.x, c.y + r * 0.62f)
        quadraticBezierTo(c.x - r * 0.45f, c.y + r * 0.72f, c.x - r * 0.85f, c.y)
        close()
    }
    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFF59D), Color(0xFFFFEE58), Color(0xFFFBC02D)),
            center = Offset(c.x - r * 0.25f, c.y - r * 0.25f),
            radius = r * 1.2f
        )
    )
    drawPath(path = path, color = Color(0xFFF57F17), style = Stroke(width = r * 0.08f))

    // Odlesk
    drawOval(
        color = Color(0x99FFFFFF),
        topLeft = Offset(c.x - r * 0.45f, c.y - r * 0.35f),
        size = Size(r * 0.65f, r * 0.22f)
    )
}

private fun DrawScope.drawOrangeSymbol(c: Offset, r: Float) {
    val rad = r * 0.76f
    drawCircle(color = Color(0xFFE65100), radius = rad + 2f, center = Offset(c.x, c.y + r * 0.06f))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFCC80), Color(0xFFFF9800), Color(0xFFE65100)),
            center = Offset(c.x - rad * 0.3f, c.y - rad * 0.25f),
            radius = rad * 1.3f
        ),
        radius = rad,
        center = Offset(c.x, c.y + r * 0.06f)
    )
    // Zeleny listek
    val leaf = Path().apply {
        moveTo(c.x, c.y - rad * 0.88f)
        quadraticBezierTo(c.x + r * 0.45f, c.y - rad * 1.2f, c.x + r * 0.52f, c.y - rad * 0.75f)
        quadraticBezierTo(c.x + r * 0.2f, c.y - rad * 0.65f, c.x, c.y - rad * 0.88f)
        close()
    }
    drawPath(leaf, color = Color(0xFF388E3C))
    drawCircle(
        color = Color(0xAAFFFFFF),
        radius = rad * 0.20f,
        center = Offset(c.x - rad * 0.35f, c.y - rad * 0.25f)
    )
}

private fun DrawScope.drawPlumSymbol(c: Offset, r: Float) {
    val w = r * 1.38f
    val h = r * 1.56f
    val topLeft = Offset(c.x - w / 2f, c.y - h / 2f + r * 0.08f)
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF9575CD), Color(0xFF512DA8), Color(0xFF311B92)),
            center = Offset(c.x - r * 0.25f, c.y - r * 0.2f),
            radius = r
        ),
        topLeft = topLeft,
        size = Size(w, h)
    )
    drawOval(
        color = Color(0xFF1A0033),
        topLeft = topLeft,
        size = Size(w, h),
        style = Stroke(width = r * 0.07f)
    )
    // Typicka ryha svestky
    val crease = Path().apply {
        moveTo(c.x + r * 0.05f, topLeft.y + h * 0.12f)
        quadraticBezierTo(c.x + r * 0.28f, c.y, c.x + r * 0.05f, topLeft.y + h * 0.88f)
    }
    drawPath(crease, color = Color(0x881A0033), style = Stroke(width = r * 0.06f, cap = StrokeCap.Round))
    // Stopka
    drawLine(
        color = Color(0xFF5D4037),
        start = Offset(c.x, topLeft.y + 2f),
        end = Offset(c.x + r * 0.15f, topLeft.y - r * 0.18f),
        strokeWidth = r * 0.1f,
        cap = StrokeCap.Round
    )
    // Odlesk
    drawOval(
        color = Color(0x99FFFFFF),
        topLeft = Offset(c.x - r * 0.38f, c.y - r * 0.35f),
        size = Size(r * 0.24f, r * 0.48f)
    )
}

private fun DrawScope.drawGrapesSymbol(c: Offset, r: Float) {
    // Stopka a listek nahore
    drawLine(
        color = Color(0xFF4E342E),
        start = Offset(c.x, c.y - r * 0.55f),
        end = Offset(c.x, c.y - r * 0.85f),
        strokeWidth = r * 0.1f,
        cap = StrokeCap.Round
    )
    val rows = listOf(
        listOf(-0.42f, 0f, 0.42f) to -0.30f,
        listOf(-0.48f, -0.16f, 0.16f, 0.48f) to 0.02f,
        listOf(-0.32f, 0f, 0.32f) to 0.34f,
        listOf(-0.16f, 0.16f) to 0.62f,
        listOf(0f) to 0.84f
    )
    val berryR = r * 0.23f
    for ((xOffsets, yFactor) in rows) {
        for (xf in xOffsets) {
            val pos = Offset(c.x + xf * r, c.y + yFactor * r - r * 0.12f)
            drawCircle(color = Color(0xFF4A148C), radius = berryR + 1.5f, center = pos)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFCE93D8), Color(0xFF8E24AA), Color(0xFF4A148C)),
                    center = Offset(pos.x - berryR * 0.3f, pos.y - berryR * 0.3f),
                    radius = berryR * 1.3f
                ),
                radius = berryR,
                center = pos
            )
        }
    }
}

private fun DrawScope.drawMelonSymbol(c: Offset, r: Float) {
    val w = r * 1.68f
    val h = r * 1.26f
    val topLeft = Offset(c.x - w / 2f, c.y - h / 2f)

    // Tmave zeleny meloun s pruhy
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF66BB6A), Color(0xFF2E7D32), Color(0xFF1B5E20)),
            center = Offset(c.x - r * 0.25f, c.y - r * 0.2f),
            radius = r * 1.1f
        ),
        topLeft = topLeft,
        size = Size(w, h)
    )
    // Svetle zelene pruhy
    for (dy in listOf(-0.28f, 0f, 0.28f)) {
        drawLine(
            color = Color(0xFFA5D6A7),
            start = Offset(topLeft.x + w * 0.12f, c.y + dy * h),
            end = Offset(topLeft.x + w * 0.88f, c.y + dy * h),
            strokeWidth = r * 0.1f,
            cap = StrokeCap.Round
        )
    }
    // Vykrojena cervena slupka uprostred
    val sliceW = w * 0.72f
    val sliceH = h * 0.56f
    drawOval(
        color = Color(0xFFFF5252),
        topLeft = Offset(c.x - sliceW / 2f, c.y - sliceH / 2f),
        size = Size(sliceW, sliceH)
    )
    // Cerna seminka
    for (dx in listOf(-0.22f, 0f, 0.22f)) {
        drawCircle(
            color = Color(0xFF212121),
            radius = r * 0.055f,
            center = Offset(c.x + dx * r, c.y)
        )
    }
    drawOval(
        color = Color(0xFF0D3B10),
        topLeft = topLeft,
        size = Size(w, h),
        style = Stroke(width = r * 0.08f)
    )
}

private fun DrawScope.drawBellSymbol(c: Offset, r: Float) {
    // Srdce zvonu dole
    drawCircle(
        color = Color(0xFFFF8F00),
        radius = r * 0.22f,
        center = Offset(c.x, c.y + r * 0.68f)
    )

    val bellPath = Path().apply {
        moveTo(c.x, c.y - r * 0.78f)
        quadraticBezierTo(c.x + r * 0.55f, c.y - r * 0.75f, c.x + r * 0.58f, c.y + r * 0.15f)
        lineTo(c.x + r * 0.82f, c.y + r * 0.56f)
        lineTo(c.x - r * 0.82f, c.y + r * 0.56f)
        lineTo(c.x - r * 0.58f, c.y + r * 0.15f)
        quadraticBezierTo(c.x - r * 0.55f, c.y - r * 0.75f, c.x, c.y - r * 0.78f)
        close()
    }
    drawPath(
        path = bellPath,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFFFF59D), Color(0xFFFFD600), Color(0xFFFF8F00), Color(0xFFFFEA00)),
            start = Offset(c.x - r * 0.7f, c.y - r * 0.7f),
            end = Offset(c.x + r * 0.7f, c.y + r * 0.6f)
        )
    )
    drawPath(bellPath, color = Color(0xFF8D6E63), style = Stroke(width = r * 0.08f, join = StrokeJoin.Round))
}

private fun DrawScope.drawBarSymbol(c: Offset, r: Float) {
    val w = r * 1.76f
    val h = r * 1.05f
    val topLeft = Offset(c.x - w / 2f, c.y - h / 2f)

    // Zlaty ramecek
    drawRoundRect(
        color = Color(0xFFFFD700),
        topLeft = Offset(topLeft.x - 3f, topLeft.y - 3f),
        size = Size(w + 6f, h + 6f),
        cornerRadius = CornerRadius(10f, 10f)
    )
    // Cerny poklad BAR
    drawRoundRect(
        color = Color(0xFF1A1A1A),
        topLeft = topLeft,
        size = Size(w, h),
        cornerRadius = CornerRadius(8f, 8f)
    )
    // Tri vodorovne zlate pruhy symbolizujici 3x BAR cihlicku
    val barH = h * 0.20f
    for (idx in 0..2) {
        val by = topLeft.y + h * 0.14f + idx * (barH + h * 0.06f)
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(Color(0xFFFFD54F), Color(0xFFFFFFFF), Color(0xFFFFB300))
            ),
            topLeft = Offset(topLeft.x + w * 0.12f, by),
            size = Size(w * 0.76f, barH),
            cornerRadius = CornerRadius(4f, 4f)
        )
    }
}

private fun DrawScope.drawSevenSymbol(c: Offset, r: Float) {
    val sevenPath = Path().apply {
        moveTo(c.x - r * 0.72f, c.y - r * 0.75f)
        lineTo(c.x + r * 0.76f, c.y - r * 0.75f)
        lineTo(c.x + r * 0.76f, c.y - r * 0.35f)
        lineTo(c.x + r * 0.05f, c.y + r * 0.78f)
        lineTo(c.x - r * 0.42f, c.y + r * 0.78f)
        lineTo(c.x + r * 0.25f, c.y - r * 0.35f)
        lineTo(c.x - r * 0.72f, c.y - r * 0.35f)
        close()
    }
    // Zlaty 3D stin
    drawPath(
        path = sevenPath,
        color = Color(0xFFFFD700),
        style = Stroke(width = r * 0.18f, join = StrokeJoin.Round)
    )
    // Ohnive cervena vypln sedmicky
    drawPath(
        path = sevenPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFF5252), Color(0xFFD50000), Color(0xFF8E0000)),
            startY = c.y - r * 0.75f,
            endY = c.y + r * 0.78f
        )
    )
}

private fun DrawScope.drawJokerSymbol(c: Offset, r: Float) {
    // Zlato-fialova peticipa hvezda + kasparovska cepice s rolnickami
    val starPath = Path()
    val outerR = r * 0.88f
    val innerR = r * 0.40f
    for (i in 0 until 10) {
        val angle = -PI / 2.0 + i * (PI / 5.0)
        val rad = if (i % 2 == 0) outerR else innerR
        val px = c.x + (cos(angle) * rad).toFloat()
        val py = c.y + (sin(angle) * rad).toFloat()
        if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
    }
    starPath.close()

    drawPath(
        path = starPath,
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFF59D), Color(0xFFFFD600), Color(0xFFFF6D00)),
            center = c,
            radius = outerR
        )
    )
    drawPath(
        path = starPath,
        color = Color(0xFFD50000),
        style = Stroke(width = r * 0.10f, join = StrokeJoin.Round)
    )

    // Rubín uprostred zolika
    drawCircle(
        color = Color(0xFFD50000),
        radius = r * 0.28f,
        center = c
    )
    drawCircle(
        color = Color.White,
        radius = r * 0.10f,
        center = Offset(c.x - r * 0.08f, c.y - r * 0.08f)
    )
}
