package com.leekleak.venusmonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Preview
fun App() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(16.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1.0f) // Takes up 40% of the horizontal screen width
                            .fillMaxHeight()
                    ) {
                        RobotTab(37, "CpE43hdC")
                    }
                    Box(
                        modifier = Modifier
                            .weight(1.0f) // Takes up 40% of the horizontal screen width
                            .fillMaxHeight()
                    ) {
                        RobotTab(87, "s5Bpx5Yo")
                    }
                    Box(
                        modifier = Modifier
                            .weight(1.5f) // Takes up 60% of the horizontal screen width
                            .fillMaxHeight()
                    ) {
                        CubeMapCanvas()
                    }
                }
            }
        }
    }

@Composable
private fun RobotTab(
    number: Int,
    password: String,
) {
    val helperMQTT: HelperMQTT = koinInject()
    val scope = rememberCoroutineScope()
    val messageFlow = remember(number, password) { helperMQTT.getFlowBot(number, password) }
    val message1 by messageFlow.collectAsState("")

    LaunchedEffect(message1) {
        if (message1.isEmpty()) return@LaunchedEffect
        if (message1[0] == '2') {
            try {
                val message = message1.map { char -> char.digitToInt().toByte() }.toByteArray()
                val color = message[1].toInt()
                val height = message[2].toInt()
                val x = (message[3] * 100 + message[4] * 10 + message[5])
                val y = (message[6] * 100 + message[7] * 10 + message[8])

                if (x > 50 || y > 50) return@LaunchedEffect
                matrix[x][y].objectData = ObjectData.entries[height]
                matrix[x][y].colorData = ColorData.entries[color]
            } catch (e: Exception) {
                println(e.message)
            }
        }
    }
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
            .width(250.dp)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProvideTextStyle(TextStyle(color = MaterialTheme.colorScheme.onSurface)) {
            Text("Robot $number message:")
            Box(Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
                .padding(8.dp)
            ) {
                Text(message1)
            }
        }

        HorizontalDivider()
        val textFieldStateX = rememberTextFieldState("")
        TextField(
            state = textFieldStateX,
            label = { Text("Coordinate X") }
        )
        val textFieldStateY = rememberTextFieldState("")
        TextField(
            state = textFieldStateY,
            label = { Text("Coordinate Y") }
        )
        Button(onClick = {
            scope.launch {
                val messag= "0${textFieldStateX.text}${textFieldStateY.text}"
                    .map { char -> char.digitToInt().toByte() }
                    .toByteArray()
                helperMQTT.sendMessage(number, ByteString(messag))
            }
        }) {
            Text("Move to coordinate")
        }
        HorizontalDivider()
        Button(onClick = {
            scope.launch {
                val message = "1"
                    .map { char -> char.digitToInt().toByte() }
                    .toByteArray()
                helperMQTT.sendMessage(number, ByteString(message))
            }
        }) {
            Text("Send Scan")
        }
    }
}