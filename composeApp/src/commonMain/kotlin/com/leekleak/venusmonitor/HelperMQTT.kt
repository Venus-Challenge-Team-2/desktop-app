package com.leekleak.venusmonitor

import de.kempmobil.ktor.mqtt.MqttClient
import de.kempmobil.ktor.mqtt.PublishRequest
import de.kempmobil.ktor.mqtt.QoS
import de.kempmobil.ktor.mqtt.buildFilterList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlin.time.Duration.Companion.hours

class HelperMQTT {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clients = mutableMapOf<Int, MqttClient>()
    private val robotBorderPaths = mutableMapOf<Int, MutableList<Point>>()
    private val borderCompletion = mutableMapOf<Int, CompletableDeferred<List<Point>>>()
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

                            print("x: $x, y: $y, height: $height, color: $color\n")
                            MAP_MATRIX[x][y].colorData = ColorData.entries[color]
                            MAP_MATRIX[x][y].objectData = ObjectData.entries[height]
                        } catch (e: Exception) {
                            println(e.message)
                        }
                    } else if (line[0] == '3') {
                        try {
                            val message = line.map { char -> char.digitToInt().toByte() }.toByteArray()
                            val x = (message[1] * 100 + message[2] * 10 + message[3])
                            val y = (message[4] * 100 + message[5] * 10 + message[6])
                            val temp = (message[7] * 100 + message[8] * 10 + message[9])

                            print("x: $x, y: $y, temp: $temp")
                            if (borderCompletion.containsKey(number)) {
                                robotBorderPaths.getOrPut(number) { mutableListOf() }.add(Point(x.toDouble(), y.toDouble()))
                            }
                            MAP_MATRIX[x][y].temperature = temp.toDouble()
                        } catch (e: Exception) {
                            print(line)
                            println(e.message)
                        }
                    } else {
                        if (isBorderStopSignal(line)) {
                            borderCompletion.remove(number)?.complete(robotBorderPaths[number]?.toList() ?: emptyList())
                        }
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

    suspend fun moveToCoordinateWithScan(number: Int, x: Int, y: Int) {
        moveToCoordinate(number, x, y)
        scan(number)
    }

    suspend fun calculateBorder(number: Int, timeoutMillis: Long = 30_000): List<Point> {
        robotBorderPaths[number] = mutableListOf()
        val completion = CompletableDeferred<List<Point>>()
        borderCompletion[number] = completion

        return withTimeoutOrNull(timeoutMillis) {
            completion.await()
        } ?: robotBorderPaths[number]?.toList().orEmpty().also {
            borderCompletion.remove(number)
        }
    }

    private fun isBorderStopSignal(line: String): Boolean {
        val text = line.trim()
        return text.equals("STOP", ignoreCase = true) || text.equals("END", ignoreCase = true) || text == "4" || text.startsWith("4")
    }

    suspend fun divideArea(number: Int, border: List<Point>) {
        val areaDivider = AreaDivider(5.0)
        val points = areaDivider.divideArea(border)
        for (point in points) {
            moveToCoordinateWithScan(number, point.x.toInt(), point.y.toInt())
            //TODO: add functionality to move around obstacles, and remove points that are obstructed by obstacles from the list of points to visit
        }
    }

    suspend fun runAlgorithm(
        borderRobotNumber: Int = 37, //placeholder
        scanRobotNumber: Int = 87, //placeholder
        borderTimeoutMillis: Long = 30_000
    ) {
        val border = calculateBorder(borderRobotNumber, borderTimeoutMillis)

        if (border.isEmpty()) {
            return
        }

        divideArea(scanRobotNumber, border)
    }

}
