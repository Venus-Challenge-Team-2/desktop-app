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

    fun addPolygon(depth: Float, p1: Offset, p2: Offset, p3: Offset, p4: Offset, color: Color) {
        if (polygonCount >= depths.size) return
        val idx = polygonCount
        depths[idx] = depth
        colors[idx] = color.toArgb()

        val pts = points[idx]
        pts[0] = p1.x; pts[1] = p1.y
        pts[2] = p2.x; pts[3] = p2.y
        pts[4] = p3.x; pts[5] = p3.y
        pts[6] = p4.x; pts[7] = p4.y

        polygonCount++
    }

    fun addText(depth: Float, text: String, x: Float, y: Float) {
        if (textCount >= textDepths.size) return
        val idx = textCount
        textDepths[idx] = depth
        texts[idx] = text
        textPositionsX[idx] = x
        textPositionsY[idx] = y
        textCount++
    }

    fun sortPolygons() {
        if (sortedIndices.size < polygonCount) {
            sortedIndices = IntArray(polygonCount)
        }

        for (i in 0 until polygonCount) {
            sortedIndices[i] = i
        }

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
    val currentMapState = remember {
        mutableStateListOf<MutableList<PointData>>().apply {
            MAP_MATRIX.forEach { row -> add(row.toMutableList()) }
        }
    }

    var showTemperature by remember { mutableStateOf(false) }
    var angleX by remember { mutableStateOf(0.5f) }
    var angleY by remember { mutableStateOf(1f) }
    var camX by remember { mutableStateOf(0f) }
    var camY by remember { mutableStateOf(0f) }
    var zoomScale by remember { mutableStateOf(0.5f) }

    var mapUpdateTrigger by remember { mutableStateOf(0) }

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
                @Suppress("UNUSED_VARIABLE")
                val trigger = mapUpdateTrigger

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

                for (gridX in 0 until currentMapState.size) {
                    val row = currentMapState[gridX]
                    val x = (gridX - halfMapX) * SQUARE_SIZE

                    for (gridY in 0 until row.size) {
                        val point = row[gridY]
                        val y = (gridY - halfMapY) * SQUARE_SIZE

                        val tx = x - camX
                        val ty = y - camY
                        val baseDepth = tx * sinY + ty * cosY
                        val sizeOffset = SQUARE_SIZE * 0.5f

                        val f000 = projectToOffset(x - sizeOffset, y - sizeOffset, 0f, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue
                        val f100 = projectToOffset(x + sizeOffset, y - sizeOffset, 0f, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue
                        val f101 = projectToOffset(x + sizeOffset, y + sizeOffset, 0f, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue
                        val f001 = projectToOffset(x - sizeOffset, y + sizeOffset, 0f, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue

                        val tileColor = if (point.objectData == ObjectData.HOLE) Color(0xFF13131A) else Color(0xFF464A51)
                        renderPool.addPolygon(baseDepth + 100f, f000, f100, f101, f001, tileColor)

                        if (point.objectData == ObjectData.NO_OBJECT || point.objectData == ObjectData.HOLE) continue

                        val height = when (point.objectData) {
                            ObjectData.SMALL_CUBE -> SQUARE_SIZE
                            ObjectData.BIG_CUBE -> SQUARE_SIZE * 2f
                            ObjectData.MOUNTAIN -> SQUARE_SIZE * 10f
                            else -> 0f
                        }

                        val t000 = projectToOffset(x - sizeOffset, y - sizeOffset, height, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue
                        val t100 = projectToOffset(x + sizeOffset, y - sizeOffset, height, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue
                        val t101 = projectToOffset(x + sizeOffset, y + sizeOffset, height, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue
                        val t001 = projectToOffset(x - sizeOffset, y + sizeOffset, height, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY) ?: continue

                        var baseColor = when (point.colorData) {
                            ColorData.RED -> Color(0xFFD32F2F)
                            ColorData.BLACK -> Color(0xFF212121)
                            ColorData.BLUE -> Color(0xFF1976D2)
                            ColorData.GREEN -> Color(0xFF388E3C)
                            ColorData.WHITE -> Color(0xFFFFFFFF)
                        }
                        if (point.objectData == ObjectData.MOUNTAIN) baseColor = Color(0xFF8D6554)

                        val isFaceVisible = { p1: Offset, p2: Offset, p3: Offset ->
                            (p2.x - p1.x) * (p3.y - p1.y) - (p2.y - p1.y) * (p3.x - p1.x) > 0
                        }

                        renderPool.addPolygon(baseDepth + 0.06f, t000, t100, t101, t001, baseColor)

                        if (isFaceVisible(f001, f101, t101)) {
                            val frontColor = Color(baseColor.red * 0.9f, baseColor.green * 0.9f, baseColor.blue * 0.9f)
                            renderPool.addPolygon(baseDepth + 0.05f, f001, f101, t101, t001, frontColor)
                        }
                        if (isFaceVisible(f101, f100, t100)) {
                            val rightColor = Color(baseColor.red * 0.75f, baseColor.green * 0.75f, baseColor.blue * 0.75f)
                            renderPool.addPolygon(baseDepth + 0.04f, f100, f101, t101, t100, rightColor)
                        }
                        if (isFaceVisible(f000, f001, t001)) {
                            val leftColor = Color(baseColor.red * 0.65f, baseColor.green * 0.65f, baseColor.blue * 0.65f)
                            renderPool.addPolygon(baseDepth + 0.03f, f000, f001, t001, t000, leftColor)
                        }
                        if (isFaceVisible(f100, f000, t000)) {
                            val backColor = Color(baseColor.red * 0.5f, baseColor.green * 0.5f, baseColor.blue * 0.5f)
                            renderPool.addPolygon(baseDepth - 0.05f, f000, f100, t100, t000, backColor)
                        }

                        if (showTemperature && (point.objectData == ObjectData.SMALL_CUBE || point.objectData == ObjectData.BIG_CUBE)) {
                            projectToOffset(x, y, height + 0.3f, camX, camY, centerX, centerY, scale, cameraDistance, focalLength, cosX, sinX, cosY, sinY)?.let { textPos ->
                                renderPool.addText(baseDepth + 0.5f, "${point.temperature.toInt()}°C", textPos.x, textPos.y)
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

                    if (showTemperature && renderPool.textCount > 0) {
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
                        Switch(checked = showTemperature, onCheckedChange = { showTemperature = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                for (x in currentMapState.indices) {
                                    for (y in currentMapState[x].indices) {
                                        val point = currentMapState[x][y]

                                        point.objectData = ObjectData.NO_OBJECT
                                        point.colorData = ColorData.entries.random()
                                        point.temperature = Random.nextDouble(MIN_TEMP, MAX_TEMP)
                                    }
                                }
                                mapUpdateTrigger++
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
                                for (x in 0 until currentMapState.size) {
                                    val rowSize = currentMapState[x].size
                                    currentMapState[x] = MutableList(rowSize) {
                                        PointData(
                                            objectData = OBJECT_POOL.random(),
                                            colorData = ColorData.entries.random(),
                                            temperature = Random.nextDouble(MIN_TEMP, MAX_TEMP)
                                        )
                                    }
                                }
                                mapUpdateTrigger++
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

private fun projectToOffset(
    x: Float, y: Float, z: Float,
    camX: Float, camY: Float,
    centerX: Float, centerY: Float,
    scale: Float, cameraDistance: Float, focalLength: Float,
    cosX: Float, sinX: Float, cosY: Float, sinY: Float
): Offset? {
    val tx = x - camX
    val ty = y - camY
    val x1 = tx * cosY - ty * sinY
    val y1 = tx * sinY + ty * cosY
    val y2 = y1 * cosX - z * sinX
    val z2 = y1 * sinX + z * cosX
    val translatedZ = y2 + cameraDistance

    if (translatedZ <= 0.5f) return null
    val perspective = focalLength / translatedZ
    return Offset(centerX + x1 * scale * perspective, centerY - z2 * scale * perspective)
}