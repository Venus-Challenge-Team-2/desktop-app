package com.leekleak.venusmonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Preview
fun App() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .safeContentPadding()
                .fillMaxSize(),
        ) {
            Row (
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RobotTab(37, "CpE43hdC")
                RobotTab(87, "s5Bpx5Yo")
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
    val message1 by helperMQTT.getFlowBot(number, password).collectAsState("")
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
        val textFieldState = rememberTextFieldState("")
        TextField(
            state = textFieldState,
            label = { Text("Send message") }
        )
        Button(onClick = {
            scope.launch {
                helperMQTT.sendMessage(number, textFieldState.text.toString())
            }
        }) {
            Text("Send")
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
                helperMQTT.sendMessage(number, "0 ${textFieldStateX.text} ${textFieldStateY.text}")
            }
        }) {
            Text("Move to coordinate")
        }
        HorizontalDivider()
        Button(onClick = {
            scope.launch {
                helperMQTT.sendMessage(number, "1")
            }
        }) {
            Text("Send Scan")
        }
    }
}