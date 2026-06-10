package com.leekleak.venusmonitor

data class Point(val x: Double, val y: Double)

class AreaDivider(private val scanRange: Double) {
    val sweepPoints = mutableListOf<Point>()

    fun divideArea(border: List<Point>): List<Point> {
        if (border.size < 3) return emptyList()

        val pointMinX = border.minOf { it.x }
        val pointMinY = border.minOf { it.y }
        val pointMaxX = border.maxOf { it.x }
        val pointMaxY = border.maxOf { it.y }

        val width = pointMaxX - pointMinX
        val height = pointMaxY - pointMinY
        val squaresX = maxOf(0, (width / (scanRange * 2.0)).toInt())
        val squaresY = maxOf(0, (height / (scanRange * 2.0)).toInt())

        val rawPoints = mutableListOf<Point>()
        for (y in 0 until squaresY) {
            for (x in 0 until squaresX) {
                rawPoints.add(
                    Point(
                        pointMinX + x * scanRange * 2.0 + scanRange,
                        pointMinY + y * scanRange * 2.0 + scanRange
                    )
                )
            }
        }

        sweepPoints.clear()
        sweepPoints += rawPoints.filter { isPointInPolygon(it, border) }
        return sweepPoints
    }

    fun isPointInPolygon(point: Point, polygon: List<Point>): Boolean {
        if (polygon.size < 3) return false

        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val xi = polygon[i].x
            val yi = polygon[i].y
            val xj = polygon[j].x
            val yj = polygon[j].y

            val intersects = ((yi > point.y) != (yj > point.y)) &&
                (point.x <= (xj - xi) * (point.y - yi) / (yj - yi) + xi)

            if (intersects) {
                inside = !inside
            }

            j = i
        }

        return inside
    }
}
