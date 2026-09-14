package dev.sharedlists.windows

import dev.sharedlists.client.ListItemId
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionListener
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.DefaultListModel
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class WindowsClientFrame(
    private val controller: WindowsSynchronizationController,
) : JFrame("Shared Lists") {
    private val connectButton = JButton("Connect")
    private val createListButton = JButton("Create list")
    private val createItemButton = JButton("Add item")
    private val createDeviceKeyButton = JButton("Set up device")
    private val deleteListButton = JButton("Delete list")
    private val deleteItemButton = JButton("Delete item")
    private val emptyStateLabel = JLabel()
    private val fingerprintField = JTextField(64)
    private val hostField = JTextField(18)
    private val exportDeviceKeyButton = JButton("Export public key")
    private val listModel = DefaultListModel<String>()
    private val listView = JList(listModel)
    private val itemModel = DefaultListModel<WindowsItemRow>()
    private val itemView = JList(itemModel)
    private val portField = JTextField(5)
    private val resetDeviceButton = JButton("Reset device setup")
    private val renameListButton = JButton("Rename list")
    private val editItemButton = JButton("Edit item")
    private val retryDeviceKeyButton = JButton("Retry device key")
    private val statusLabel = JLabel()

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        minimumSize = Dimension(680, 420)
        add(configurationPanel(), BorderLayout.NORTH)
        add(contentPanel(), BorderLayout.CENTER)
        connectButton.addActionListener(
            ActionListener {
                controller.connect(hostField.text, portField.text, fingerprintField.text)
            },
        )
        createListButton.addActionListener(ActionListener { createList() })
        createItemButton.addActionListener(ActionListener { createItem() })
        createDeviceKeyButton.addActionListener(
            ActionListener {
                if (
                    JOptionPane.showConfirmDialog(
                        this,
                        "Create a non-exportable Windows device key? You will export its public key for the server administrator.",
                        "Set up device",
                        JOptionPane.OK_CANCEL_OPTION,
                    ) == JOptionPane.OK_OPTION
                ) {
                    controller.createDeviceKey(hostField.text, portField.text, fingerprintField.text)
                }
            },
        )
        resetDeviceButton.addActionListener(
            ActionListener {
                if (
                    JOptionPane.showConfirmDialog(
                        this,
                        "Delete this device key and create a new identity? Cached shared-list state is retained.",
                        "Reset device setup",
                        JOptionPane.OK_CANCEL_OPTION,
                    ) == JOptionPane.OK_OPTION
                ) {
                    controller.resetDeviceSetup()
                }
            },
        )
        exportDeviceKeyButton.addActionListener(ActionListener { exportPublicKey() })
        deleteListButton.addActionListener(ActionListener { deleteSelectedList() })
        deleteItemButton.addActionListener(ActionListener { deleteSelectedItem() })
        editItemButton.addActionListener(ActionListener { editSelectedItem() })
        renameListButton.addActionListener(ActionListener { renameSelectedList() })
        retryDeviceKeyButton.addActionListener(ActionListener { controller.retryDeviceKey() })
        listView.addListSelectionListener { renderItems() }
        controller.observePresentation(::render)
        pack()
        setLocationByPlatform(true)
    }

    private fun configurationPanel(): JPanel =
        JPanel(FlowLayout(FlowLayout.LEADING)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 6, 12)
            add(JLabel("Server"))
            add(hostField)
            add(JLabel("Port"))
            add(portField)
            add(JLabel("SHA-256 fingerprint"))
            add(fingerprintField)
            add(connectButton)
            add(createDeviceKeyButton)
            add(exportDeviceKeyButton)
            add(resetDeviceButton)
            add(retryDeviceKeyButton)
            add(createListButton)
            add(createItemButton)
            add(renameListButton)
            add(deleteListButton)
            add(editItemButton)
            add(deleteItemButton)
        }

    private fun contentPanel(): JPanel =
        JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(6, 12, 12, 12)
            add(statusLabel, BorderLayout.NORTH)
            add(JPanel(BorderLayout(12, 0)).apply {
                add(
                    JScrollPane(
                        listView.apply { selectionMode = ListSelectionModel.SINGLE_SELECTION },
                    ),
                    BorderLayout.WEST,
                )
                add(
                    JScrollPane(
                        itemView.apply { selectionMode = ListSelectionModel.SINGLE_SELECTION },
                    ),
                    BorderLayout.CENTER,
                )
            }, BorderLayout.CENTER)
            add(emptyStateLabel, BorderLayout.SOUTH)
        }

    private fun render(presentation: WindowsClientPresentation) {
        val applyPresentation = {
            val shouldOpenExportDialog = presentation.exportRequired && !exportDeviceKeyButton.isVisible
            presentation.configuration?.let { configuration ->
                hostField.text = configuration.host
                portField.text = configuration.port.toString()
                fingerprintField.text = configuration.certificateFingerprint
            }
            listModel.clear()
            presentation.lists.forEach(listModel::addElement)
            renderItems()
            emptyStateLabel.text = presentation.emptyStateMessage.orEmpty()
            statusLabel.text = presentation.statusMessage.orEmpty()
            connectButton.isEnabled = !presentation.connectionActive
            createListButton.isEnabled = presentation.editingEnabled
            createItemButton.isEnabled = presentation.editingEnabled && listView.selectedValue != null
            renameListButton.isEnabled = presentation.editingEnabled && listView.selectedValue != null
            deleteListButton.isEnabled = presentation.editingEnabled && listView.selectedValue != null
            editItemButton.isEnabled = presentation.editingEnabled && itemView.selectedValue != null
            deleteItemButton.isEnabled = presentation.editingEnabled && itemView.selectedValue != null
            createDeviceKeyButton.isVisible = presentation.setupRequired
            exportDeviceKeyButton.isVisible = presentation.exportRequired
            resetDeviceButton.isVisible = !presentation.setupRequired && !presentation.unreadableDeviceKey
            retryDeviceKeyButton.isVisible = presentation.unreadableDeviceKey
            fingerprintField.isEnabled = !presentation.connectionActive
            hostField.isEnabled = !presentation.connectionActive
            portField.isEnabled = !presentation.connectionActive
            if (shouldOpenExportDialog) {
                exportPublicKey()
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            applyPresentation()
        } else {
            SwingUtilities.invokeLater(applyPresentation)
        }
    }

    private fun exportPublicKey() {
        val chooser = JFileChooser().apply {
            selectedFile = File("sharedlists-device-public-key.pem")
            dialogTitle = "Export Shared Lists device public key"
        }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            controller.exportDevicePublicKey(chooser.selectedFile)
        }
    }

    private fun createList() {
        val name = JOptionPane.showInputDialog(this, "List name", "Create shared list", JOptionPane.PLAIN_MESSAGE)
            ?: return
        controller.createList(name)
    }

    private fun createItem() {
        val listName = listView.selectedValue ?: return
        val text = JOptionPane.showInputDialog(this, "Item text", "Add item", JOptionPane.PLAIN_MESSAGE) ?: return
        controller.createItem(listName, text)
    }

    private fun deleteSelectedList() {
        val name = listView.selectedValue ?: return
        if (
            JOptionPane.showConfirmDialog(
                this,
                "Delete \"$name\" permanently?",
                "Delete shared list",
                JOptionPane.OK_CANCEL_OPTION,
            ) == JOptionPane.OK_OPTION
        ) {
            controller.deleteList(name)
        }
    }

    private fun deleteSelectedItem() {
        val listName = listView.selectedValue ?: return
        val item = itemView.selectedValue ?: return
        controller.deleteItem(listName, item.id)
    }

    private fun editSelectedItem() {
        val listName = listView.selectedValue ?: return
        val item = itemView.selectedValue ?: return
        val text = JOptionPane.showInputDialog(this, "Item text", item.text) ?: return
        controller.editItemText(listName, item.id, text)
    }

    private fun renameSelectedList() {
        val currentName = listView.selectedValue ?: return
        val name = JOptionPane.showInputDialog(this, "List name", currentName) ?: return
        controller.renameList(currentName, name)
    }

    private fun renderItems() {
        itemModel.clear()
        listView.selectedValue?.let { listName ->
            controller.items(listName).forEach { item ->
                itemModel.addElement(WindowsItemRow(item.id, item.text))
            }
        }
    }

    private data class WindowsItemRow(
        val id: ListItemId,
        val text: String,
    ) {
        override fun toString(): String = text
    }
}
