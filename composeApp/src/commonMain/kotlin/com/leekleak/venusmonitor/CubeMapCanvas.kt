package com.leekleak.venusmonitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

const val SQUARE_SIZE: Float = 1f
val MAP_WIDTH_X: Int = MAP_MATRIX.size
val MAP_WIDTH_Y: Int = MAP_MATRIX.firstOrNull()?.size ?: 0
val MAX_DIMENSION: Float = maxOf(MAP_WIDTH_X, MAP_WIDTH_Y).toFloat()

private fun getAdjustedNeighborhoodTemperature(gridX: Int, gridY: Int, radius: Int = 4): Double {
    val currentTileTemp = MAP_MATRIX[gridX][gridY].temperature
    var maxInfluencedTemp = currentTileTemp

    val minX = (gridX - radius).coerceAtLeast(0)
    val maxX = (gridX + radius).coerceAtMost(MAP_WIDTH_X - 1)
    val minY = (gridY - radius).coerceAtLeast(0)
    val maxY = (gridY + radius).coerceAtMost(MAP_WIDTH_Y - 1)
    
    for (nx in minX..maxX) {
        val row = MAP_MATRIX[nx]
        for (ny in minY..maxY) {
            val neighborTemp = row[ny].temperature

            if (neighborTemp > MIN_TEMP) {
                val dx = nx - gridX
                val dy = ny - gridY
                val distance = kotlin.math.sqrt((dx * dx + dy * dy).toFloat())

                if (distance <= radius) {
                    val falloff = 1.0 - (distance / radius)
                    val leakedHeat = neighborTemp * falloff

                    if (leakedHeat > maxInfluencedTemp) {
                        maxInfluencedTemp = leakedHeat
                    }
                }
            }
        }
    }

    return maxInfluencedTemp.coerceIn(MIN_TEMP, MAX_TEMP)
}

private fun getBlurredTemperature(gridX: Int, gridY: Int, radius: Int = 4): Double {
    var totalTemp = 0.0
    var count = 0

    val minX = (gridX - radius).coerceAtLeast(0)
    val maxX = (gridX + radius).coerceAtMost(MAP_WIDTH_X - 1)
    val minY = (gridY - radius).coerceAtLeast(0)
    val maxY = (gridY + radius).coerceAtMost(MAP_WIDTH_Y - 1)

    for (nx in minX..maxX) {
        val row = MAP_MATRIX[nx]
        for (ny in minY..maxY) {
            totalTemp += row[ny].temperature
            count++
        }
    }

    return if (count > 0) totalTemp / count else MAP_MATRIX[gridX][gridY].temperature
}
class RenderItemPool(initialCapacity: Int) {
    var polygonCount = 0
    var textCount = 0

    val depths = FloatArray(initialCapacity)
    val points = Array(initialCapacity) { FloatArray(8) }
    val colors = IntArray(initialCapacity)

    val textDepths = FloatArray(initialCapacity / 4)
    val texts = Array(initialCapacity / 4) { "" }
    val textPositionsX = FloatArray(initialCapacity / 4)
    val textPositionsY = FloatArray(initialCapacity / 4)

    var sortedIndices = IntArray(initialCapacity) { it }

    fun reset() {
        polygonCount = 0
        textCount = 0
    }

    fun addPolygon(depth: Float, px1: Float, py1: Float, px2: Float, py2: Float, px3: Float, py3: Float, px4: Float, py4: Float, color: Color) {
        if (polygonCount >= depths.size) return
        val idx = polygonCount
        depths[idx] = depth
        colors[idx] = color.toArgb()

        val pts = points[idx]
        pts[0] = px1; pts[1] = py1
        pts[2] = px2; pts[3] = py2
        pts[4] = px3; pts[5] = py3
        pts[6] = px4; pts[7] = py4

        polygonCount++
    }

    fun sortPolygons() {
        if (sortedIndices.size < polygonCount) {
            sortedIndices = IntArray(polygonCount)
        }
        for (i in 0 until polygonCount) {
            sortedIndices[i] = i
        }
        // Insertion Sort (Stable & fast for near-sorted spatial frames)
        for (i in 1 until polygonCount) {
            val keyIdx = sortedIndices[i]
            val keyDepth = depths[keyIdx]
            var j = i - 1
            while (j >= 0 && depths[sortedIndices[j]] < keyDepth) {
                sortedIndices[j + 1] = sortedIndices[j]
                j--
            }
            sortedIndices[j + 1] = keyIdx
        }
    }
}

@Composable
fun CubeMapCanvas(modifier: Modifier = Modifier.fillMaxSize()) {
    var stateTrigger by remember { mutableStateOf(0) }

    var showTemperatureMap by remember { mutableStateOf(false) }
    var angleX by remember { mutableStateOf(0.5f) }
    var angleY by remember { mutableStateOf(1f) }
    var camX by remember { mutableStateOf(0f) }
    var camY by remember { mutableStateOf(0f) }
    var zoomScale by remember { mutableStateOf(0.5f) }

    val focusRequester = remember { FocusRequester() }
    val moveSpeed = 2.0f
    val minZoom = 0.2f
    val maxZoom = 4.0f

    val sharedPath = remember { Path() }
    val textPaint = remember {
        androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
            color = org.jetbrains.skia.Color.WHITE
        }
    }

    val renderPool = remember { RenderItemPool(MAP_WIDTH_X * MAP_WIDTH_Y * 6) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(Modifier.fillMaxHeight()) {
        Box(modifier = modifier.weight(1f)) {
            Canvas(
                modifier = modifier
                    .background(Color(0xFF13131A))
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            val forwardX = sin(angleY) * moveSpeed
                            val forwardY = cos(angleY) * moveSpeed
                            val rightX = cos(angleY) * moveSpeed
                            val rightY = -sin(angleY) * moveSpeed

                            when (keyEvent.key) {
                                Key.W -> { camX += forwardX; camY += forwardY; true }
                                Key.S -> { camX -= forwardX; camY -= forwardY; true }
                                Key.A -> { camX -= rightX; camY -= rightY; true }
                                Key.D -> { camX += rightX; camY += rightY; true }
                                Key.DirectionUp -> { zoomScale = (zoomScale - 0.05f).coerceIn(minZoom, maxZoom); true }
                                Key.DirectionDown -> { zoomScale = (zoomScale + 0.05f).coerceIn(minZoom, maxZoom); true }
                                else -> false
                            }
                        } else false
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Scroll) {
                                    val delta = event.changes.first().scrollDelta.y
                                    zoomScale = (zoomScale - delta * 0.1f).coerceIn(minZoom, maxZoom)
                                } else if (event.type == PointerEventType.Press) {
                                    focusRequester.requestFocus()
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            angleY += pan.x * 0.005f
                            angleX += pan.y * 0.005f
                            zoomScale = (zoomScale * zoom).coerceIn(minZoom, maxZoom)
                        }
                    }
            ) {
                val frameDependency = stateTrigger

                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val scale = (minOf(size.width, size.height) / (MAX_DIMENSION * 0.4f)) * zoomScale
                val currentTextSize = 12f * zoomScale
                val cameraDistance = MAX_DIMENSION * 1.0f
                val focalLength = MAX_DIMENSION * 0.8f

                val cosY = cos(angleY)
                val sinY = sin(angleY)
                val cosX = cos(angleX)
                val sinX = sin(angleX)

                renderPool.reset()

                val halfMapX = MAP_WIDTH_X / 2f
                val halfMapY = MAP_WIDTH_Y / 2f

                for (gridX in MAP_MATRIX.indices) {
                    val row = MAP_MATRIX[gridX]
                    val x = (gridX - halfMapX) * SQUARE_SIZE

                    for (gridY in row.indices) {
                        val point = row[gridY]
                        val y = (gridY - halfMapY) * SQUARE_SIZE

                        val tx = x - camX
                        val ty = y - camY
                        val baseDepth = tx * sinY + ty * cosY
                        val sizeOffset = SQUARE_SIZE * 0.5f

                        val p000 = projectPacked(x - sizeOffset, y - sizeOffset, 0f, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue
                        val p100 = projectPacked(x + sizeOffset, y - sizeOffset, 0f, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue
                        val p101 = projectPacked(x + sizeOffset, y + sizeOffset, 0f, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue
                        val p001 = projectPacked(x - sizeOffset, y + sizeOffset, 0f, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue

                        val f000x = unpackX(p000); val f000y = unpackY(p000)
                        val f100x = unpackX(p100); val f100y = unpackY(p100)
                        val f101x = unpackX(p101); val f101y = unpackY(p101)
                        val f001x = unpackX(p001); val f001y = unpackY(p001)

                        val tileColor = if (showTemperatureMap) {
                            val adjustedTemp = getAdjustedNeighborhoodTemperature(gridX, gridY, radius = 10)

                            val range = (MAX_TEMP - MIN_TEMP).toFloat()
                            val fraction = if (range > 0f) {
                                ((adjustedTemp - MIN_TEMP).toFloat() / range).coerceIn(0f, 1f)
                            } else 0.5f

                            Color(
                                red = fraction,
                                green = 0f,
                                blue = 1f - fraction,
                                alpha = 1f
                            )
                        } else {
                            if (point.objectData == ObjectData.HOLE) Color(0xFF13131A) else Color(0xFF464A51)
                        }

                        renderPool.addPolygon(
                            baseDepth + 100f,
                            f000x, f000y,
                            f100x, f100y,
                            f101x, f101y,
                            f001x, f001y,
                            tileColor
                        )

                        if (showTemperatureMap) {
                            val textIndex = renderPool.textCount
                            if (textIndex < renderPool.textDepths.size) {
                                renderPool.textDepths[textIndex] = baseDepth

                                val tempInt = (point.temperature * 10).toInt()
                                val whole = tempInt / 10
                                val fraction = kotlin.math.abs(tempInt % 10)

                                renderPool.texts[textIndex] = "$whole.$fraction°"

                                renderPool.textPositionsX[textIndex] = (f000x + f100x + f101x + f001x) / 4f
                                renderPool.textPositionsY[textIndex] = (f000y + f100y + f101y + f001y) / 4f
                                renderPool.textCount++
                            }
                        }

                        if (!showTemperatureMap && point.objectData != ObjectData.NO_OBJECT && point.objectData != ObjectData.HOLE) {
                            val height = when (point.objectData) {
                                ObjectData.SMALL_CUBE -> SQUARE_SIZE
                                ObjectData.BIG_CUBE -> SQUARE_SIZE * 2f
                                ObjectData.MOUNTAIN -> SQUARE_SIZE * 10f
                                else -> 0f
                            }

                            val pt000 = projectPacked(x - sizeOffset, y - sizeOffset, height, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue
                            val pt100 = projectPacked(x + sizeOffset, y - sizeOffset, height, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue
                            val pt101 = projectPacked(x + sizeOffset, y + sizeOffset, height, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue
                            val pt001 = projectPacked(x - sizeOffset, y + sizeOffset, height, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue

                            val t000x = unpackX(pt000); val t000y = unpackY(pt000)
                            val t100x = unpackX(pt100); val t100y = unpackY(pt100)
                            val t101x = unpackX(pt101); val t101y = unpackY(pt101)
                            val t001x = unpackX(pt001); val t001y = unpackY(pt001)

                            var baseColor = when (point.colorData) {
                                ColorData.RED -> Color(0xFFD32F2F)
                                ColorData.BLACK -> Color(0xFF212121)
                                ColorData.BLUE -> Color(0xFF1976D2)
                                ColorData.GREEN -> Color(0xFF388E3C)
                                ColorData.WHITE -> Color(0xFFFFFFFF)
                            }
                            if (point.objectData == ObjectData.MOUNTAIN) baseColor = Color(0xFF8D6554)

                            renderPool.addPolygon(baseDepth + 0.06f, t000x, t000y, t100x, t100y, t101x, t101y, t001x, t001y, baseColor)

                            if ((f101x - f001x) * (t001y - f001y) - (f101y - f001y) * (t001x - f001x) > 0) {
                                val frontColor = Color(baseColor.red * 0.9f, baseColor.green * 0.9f, baseColor.blue * 0.9f)
                                renderPool.addPolygon(baseDepth + 0.05f, f001x, f001y, f101x, f101y, t101x, t101y, t001x, t001y, frontColor)
                            }
                            if ((f100x - f101x) * (t101y - f101y) - (f100y - f101y) * (t101x - f101x) > 0) {
                                val rightColor = Color(baseColor.red * 0.75f, baseColor.green * 0.75f, baseColor.blue * 0.75f)
                                renderPool.addPolygon(baseDepth + 0.04f, f100x, f100y, f101x, f101y, t101x, t101y, t100x, t100y, rightColor)
                            }
                            if ((f001x - f000x) * (t000y - f000y) - (f001y - f000y) * (t000x - f000x) > 0) {
                                val leftColor = Color(baseColor.red * 0.65f, baseColor.green * 0.65f, baseColor.blue * 0.65f)
                                renderPool.addPolygon(baseDepth + 0.03f, f000x, f000y, f001x, f001y, t001x, t001y, t000x, t000y, leftColor)
                            }
                            if ((f000x - f100x) * (t100y - f100y) - (f000y - f100y) * (t100x - f100x) > 0) {
                                val backColor = Color(baseColor.red * 0.5f, baseColor.green * 0.5f, baseColor.blue * 0.5f)
                                renderPool.addPolygon(baseDepth - 0.05f, f100x, f100y, f000x, f000y, t000x, t000y, t100x, t100y, backColor)
                            }
                        }
                    }
                }

                renderPool.sortPolygons()

                clipRect {
                    val font = org.jetbrains.skia.Font(null, currentTextSize)

                    for (i in 0 until renderPool.polygonCount) {
                        val originalIdx = renderPool.sortedIndices[i]
                        val pts = renderPool.points[originalIdx]

                        sharedPath.reset()
                        sharedPath.moveTo(pts[0], pts[1])
                        sharedPath.lineTo(pts[2], pts[3])
                        sharedPath.lineTo(pts[4], pts[5])
                        sharedPath.lineTo(pts[6], pts[7])
                        sharedPath.close()

                        drawPath(path = sharedPath, color = Color(renderPool.colors[originalIdx]))
                    }

                    if (showTemperatureMap && renderPool.textCount > 0) {
                        drawContext.canvas.nativeCanvas.apply {
                            for (i in 0 until renderPool.textCount) {
                                val text = renderPool.texts[i]
                                val textWidth = font.measureTextWidth(text, textPaint)

                                drawString(
                                    text,
                                    renderPool.textPositionsX[i] - (textWidth / 2f),
                                    renderPool.textPositionsY[i],
                                    font,
                                    textPaint
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(Modifier.height(200.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24).copy(alpha = 0.95f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.width(300.dp).clip(RoundedCornerShape(10.dp))
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Temperature", color = Color.White)
                        Switch(checked = showTemperatureMap, onCheckedChange = { showTemperatureMap = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                for (row in MAP_MATRIX) {
                                    for (point in row) {
                                        point.objectData = ObjectData.NO_OBJECT
                                        point.colorData = ColorData.entries.random()
                                        point.temperature = MIN_TEMP
                                    }
                                }
                                stateTrigger++
                                focusRequester.requestFocus()
                            }
                        ) {
                            Text("Clear Map")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                for (row in MAP_MATRIX) {
                                    for (point in row) {
                                        point.objectData = OBJECT_POOL.random()
                                        point.colorData = ColorData.entries.random()
                                        point.temperature = Random.nextDouble(MIN_TEMP, MAX_TEMP)
                                    }
                                }
                                stateTrigger++
                                focusRequester.requestFocus()
                            }
                        ) {
                            Text("Random Map")
                        }
                    }
                }
            }
        }
    }
}

private fun projectPacked(
    x: Float, y: Float, z: Float,
    camX: Float, camY: Float,
    centerX: Float, centerY: Float,
    scale: Float, cameraDistance: Float, focalLength: Float,
    cosX: Float, sinX: Float, cosY: Float, sinY: Float
): Long? {
    val tx = x - camX
    val ty = y - camY
    val x1 = tx * cosY - ty * sinY
    val y1 = tx * sinY + ty * cosY
    val y2 = y1 * cosX - z * sinX
    val z2 = y1 * sinX + z * cosX
    val translatedZ = y2 + cameraDistance

    if (translatedZ <= 0.5f) return null
    val perspective = focalLength / translatedZ

    val outX = centerX + x1 * scale * perspective
    val outY = centerY - z2 * scale * perspective

    val xBits = outX.toBits().toLong() and 0xFFFFFFFFL
    val yBits = outY.toBits().toLong() and 0xFFFFFFFFL
    return (xBits shl 32) or yBits
}

private fun unpackX(packed: Long): Float = Float.fromBits((packed ushr 32).toInt())
private fun unpackY(packed: Long): Float = Float.fromBits((packed and 0xFFFFFFFFL).toInt())