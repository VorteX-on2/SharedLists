package dev.sharedlists.windows

import java.awt.EventQueue
import java.util.ServiceLoader

fun main() {
    EventQueue.invokeLater {
        val enrollment = WindowsCngDeviceEnrollment()
        val providedSigner = ServiceLoader.load(WindowsDeviceSignerProvider::class.java)
            .findFirst()
            .orElse(null)
            ?.load()
        val controller = WindowsSynchronizationController(
            clientFactory = WindowsGrpcClientFactory(deviceSigner = { providedSigner ?: enrollment.current() }),
            deviceEnrollment = if (providedSigner == null) enrollment else null,
        )
        WindowsClientFrame(controller).isVisible = true
    }
}
