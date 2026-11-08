package cz.mares.bandita

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
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
    machineMode: MachineMode,
    reels: List<List<SlotSymbol>>,
    spinningReels: List<Boolean>,
    reelPhaseOffsets: List<Float>,
    heldReels: List<Boolean>,
    suggestedHoldReels: List<Boolean>,
    canHoldNow: Boolean,
    winningLines: List<WinningLineResult>,
    leverPullProgress: Float,
    blinkPhase: Boolean,
    onReelTapped: (Int) -> Unit,
    onLeverPulled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reelCount = machineMode.reelCount

    Canvas(
        modifier = modifier.pointerInput(reelCount, canHoldNow) {
            detectTapGestures { tapOffset ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val cabinetWidth = w * 0.90f
                if (tapOffset.x > w * 0.88f) {
                    onLeverPulled()
                } else if (canHoldNow) {
                    val pad = min(cabinetWidth, h) * 0.032f
                    val windowLeft = pad * 1.4f
                    val windowWidth = cabinetWidth - pad * 2.8f
                    val reelGap = windowWidth * 0.016f
                    val reelWidth = (windowWidth - reelGap * (reelCount + 1)) / reelCount.toFloat()
                    for (r in 0 until reelCount) {
                        val rx = windowLeft + reelGap + r * (reelWidth + reelGap)
                        if (tapOffset.x in rx..(rx + reelWidth)) {
                            onReelTapped(r)
                            break
                        }
                    }
                }
            }
        }
    ) {
        val w = size.width
        val h = size.height

        // 90 % sirky pro okno valcu, 10 % vpravo pro mechanickou paku
        val cabinetWidth = w * 0.90f
        val pad = min(cabinetWidth, h) * 0.032f
        val windowLeft = pad * 1.4f
        val windowTop = pad
        val windowWidth = cabinetWidth - pad * 2.8f
        val windowHeight = h - pad * 2f

        // 1) Kovovy a zlaty vnejsi ram okna valcu
        val frameColors = when (machineMode) {
            MachineMode.CLASSIC_3V -> listOf(Color(0xFFFFD54F), Color(0xFFB28900), Color(0xFFFFF176), Color(0xFF8D6E63))
            MachineMode.SUPER_4V -> listOf(Color(0xFF80D8FF), Color(0xFF0277BD), Color(0xFFE1F5FE), Color(0xFF01579B))
            MachineMode.CRISSCROSS_27 -> listOf(Color(0xFFFF80AB), Color(0xFFC51162), Color(0xFFFFD740), Color(0xFF880E4F))
            MachineMode.VEGAS_5V -> listOf(Color(0xFF69F0AE), Color(0xFF00C853), Color(0xFFFFD700), Color(0xFF1B5E20))
        }

        drawRoundRect(
            brush = Brush.linearGradient(colors = frameColors),
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

        val reelGap = windowWidth * 0.016f
        val reelWidth = (windowWidth - reelGap * (reelCount + 1)) / reelCount.toFloat()
        val reelHeight = windowHeight - reelGap * 2f
        val cellHeight = reelHeight / 3f

        // 2) Vykresleni 3, 4 nebo 5 valcu podle zvoleneho automatu
        for (reelIdx in 0 until reelCount) {
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
                        listOf(SlotSymbol.SEVEN, SlotSymbol.BAR, SlotSymbol.CHERRY)
                    }
                    for (row in 0..2) {
                        val sym = colSyms.getOrElse(row) { SlotSymbol.CHERRY }
                        val cy = ry + row * cellHeight + cellHeight * 0.5f
                        val cx = rx + reelWidth * 0.5f

                        val isWinningCell = winningLines.any { win ->
                            reelIdx in win.activeColumns &&
                                win.rowsPerReel.getOrElse(reelIdx) { -1 } == row
                        }

                        if (isWinningCell) {
                            drawRoundRect(
                                color = if (blinkPhase) Color(0x55FFD700) else Color(0x33FF5252),
                                topLeft = Offset(rx + 3f, ry + row * cellHeight + 3f),
                                size = Size(reelWidth - 6f, cellHeight - 6f),
                                cornerRadius = CornerRadius(8f, 8f)
                            )
                        }

                        drawSlotSymbol(
                            symbol = sym,
                            center = Offset(cx, cy),
                            boxSize = min(reelWidth, cellHeight) * 0.82f,
                            isHighlighted = isWinningCell && blinkPhase
                        )

                        if (row > 0) {
                            drawLine(
                                color = Color(0x335D4037),
                                start = Offset(rx + 5f, ry + row * cellHeight),
                                end = Offset(rx + reelWidth - 5f, ry + row * cellHeight),
                                strokeWidth = 1.5f
                            )
                        }
                    }
                }

                // 3D zakriveny stin valce nahore a dole
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

            // Ramecek kolem valce (zlate pro aktivni HOLD, blikajici zelene pro doporuceny TIP)
            val isHeld = heldReels.getOrElse(reelIdx) { false }
            val isSuggested = canHoldNow && suggestedHoldReels.getOrElse(reelIdx) { false }
            val borderColor = when {
                isHeld -> Color(0xFFFFAB00)
                isSuggested && blinkPhase -> Color(0xFF00E676)
                else -> Color(0xFF4E342E)
            }
            val borderWidth = if (isHeld || (isSuggested && blinkPhase)) 4.5f else 2f

            drawRoundRect(
                color = borderColor,
                topLeft = Offset(rx, ry),
                size = Size(reelWidth, reelHeight),
                cornerRadius = CornerRadius(10f, 10f),
                style = Stroke(width = borderWidth)
            )
        }

        // 3) Vodici linky vyhernich linii a laserove paprsky vyher
        val rowCentersY = List(3) { r -> windowTop + reelGap + r * cellHeight + cellHeight * 0.5f }
        val reelCentersX = List(reelCount) { c -> windowLeft + reelGap + c * (reelWidth + reelGap) + reelWidth * 0.5f }

        // Jemna vodici linka uprostred (hlavni linie 1)
        drawLine(
            color = Color(0x44FF1744),
            start = Offset(windowLeft, rowCentersY[1]),
            end = Offset(windowLeft + windowWidth, rowCentersY[1]),
            strokeWidth = 2f
        )

        // Znaky linii na levem okraji
        val badgeRadius = min(pad * 0.65f, 12f)
        val lineBadgePositions = listOf(
            1 to Offset(windowLeft - badgeRadius * 0.5f, rowCentersY[1]),
            2 to Offset(windowLeft - badgeRadius * 0.5f, rowCentersY[0]),
            3 to Offset(windowLeft - badgeRadius * 0.5f, rowCentersY[2]),
            4 to Offset(windowLeft - badgeRadius * 0.5f, windowTop + badgeRadius),
            5 to Offset(windowLeft - badgeRadius * 0.5f, windowTop + windowHeight - badgeRadius)
        )
        for ((lineNo, pos) in lineBadgePositions) {
            val isLineWon = winningLines.any { it.lineId == lineNo }
            val badgeBaseColor = when (machineMode) {
                MachineMode.CRISSCROSS_27 -> Color(0xFFAA00FF)
                MachineMode.VEGAS_5V -> Color(0xFF00C853)
                else -> Color(0xFFD50000)
            }
            drawCircle(
                color = if (isLineWon && blinkPhase) Color(0xFFFFEA00) else badgeBaseColor,
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
        val linePalette = listOf(
            Color(0xFFFF1744),
            Color(0xFF00E676),
            Color(0xFF00B0FF),
            Color(0xFFFFEA00),
            Color(0xFFE040FB),
            Color(0xFFFF9100),
            Color(0xFF18FFFF),
            Color(0xFFFF4081),
            Color(0xFF76FF03)
        )

        for (win in winningLines) {
            val lineColor = linePalette[(win.lineId - 1).coerceAtLeast(0) % linePalette.size]
            val alpha = if (blinkPhase) 1f else 0.45f
            val cols = win.activeColumns.sorted()
            for (idx in 0 until cols.size - 1) {
                val cStart = cols[idx]
                val cEnd = cols[idx + 1]
                if (cStart in reelCentersX.indices && cEnd in reelCentersX.indices) {
                    val rStart = win.rowsPerReel.getOrElse(cStart) { 1 }.coerceIn(0, 2)
                    val rEnd = win.rowsPerReel.getOrElse(cEnd) { 1 }.coerceIn(0, 2)
                    val pStart = Offset(reelCentersX[cStart], rowCentersY[rStart])
                    val pEnd = Offset(reelCentersX[cEnd], rowCentersY[rEnd])
                    drawLine(
                        color = lineColor.copy(alpha = alpha * 0.45f),
                        start = pStart,
                        end = pEnd,
                        strokeWidth = 12f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = lineColor.copy(alpha = alpha),
                        start = pStart,
                        end = pEnd,
                        strokeWidth = 5f,
                        cap = StrokeCap.Round
                    )
                }
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

    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF9E9E9E), Color(0xFF424242), Color(0xFF212121))
        ),
        topLeft = Offset(areaLeft - 2f, pivotY - 22f),
        size = Size(areaWidth * 0.48f, 44f),
        cornerRadius = CornerRadius(8f, 8f)
    )

    val topY = areaHeight * 0.14f
    val bottomY = areaHeight * 0.84f
    val knobY = topY + (bottomY - topY) * pullProgress.coerceIn(0f, 1f)
    val knobX = areaLeft + areaWidth * 0.62f

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

    val stemPath = Path().apply {
        moveTo(leftCherry.x, leftCherry.y - r * 0.2f)
        quadraticBezierTo(c.x - r * 0.25f, c.y - r * 0.45f, topStem.x, topStem.y)
        moveTo(rightCherry.x, rightCherry.y - r * 0.2f)
        quadraticBezierTo(c.x + r * 0.30f, c.y - r * 0.35f, topStem.x, topStem.y)
    }
    drawPath(stemPath, color = Color(0xFF2E7D32), style = Stroke(width = r * 0.11f, cap = StrokeCap.Round))

    val leafPath = Path().apply {
        moveTo(topStem.x, topStem.y)
        quadraticBezierTo(topStem.x + r * 0.45f, topStem.y - r * 0.18f, topStem.x + r * 0.55f, topStem.y + r * 0.15f)
        quadraticBezierTo(topStem.x + r * 0.2f, topStem.y + r * 0.22f, topStem.x, topStem.y)
        close()
    }
    drawPath(leafPath, color = Color(0xFF43A047))

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
    val crease = Path().apply {
        moveTo(c.x + r * 0.05f, topLeft.y + h * 0.12f)
        quadraticBezierTo(c.x + r * 0.28f, c.y, c.x + r * 0.05f, topLeft.y + h * 0.88f)
    }
    drawPath(crease, color = Color(0x881A0033), style = Stroke(width = r * 0.06f, cap = StrokeCap.Round))
    drawLine(
        color = Color(0xFF5D4037),
        start = Offset(c.x, topLeft.y + 2f),
        end = Offset(c.x + r * 0.15f, topLeft.y - r * 0.18f),
        strokeWidth = r * 0.1f,
        cap = StrokeCap.Round
    )
    drawOval(
        color = Color(0x99FFFFFF),
        topLeft = Offset(c.x - r * 0.38f, c.y - r * 0.35f),
        size = Size(r * 0.24f, r * 0.48f)
    )
}

private fun DrawScope.drawGrapesSymbol(c: Offset, r: Float) {
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

    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF66BB6A), Color(0xFF2E7D32), Color(0xFF1B5E20)),
            center = Offset(c.x - r * 0.25f, c.y - r * 0.2f),
            radius = r * 1.1f
        ),
        topLeft = topLeft,
        size = Size(w, h)
    )
    for (dy in listOf(-0.28f, 0f, 0.28f)) {
        drawLine(
            color = Color(0xFFA5D6A7),
            start = Offset(topLeft.x + w * 0.12f, c.y + dy * h),
            end = Offset(topLeft.x + w * 0.88f, c.y + dy * h),
            strokeWidth = r * 0.1f,
            cap = StrokeCap.Round
        )
    }
    val sliceW = w * 0.72f
    val sliceH = h * 0.56f
    drawOval(
        color = Color(0xFFFF5252),
        topLeft = Offset(c.x - sliceW / 2f, c.y - sliceH / 2f),
        size = Size(sliceW, sliceH)
    )
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
    val w = r * 1.78f
    val h = r * 1.08f
    val topLeft = Offset(c.x - w / 2f, c.y - h / 2f)

    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(Color(0xFFFFF59D), Color(0xFFFFB300), Color(0xFFF57F17))
        ),
        topLeft = Offset(topLeft.x - 3f, topLeft.y - 3f),
        size = Size(w + 6f, h + 6f),
        cornerRadius = CornerRadius(10f, 10f)
    )
    drawRoundRect(
        color = Color(0xFF141414),
        topLeft = topLeft,
        size = Size(w, h),
        cornerRadius = CornerRadius(8f, 8f)
    )
    drawRoundRect(
        color = Color(0xFFFFD54F),
        topLeft = Offset(topLeft.x + 3.5f, topLeft.y + 3.5f),
        size = Size(w - 7f, h - 7f),
        cornerRadius = CornerRadius(6f, 6f),
        style = Stroke(width = 1.5f)
    )

    val strokeW = (r * 0.115f).coerceAtLeast(2.2f)
    val letterStyle = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val letterColor = Color(0xFFFFEA00)

    val topY = c.y - h * 0.25f
    val midY = c.y
    val botY = c.y + h * 0.25f

    // Pismeno B
    val bxL = c.x - w * 0.34f
    val bxR = c.x - w * 0.15f
    val pathB = Path().apply {
        moveTo(bxL, topY)
        lineTo(bxL, botY)
        moveTo(bxL, topY)
        lineTo(bxR - strokeW * 0.3f, topY)
        quadraticBezierTo(bxR + strokeW * 0.5f, (topY + midY) * 0.5f, bxR - strokeW * 0.3f, midY)
        lineTo(bxL, midY)
        moveTo(bxR - strokeW * 0.2f, midY)
        quadraticBezierTo(bxR + strokeW * 0.6f, (midY + botY) * 0.5f, bxR - strokeW * 0.2f, botY)
        lineTo(bxL, botY)
    }
    drawPath(pathB, color = letterColor, style = letterStyle)

    // Pismeno A
    val axL = c.x - w * 0.09f
    val axM = c.x
    val axR = c.x + w * 0.09f
    val pathA = Path().apply {
        moveTo(axL, botY)
        lineTo(axM, topY)
        lineTo(axR, botY)
        moveTo(axL + (axR - axL) * 0.22f, midY + (botY - midY) * 0.3f)
        lineTo(axR - (axR - axL) * 0.22f, midY + (botY - midY) * 0.3f)
    }
    drawPath(pathA, color = letterColor, style = letterStyle)

    // Pismeno R
    val rxL = c.x + w * 0.16f
    val rxR = c.x + w * 0.34f
    val pathR = Path().apply {
        moveTo(rxL, botY)
        lineTo(rxL, topY)
        lineTo(rxR - strokeW * 0.3f, topY)
        quadraticBezierTo(rxR + strokeW * 0.5f, (topY + midY) * 0.5f, rxR - strokeW * 0.3f, midY)
        lineTo(rxL, midY)
        moveTo(rxL + (rxR - rxL) * 0.35f, midY)
        lineTo(rxR, botY)
    }
    drawPath(pathR, color = letterColor, style = letterStyle)
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
    drawPath(
        path = sevenPath,
        color = Color(0xFFFFD700),
        style = Stroke(width = r * 0.18f, join = StrokeJoin.Round)
    )
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
