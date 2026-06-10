package com.leekleak.venusmonitor

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlin.random.Random

const val MIN_TEMP = 10.0
const val MAX_TEMP = 30.0

const val MAP_SIZE_X = 100
const val MAP_SIZE_Y = 100

data class PointData(
    var objectData: ObjectData,
    var colorData: ColorData,
    var temperature: Double
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

val OBJECT_POOL: List<ObjectData> = OBJECT_WEIGHTS.flatMap { (obj, weight) ->
    List(weight) { obj }
}
//*
val MAP_MATRIX: SnapshotStateList<List<PointData>> = mutableStateListOf<List<PointData>>().apply {
    repeat(MAP_SIZE_X) {
        add(List(MAP_SIZE_Y) {
            PointData(
                objectData = ObjectData.NO_OBJECT,
                colorData = ColorData.entries.random(),
                temperature = MIN_TEMP
            )
        })
    }
}
//*/
/*
var MAP_MATRIX: List<List<PointData>> = List(MAP_SIZE_X) {
    List(MAP_SIZE_Y) {
        PointData(
            objectData = ObjectData.NO_OBJECT,
            //objectData = OBJECT_POOL.random(),
            colorData = ColorData.entries.random(),
            temperature = MIN_TEMP
        )
    }
}

//*/