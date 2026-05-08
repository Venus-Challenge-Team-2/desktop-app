package com.leekleak.venusmonitor

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform