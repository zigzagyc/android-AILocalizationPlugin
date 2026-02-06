package com.antigravity.localization

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class FileSelectionDialog(private val files: List<VirtualFile>) : DialogWrapper(true) {
    private val fileList = JBList(files.map { it.path })

    init {
        title = "Select Files to Translate"
        fileList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        fileList.selectedIndices = files.indices.toList().toIntArray() // Select all by default
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(JLabel("Found multiple string files. Select ones to translate:"), BorderLayout.NORTH)
        val scrollPane = JBScrollPane(fileList)
        scrollPane.preferredSize = Dimension(600, 300)
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    fun getSelectedFiles(): List<VirtualFile> {
        val indices = fileList.selectedIndices
        return indices.map { files[it] }
    }
}
