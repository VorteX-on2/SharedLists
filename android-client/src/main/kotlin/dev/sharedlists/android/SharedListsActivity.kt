package dev.sharedlists.android

import android.app.Activity
import android.app.AlertDialog
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
import dev.sharedlists.client.SharedListId

class SharedListsActivity : Activity() {
    private lateinit var controller: AndroidSynchronizationController
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private var selectedListId: SharedListId? = null

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
                if (presentation.editingEnabled) addButton("Create shared list") { prompt("New shared list") { controller.createList(it) } }
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
        if (resources.configuration.screenWidthDp >= WIDE_LAYOUT_MINIMUM_DP) {
            addWideListDetail(lists)
        } else {
            lists.forEach { list ->
                addButton(list.name) { showList(list) }
                list.items.filterNot { it.marked }.take(4).forEach { item -> addText(item.text) }
            }
        }
    }

    private fun addWideListDetail(lists: List<SharedList>) {
        val selected = lists.firstOrNull { it.id == selectedListId } ?: lists.first()
        selectedListId = selected.id
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            contentDescription = "Shared lists and selected list detail"
            addView(LinearLayout(this@SharedListsActivity).apply {
                orientation = LinearLayout.VERTICAL
                lists.forEach { list ->
                    addView(Button(this@SharedListsActivity).apply {
                        text = list.name
                        isAllCaps = false
                        setOnClickListener {
                            selectedListId = list.id
                            controller.observe(::render)
                        }
                    })
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(LinearLayout(this@SharedListsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SharedListsActivity).apply { text = selected.name; textSize = 22f })
                selected.items.forEach { item ->
                    addView(Button(this@SharedListsActivity).apply {
                        text = if (item.marked) "✓ ${item.text}" else item.text
                        contentDescription = "Edit ${item.text}"
                        setOnClickListener { prompt("Edit item", item.text) { controller.editItemText(selected.id, item.id, it) } }
                    })
                }
                addView(Button(this@SharedListsActivity).apply {
                    text = "Open list controls"
                    setOnClickListener { showList(selected) }
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        })
    }

    private fun showList(list: SharedList) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        container.addView(TextView(this).apply { text = list.name; textSize = 22f })
        container.addView(Button(this).apply {
            text = "Rename list"
            setOnClickListener { prompt("Rename shared list", list.name) { controller.renameList(list.id, it) } }
        })
        container.addView(Button(this).apply {
            text = "Add item"
            setOnClickListener { prompt("New item") { controller.createItem(list.id, it) } }
        })
        list.items.forEachIndexed { index, item ->
            container.addView(Button(this).apply {
                text = if (item.marked) "✓ ${item.text}" else item.text
                contentDescription = "Edit ${item.text}"
                setOnClickListener { prompt("Edit item", item.text) { controller.editItemText(list.id, item.id, it) } }
            })
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(this@SharedListsActivity).apply {
                    text = if (item.marked) "Unmark" else "Mark"
                    setOnClickListener { controller.setMarked(list.id, item.id, !item.marked) }
                })
                addView(Button(this@SharedListsActivity).apply {
                    text = "Up"
                    isEnabled = index > 0
                    setOnClickListener { controller.moveItem(list.id, item.id, index - 1) }
                })
                addView(Button(this@SharedListsActivity).apply {
                    text = "Down"
                    isEnabled = index < list.items.lastIndex
                    setOnClickListener { controller.moveItem(list.id, item.id, index + 1) }
                })
                addView(Button(this@SharedListsActivity).apply {
                    text = "Delete"
                    setOnClickListener { controller.deleteItem(list.id, item.id) }
                })
            })
        }
        container.addView(Button(this).apply {
            text = "Delete list"
            setOnClickListener {
                AlertDialog.Builder(this@SharedListsActivity)
                    .setMessage("Delete ${list.name}?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ -> controller.deleteList(list.id) }
                    .show()
            }
        })
        AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(container) }).setNegativeButton("Close", null).show()
    }

    private fun prompt(title: String, initialValue: String = "", action: (String) -> Unit) {
        val input = EditText(this).apply { setText(initialValue); selectAll() }
        AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> action(input.text.toString()) }.show()
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

    private companion object {
        const val WIDE_LAYOUT_MINIMUM_DP = 840
    }
}
