package com.leekleak.venusmonitor

import de.kempmobil.ktor.mqtt.MqttClient
import de.kempmobil.ktor.mqtt.PublishRequest
import de.kempmobil.ktor.mqtt.QoS
import de.kempmobil.ktor.mqtt.buildFilterList
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.decodeToString
import kotlin.time.Duration.Companion.hours

class HelperMQTT {
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
                trySend(publish.payload.decodeToString())
            }
        }

        client.connect(true).onSuccess { connack ->
            if (connack.isSuccess) {
                client.subscribe(buildFilterList { +"/pynqbridge/$number/send" })
            }
        }.onFailure {
            throw it
        }

        awaitClose {
            collectJob.cancel()
            launch { client.disconnect() }
            println("MQTT Flow closed.")
        }
    }

    suspend fun sendMessage(number: Int, message: String) {
        clients[number]?.let {
            it.publish(PublishRequest("/pynqbridge/$number/recv") {
                desiredQoS = QoS.AT_LEAST_ONCE
                messageExpiryInterval = 12.hours
                payload(message)
            })
        }
    }
}