package com.leekleak.venusmonitor

import kotlin.random.Random
import kotlin.time.Clock.System.now

private val TEMPERATURE_RANGE = 10f..30f

// Use val for immutability, which is preferred in modern Kotlin and for UI state in Compose
data class PointData(
    val xCord: Int,
    val yCord: Int,
    val objectData: ObjectData,
    val colorData: ColorData,
    val temperature: Double,
)

enum class ObjectData {
    NO_OBJECT,
    SMALL_CUBE,
    BIG_CUBE,
    MOUNTAIN
}

enum class ColorData {
    RED,
    BLACK,
    BLUE,
    GREEN,
    WHITE,
}

fun generateMapData(): List<PointData> = buildList {
    for (x in -2..2) {
        for (y in -2..2) {
            add(
                PointData(
                    xCord = x,
                    yCord = y,
                    objectData = ObjectData.entries.random(),
                    colorData = ColorData.entries.random(),
                    temperature = Random(now().nanosecondsOfSecond).nextDouble() * 20f + 10f,
                )
            )
        }
    }
}
