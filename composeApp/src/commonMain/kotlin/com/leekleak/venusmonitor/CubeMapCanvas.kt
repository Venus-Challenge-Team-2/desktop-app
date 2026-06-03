package com.leekleak.venusmonitor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

const val SQUARE_SIZE: Float = 1f
val MAP_WIDTH_X: Int = MAP_MATRIX.size
val MAP_WIDTH_Y: Int = MAP_MATRIX.firstOrNull()?.size ?: 0
val MAX_DIMENSION: Float = maxOf(MAP_WIDTH_X, MAP_WIDTH_Y).toFloat()

var showTemperature = false
var showSettingsPanel = false
sealed class RenderOp(val depth: Float) {
    class Polygon(depth: Float, val points: List<Offset>, val color: Color) : RenderOp(depth)
    class Text(depth: Float, val text: String, val position: Offset) : RenderOp(depth)
}

@Composable
fun CubeMapCanvas(modifier: Modifier = Modifier.fillMaxSize()) {
    var currentMapState = remember {
        mutableStateListOf<MutableList<PointData>>().apply {
            MAP_MATRIX.forEach { row -> add(row.toMutableList()) }
        }
    }
    var angleX by remember { mutableStateOf(0.5f) }
    var angleY by remember { mutableStateOf(1f) }
    var camX by remember { mutableStateOf(0f) }
    var camY by remember { mutableStateOf(0f) }
    var zoomScale by remember { mutableStateOf(0.5f) }
    //var showTemperature by remember { mutableStateOf(false) }
    //var showSettingsPanel by remember { mutableStateOf(false) }

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


    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentMapState.clear()
            currentMapState = mutableStateListOf<MutableList<PointData>>().apply {
                MAP_MATRIX.forEach { row -> add(row.toMutableList()) }
            }
            delay(100)
        }
    }
    Column (Modifier.fillMaxHeight()){
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
                                // Movement WASD
                                Key.W -> { camX += forwardX; camY += forwardY; true }
                                Key.S -> { camX -= forwardX; camY -= forwardY; true }
                                Key.A -> { camX -= rightX; camY -= rightY; true }
                                Key.D -> { camX += rightX; camY += rightY; true }

                                // Arrow keys Zoom
                                Key.DirectionUp -> { zoomScale = (zoomScale - 0.05f).coerceIn(minZoom, maxZoom); true }
                                Key.DirectionDown -> { zoomScale = (zoomScale + 0.05f).coerceIn(minZoom, maxZoom); true }
                                else -> false
                            }
                        } else false
                    }
                    // Mouse scroll zoom
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Scroll) {
                                    val delta = event.changes.first().scrollDelta.y
                                    zoomScale = (zoomScale - delta * 0.1f).coerceIn(minZoom, maxZoom)
                                }
                                // click inside sim to focus and be able to move and zoom
                                else if (event.type == PointerEventType.Press) {
                                    focusRequester.requestFocus()
                                }
                            }
                        }
                    }
                    // Touchpad zoom
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
                val cameraDistance = MAX_DIMENSION * 1.0f
                val focalLength = MAX_DIMENSION * 0.8f

                fun project(x: Float, y: Float, z: Float): Offset? {
                    val tx = x - camX
                    val ty = y - camY
                    val x1 = tx * cos(angleY) - ty * sin(angleY)
                    val y1 = tx * sin(angleY) + ty * cos(angleY)
                    val y2 = y1 * cos(angleX) - z * sin(angleX)
                    val z2 = y1 * sin(angleX) + z * cos(angleX)
                    val translatedZ = y2 + cameraDistance

                    if (translatedZ <= 0.5f) return null
                    val perspective = focalLength / translatedZ
                    return Offset(centerX + x1 * scale * perspective, centerY - z2 * scale * perspective)
                }

                val renderList = ArrayList<RenderOp>(currentMapState.size * MAP_WIDTH_Y * 6)

                currentMapState.forEachIndexed { gridX, row ->
                    row.forEachIndexed { gridY, point ->
                        val x = (gridX - MAP_WIDTH_X / 2f) * SQUARE_SIZE
                        val y = (gridY - MAP_WIDTH_Y / 2f) * SQUARE_SIZE

                        renderObject(
                            x = x,
                            y = y,
                            point = point,
                            camX = camX,
                            camY = camY,
                            angleX = angleX,
                            angleY = angleY,
                            showTemperature = showTemperature,
                            project = ::project,
                            renderList = renderList
                        )
                    }
                }

                renderList.sortByDescending { it.depth }

                clipRect {
                    val font = org.jetbrains.skia.Font(null, currentTextSize)
                    renderList.forEach { operation ->
                        when (operation) {
                            is RenderOp.Polygon -> {
                                sharedPath.reset()
                                val first = operation.points.firstOrNull() ?: return@forEach
                                sharedPath.moveTo(first.x, first.y)
                                for (pIdx in 1 until operation.points.size) {
                                    sharedPath.lineTo(operation.points[pIdx].x, operation.points[pIdx].y)
                                }
                                sharedPath.close()
                                drawPath(path = sharedPath, color = operation.color)
                            }

                            is RenderOp.Text -> {
                                drawContext.canvas.nativeCanvas.apply {
                                    val textLine = org.jetbrains.skia.TextLine.make(operation.text, font)
                                    drawTextLine(
                                        textLine,
                                        operation.position.x - (textLine.width / 2f),
                                        operation.position.y,
                                        textPaint
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Column (Modifier.height(200.dp)){

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
                                    val emptyMap = List(currentMapState.size) {
                                        MutableList(MAP_WIDTH_Y) {
                                            PointData(
                                                objectData = ObjectData.NO_OBJECT,
                                                colorData = ColorData.entries.random(),
                                                temperature = Random.nextDouble(MIN_TEMP, MAX_TEMP)
                                            )
                                        }
                                    }
                                    currentMapState.clear()
                                    currentMapState.addAll(emptyMap)
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
                                    val randomMap = List(currentMapState.size) {
                                        MutableList(MAP_SIZE_Y) {
                                            PointData(
                                                objectData = OBJECT_POOL.random(),
                                                colorData = ColorData.entries.random(),
                                                temperature = Random.nextDouble(MIN_TEMP, MAX_TEMP)
                                            )
                                        }
                                    }
                                    currentMapState.clear()
                                    currentMapState.addAll(randomMap)
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

fun renderObject(
    x: Float,
    y: Float,
    point: PointData,
    camX: Float,
    camY: Float,
    angleX: Float,
    angleY: Float,
    showTemperature: Boolean,
    project: (Float, Float, Float) -> Offset?,
    renderList: ArrayList<RenderOp>
) {
    val tx = x - camX
    val ty = y - camY
    val baseDepth = tx * sin(angleY) + ty * cos(angleY)
    val sizeOffset = SQUARE_SIZE * 0.5f

    val f000 = project(x - sizeOffset, y - sizeOffset, 0f) ?: return
    val f100 = project(x + sizeOffset, y - sizeOffset, 0f) ?: return
    val f101 = project(x + sizeOffset, y + sizeOffset, 0f) ?: return
    val f001 = project(x - sizeOffset, y + sizeOffset, 0f) ?: return

    val tileColor = if (point.objectData == ObjectData.HOLE) Color(0xFF13131A) else Color(0xFF464A51)
    renderList.add(RenderOp.Polygon(baseDepth + 100f, listOf(f000, f100, f101, f001), tileColor))

    if (point.objectData == ObjectData.NO_OBJECT || point.objectData == ObjectData.HOLE) return

    val height = when (point.objectData) {
        ObjectData.SMALL_CUBE -> SQUARE_SIZE
        ObjectData.BIG_CUBE -> SQUARE_SIZE * 2f
        ObjectData.MOUNTAIN -> SQUARE_SIZE * 10f
        else -> 0f
    }

    val t000 = project(x - sizeOffset, y - sizeOffset, height) ?: return
    val t100 = project(x + sizeOffset, y - sizeOffset, height) ?: return
    val t101 = project(x + sizeOffset, y + sizeOffset, height) ?: return
    val t001 = project(x - sizeOffset, y + sizeOffset, height) ?: return

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

    renderList.add(RenderOp.Polygon(baseDepth + 0.06f, listOf(t000, t100, t101, t001), baseColor))

    if (isFaceVisible(f001, f101, t101)) {
        val frontColor = Color(baseColor.red * 0.9f, baseColor.green * 0.9f, baseColor.blue * 0.9f)
        renderList.add(RenderOp.Polygon(baseDepth + 0.05f, listOf(f001, f101, t101, t001), frontColor))
    }
    if (isFaceVisible(f101, f100, t100)) {
        val rightColor = Color(baseColor.red * 0.75f, baseColor.green * 0.75f, baseColor.blue * 0.75f)
        renderList.add(RenderOp.Polygon(baseDepth + 0.04f, listOf(f100, f101, t101, t100), rightColor))
    }
    if (isFaceVisible(f000, f001, t001)) {
        val leftColor = Color(baseColor.red * 0.65f, baseColor.green * 0.65f, baseColor.blue * 0.65f)
        renderList.add(RenderOp.Polygon(baseDepth + 0.03f, listOf(f000, f001, t001, t000), leftColor))
    }
    if (isFaceVisible(f100, f000, t000)) {
        val backColor = Color(baseColor.red * 0.5f, baseColor.green * 0.5f, baseColor.blue * 0.5f)
        renderList.add(RenderOp.Polygon(baseDepth - 0.05f, listOf(f000, f100, t100, t000), backColor))
    }

    if (showTemperature && (point.objectData == ObjectData.SMALL_CUBE || point.objectData == ObjectData.BIG_CUBE)) {
        project(x, y, height + 0.3f)?.let { textPos ->
            renderList.add(RenderOp.Text(baseDepth + 0.5f, "${point.temperature.toInt()}°C", textPos))
        }
    }
}