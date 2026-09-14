package dev.sharedlists.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.sharedlists.client.SharedList
import dev.sharedlists.client.EnrollmentState

class SharedListsActivity : Activity() {
    private lateinit var controller: AndroidSynchronizationController
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = AndroidSynchronizationController(applicationContext)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { textSize = 16f }
        content.addView(status)
        setContentView(ScrollView(this).apply { addView(content) })
        controller.observe(::render)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = controller.onNetworkAvailable(true)

            override fun onLost(network: Network) = controller.onNetworkAvailable(false)
        }
    }

    override fun onStart() {
        super.onStart()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        controller.onForeground()
    }

    override fun onStop() {
        controller.onBackground()
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        super.onStop()
    }

    private fun render(presentation: AndroidClientPresentation) {
        runOnUiThread {
            status.text = presentation.status
            while (content.childCount > 1) content.removeViewAt(1)
            if (presentation.configuration == null) {
                addConfigurationForm()
            }
            when (presentation.enrollment) {
                EnrollmentState.UNCONFIGURED -> addButton("Create device key", controller::createDeviceKey)
                EnrollmentState.UNREADABLE_DEVICE_KEY -> {
                    addText("The device key could not be read. Retry after Android Keystore is available.")
                    addButton("Retry device key", controller::retryDeviceKey)
                }
                else -> Unit
            }
            if (presentation.exportRequired) addButton("Export public key", ::sharePublicKey)
            if (presentation.enrollment == EnrollmentState.ENROLLED) {
                addLists(presentation.canonicalState.lists)
                addButton("Reset local synchronization data", controller::resetLocalData)
                addButton("Reset device setup", controller::resetDeviceSetup)
            }
        }
    }

    private fun addConfigurationForm() {
        val host = EditText(this).apply { hint = "Server IP address" }
        val port = EditText(this).apply { hint = "Port"; inputType = InputType.TYPE_CLASS_NUMBER }
        val fingerprint = EditText(this).apply { hint = "SHA-256 server fingerprint" }
        content.addView(host)
        content.addView(port)
        content.addView(fingerprint)
        addButton("Save server configuration") {
            if (!controller.configure(host.text.toString(), port.text.toString(), fingerprint.text.toString())) {
                status.text = "Enter a server address, port, and SHA-256 fingerprint."
            }
        }
    }

    private fun addLists(lists: List<SharedList>) {
        if (lists.isEmpty()) {
            addText("No shared lists yet.")
            return
        }
        lists.forEach { list ->
            addText(list.name)
            list.items.take(4).forEach { item -> addText(if (item.marked) "✓ ${item.text}" else item.text) }
        }
    }

    private fun addButton(label: String, action: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            contentDescription = label
            setOnClickListener { action() }
        })
    }

    private fun addText(value: String) {
        content.addView(TextView(this).apply {
            text = value
            gravity = Gravity.START
            textSize = 18f
        })
    }

    private fun sharePublicKey() {
        val (fileName, pem) = controller.exportPublicKey()
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(fileName, pem))
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, pem)
            putExtra(Intent.EXTRA_TITLE, fileName)
        }, "Share device public key"))
    }
}
