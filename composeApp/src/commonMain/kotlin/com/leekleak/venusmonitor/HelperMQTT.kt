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

                            val newObject = ObjectData.entries[height]
                            val newColor = ColorData.entries[color]

                            if (newObject == ObjectData.SMALL_CUBE || newObject == ObjectData.BIG_CUBE) {
                                val range = 3
                                var isDuplicate = false
                                for (dx in -range..range) {
                                    for (dy in -range..range) {
                                        val nx = x + dx
                                        val ny = y + dy
                                        if (nx in 0 until MAP_SIZE_X && ny in 0 until MAP_SIZE_Y) {
                                            val existing = MAP_MATRIX[nx][ny]
                                            if ((existing.objectData == ObjectData.SMALL_CUBE || existing.objectData == ObjectData.BIG_CUBE) &&
                                                existing.colorData == newColor && (nx != x || ny != y)
                                            ) {
                                                isDuplicate = true
                                                break
                                            }
                                        }
                                    }
                                    if (isDuplicate) break
                                }
                                if (isDuplicate) return@forEach

                                // Clear nearby mountains as they were likely misreported rocks
                                for (dx in -range..range) {
                                    for (dy in -range..range) {
                                        val nx = x + dx
                                        val ny = y + dy
                                        if (nx in 0 until MAP_SIZE_X && ny in 0 until MAP_SIZE_Y) {
                                            if (MAP_MATRIX[nx][ny].objectData == ObjectData.MOUNTAIN) {
                                                MAP_MATRIX[nx][ny].objectData = ObjectData.NO_OBJECT
                                            }
                                        }
                                    }
                                }
                            }

                            if (newObject == ObjectData.MOUNTAIN) {
                                val blockRange = 3
                                var nearBlock = false
                                for (dx in -blockRange..blockRange) {
                                    for (dy in -blockRange..blockRange) {
                                        val nx = x + dx
                                        val ny = y + dy
                                        if (nx in 0 until MAP_SIZE_X && ny in 0 until MAP_SIZE_Y) {
                                            val obj = MAP_MATRIX[nx][ny].objectData
                                            if (obj == ObjectData.SMALL_CUBE || obj == ObjectData.BIG_CUBE) {
                                                nearBlock = true; break
                                            }
                                        }
                                    }
                                    if (nearBlock) break
                                }
                                if (nearBlock) return@forEach
                            }

                            MAP_MATRIX[x][y].colorData = newColor
                            MAP_MATRIX[x][y].objectData = newObject

                            // Forward map update to robot 37 if it came from robot 87
                            if (number == 87) {
                                sendObstacle(37, x, y, height)
                            }
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

    suspend fun sendMessage(number: Int, message: ByteString) {
        clients[number]?.let {
            it.publish(PublishRequest("/pynqbridge/$number/recv") {
                desiredQoS = QoS.EXACTLY_ONE
                messageExpiryInterval = 12.hours
                payload(message)
            })
        }
    }

    suspend fun sendObstacle(number: Int, x: Int, y: Int, type: Int) {
        val message = "3${type}${x.toString().padStart(3, '0')}${y.toString().padStart(3, '0')}"
            .map { char -> char.digitToInt().toByte() }
            .toByteArray()
        sendMessage(number, ByteString(message))
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

    suspend fun runExploration(number: Int) {
        val message = "4"
            .map { char -> char.digitToInt().toByte() }
            .toByteArray()
        sendMessage(number, ByteString(message))
    }

    fun resetExploration() {
        explorationPoints.clear()
        println("Exploration progress reset.")
    }
}
