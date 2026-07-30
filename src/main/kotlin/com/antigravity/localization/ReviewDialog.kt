package com.antigravity.localization

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

data class TranslationResult(
    val file: VirtualFile,
    val key: String,
    val original: String,
    var translated: String,
    val ratio: Float,
    val suggestion: String = "",
    val warningTitle: String = "",
    val warningDetail: String = "",
    val existingTranslation: String? = null,
    val status: String = "New",
    var useExisting: Boolean = false,
    val layoutAdaptabilitySuggestion: String = ""
)

class ReviewDialog(
    private val context: List<TranslationResult>,
    private val project: Project? = null,
    private val targetLang: String = "target",
    private val referencedLayoutFiles: List<VirtualFile> = emptyList()
) : DialogWrapper(true) {

    private val tableModel = object : DefaultTableModel(
        arrayOf("File", "Key", "Original", "Existing", "New Translation", "Keep Existing?", "Status", "Text Suggestion", "Layout Adaptability Suggestion"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean {
            return column == 4 || column == 5 || column == 7 || column == 8
        }

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return if (columnIndex == 5) java.lang.Boolean::class.java else String::class.java
        }
    }

    private val table = JBTable(tableModel)

    init {
        title = "Review & Select Translations"
        
        for (res in context) {
            val existingDisplay = res.existingTranslation ?: "-"
            tableModel.addRow(arrayOf(
                res.file.name,
                res.key,
                res.original,
                existingDisplay,
                res.translated,
                res.useExisting,
                res.status,
                res.suggestion,
                res.layoutAdaptabilitySuggestion
            ))
        }

        // Adjust column widths
        table.columnModel.getColumn(0).preferredWidth = 90
        table.columnModel.getColumn(1).preferredWidth = 120
        table.columnModel.getColumn(2).preferredWidth = 150
        table.columnModel.getColumn(3).preferredWidth = 150
        table.columnModel.getColumn(4).preferredWidth = 150
        table.columnModel.getColumn(5).preferredWidth = 90
        table.columnModel.getColumn(6).preferredWidth = 80
        table.columnModel.getColumn(7).preferredWidth = 130
        table.columnModel.getColumn(8).preferredWidth = 180

        // Set custom renderer for warning/status coloring
        table.setDefaultRenderer(Object::class.java, LengthWarningRenderer())

        init()
    }

    override fun createCenterPanel(): JComponent {
        val scrollPane = JBScrollPane(table)
        scrollPane.preferredSize = Dimension(1100, 550)
        return scrollPane
    }

    override fun createSouthAdditionalPanel(): JPanel? {
        val proj = project ?: return null
        val panel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        val captureBtn = javax.swing.JButton("Capture Layout Screenshots ($targetLang)")
        captureBtn.addActionListener {
            val currentConfirmed = getConfirmedTranslations().associate { it.key to it.translated }
            val filesToCapture = if (referencedLayoutFiles.isNotEmpty()) {
                referencedLayoutFiles
            } else {
                findProjectLayoutFiles(proj)
            }

            if (filesToCapture.isEmpty()) {
                Messages.showInfoMessage(proj, "No layout XML files found to preview.", "Info")
                return@addActionListener
            }

            val saved = LayoutPreviewRenderer.captureLayoutScreenshots(proj, filesToCapture, targetLang, currentConfirmed)
            if (saved.isNotEmpty()) {
                val msg = "Captured ${saved.size} layout screenshots in project directory:\nscreenshots/$targetLang/\n\nFiles:\n" + saved.joinToString("\n") { it.name }
                Messages.showInfoMessage(proj, msg, "Screenshots Captured")
            } else {
                Messages.showWarningDialog(proj, "Failed to capture layout screenshots.", "Warning")
            }
        }
        panel.add(captureBtn)
        return panel
    }

    private fun findProjectLayoutFiles(proj: Project): List<VirtualFile> {
        val list = mutableListOf<VirtualFile>()
        val basePath = proj.basePath ?: return list
        val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(basePath) ?: return list
        
        fun search(file: VirtualFile) {
            if (file.isDirectory) {
                for (child in file.children) search(child)
            } else if (file.extension == "xml" && file.parent?.name?.startsWith("layout") == true) {
                list.add(file)
            }
        }
        search(baseDir)
        return list
    }

    fun getConfirmedTranslations(): List<TranslationResult> {
        val list = mutableListOf<TranslationResult>()
        
        val tableData = mutableMapOf<String, Pair<String, Boolean>>()
        for (i in 0 until tableModel.rowCount) {
            val fileName = tableModel.getValueAt(i, 0) as String
            val key = tableModel.getValueAt(i, 1) as String
            val newTranslation = tableModel.getValueAt(i, 4) as String
            val keepExisting = tableModel.getValueAt(i, 5) as? Boolean ?: false
            tableData["$fileName::$key"] = Pair(newTranslation, keepExisting)
        }

        for (res in context) {
            val uniqueKey = "${res.file.name}::${res.key}"
            val pair = tableData[uniqueKey]
            if (pair != null) {
                val (editedNewTranslation, keepExisting) = pair
                val finalTranslation = if (keepExisting && res.existingTranslation != null) {
                    res.existingTranslation
                } else {
                    editedNewTranslation
                }
                list.add(res.copy(translated = finalTranslation, useExisting = keepExisting))
            } else {
                list.add(res)
            }
        }
        return list
    }

    private inner class LengthWarningRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            
            if (table != null) {
                try {
                    val key = table.getValueAt(row, 1) as? String
                    val res = context.find { it.key == key }
                    val status = table.getValueAt(row, 6) as? String ?: ""
                    val warningTitle = res?.warningTitle ?: ""
                    
                    if (res != null) {
                        val tooltips = mutableListOf<String>()
                        if (res.warningDetail.isNotBlank()) tooltips.add("Length Warning: ${res.warningDetail}")
                        if (res.layoutAdaptabilitySuggestion.isNotBlank()) tooltips.add("Layout Suggestion: ${res.layoutAdaptabilitySuggestion}")
                        if (tooltips.isNotEmpty()) {
                            toolTipText = tooltips.joinToString(" | ")
                        } else {
                            toolTipText = null
                        }

                        if (!isSelected) {
                            when {
                                warningTitle.isNotBlank() || res.layoutAdaptabilitySuggestion.isNotBlank() -> {
                                    c.background = Color(255, 200, 200) // Light Red for Length/Layout warning
                                }
                                status == "Missing" -> {
                                    c.background = Color(255, 235, 180) // Soft Orange for Missing key
                                }
                                status == "Untranslated" -> {
                                    c.background = Color(255, 255, 190) // Soft Yellow for Untranslated item
                                }
                                res.ratio > 1.5 -> {
                                    c.background = Color(255, 230, 200) // Heuristic long string
                                }
                                else -> {
                                    c.background = table.background
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore render errors
                }
            }
            return c
        }
    }
}
