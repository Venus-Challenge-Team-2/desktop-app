package com.leekleak.venusmonitor

import androidx.compose.runtime.Immutable
import kotlin.random.Random

private const val MIN_TEMP = 10.0
private const val MAX_TEMP = 30.0

@Immutable
data class PointData(
    val coordinates: Triple<Float, Float, Float>,
    val objectData: ObjectData,
    var colorData: ColorData,
    val temperature: Double
)

enum class ObjectData {
    NO_OBJECT, SMALL_CUBE, BIG_CUBE, MOUNTAIN, HOLE
}
enum class ColorData {
    RED, BLACK, BLUE, GREEN, WHITE
}

private val OBJECT_WEIGHTS = listOf(
    ObjectData.NO_OBJECT to 30,
    ObjectData.SMALL_CUBE to 1,
    ObjectData.BIG_CUBE to 1,
    ObjectData.MOUNTAIN to 1,
    ObjectData.HOLE to 1
)

private val OBJECT_POOL: List<ObjectData> = OBJECT_WEIGHTS.flatMap { (obj, weight) ->
    List(weight) { obj }
}
fun generateMapData(): List<PointData> = buildList {
    for (x in -50..50) {
        for (z in -50..50) {
            add(
                PointData(
                    coordinates = Triple(x * 1f, 1f, z * 1f),
                    objectData = OBJECT_POOL.random(),
                    colorData = ColorData.entries.random(),
                    temperature = Random.nextDouble(MIN_TEMP, MAX_TEMP)
                )
            )
        }
    }
}