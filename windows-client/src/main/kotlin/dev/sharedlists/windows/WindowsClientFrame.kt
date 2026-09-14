package dev.sharedlists.windows

import dev.sharedlists.client.ListItem
import dev.sharedlists.client.ListItemId
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.ActionListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
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
    private val cardsLayout = CardLayout()
    private val cardsPanel = JPanel(cardsLayout)
    private val connectButton = JButton("Connect")
    private val createListButton = JButton("Create list")
    private val createItemButton = JButton("Add item")
    private val createDeviceKeyButton = JButton("Set up device")
    private val deleteListButton = JButton("Delete list")
    private val deleteItemButton = JButton("Delete item")
    private val hideMarkedCheckBox = JCheckBox("Hide marked")
    private val emptyStateLabel = JLabel()
    private val fingerprintField = JTextField(64)
    private val hostField = JTextField(18)
    private val exportDeviceKeyButton = JButton("Export public key")
    private val listModel = DefaultListModel<WindowsListRow>()
    private val listView = JList(listModel)
    private val markedCheckBox = JCheckBox("Marked")
    private val narrowCardModel = DefaultListModel<WindowsListCard>()
    private val narrowCardView = JList(narrowCardModel)
    private val itemModel = DefaultListModel<WindowsItemRow>()
    private val itemTextField = JTextField()
    private val itemView = JList(itemModel)
    private val itemReorderGesture = WindowsItemReorderGesture()
    private val moveDownButton = JButton("Move down")
    private val moveUpButton = JButton("Move up")
    private val portField = JTextField(5)
    private val quickAddButton = JButton("Quick add")
    private val quickMarkButton = JButton("Quick mark")
    private val resetDeviceButton = JButton("Reset device setup")
    private val renameListButton = JButton("Rename list")
    private val editItemButton = JButton("Edit item")
    private val retryDeviceKeyButton = JButton("Retry device key")
    private val retrySynchronizationButton = JButton("Retry now")
    private val statusLabel = JLabel()
    private val takeOverSynchronizationButton = JButton("Take over syncing")
    private val alphabeticalSortCheckBox = JCheckBox("Sort A–Z")

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
        quickAddButton.addActionListener(ActionListener { quickAddItem() })
        quickMarkButton.addActionListener(ActionListener { quickMarkItem() })
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
        markedCheckBox.addActionListener(ActionListener { setSelectedItemMarked() })
        hideMarkedCheckBox.addActionListener(ActionListener { controller.setHideMarked(hideMarkedCheckBox.isSelected) })
        moveDownButton.addActionListener(ActionListener { moveSelectedItem(1) })
        moveUpButton.addActionListener(ActionListener { moveSelectedItem(-1) })
        renameListButton.addActionListener(ActionListener { renameSelectedList() })
        alphabeticalSortCheckBox.addActionListener(
            ActionListener { controller.setAlphabeticalSort(alphabeticalSortCheckBox.isSelected) },
        )
        retryDeviceKeyButton.addActionListener(ActionListener { controller.retryDeviceKey() })
        retrySynchronizationButton.addActionListener(ActionListener { controller.retryNow() })
        takeOverSynchronizationButton.addActionListener(ActionListener { controller.takeOverSynchronization() })
        listView.addListSelectionListener { renderItems() }
        narrowCardView.addListSelectionListener { selectNarrowCard() }
        itemView.addListSelectionListener {
            itemTextField.text = itemView.selectedValue?.text.orEmpty()
            markedCheckBox.isSelected = itemView.selectedValue?.marked ?: false
            updateReorderButtonState()
        }
        itemView.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (controller.presentation().reorderingEnabled) {
                        itemReorderGesture.begin(itemView, event.point)
                    } else {
                        itemReorderGesture.cancel()
                    }
                }

                override fun mouseReleased(event: MouseEvent) {
                    itemReorderGesture.finish(itemView, event.point)?.let { (sourceIndex, destinationIndex) ->
                        moveSelectedItem(sourceIndex, destinationIndex)
                    }
                }
            },
        )
        controller.observePresentation(::render)
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    renderLayout()
                }
            },
        )
        addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) {
                    controller.onBackground()
                }

                override fun windowOpened(event: WindowEvent) {
                    controller.onForeground()
                }

                override fun windowDeiconified(event: WindowEvent) {
                    controller.onForeground()
                }

                override fun windowIconified(event: WindowEvent) {
                    controller.onBackground()
                }
            },
        )
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
            add(retrySynchronizationButton)
            add(takeOverSynchronizationButton)
            add(createListButton)
            add(createItemButton)
            add(renameListButton)
            add(deleteListButton)
            add(editItemButton)
            add(deleteItemButton)
            add(moveUpButton)
            add(moveDownButton)
            add(alphabeticalSortCheckBox)
            add(hideMarkedCheckBox)
        }

    private fun contentPanel(): JPanel =
        JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(6, 12, 12, 12)
            add(statusLabel, BorderLayout.NORTH)
            add(JPanel(BorderLayout(12, 0)).apply {
                cardsPanel.add(
                    JScrollPane(narrowCardView.apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }),
                    WindowsLayoutMode.NARROW.cardName,
                )
                cardsPanel.add(
                    JPanel(BorderLayout(12, 0)).apply {
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
                    },
                    WindowsLayoutMode.WIDE.cardName,
                )
                add(cardsPanel, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout(0, 6)).apply {
                add(itemTextField, BorderLayout.NORTH)
                add(emptyStateLabel, BorderLayout.SOUTH)
                add(markedCheckBox, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.LEADING)).apply {
                    add(quickAddButton)
                    add(quickMarkButton)
                }, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
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
            presentation.sharedLists.forEach(listModel::addElement)
            narrowCardModel.clear()
            presentation.cards.forEach(narrowCardModel::addElement)
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
            itemTextField.isEnabled = presentation.editingEnabled && itemView.selectedValue != null
            markedCheckBox.isEnabled = presentation.editingEnabled && itemView.selectedValue != null
            quickAddButton.isEnabled = presentation.editingEnabled && narrowCardView.selectedValue != null
            quickMarkButton.isEnabled =
                presentation.editingEnabled && narrowCardView.selectedValue?.unmarkedItems?.isNotEmpty() == true
            alphabeticalSortCheckBox.isSelected = presentation.alphabeticalSort
            hideMarkedCheckBox.isSelected = presentation.hideMarked
            alphabeticalSortCheckBox.isEnabled = presentation.editingEnabled
            hideMarkedCheckBox.isEnabled = presentation.editingEnabled
            updateReorderButtonState()
            createDeviceKeyButton.isVisible = presentation.setupRequired
            exportDeviceKeyButton.isVisible = presentation.exportRequired
            resetDeviceButton.isVisible = !presentation.setupRequired && !presentation.unreadableDeviceKey
            retryDeviceKeyButton.isVisible = presentation.unreadableDeviceKey
            retrySynchronizationButton.isVisible = presentation.retryAvailable
            takeOverSynchronizationButton.isVisible = presentation.takeoverAvailable
            fingerprintField.isEnabled = !presentation.connectionActive
            hostField.isEnabled = !presentation.connectionActive
            portField.isEnabled = !presentation.connectionActive
            if (shouldOpenExportDialog) {
                exportPublicKey()
            }
            renderLayout()
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
        val list = listView.selectedValue ?: return
        val text = JOptionPane.showInputDialog(this, "Item text", "Add item", JOptionPane.PLAIN_MESSAGE) ?: return
        controller.createItem(list.id, text)
    }

    private fun quickAddItem() {
        val card = narrowCardView.selectedValue ?: return
        val text = JOptionPane.showInputDialog(this, "Item text", "Quick add item", JOptionPane.PLAIN_MESSAGE) ?: return
        controller.createItem(card.id, text)
    }

    private fun quickMarkItem() {
        val card = narrowCardView.selectedValue ?: return
        val item = card.unmarkedItems.firstOrNull() ?: return
        controller.setItemMarked(card.id, item.id, true)
    }

    private fun deleteSelectedList() {
        val list = listView.selectedValue ?: return
        if (
            JOptionPane.showConfirmDialog(
                this,
                "Delete \"${list.name}\" permanently?",
                "Delete shared list",
                JOptionPane.OK_CANCEL_OPTION,
            ) == JOptionPane.OK_OPTION
        ) {
            controller.deleteList(list.name)
        }
    }

    private fun deleteSelectedItem() {
        val list = listView.selectedValue ?: return
        val item = itemView.selectedValue ?: return
        controller.deleteItem(list.id, item.id)
    }

    private fun editSelectedItem() {
        val list = listView.selectedValue ?: return
        val item = itemView.selectedValue ?: return
        controller.editItemText(list.id, item.id, itemTextField.text)
    }

    private fun renderItems() {
        itemModel.clear()
        itemTextField.text = ""
        markedCheckBox.isSelected = false
        listView.selectedValue?.let { list ->
            controller.items(list.id).forEach { item ->
                itemModel.addElement(WindowsItemRow(item.id, item.marked, item.text))
            }
        }
    }

    private fun renderLayout() {
        cardsLayout.show(cardsPanel, WindowsLayoutMode.forWidth(width).cardName)
    }

    private fun moveSelectedItem(offset: Int) {
        val list = listView.selectedValue ?: return
        val item = itemView.selectedValue ?: return
        controller.moveItem(list.id, item.id, itemView.selectedIndex + offset)
    }

    private fun moveSelectedItem(sourceIndex: Int, destinationIndex: Int) {
        val list = listView.selectedValue ?: return
        val item = itemModel.getElementAt(sourceIndex)
        controller.moveItem(list.id, item.id, destinationIndex)
    }

    private fun updateReorderButtonState() {
        val reorderingEnabled = controller.presentation().reorderingEnabled
        moveUpButton.isEnabled = reorderingEnabled && itemView.selectedIndex > 0
        moveDownButton.isEnabled = reorderingEnabled && itemView.selectedIndex in 0 until itemModel.size - 1
    }

    private fun renameSelectedList() {
        val currentName = listView.selectedValue?.name ?: return
        val name = JOptionPane.showInputDialog(this, "List name", currentName) ?: return
        controller.renameList(currentName, name)
    }

    private fun selectNarrowCard() {
        val card = narrowCardView.selectedValue ?: return
        listView.setSelectedValue(WindowsListRow(card.id, card.name), true)
    }

    private fun setSelectedItemMarked() {
        val list = listView.selectedValue ?: return
        val item = itemView.selectedValue ?: return
        controller.setItemMarked(list.id, item.id, markedCheckBox.isSelected)
    }

    private data class WindowsItemRow(
        val id: ListItemId,
        val marked: Boolean,
        val text: String,
    ) {
        override fun toString(): String = if (marked) "[marked] $text" else text
    }
}

enum class WindowsLayoutMode(
    val cardName: String,
) {
    NARROW("narrow"),
    WIDE("wide");

    companion object {
        private const val WIDE_BREAKPOINT = 840

        fun forWidth(width: Int): WindowsLayoutMode =
            if (width >= WIDE_BREAKPOINT) WIDE else NARROW
    }
}

internal class WindowsItemReorderGesture {
    private var sourceIndex = -1

    fun begin(itemView: JList<*>, point: Point) {
        sourceIndex = itemIndexAt(itemView, point)
    }

    fun finish(itemView: JList<*>, point: Point): Pair<Int, Int>? {
        val destinationIndex = itemIndexAt(itemView, point)
        val result = if (sourceIndex >= 0 && destinationIndex >= 0 && sourceIndex != destinationIndex) {
            sourceIndex to destinationIndex
        } else {
            null
        }
        sourceIndex = -1
        return result
    }

    fun cancel() {
        sourceIndex = -1
    }

    private fun itemIndexAt(itemView: JList<*>, point: Point): Int =
        itemView.locationToIndex(point).takeIf { index ->
            index >= 0 && itemView.getCellBounds(index, index)?.contains(point) == true
        } ?: -1
}
