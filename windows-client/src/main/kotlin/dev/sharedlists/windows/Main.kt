package dev.sharedlists.windows

import java.awt.EventQueue
import java.util.ServiceLoader

fun main() {
    EventQueue.invokeLater {
        val signer = ServiceLoader.load(WindowsDeviceSignerProvider::class.java)
            .findFirst()
            .orElse(null)
            ?.load()
        val controller = WindowsSynchronizationController(WindowsGrpcClientFactory(signer))
        WindowsClientFrame(controller).isVisible = true
    }
}
