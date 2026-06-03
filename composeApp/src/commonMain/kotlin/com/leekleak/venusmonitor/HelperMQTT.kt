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
import kotlin.time.Duration.Companion.hours

class HelperMQTT {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clients = mutableMapOf<Int, MqttClient>()
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

                            MAP_MATRIX[x][y].objectData = ObjectData.entries[height]
                            MAP_MATRIX[x][y].colorData = ColorData.entries[color]
                        } catch (e: Exception) {
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