package com.leekleak.venusmonitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

const val SQUARE_SIZE: Float = 1f
val MAP_WIDTH_X: Int = matrix.size
val MAP_WIDTH_Y: Int = matrix.firstOrNull()?.size ?: 0

val MAX_DIMENSION: Float = maxOf(MAP_WIDTH_X, MAP_WIDTH_Y).toFloat()

@Composable
fun CubeMapCanvas(modifier: Modifier = Modifier.fillMaxSize()) {
    var angleX by remember { mutableStateOf(0.5f) }
    var angleY by remember { mutableStateOf(0.7f) }

    var camX by remember { mutableStateOf(0f) }
    var camY by remember { mutableStateOf(0f) }

    var zoomScale by remember { mutableStateOf(1.0f) }

    var showTemperature by remember { mutableStateOf(false) }
    var showSettingsPanel by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val moveSpeed = 2.0f

    val minZoom = 0.2f
    val maxZoom = 4.0f

    val textPaint = remember {
        androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
            color = org.jetbrains.skia.Color.WHITE
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    Box(modifier = modifier) {
        Canvas(
            modifier = modifier
                .background(Color(0xFF262633))
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        val forwardX = sin(angleY) * moveSpeed
                        val forwardY = cos(angleY) * moveSpeed
                        val rightX = cos(angleY) * moveSpeed
                        val rightY = -sin(angleY) * moveSpeed

                        when (keyEvent.key) {
                            Key.W -> {
                                camX += forwardX; camY += forwardY; true
                            }

                            Key.S -> {
                                camX -= forwardX; camY -= forwardY; true
                            }

                            Key.A -> {
                                camX -= rightX; camY -= rightY; true
                            }

                            Key.D -> {
                                camX += rightX; camY += rightY; true
                            }

                            Key.DirectionUp -> {
                                zoomScale = (zoomScale - 0.05f).coerceIn(minZoom, maxZoom)
                                true
                            }

                            Key.DirectionDown -> {
                                zoomScale = (zoomScale + 0.05f).coerceIn(minZoom, maxZoom)
                                true
                            }

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
                                zoomScale = (zoomScale - delta * 0.05f).coerceIn(minZoom, maxZoom)
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
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val scale = (minOf(size.width, size.height) / (MAX_DIMENSION * 0.4f)) * zoomScale

            val currentTextSize = 12f * zoomScale

            fun project(x: Float, y: Float, z: Float): Offset? {
                val tx = x - camX
                val ty = y - camY

                val x1 = tx * cos(angleY) - ty * sin(angleY)
                val y1 = tx * sin(angleY) + ty * cos(angleY)

                val y2 = y1 * cos(angleX) - z * sin(angleX)
                val z2 = y1 * sin(angleX) + z * cos(angleX)

                val cameraDistance = MAX_DIMENSION * 1.0f
                val translatedZ = y2 + cameraDistance

                if (translatedZ <= 0.5f) return null

                val focalLength = MAX_DIMENSION * 0.8f
                val perspective = focalLength / translatedZ

                return Offset(
                    x = centerX + x1 * scale * perspective,
                    y = centerY - z2 * scale * perspective
                )
            }

            clipRect {
                buildList {
                    matrix.forEachIndexed { gridX, row ->
                        row.forEachIndexed { gridY, point ->
                            val x = (gridX - MAP_WIDTH_X / 2f) * SQUARE_SIZE
                            val y = (gridY - MAP_WIDTH_Y / 2f) * SQUARE_SIZE
                            add(Triple(x, y, point))
                        }
                    }
                }.sortedByDescending { (x, y, _) ->
                    (x - camX) * sin(angleY) + (y - camY) * cos(angleY)
                }.forEach { (x, y, point) ->
                    val sizeOffset = SQUARE_SIZE * 0.49f

                    val f000 = project(x - sizeOffset, y - sizeOffset, 0f) ?: return@forEach
                    val f100 = project(x + sizeOffset, y - sizeOffset, 0f) ?: return@forEach
                    val f101 = project(x + sizeOffset, y + sizeOffset, 0f) ?: return@forEach
                    val f001 = project(x - sizeOffset, y + sizeOffset, 0f) ?: return@forEach

                    val tileColor = if (point.objectData == ObjectData.HOLE) Color(0xFF13131A) else Color(0xFF6E7076)

                    drawPath(
                        path = Path().apply {
                            moveTo(f000.x, f000.y); lineTo(f100.x, f100.y)
                            lineTo(f101.x, f101.y); lineTo(f001.x, f001.y); close()
                        },
                        color = tileColor
                    )

                    if (point.objectData == ObjectData.NO_OBJECT || point.objectData == ObjectData.HOLE) return@forEach

                    val height = when (point.objectData) {
                        ObjectData.SMALL_CUBE -> SQUARE_SIZE
                        ObjectData.BIG_CUBE -> SQUARE_SIZE * 2f
                        ObjectData.MOUNTAIN -> SQUARE_SIZE * 10f
                        else -> 0f
                    }

                    val baseColor = when (point.colorData) {
                        ColorData.RED -> Color(0xFFD32F2F)
                        ColorData.BLACK -> Color(0xFF212121)
                        ColorData.BLUE -> Color(0xFF1976D2)
                        ColorData.GREEN -> Color(0xFF388E3C)
                        ColorData.WHITE -> Color(0xFFFFFFFF)
                    }

                    val t000 = project(x - sizeOffset, y - sizeOffset, height) ?: return@forEach
                    val t100 = project(x + sizeOffset, y - sizeOffset, height) ?: return@forEach
                    val t101 = project(x + sizeOffset, y + sizeOffset, height) ?: return@forEach
                    val t001 = project(x - sizeOffset, y + sizeOffset, height) ?: return@forEach

                    // Top Face
                    drawPath(Path().apply {
                        moveTo(t000.x, t000.y); lineTo(t100.x, t100.y)
                        lineTo(t101.x, t101.y); lineTo(t001.x, t001.y); close()
                    }, color = baseColor)

                    // Front Face
                    drawPath(
                        Path().apply {
                            moveTo(f001.x, f001.y); lineTo(f101.x, f101.y)
                            lineTo(t101.x, t101.y); lineTo(t001.x, t001.y); close()
                        },
                        color = baseColor.copy(
                            red = baseColor.red * 0.85f,
                            green = baseColor.green * 0.85f,
                            blue = baseColor.blue * 0.85f
                        )
                    )

                    // Right Side Face
                    drawPath(
                        Path().apply {
                            moveTo(f100.x, f100.y); lineTo(f101.x, f101.y)
                            lineTo(t101.x, t101.y); lineTo(t100.x, t100.y); close()
                        },
                        color = baseColor.copy(
                            red = baseColor.red * 0.70f,
                            green = baseColor.green * 0.70f,
                            blue = baseColor.blue * 0.70f
                        )
                    )

                    // Left Side Face
                    drawPath(
                        Path().apply {
                            moveTo(f000.x, f000.y); lineTo(f001.x, f001.y)
                            lineTo(t001.x, t001.y); lineTo(t000.x, t000.y); close()
                        },
                        color = baseColor.copy(
                            red = baseColor.red * 0.60f,
                            green = baseColor.green * 0.60f,
                            blue = baseColor.blue * 0.60f
                        )
                    )

                    // Back Face
                    drawPath(
                        Path().apply {
                            moveTo(f000.x, f000.y); lineTo(f100.x, f100.y)
                            lineTo(t100.x, t100.y); lineTo(t000.x, t000.y); close()
                        },
                        color = baseColor.copy(
                            red = baseColor.red * 0.50f,
                            green = baseColor.green * 0.50f,
                            blue = baseColor.blue * 0.50f
                        )
                    )
                    if (showTemperature) {
                        if (point.objectData == ObjectData.SMALL_CUBE || point.objectData == ObjectData.BIG_CUBE) {
                            val textHeightOffset = height + 0.3f
                            val textPosition = project(x, y, textHeightOffset)

                            if (textPosition != null) {
                                drawContext.canvas.nativeCanvas.apply {
                                    val textString = "${point.temperature.toInt()}°C"
                                    val font = org.jetbrains.skia.Font(null, currentTextSize)
                                    val textLine = org.jetbrains.skia.TextLine.make(textString, font)

                                    val textWidth = textLine.width
                                    val centerXOffset = textPosition.x - (textWidth / 2f)

                                    drawTextLine(
                                        line = textLine,
                                        x = centerXOffset,
                                        y = textPosition.y,
                                        paint = textPaint
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Button(
            onClick = { showSettingsPanel = !showSettingsPanel },
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopEnd)
        ) {
            Text(if (showSettingsPanel) "Close Menu" else "Map Settings")
        }

        if (showSettingsPanel) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E1E24).copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .padding(top = 70.dp, end = 16.dp)
                    .width(240.dp)
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Display Configurations",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Divider(color = Color.Gray.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Temperature", color = Color.White)
                        Switch(
                            checked = showTemperature,
                            onCheckedChange = {
                                showTemperature = it
                                focusRequester.requestFocus()
                            }
                        )
                    }
                }
            }
        }
    }
}
