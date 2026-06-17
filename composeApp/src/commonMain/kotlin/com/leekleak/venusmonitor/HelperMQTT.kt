package com.leekleak.venusmonitor

import de.kempmobil.ktor.mqtt.MqttClient
import de.kempmobil.ktor.mqtt.PublishRequest
import de.kempmobil.ktor.mqtt.QoS
import de.kempmobil.ktor.mqtt.buildFilterList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.hours

class HelperMQTT {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clients = mutableMapOf<Int, MqttClient>()

    var explorationPoints = mutableListOf<Pair<Int, Int>>()
    private var currentPath = mutableListOf<Pair<Int, Int>>()
    private var isExplorationActive = false
    private var isWaitingForScan = false
    private var lastTarget: Pair<Int, Int>? = null
    fun getFlowBot(number: Int, pass: String): Flow<String> = callbackFlow {
        if (!clients.containsKey(number)) {
            clients[number] = MqttClient("mqtt.ics.ele.tue.nl", 1883) {
                username = "robot_${number}_1"
                password = pass
                keepAliveSeconds = 60u
            }
        }
        val client = clients[number]!!
        val collectJob = launch {
            client.publishedPackets.collect { publish ->
                val string = publish.payload.decodeToString()
                val lines = string.lines()
                lines.forEach { line ->
                    if (line.isEmpty()) return@forEach
                    if (line[0] == '2') {
                        try {
                            val message = line.map { char -> char.digitToInt().toByte() }.toByteArray()
                            val color = message[1].toInt()
                            val height = message[2].toInt()
                            val x = (message[3] * 100 + message[4] * 10 + message[5])
                            val y = (message[6] * 100 + message[7] * 10 + message[8])

                            //print("x: $x, y: $y, height: $height, color: $color\n")
                            MAP_MATRIX[x][y].colorData = ColorData.entries[color]
                            MAP_MATRIX[x][y].objectData = ObjectData.entries[height]
                        } catch (e: Exception) {
                            println(e.message)
                        }
                    } else if (line[0] == '3') {
                        try {
                            val message =
                                line.map { char -> char.digitToInt().toByte() }.toByteArray()
                            val x = (message[1] * 100 + message[2] * 10 + message[3])
                            val y = (message[4] * 100 + message[5] * 10 + message[6])
                            val temp = (message[7] * 100 + message[8] * 10 + message[9])
                            if(number == 37) {
                                currentX37 = x;
                                currentY37 = y;
                            }
                            else if(number == 87) {
                                currentX87 = x;
                                currentY87 = y;
                            }
                            //print("x: $x, y: $y, temp: $temp")
                            MAP_MATRIX[x][y].temperature = temp.toDouble()
                        } catch (e: Exception) {
                            print(line)
                            println(e.message)
                        }

                    } else if (line[0] == '5' && number == 37) {
                        runExploration(number)
                    } else {
                        trySend(line)
                    }
                }
            }
        }

        scope.launch {
            client.connect(true).onSuccess { connack ->
                if (connack.isSuccess) {
                    client.subscribe(buildFilterList { +"/pynqbridge/$number/send" })
                } else {
                    trySend("Connection Rejected")
                }
            }.onFailure { _ ->
                trySend("Connection Failed")
            }
        }

        awaitClose {
            collectJob.cancel()
            scope.launch {
                try {
                    client.disconnect()
                } catch (e: Exception) {
                    println("Error disconnecting MQTT client: ${e.message}")
                }
            }
            println("MQTT Flow closed.")
        }
    }
    
    suspend fun runExploration(number: Int) {
        if (number != 37) return

        if (!isExplorationActive) {
            val enclosedPoints = findEnclosedArea()
            if (enclosedPoints.size >= 30 * 30) {
                val minX = enclosedPoints.minOf { it.first }
                val maxX = enclosedPoints.maxOf { it.first }
                val minY = enclosedPoints.minOf { it.second }
                val maxY = enclosedPoints.maxOf { it.second }

                if (maxX - minX >= 29 && maxY - minY >= 29) {
                    explorationPoints = planScanPoints(enclosedPoints)
                    if (explorationPoints.isNotEmpty()) {
                        isExplorationActive = true
                        isWaitingForScan = false
                        println("Exploration started for robot 37. Points: ${explorationPoints.size}")
                    }
                }
            }
        }

        if (isExplorationActive) {
            if (isWaitingForScan) {
                scan(37)
                isWaitingForScan = false
                println("Robot 37 scanning at $lastTarget")
                return
            }

            if (currentPath.isNotEmpty()) {
                val next = currentPath.removeAt(0)
                moveToCoordinate(37, next.first, next.second)
                println("Robot 37 moving to $next")
                if (currentPath.isEmpty()) {
                    isWaitingForScan = true
                }
            } else if (explorationPoints.isNotEmpty()) {
                val nextTarget = explorationPoints.removeAt(0)
                lastTarget = nextTarget
                val path = findPathWithBuffer(currentX37, currentY37, nextTarget.first, nextTarget.second, 1)
                if (!path.isNullOrEmpty()) {
                    currentPath = path.toMutableList()
                    val nextStep = currentPath.removeAt(0)
                    moveToCoordinate(37, nextStep.first, nextStep.second)
                    println("Robot 37 starting path to $nextTarget, next step $nextStep")
                    if (currentPath.isEmpty()) {
                        isWaitingForScan = true
                    }
                } else {
                    println("Robot 37: Target $nextTarget unreachable, skipping.")
                    runExploration(37) // Try next point
                }
            } else {
                isExplorationActive = false
                println("Exploration finished for robot 37.")
            }
        }
    }

    private fun findEnclosedArea(): List<Pair<Int, Int>> {
        val isBlackHole = Array(MAP_SIZE_X) { x ->
            BooleanArray(MAP_SIZE_Y) { y ->
                MAP_MATRIX[x][y].objectData == ObjectData.HOLE
            }
        }

        val isOutside = Array(MAP_SIZE_X) { BooleanArray(MAP_SIZE_Y) }
        val queue = mutableListOf<Pair<Int, Int>>()

        for (x in 0 until MAP_SIZE_X) {
            if (!isBlackHole[x][0]) { isOutside[x][0] = true; queue.add(x to 0) }
            if (!isBlackHole[x][MAP_SIZE_Y - 1]) { isOutside[x][MAP_SIZE_Y - 1] = true; queue.add(x to MAP_SIZE_Y - 1) }
        }
        for (y in 0 until MAP_SIZE_Y) {
            if (!isBlackHole[0][y]) { isOutside[0][y] = true; queue.add(0 to y) }
            if (!isBlackHole[MAP_SIZE_X - 1][y]) { isOutside[MAP_SIZE_X - 1][y] = true; queue.add(MAP_SIZE_X - 1 to y) }
        }

        var head = 0
        while (head < queue.size) {
            val (cx, cy) = queue[head++]
            val neighbors = listOf(cx - 1 to cy, cx + 1 to cy, cx to cy - 1, cx to cy + 1)
            for ((nx, ny) in neighbors) {
                if (nx in 0 until MAP_SIZE_X && ny in 0 until MAP_SIZE_Y && !isOutside[nx][ny] && !isBlackHole[nx][ny]) {
                    isOutside[nx][ny] = true
                    queue.add(nx to ny)
                }
            }
        }

        val enclosedComponents = mutableListOf<MutableList<Pair<Int, Int>>>()
        val visitedEnclosed = Array(MAP_SIZE_X) { BooleanArray(MAP_SIZE_Y) }

        for (x in 0 until MAP_SIZE_X) {
            for (y in 0 until MAP_SIZE_Y) {
                if (!isOutside[x][y] && !isBlackHole[x][y] && !visitedEnclosed[x][y]) {
                    val component = mutableListOf<Pair<Int, Int>>()
                    val compQueue = mutableListOf<Pair<Int, Int>>()
                    val start = x to y
                    visitedEnclosed[x][y] = true
                    compQueue.add(start)
                    component.add(start)

                    var qHead = 0
                    while (qHead < compQueue.size) {
                        val (cx, cy) = compQueue[qHead++]
                        val neighbors = listOf(cx - 1 to cy, cx + 1 to cy, cx to cy - 1, cx to cy + 1)
                        for ((nx, ny) in neighbors) {
                            if (nx in 0 until MAP_SIZE_X && ny in 0 until MAP_SIZE_Y &&
                                !isOutside[nx][ny] && !isBlackHole[nx][ny] && !visitedEnclosed[nx][ny]
                            ) {
                                visitedEnclosed[nx][ny] = true
                                compQueue.add(nx to ny)
                                component.add(nx to ny)
                            }
                        }
                    }
                    enclosedComponents.add(component)
                }
            }
        }
        return enclosedComponents.maxByOrNull { it.size } ?: emptyList()
    }

    private fun planScanPoints(area: List<Pair<Int, Int>>): MutableList<Pair<Int, Int>> {
        if (area.isEmpty()) return mutableListOf()
        val areaSet = area.toSet()
        val minX = area.minOf { it.first }
        val maxX = area.maxOf { it.first }
        val minY = area.minOf { it.second }
        val maxY = area.maxOf { it.second }

        val points = mutableListOf<Pair<Int, Int>>()
        // Scan a 30x30 area. Distribute points every 15 units.
        for (x in minX + 15..maxX step 15) {
            for (y in minY + 15..maxY step 15) {
                if (areaSet.contains(x to y)) {
                    points.add(x to y)
                } else {
                    // If center is not in area, find nearest in area
                    area.minByOrNull { (ax, ay) -> (ax - x) * (ax - x) + (ay - y) * (ay - y) }?.let {
                        if (!points.contains(it)) points.add(it)
                    }
                }
            }
        }
        return points
    }

    internal fun findPathWithBuffer(sx: Int, sy: Int, ex: Int, ey: Int, buffer: Int): List<Pair<Int, Int>>? {
        if (sx == ex && sy == ey) return emptyList()

        val isObstacle = Array(MAP_SIZE_X) { x ->
            BooleanArray(MAP_SIZE_Y) { y ->
                MAP_MATRIX[x][y].objectData != ObjectData.NO_OBJECT
            }
        }

        fun isSafe(x: Int, y: Int): Boolean {
            if (x !in 0 until MAP_SIZE_X || y !in 0 until MAP_SIZE_Y) return false
            for (dx in -buffer..buffer) {
                for (dy in -buffer..buffer) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until MAP_SIZE_X && ny in 0 until MAP_SIZE_Y) {
                        if (isObstacle[nx][ny]) return false
                    }
                }
            }
            return true
        }

        if (!isSafe(sx, sy) || !isSafe(ex, ey)) return null

        data class Node(val x: Int, val y: Int, val g: Double, val h: Double) : Comparable<Node> {
            val f = g + h
            override fun compareTo(other: Node): Int = f.compareTo(other.f)
        }

        fun heuristic(x: Int, y: Int): Double {
            val dx = abs(x - ex)
            val dy = abs(y - ey)
            // Octile distance for 8-connected grid
            return (dx + dy) + (sqrt(2.0) - 2) * if (dx < dy) dx else dy
        }

        val openSet = mutableListOf<Node>()
        openSet.add(Node(sx, sy, 0.0, heuristic(sx, sy)))
        val parent = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>>()
        val gScore = mutableMapOf<Pair<Int, Int>, Double>()
        gScore[sx to sy] = 0.0

        while (openSet.isNotEmpty()) {
            openSet.sortBy { it.f }
            val current = openSet.removeAt(0)

            if (current.x == ex && current.y == ey) {
                val path = mutableListOf<Pair<Int, Int>>()
                var p: Pair<Int, Int>? = ex to ey
                while (p != null && p != (sx to sy)) {
                    path.add(p)
                    p = parent[p]
                }
                return simplifyPath(path.reversed(), buffer, isObstacle)
            }

            for (dx in -1..1) {
                for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = current.x + dx
                    val ny = current.y + dy

                    if (isSafe(nx, ny)) {
                        val moveCost = if (abs(dx) + abs(dy) == 2) sqrt(2.0) else 1.0
                        val tentativeGScore = (gScore[current.x to current.y] ?: Double.MAX_VALUE) + moveCost
                        if (tentativeGScore < (gScore[nx to ny] ?: Double.MAX_VALUE)) {
                            parent[nx to ny] = current.x to current.y
                            gScore[nx to ny] = tentativeGScore
                            if (openSet.none { it.x == nx && it.y == ny }) {
                                openSet.add(Node(nx, ny, tentativeGScore, heuristic(nx, ny)))
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun simplifyPath(path: List<Pair<Int, Int>>, buffer: Int, isObstacle: Array<BooleanArray>): List<Pair<Int, Int>> {
        if (path.size <= 2) return path
        val simplified = mutableListOf<Pair<Int, Int>>()
        simplified.add(path[0])
        var currentIdx = 0
        while (currentIdx < path.size - 1) {
            var nextIdx = path.size - 1
            while (nextIdx > currentIdx + 1) {
                if (hasLineOfSight(path[currentIdx], path[nextIdx], buffer, isObstacle)) {
                    break
                }
                nextIdx--
            }
            simplified.add(path[nextIdx])
            currentIdx = nextIdx
        }
        return simplified
    }

    private fun hasLineOfSight(p1: Pair<Int, Int>, p2: Pair<Int, Int>, buffer: Int, isObstacle: Array<BooleanArray>): Boolean {
        var x = p1.first.toDouble()
        var y = p1.second.toDouble()
        val dx = p2.first - p1.first
        val dy = p2.second - p1.second
        val distance = sqrt((dx * dx + dy * dy).toDouble())
        if (distance == 0.0) return true
        
        val stepX = dx / distance
        val stepY = dy / distance
        
        val steps = distance.toInt()
        for (i in 1..steps) {
            x += stepX
            y += stepY
            if (!isSafeWithObstacles(x.toInt(), y.toInt(), buffer, isObstacle)) return false
        }
        return isSafeWithObstacles(p2.first, p2.second, buffer, isObstacle)
    }

    private fun isSafeWithObstacles(x: Int, y: Int, buffer: Int, isObstacle: Array<BooleanArray>): Boolean {
        if (x !in 0 until MAP_SIZE_X || y !in 0 until MAP_SIZE_Y) return false
        for (dx in -buffer..buffer) {
            for (dy in -buffer..buffer) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until MAP_SIZE_X && ny in 0 until MAP_SIZE_Y) {
                    if (isObstacle[nx][ny]) return false
                }
            }
        }
        return true
    }

    suspend fun sendMessage(number: Int, message: ByteString) {
        clients[number]?.let {
            it.publish(PublishRequest("/pynqbridge/$number/recv") {
                desiredQoS = QoS.EXACTLY_ONE
                messageExpiryInterval = 12.hours
                payload(message)
            })
        }
    }

    suspend fun moveToCoordinate(number: Int, x: Int, y: Int) {
        val message = "0${x.toString().padStart(3, '0')}${y.toString().padStart(3, '0')}"
            .map { char -> char.digitToInt().toByte() }
            .toByteArray()
        sendMessage(number, ByteString(message))
    }

    suspend fun scan(number: Int) {
        val message = "1"
            .map { char -> char.digitToInt().toByte() }
            .toByteArray()
        sendMessage(number, ByteString(message))
    }
}