package dev.sharedlists.windows

import java.awt.EventQueue

fun main() {
    EventQueue.invokeLater {
        val controller = WindowsSynchronizationController(UnconfiguredWindowsClientFactory)
        WindowsClientFrame(controller).isVisible = true
    }
}
