package com.antigravity.localization

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

data class TranslationResult(val file: VirtualFile, val key: String, val original: String, var translated: String, val ratio: Float)

class ReviewDialog(private val context: List<TranslationResult>) : DialogWrapper(true) {

    private val tableModel = object : DefaultTableModel(arrayOf("File", "Key", "Original", "Translated", "Ratio"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean {
            return column == 3 // Only Translated column is editable
        }

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return String::class.java
        }
    }

    private val table = JBTable(tableModel)

    init {
        title = "Review Translations"
        
        for (res in context) {
            tableModel.addRow(arrayOf(res.file.name, res.key, res.original, res.translated, String.format("%.2f", res.ratio)))
        }

        // Adjust column widths
        table.columnModel.getColumn(0).preferredWidth = 100
        table.columnModel.getColumn(1).preferredWidth = 150
        table.columnModel.getColumn(2).preferredWidth = 200
        table.columnModel.getColumn(3).preferredWidth = 200
        table.columnModel.getColumn(4).preferredWidth = 50

        // Set custom renderer for warning
        table.setDefaultRenderer(Object::class.java, LengthWarningRenderer())

        init()
    }

    override fun createCenterPanel(): JComponent {
        val scrollPane = JBScrollPane(table)
        scrollPane.preferredSize = Dimension(800, 500)
        return scrollPane
    }

    fun getConfirmedTranslations(): List<TranslationResult> {
        val list = mutableListOf<TranslationResult>()
        // We assume the rows in table correspond to 'context' if not sorted.
        // If sorted, we need to be careful. 
        // Best approach: Use the original 'context' list and update the 'translated' value from the table map.
        // We key by (File + Key).
        
        // Let's build a map from the table data for O(1) lookup
        // Key: fileName + "::" + keyName
        val tableData = mutableMapOf<String, String>()
        for (i in 0 until tableModel.rowCount) {
            val fileName = tableModel.getValueAt(i, 0) as String
            val key = tableModel.getValueAt(i, 1) as String
            val translated = tableModel.getValueAt(i, 3) as String
            tableData["$fileName::$key"] = translated
        }

        for (res in context) {
            val uniqueKey = "${res.file.name}::${res.key}"
            val newTranslation = tableData[uniqueKey]
            if (newTranslation != null) {
                list.add(res.copy(translated = newTranslation))
            } else {
                // Should not happen if rows match, but safe fallback
                list.add(res)
            }
        }
        return list
    }

    private class LengthWarningRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            
            if (table != null) {
                try {
                    // Ratio is at column 4
                    val ratioStr = table.getValueAt(row, 4) as? String
                    if (ratioStr != null) {
                        val ratio = ratioStr.toFloatOrNull() ?: 0f
                        
                        // Heuristic: If translation is > 1.5x longer than original
                        if (ratio > 1.5) {
                            if (!isSelected) {
                                c.background = Color(255, 200, 200) // Light red background warning
                            }
                            toolTipText = "Translation is significantly longer than original (> 150%)"
                        } else {
                            if (!isSelected) {
                                c.background = table.background
                            }
                            toolTipText = null
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
