package com.leekleak.venusmonitor

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PathfindingTest {

    @Test
    fun testSimplePath() {
        val helper = HelperMQTT()
        // Initialize map to be empty
        for (x in 0 until MAP_SIZE_X) {
            for (y in 0 until MAP_SIZE_Y) {
                MAP_MATRIX[x][y].objectData = ObjectData.NO_OBJECT
            }
        }

        val path = helper.callFindPathWithBuffer(20, 20, 50, 50, 3)
        assertNotNull(path, "Path should not be null")
        assertTrue(path.isNotEmpty(), "Path should not be empty")
        println("Simplified Path: $path")
        // A straight path should be simplified to just the end point (since we add p1 and then the last visible point)
        // Or if start is 20,20 and end is 50,50, it might be just [(21,21), (50,50)] or similar
        assertTrue(path.size <= 2, "Path should be highly simplified, but got ${path.size} points")
    }

    @Test
    fun testObstacleAvoidance() {
        val helper = HelperMQTT()
        // Initialize map to be empty
        for (x in 0 until MAP_SIZE_X) {
            for (y in 0 until MAP_SIZE_Y) {
                MAP_MATRIX[x][y].objectData = ObjectData.NO_OBJECT
            }
        }

        // Place a wall
        for (y in 0 until 80) {
            MAP_MATRIX[40][y].objectData = ObjectData.BIG_CUBE
        }

        val path = helper.callFindPathWithBuffer(20, 20, 60, 20, 3)
        assertNotNull(path, "Path should not be null")
        assertTrue(path.isNotEmpty(), "Path should not be empty")
        
        println("Obstacle Avoidance Path: $path")
        
        // Ensure path doesn't go through wall (x=40)
        // Since it's simplified, we need to check segments
        var prev = 20 to 20 // start is not in path list but it is the start
        for (point in path) {
             // Check if segment (prev, point) intersects the wall at x=40, y<80
             if (prev.first < 40 && point.first > 40) {
                 // The wall is at x=40. Linear interpolation to find y at x=40
                 val t = (40.0 - prev.first) / (point.first - prev.first)
                 val yAtWall = prev.second + t * (point.second - prev.second)
                 assertTrue(yAtWall >= 80 - 3, "Path segment from $prev to $point crosses wall at y=$yAtWall (wall ends at 80, buffer 3)")
             }
             prev = point
        }
    }
    
    // Helper to access private method if we make it public or use internal
    private fun HelperMQTT.callFindPathWithBuffer(sx: Int, sy: Int, ex: Int, ey: Int, buffer: Int): List<Pair<Int, Int>>? {
        // We will make findPathWithBuffer internal for testing
        return this.findPathWithBuffer(sx, sy, ex, ey, buffer)
    }
}
