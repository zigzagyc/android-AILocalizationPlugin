package com.antigravity.localization

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.table.JBTable
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.table.DefaultTableModel
import com.intellij.openapi.project.Project
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.UIUtil
import javax.swing.table.TableRowSorter
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import java.awt.FlowLayout
import javax.swing.JButton

class TranslationDialog(private val project: Project, private val stringMap: Map<String, String>) : DialogWrapper(true) {
    val serviceComboBox = ComboBox(arrayOf("OpenAI (ChatGPT)", "Gemini", "Grok (xAI)", "Google Translate", "Microsoft Translator", "DeepL", "AWS Translate"))
    val apiKeyField = JPasswordField()
    val regionComboBox = ComboBox(arrayOf<String>())
    val regionLabel = JBLabel("Region:")
    val modelComboBox = ComboBox(arrayOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo"))
    val modelLabel = JBLabel("Model:")
    val statsLabel = JBLabel("Selected: 0 strings (0 chars)")
    
    val searchField = JBTextField()
    val selectAllButton = JButton("Select All")
    val selectNoneButton = JButton("Select None")
    val selectUsedButton = JButton("Select Used Only")

    val apiKeyHelpLabel = JBLabel().apply { 
        setCopyable(true)
        font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        foreground = UIUtil.getContextHelpForeground()
    }
    
    // Comprehensive language list (Common + Major)
    private val languages = mapOf(
        "Arabic (ar)" to "ar",
        "Chinese Simplified (zh-CN)" to "zh-CN",
        "Chinese Traditional (zh-TW)" to "zh-TW",
        "Dutch (nl)" to "nl",
        "English (en)" to "en",
        "French (fr)" to "fr",
        "German (de)" to "de",
        "Hindi (hi)" to "hi",
        "Indonesian (in)" to "in",
        "Italian (it)" to "it",
        "Japanese (ja)" to "ja",
        "Korean (ko)" to "ko",
        "Portuguese (pt)" to "pt",
        "Russian (ru)" to "ru",
        "Spanish (es)" to "es",
        "Thai (th)" to "th",
        "Turkish (tr)" to "tr",
        "Vietnamese (vi)" to "vi"
    )
    
    val targetLangComboBox = ComboBox(languages.keys.sorted().toTypedArray())
    val contextField = JBTextField()
    
    // Table for string selection
    private val tableModel = object : DefaultTableModel(arrayOf("Select", "Key", "Value"), 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> {
            return if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
        }
        override fun isCellEditable(row: Int, column: Int): Boolean {
            return column == 0 // Only checkbox is editable
        }
    }
    val stringTable = JBTable(tableModel)
    private val rowSorter = TableRowSorter(tableModel)

    private val properties = com.intellij.ide.util.PropertiesComponent.getInstance()
    private val KEY_PREFIX = "com.antigravity.localization.key."
    private val REGION_PREFIX = "com.antigravity.localization.region."
    private val MODEL_PREFIX = "com.antigravity.localization.model."

    init {
        title = "Translate Android Strings"
        
        // Populate table
        for ((key, value) in stringMap) {
            tableModel.addRow(arrayOf(true, key, value)) // Default selected
        }
        
        stringTable.rowSorter = rowSorter
        
        stringTable.columnModel.getColumn(0).maxWidth = 50
        stringTable.columnModel.getColumn(0).preferredWidth = 50
        stringTable.columnModel.getColumn(1).preferredWidth = 150
        stringTable.columnModel.getColumn(2).preferredWidth = 250
        
        regionComboBox.isEditable = true // Allow custom regions
        modelComboBox.isEditable = true
        
        // Update help text and load key on selection
        serviceComboBox.addItemListener { e ->
            if (e.stateChange == ItemEvent.DESELECTED) {
                 // Save key/region for previous service
                 val prevService = e.item as String
                 val prevKey = String(apiKeyField.password)
                 val prevRegion = regionComboBox.item as? String ?: ""
                 saveKey(prevService, prevKey)
                 saveRegion(prevService, prevRegion)
            }
            if (e.stateChange == ItemEvent.SELECTED) {
                updateUIForService()
                val newService = e.item as String
                apiKeyField.text = loadKey(newService)
                // Load region
                val savedRegion = loadRegion(newService)
                if (savedRegion.isNotEmpty()) {
                    regionComboBox.item = savedRegion
                }
                // Load model if applicable
                if (newService == "OpenAI (ChatGPT)") {
                    val savedModel = properties.getValue(MODEL_PREFIX + "OpenAI", "gpt-4o")
                    modelComboBox.item = savedModel
                }
                updateStats()
            }
        }
        
        modelComboBox.addItemListener {
            if (it.stateChange == ItemEvent.SELECTED) {
                updateStats() // cost changes with model
            }
        }
        
        // Table listener for stats
        tableModel.addTableModelListener { updateStats() }
        
        // Search Field Listener
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = filter()
            override fun removeUpdate(e: DocumentEvent?) = filter()
            override fun changedUpdate(e: DocumentEvent?) = filter()
        })
        
        // Button Actions
        selectAllButton.addActionListener {
            setSelectionForVisibleRows(true)
        }
        selectNoneButton.addActionListener {
            setSelectionForVisibleRows(false)
        }
        selectUsedButton.addActionListener {
            scanAndSelectUsedKeys()
        }
        
        // Spacebar to toggle selection
        stringTable.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_SPACE) {
                    val selectedRows = stringTable.selectedRows
                    if (selectedRows.isNotEmpty()) {
                        // Toggle based on the first selected row's state (inverted)
                        val firstModelIndex = stringTable.convertRowIndexToModel(selectedRows[0])
                        val currentState = tableModel.getValueAt(firstModelIndex, 0) as Boolean
                        val newState = !currentState
                        
                        // Snapshot indices to avoid sort interference
                        val modelIndices = IntArray(selectedRows.size)
                        for (i in selectedRows.indices) {
                            modelIndices[i] = stringTable.convertRowIndexToModel(selectedRows[i])
                        }

                        for (modelIndex in modelIndices) {
                            tableModel.setValueAt(newState, modelIndex, 0)
                        }
                        e.consume()
                        stringTable.repaint()
                    }
                }
            }
        })
        
        // Initial state
        updateUIForService()
        val initialService = serviceComboBox.item
        apiKeyField.text = loadKey(initialService)
        val initialRegion = loadRegion(initialService)
        if (initialRegion.isNotEmpty()) {
            regionComboBox.item = initialRegion
        }
        if (initialService == "OpenAI (ChatGPT)") {
             val savedModel = properties.getValue(MODEL_PREFIX + "OpenAI", "gpt-4o")
             modelComboBox.item = savedModel
        }
        updateStats()

        init()
    }
    
    private fun filter() {
        val text = searchField.text
        if (text.isNullOrEmpty()) {
            rowSorter.rowFilter = null
        } else {
            try {
                // Try to use regex if it looks like one or generic text search
                // (?i) for case insensitivity
                // Use a safe regex or literal match
                // If the user wants wildcards like *, transform them to .* for regex
                val regexPattern = if (text.contains("*") || text.contains("?")) {
                    // Simple wildcard to regex conversion
                    text.replace(".", "\\.").replace("*", ".*").replace("?", ".")
                } else {
                    // Regular generic substring match (quoted to avoid regex syntax errors if not intended)
                    java.util.regex.Pattern.quote(text)
                }
                
                // If it fails to compile (e.g. invalid regex if user typed it), fallback purely to text contains
                try {
                     rowSorter.rowFilter = RowFilter.regexFilter("(?i)$regexPattern", 1, 2) // Searching Key (1) and Value (2)
                } catch (e: Exception) {
                     // Fallback to simple literal contains via regex quote which we did above, 
                     // but if user typed explicitly broken regex we catch it here.
                     // Just try simple contains
                     rowSorter.rowFilter = RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text), 1, 2)
                }
            } catch (e: Exception) {
                rowSorter.rowFilter = null
            }
        }
        updateStats()
    }

    private fun setSelectionForVisibleRows(selected: Boolean) {
        // Snapshot model indices first to prevent sort interference during updates
        val rowCount = stringTable.rowCount
        val modelIndices = IntArray(rowCount)
        for (i in 0 until rowCount) {
            modelIndices[i] = stringTable.convertRowIndexToModel(i)
        }
        
        for (modelIndex in modelIndices) {
            tableModel.setValueAt(selected, modelIndex, 0)
        }
    }
    
    override fun doOKAction() {
        val currentService = serviceComboBox.item
        val currentKey = String(apiKeyField.password)
        val currentRegion = regionComboBox.item as? String ?: ""
        saveKey(currentService, currentKey)
        saveRegion(currentService, currentRegion)
        
        if (currentService == "OpenAI (ChatGPT)") {
            properties.setValue(MODEL_PREFIX + "OpenAI", modelComboBox.item as String)
        }
        super.doOKAction()
    }
    
    fun getCombinedApiKey(): String {
        val rawKey = String(apiKeyField.password)
        val service = serviceComboBox.item
        val region = regionComboBox.item as? String
        
        if (!region.isNullOrBlank()) {
             if (service == "Microsoft Translator") {
                 // Expecting KEY:REGION 
                 // If user already typed KEY:REGION, don't double append
                 if (rawKey.contains(":")) return rawKey // Assume user knows what they are doing or legacy
                 return "$rawKey:$region"
             }
             if (service == "AWS Translate") {
                 // Expecting KEY:SECRET:REGION
                 // If user typed KEY:SECRET, append region
                 // If user typed KEY:SECRET:REGION, leave it
                 val parts = rawKey.split(":")
                 if (parts.size == 3) return rawKey
                 if (parts.size == 2) return "$rawKey:$region"
             }
        }
        return rawKey
    }

    private fun saveKey(service: String, key: String) {
        if (key.isNotBlank()) {
            properties.setValue(KEY_PREFIX + service, key)
        }
    }

    private fun loadKey(service: String): String {
        return properties.getValue(KEY_PREFIX + service, "")
    }

    private fun saveRegion(service: String, region: String) {
        if (region.isNotBlank()) {
            properties.setValue(REGION_PREFIX + service, region)
        }
    }

    private fun updateStats() {
        var count = 0
        var chars = 0
        
        for (i in 0 until tableModel.rowCount) {
            if (tableModel.getValueAt(i, 0) as Boolean) {
                count++
                val value = tableModel.getValueAt(i, 2) as String
                chars += value.length
            }
        }
        
        val service = serviceComboBox.item
        var costText = ""
        
        if (service == "OpenAI (ChatGPT)") {
            val model = modelComboBox.item as String
            // Estimate tokens ~ chars / 3.5 roughly
            val estimatedTokens = chars / 3.5 
            
            val pricePerMillion = when {
                model.contains("mini") -> 0.15
                model.startsWith("gpt-4o") -> 5.00 // gpt-4o
                model.startsWith("gpt-4-turbo") -> 10.00
                model.startsWith("gpt-3.5") -> 0.50
                else -> 0.0
            }
            
            if (pricePerMillion > 0) {
                val cost = (estimatedTokens / 1_000_000.0) * pricePerMillion
                costText = String.format(" | Est. Cost: $%.6f", cost)
            } else {
                 costText = " | Cost: Unknown Model"
            }
        }
        
        statsLabel.text = "Selected: $count strings ($chars chars)$costText"
    }

    private fun loadRegion(service: String): String {
        return properties.getValue(REGION_PREFIX + service, "")
    }

    private fun updateUIForService() {
        val service = serviceComboBox.item
        updateApiHelp()
        
        // Update Region Visibility and Options
        val isMs = service == "Microsoft Translator"
        val isAws = service == "AWS Translate"
        
        regionLabel.isVisible = isMs || isAws
        regionComboBox.isVisible = isMs || isAws
        
        regionComboBox.removeAllItems()
        if (isMs) {
            val msRegions = arrayOf("global", "eastus", "eastus2", "westus", "westeurope", "northeurope", "southeastasia", "eastasia")
            for (r in msRegions) regionComboBox.addItem(r)
        } else if (isAws) {
             val awsRegions = arrayOf("us-east-1", "us-east-2", "us-west-1", "us-west-2", "eu-west-1", "eu-central-1", "ap-southeast-1", "ap-northeast-1")
             for (r in awsRegions) regionComboBox.addItem(r)
        }
    }

    private fun updateApiHelp() {
        val helpText = when (serviceComboBox.item) {
            "OpenAI (ChatGPT)" -> "Get API Key: https://platform.openai.com/api-keys"
            "Gemini" -> "Get API Key: https://aistudio.google.com/app/apikey"
            "Grok (xAI)" -> "Get API Key: https://console.x.ai/"
            "Google Translate" -> "Get API Key: https://console.cloud.google.com/apis/credentials (Enable Cloud Translation API)"
            "Microsoft Translator" -> "Get API Key: Azure Portal -> Translator -> Keys (Select Region below or use 'global')."
            "DeepL" -> "Get API Key: https://www.deepl.com/pro-api. (:fx suffix for Free API)"
            "AWS Translate" -> "Format: ACCESS_KEY:SECRET_KEY (Select Region below)."
            else -> ""
        }
        apiKeyHelpLabel.text = "<html>$helpText</html>"
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints()
        c.fill = GridBagConstraints.HORIZONTAL
        c.insets = Insets(5, 5, 5, 5)

        // Service Selection
        c.gridx = 0
        c.gridy = 0
        panel.add(JBLabel("Translation Service:"), c)
        c.gridx = 1
        c.weightx = 1.0
        panel.add(serviceComboBox, c)

        // API Key
        c.gridx = 0
        c.gridy = 1
        c.weightx = 0.0
        panel.add(JBLabel("API Key:"), c)
        c.gridx = 1
        c.weightx = 1.0
        panel.add(apiKeyField, c)
        
        // Region
        c.gridx = 0
        c.gridy = 2
        c.weightx = 0.0
        panel.add(regionLabel, c)
        c.gridx = 1
        c.weightx = 1.0
        panel.add(regionComboBox, c)

        // API Key Help
        c.gridx = 1
        c.gridy = 3
        c.weightx = 1.0
        c.insets = Insets(0, 5, 5, 5) // Less top padding
        panel.add(apiKeyHelpLabel, c)
        c.insets = Insets(5, 5, 5, 5) // Reset padding

        // Target Language
        c.gridx = 0
        c.gridy = 4
        c.weightx = 0.0
        panel.add(JBLabel("Target Language:"), c)
        c.gridx = 1
        c.weightx = 1.0
        panel.add(targetLangComboBox, c)

        // Context / Glossary
        c.gridx = 0
        c.gridy = 5
        c.weightx = 0.0
        panel.add(JBLabel("Context / Glossary (Optional):"), c)
        c.gridx = 1
        c.weightx = 1.0
        panel.add(contextField, c)
        
        // Search and Buttons Panel
        val actionPanel = JPanel(BorderLayout())
        val searchPanel = JPanel(BorderLayout())
        searchPanel.add(JBLabel("Search/Filter: "), BorderLayout.WEST)
        searchPanel.add(searchField, BorderLayout.CENTER)
        
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        buttonPanel.add(selectAllButton)
        buttonPanel.add(selectNoneButton)
        buttonPanel.add(selectUsedButton)
        
        actionPanel.add(searchPanel, BorderLayout.NORTH)
        actionPanel.add(buttonPanel, BorderLayout.CENTER)
        
        c.gridx = 0
        c.gridy = 6
        c.gridwidth = 2
        panel.add(actionPanel, c)

        // String Selection Table
        c.gridx = 0
        c.gridy = 7
        c.gridwidth = 2
        c.weightx = 1.0
        c.weighty = 1.0
        c.fill = GridBagConstraints.BOTH
        panel.add(JBLabel("Select strings to translate:"), c)
        
        c.gridy = 8
        val scrollPane = JBScrollPane(stringTable)
        scrollPane.preferredSize = Dimension(500, 300)
        panel.add(scrollPane, c)

        return panel
    }
    
    fun getSelectedLanguageCode(): String {
        val selectedName = targetLangComboBox.item
        return languages[selectedName] ?: ""
    }
    
    fun getSelectedKeys(): List<String> {
        val selected = mutableListOf<String>()
        for (i in 0 until tableModel.rowCount) {
            if (tableModel.getValueAt(i, 0) as Boolean) {
                selected.add(tableModel.getValueAt(i, 1) as String)
            }
        }
        return selected
    }

    private fun scanAndSelectUsedKeys() {
        ProgressManager.getInstance().run(object : Task.Modal(project, "Scanning for Usages...", true) {
            override fun run(indicator: ProgressIndicator) {
                val helper = PsiSearchHelper.getInstance(project)
                val scope = GlobalSearchScope.projectScope(project)
                val usedKeys = mutableSetOf<String>()
                
                val keyCount = tableModel.rowCount
                for (i in 0 until keyCount) {
                    if (indicator.isCanceled) break
                    val key = tableModel.getValueAt(i, 1) as String
                    indicator.text = "Checking usage of '$key'..."
                    indicator.fraction = i.toDouble() / keyCount.toDouble()
                    
                    var found = false
                    
                    try {
                        helper.processElementsWithWord({ element, _ ->
                            found = true
                            false 
                        }, scope, key, (com.intellij.psi.search.UsageSearchContext.IN_CODE + com.intellij.psi.search.UsageSearchContext.IN_STRINGS).toShort(), true)
                    } catch (e: Exception) {
                        // Ignore indexing errors
                    }
                    
                    if (found) {
                        usedKeys.add(key)
                    }
                }
                
                ApplicationManager.getApplication().invokeLater {
                    for (i in 0 until tableModel.rowCount) {
                        val key = tableModel.getValueAt(i, 1) as String
                        val isUsed = usedKeys.contains(key)
                        // If searching/filtering, we still update underlying model
                        tableModel.setValueAt(isUsed, i, 0)
                    }
                }
            }
        })
    }
}
