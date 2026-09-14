package dev.sharedlists.windows

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionListener
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class WindowsClientFrame(
    private val controller: WindowsSynchronizationController,
) : JFrame("Shared Lists") {
    private val connectButton = JButton("Connect")
    private val emptyStateLabel = JLabel()
    private val fingerprintField = JTextField(64)
    private val hostField = JTextField(18)
    private val listModel = javax.swing.DefaultListModel<String>()
    private val portField = JTextField(5)
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
        }

    private fun contentPanel(): JPanel =
        JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(6, 12, 12, 12)
            add(statusLabel, BorderLayout.NORTH)
            add(
                JScrollPane(
                    JList(listModel).apply {
                        selectionMode = ListSelectionModel.SINGLE_SELECTION
                        isEnabled = false
                    },
                ),
                BorderLayout.CENTER,
            )
            add(emptyStateLabel, BorderLayout.SOUTH)
        }

    private fun render(presentation: WindowsClientPresentation) {
        val applyPresentation = {
            presentation.configuration?.let { configuration ->
                hostField.text = configuration.host
                portField.text = configuration.port.toString()
                fingerprintField.text = configuration.certificateFingerprint
            }
            listModel.clear()
            presentation.lists.forEach(listModel::addElement)
            emptyStateLabel.text = presentation.emptyStateMessage.orEmpty()
            statusLabel.text = presentation.statusMessage.orEmpty()
            connectButton.isEnabled = true
        }
        if (SwingUtilities.isEventDispatchThread()) {
            applyPresentation()
        } else {
            SwingUtilities.invokeLater(applyPresentation)
        }
    }
}
