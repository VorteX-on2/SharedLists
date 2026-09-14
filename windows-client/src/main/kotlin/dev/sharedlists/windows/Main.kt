package dev.sharedlists.windows

import java.awt.EventQueue

fun main() {
    EventQueue.invokeLater {
        val enrollment = WindowsCngDeviceEnrollment()
        val controller = WindowsSynchronizationController(
            clientFactory = WindowsGrpcClientFactory(enrollment::current),
            deviceEnrollment = enrollment,
        )
        WindowsClientFrame(controller).isVisible = true
    }
}
