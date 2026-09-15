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
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.sharedlists.client.CanonicalState
import dev.sharedlists.client.EnrollmentState
import dev.sharedlists.client.ListItem
import dev.sharedlists.client.ListItemId
import dev.sharedlists.client.SharedListId
import dev.sharedlists.client.SharedList

class SharedListsActivity : Activity() {
    private lateinit var controller: AndroidSynchronizationController
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private var fixture: PresentationFixture? = null
    private var hideMarkedItems = false
    private var selectedListId: SharedListId? = null
    private var sortAlphabetically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixture = savedInstanceState?.getString(TEST_PRESENTATION)?.let(::presentationFixture)
            ?: presentationFixture(intent.getStringExtra(TEST_PRESENTATION))
        Log.i(LOG_TAG, "onCreate action=${intent.action} component=${intent.component} fixture=${fixture?.marker} debug=${BuildConfig.DEBUG}")
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { textSize = 16f }
        content.addView(status)
        setContentView(ScrollView(this).apply {
            contentDescription = fixture?.marker
            addView(content)
        })
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = controller.onNetworkAvailable(true)

            override fun onLost(network: Network) = controller.onNetworkAvailable(false)
        }
        fixture?.let { render(it.presentation) } ?: AndroidSynchronizationController(applicationContext).also {
            controller = it
            it.observe(::render)
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i(LOG_TAG, "onStart fixture=${fixture?.marker}")
        if (fixture == null) {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
            controller.onForeground()
        }
    }

    override fun onStop() {
        if (fixture == null) {
            controller.onBackground()
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        fixture?.let { outState.putString(TEST_PRESENTATION, it.selector) }
        super.onSaveInstanceState(outState)
    }

    private fun render(presentation: AndroidClientPresentation) {
        runOnUiThread {
            status.text = presentation.status
            while (content.childCount > 1) content.removeViewAt(1)
            if (presentation.configuration == null) {
                addConfigurationForm()
            }
            when (presentation.enrollment) {
                EnrollmentState.UNCONFIGURED -> addButton("Create device key") { controller.createDeviceKey() }
                EnrollmentState.UNREADABLE_DEVICE_KEY -> {
                    addText("The device key could not be read. Retry after Android Keystore is available.")
                    addButton("Retry device key") { controller.retryDeviceKey() }
                }
                else -> Unit
            }
            if (presentation.exportRequired) addButton("Export public key", ::sharePublicKey)
            if (presentation.enrollment == EnrollmentState.ENROLLED) {
                addButton(if (hideMarkedItems) "Show marked items" else "Hide marked items") {
                    hideMarkedItems = !hideMarkedItems
                    render(presentation)
                }
                addButton(if (sortAlphabetically) "Use manual order" else "Sort alphabetically") {
                    sortAlphabetically = !sortAlphabetically
                    render(presentation)
                }
                addLists(presentation.canonicalState.lists, presentation.editingEnabled)
                if (presentation.editingEnabled) addButton("Create shared list") { prompt("New shared list") { controller.createList(it) } }
                addButton("Reset local synchronization data") { controller.resetLocalData() }
                addButton("Reset device setup") { controller.resetDeviceSetup() }
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

    private fun addLists(lists: List<SharedList>, editingEnabled: Boolean) {
        if (lists.isEmpty()) {
            addText("No shared lists yet.")
            return
        }
        if (resources.configuration.screenWidthDp >= WIDE_LAYOUT_MINIMUM_DP) {
            addWideListDetail(lists, editingEnabled)
        } else {
            lists.forEach { list ->
                addButton(list.name) { showList(list, editingEnabled) }
                displayedItems(list).take(4).forEach { item -> addText(item.text) }
            }
        }
    }

    private fun addWideListDetail(lists: List<SharedList>, editingEnabled: Boolean) {
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
                if (editingEnabled) {
                    addButtonTo(this, "Rename list") { prompt("Rename shared list", selected.name) { controller.renameList(selected.id, it) } }
                    addButtonTo(this, "Add item") { prompt("New item") { controller.createItem(selected.id, it) } }
                }
                displayedItems(selected).forEach { item ->
                    addView(Button(this@SharedListsActivity).apply {
                        text = if (item.marked) "✓ ${item.text}" else item.text
                        contentDescription = "Edit ${item.text}"
                        setOnClickListener { prompt("Edit item", item.text) { controller.editItemText(selected.id, item.id, it) } }
                    })
                    if (editingEnabled) {
                        addView(LinearLayout(this@SharedListsActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(itemActionButton(selected, item.id, item.marked, "Mark") { controller.setMarked(selected.id, item.id, !item.marked) })
                            addView(itemActionButton(selected, item.id, item.marked, "Up") { moveVisibleItem(selected, item.id, -1) })
                            addView(itemActionButton(selected, item.id, item.marked, "Down") { moveVisibleItem(selected, item.id, 1) })
                            addView(itemActionButton(selected, item.id, item.marked, "Delete") { controller.deleteItem(selected.id, item.id) })
                        })
                    }
                }
                if (editingEnabled) addButtonTo(this, "Delete list") { confirmDeleteList(selected) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        })
    }

    private fun showList(list: SharedList, editingEnabled: Boolean) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        container.addView(TextView(this).apply { text = list.name; textSize = 22f })
        if (editingEnabled) {
            container.addView(Button(this).apply {
                text = "Rename list"
                contentDescription = "Rename list"
                setOnClickListener { prompt("Rename shared list", list.name) { controller.renameList(list.id, it) } }
            })
            container.addView(Button(this).apply {
                text = "Add item"
                contentDescription = "Add item"
                setOnClickListener { prompt("New item") { controller.createItem(list.id, it) } }
            })
        }
        displayedItems(list).forEachIndexed { index, item ->
            container.addView(Button(this).apply {
                text = if (item.marked) "✓ ${item.text}" else item.text
                contentDescription = "Edit ${item.text}"
                setOnClickListener { prompt("Edit item", item.text) { controller.editItemText(list.id, item.id, it) } }
            })
            if (editingEnabled) {
                container.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(this@SharedListsActivity).apply {
                        text = if (item.marked) "Unmark" else "Mark"
                        contentDescription = "$text ${list.name}"
                        setOnClickListener { controller.setMarked(list.id, item.id, !item.marked) }
                    })
                    addView(Button(this@SharedListsActivity).apply {
                        text = "Up"
                        contentDescription = "Move ${item.text} up"
                        isEnabled = index > 0
                        setOnClickListener { moveVisibleItem(list, item.id, -1) }
                    })
                    addView(Button(this@SharedListsActivity).apply {
                        text = "Down"
                        contentDescription = "Move ${item.text} down"
                        isEnabled = index < displayedItems(list).lastIndex
                        setOnClickListener { moveVisibleItem(list, item.id, 1) }
                    })
                    addView(Button(this@SharedListsActivity).apply {
                        text = "Delete"
                        contentDescription = "Delete ${item.text}"
                        setOnClickListener { controller.deleteItem(list.id, item.id) }
                    })
                })
            }
        }
        if (editingEnabled) container.addView(Button(this).apply {
            text = "Delete list"
            contentDescription = "Delete list"
            setOnClickListener { confirmDeleteList(list) }
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

    private fun addButtonTo(container: LinearLayout, label: String, action: () -> Unit) {
        container.addView(Button(this).apply {
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

    private fun confirmDeleteList(list: SharedList) {
        AlertDialog.Builder(this)
            .setMessage("Delete ${list.name}?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> controller.deleteList(list.id) }
            .show()
    }

    private fun displayedItems(list: SharedList) =
        list.items.filterNot { hideMarkedItems && it.marked }
            .let { items -> if (sortAlphabetically) items.sortedBy { it.text.lowercase() } else items }

    private fun itemActionButton(
        list: SharedList,
        itemId: ListItemId,
        marked: Boolean,
        label: String,
        action: () -> Unit,
    ): Button =
        Button(this).apply {
            text = if (label == "Mark" && marked) "Unmark" else label
            contentDescription = "$text ${list.name}"
            val itemIndex = displayedItems(list).indexOfFirst { it.id == itemId }
            isEnabled = when (label) {
                "Up" -> itemIndex > 0
                "Down" -> itemIndex in 0 until displayedItems(list).lastIndex
                else -> true
            }
            setOnClickListener { action() }
        }

    private fun moveVisibleItem(list: SharedList, itemId: ListItemId, direction: Int) {
        val visibleItems = displayedItems(list)
        val currentIndex = visibleItems.indexOfFirst { it.id == itemId }
        val destinationIndex = (currentIndex + direction).coerceIn(0, visibleItems.lastIndex)
        if (currentIndex < 0 || destinationIndex == currentIndex) return
        val remainingItems = visibleItems.filterNot { it.id == itemId }
        controller.moveItem(
            list.id,
            itemId,
            remainingItems.getOrNull(destinationIndex - 1)?.id,
            remainingItems.getOrNull(destinationIndex)?.id,
        )
    }

    private fun presentationFixture(selector: String?): PresentationFixture? {
        if (!BuildConfig.DEBUG) return null
        return when (selector) {
            READY_POPULATED_FIXTURE -> PresentationFixture(
                READY_POPULATED_FIXTURE,
                "Shared Lists test fixture: ready populated",
                AndroidClientPresentation(
                    canonicalState = CanonicalState(
                        listOf(
                            SharedList(
                                SharedListId.parse("00000000-0000-4000-8000-000000000024"),
                                listOf(
                                    ListItem(ListItemId.parse("00000000-0000-4000-8000-000000000025"), false, "Milk"),
                                    ListItem(ListItemId.parse("00000000-0000-4000-8000-000000000026"), true, "Bread"),
                                ),
                                "Groceries",
                            ),
                        ),
                    ),
                    configuration = AndroidServerConfiguration("127.0.0.1", 8443, "A".repeat(64)),
                    editingEnabled = true,
                    enrollment = EnrollmentState.ENROLLED,
                    status = "Live synchronization.",
                ),
            )

            CACHED_READ_ONLY_FIXTURE -> PresentationFixture(
                CACHED_READ_ONLY_FIXTURE,
                "Shared Lists test fixture: cached read only",
                AndroidClientPresentation(
                    canonicalState = CanonicalState(
                        listOf(
                            SharedList(
                                SharedListId.parse("00000000-0000-4000-8000-000000000024"),
                                listOf(ListItem(ListItemId.parse("00000000-0000-4000-8000-000000000025"), false, "Milk")),
                                "Groceries",
                            ),
                        ),
                    ),
                    configuration = AndroidServerConfiguration("127.0.0.1", 8443, "A".repeat(64)),
                    enrollment = EnrollmentState.ENROLLED,
                    status = "Cached data is read-only while synchronization recovers.",
                ),
            )

            else -> null
        }
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
        const val CACHED_READ_ONLY_FIXTURE = "cached-read-only"
        const val LOG_TAG = "SharedListsActivity"
        const val READY_POPULATED_FIXTURE = "ready-populated"
        const val TEST_PRESENTATION = "dev.sharedlists.android.TEST_PRESENTATION"
        const val WIDE_LAYOUT_MINIMUM_DP = 840
    }
}

private data class PresentationFixture(
    val selector: String,
    val marker: String,
    val presentation: AndroidClientPresentation,
)
