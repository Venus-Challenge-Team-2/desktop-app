package com.leekleak.venusmonitor

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import di.initKoin

fun main() = application {
    initKoin {
        printLogger()
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Venus Monitor - Control Station",
    ) {
        App()
    }
}