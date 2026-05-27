package com.leekleak.venusmonitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.cos
import kotlin.math.sin

val POINT_DATA = generateMapData()
val SQUARE_SIZE: Float = 1f
val MAP_SIZE: Int = 50

@Composable
fun CubeMapCanvas(modifier: Modifier = Modifier.fillMaxSize()) {
    var angleX by remember { mutableStateOf(-0.5f) }
    var angleY by remember { mutableStateOf(0.7f) }

    var camX by remember { mutableStateOf(0f) }
    var camZ by remember { mutableStateOf(0f) }

    var zoomScale by remember { mutableStateOf(1.0f) }

    val focusRequester = remember { FocusRequester() }
    val moveSpeed = 2.0f

    val minZoom = 0.2f
    val maxZoom = 4.0f

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Canvas(
        modifier = modifier
            .background(Color(0xFF262633))
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val forwardX = sin(angleY) * moveSpeed
                    val forwardZ = cos(angleY) * moveSpeed
                    val rightX = cos(angleY) * moveSpeed
                    val rightZ = -sin(angleY) * moveSpeed

                    when (keyEvent.key) {
                        Key.W -> { camX += forwardX; camZ += forwardZ; true }
                        Key.S -> { camX -= forwardX; camZ -= forwardZ; true }
                        Key.A -> { camX -= rightX; camZ -= rightZ; true }
                        Key.D -> { camX += rightX; camZ += rightZ; true }
                        // Arrow Key Zooming Bindings
                        Key.DirectionUp -> {
                            zoomScale = (zoomScale + 0.05f).coerceIn(minZoom, maxZoom)
                            true
                        }
                        Key.DirectionDown -> {
                            zoomScale = (zoomScale - 0.05f).coerceIn(minZoom, maxZoom)
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
                            // Scroll up zooms in, scroll down zooms out
                            zoomScale = (zoomScale - delta * 0.05f).coerceIn(minZoom, maxZoom)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    angleY += pan.x * 0.005f
                    angleX -= pan.y * 0.005f
                    // Multiplies the zoom variable by the pinch factor change
                    zoomScale = (zoomScale * zoom).coerceIn(minZoom, maxZoom)
                }
            }
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val scale = (minOf(size.width, size.height) / (MAP_SIZE * 0.4f)) * zoomScale

        fun project(x: Float, y: Float, z: Float): Offset? {
            val tx = x - camX
            val tz = z - camZ

            val x1 = tx * cos(angleY) - tz * sin(angleY)
            val z1 = tx * sin(angleY) + tz * cos(angleY)

            val y2 = y * cos(angleX) - z1 * sin(angleX)
            val z2 = y * sin(angleX) + z1 * cos(angleX)

            val cameraDistance = MAP_SIZE * 1.0f
            val translatedZ = z2 + cameraDistance

            if (translatedZ <= 0.5f) return null

            val focalLength = MAP_SIZE * 0.8f
            val perspective = focalLength / translatedZ

            return Offset(
                x = centerX + x1 * scale * perspective,
                y = centerY - y2 * scale * perspective
            )
        }

        // Clip system boundaries to avoid app layout bleeding outside canvas space
        clipRect {
            POINT_DATA.sortedByDescending { point ->
                val (x, _, z) = point.coordinates
                (x - camX) * sin(angleY) + (z - camZ) * cos(angleY)
            }.forEach { point ->
                val (x, y, z) = point.coordinates
                val sizeOffset = SQUARE_SIZE * 0.45f

                val f000 = project(x - sizeOffset, 0f, z - sizeOffset) ?: return@forEach
                val f100 = project(x + sizeOffset, 0f, z - sizeOffset) ?: return@forEach
                val f101 = project(x + sizeOffset, 0f, z + sizeOffset) ?: return@forEach
                val f001 = project(x - sizeOffset, 0f, z + sizeOffset) ?: return@forEach

                // Determine base tile color (Change if it's a hole object)
                val tileColor = if (point.objectData == ObjectData.HOLE) Color(0xFF13131A) else Color(0xFF4D5E4D)

                drawPath(
                    path = Path().apply {
                        moveTo(f000.x, f000.y); lineTo(f100.x, f100.y)
                        lineTo(f101.x, f101.y); lineTo(f001.x, f001.y); close()
                    },
                    color = tileColor
                )

                // Skip rendering structural geometry elements for flat/empty profiles
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

                val t000 = project(x - sizeOffset, height, z - sizeOffset) ?: return@forEach
                val t100 = project(x + sizeOffset, height, z - sizeOffset) ?: return@forEach
                val t101 = project(x + sizeOffset, height, z + sizeOffset) ?: return@forEach
                val t001 = project(x - sizeOffset, height, z + sizeOffset) ?: return@forEach

                // Top Face
                drawPath(Path().apply {
                    moveTo(t000.x, t000.y); lineTo(t100.x, t100.y)
                    lineTo(t101.x, t101.y); lineTo(t001.x, t001.y); close()
                }, color = baseColor)

                // Front Face
                drawPath(Path().apply {
                    moveTo(f001.x, f001.y); lineTo(f101.x, f101.y)
                    lineTo(t101.x, t101.y); lineTo(t001.x, t001.y); close()
                }, color = baseColor.copy(red = baseColor.red * 0.85f, green = baseColor.green * 0.85f, blue = baseColor.blue * 0.85f))

                // Right Side Face
                drawPath(Path().apply {
                    moveTo(f100.x, f100.y); lineTo(f101.x, f101.y)
                    lineTo(t101.x, t101.y); lineTo(t100.x, t100.y); close()
                }, color = baseColor.copy(red = baseColor.red * 0.70f, green = baseColor.green * 0.70f, blue = baseColor.blue * 0.70f))

                // Left Side Face
                drawPath(Path().apply {
                    moveTo(f000.x, f000.y); lineTo(f001.x, f001.y)
                    lineTo(t001.x, t001.y); lineTo(t000.x, t000.y); close()
                }, color = baseColor.copy(red = baseColor.red * 0.60f, green = baseColor.green * 0.60f, blue = baseColor.blue * 0.60f))

                // Back Face
                drawPath(Path().apply {
                    moveTo(f000.x, f000.y); lineTo(f100.x, f100.y)
                    lineTo(t100.x, t100.y); lineTo(t000.x, t000.y); close()
                }, color = baseColor.copy(red = baseColor.red * 0.50f, green = baseColor.green * 0.50f, blue = baseColor.blue * 0.50f))
            }
        }
    }
}