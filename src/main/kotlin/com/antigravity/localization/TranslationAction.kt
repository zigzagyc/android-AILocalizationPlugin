package com.antigravity.localization

import com.antigravity.localization.services.TranslationService
import com.antigravity.localization.services.impl.*
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

import com.intellij.openapi.vfs.LocalFileSystem

class TranslationAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return

        val contextFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        var stringFiles = mutableListOf<VirtualFile>()

        if (contextFile != null && !contextFile.isDirectory && (contextFile.name == "strings.xml" || contextFile.name == "arrays.xml")) {
            // Case 1: Right-clicked a specific file - Translate only this one
            stringFiles.add(contextFile)
        } else {
            // Case 2: Right-clicked folder or invoked generally - Search recursively
            val searchDir = if (contextFile != null && contextFile.isDirectory) contextFile else baseDir
            findXmlFiles(searchDir, stringFiles)

            if (stringFiles.isEmpty()) {
                Messages.showInfoMessage(project, "No strings.xml found in selected location.", "Info")
                return
            }

            // If multiple files, let user select
            if (stringFiles.size > 1) {
                val fileDialog = FileSelectionDialog(stringFiles)
                if (fileDialog.showAndGet()) {
                    stringFiles = fileDialog.getSelectedFiles().toMutableList()
                } else {
                    return // User cancelled
                }
            }
        }
        
        if (stringFiles.isEmpty()) return

        // Aggregate all strings from all found strings.xml files
        val stringMap = mutableMapOf<String, String>()
        for (file in stringFiles) {
            stringMap.putAll(parseStrings(file))
        }

        if (stringMap.isEmpty()) {
             Messages.showInfoMessage(project, "No strings found in project.", "Info")
             return
        }

        // 2. Show Dialog
        val dialog = TranslationDialog(project, stringMap)
        if (!dialog.showAndGet()) return

        val serviceName = dialog.serviceComboBox.item
        val apiKey = dialog.getCombinedApiKey()
        val targetLang = dialog.getSelectedLanguageCode()
        val context = dialog.contextField.text
        val selectedKeys = dialog.getSelectedKeys().toSet()

        if (apiKey.isBlank()) {
            Messages.showErrorDialog(project, "API Key is required.", "Error")
            return
        }
        if (targetLang.isBlank()) {
            Messages.showErrorDialog(project, "Target language is required.", "Error")
            return
        }
        
        if (selectedKeys.isEmpty()) {
            Messages.showErrorDialog(project, "No strings selected for translation.", "Error")
            return
        }

        val service = getService(serviceName)
        if (service is OpenAIService) {
             val model = dialog.modelComboBox.item as? String
             if (!model.isNullOrBlank()) {
                 service.model = model
             }
        }

        object : Task.Backgroundable(project, "Translating to $targetLang...", true) {
            private var results: List<TranslationResult>? = null

            override fun run(indicator: ProgressIndicator) {
                results = translateProject(project, service, apiKey, targetLang, context, selectedKeys, stringFiles, indicator)
            }

            override fun onSuccess() {
                if (results != null && results!!.isNotEmpty()) {
                    // Show Review Dialog on EDT
                    val reviewDialog = ReviewDialog(results!!)
                    if (reviewDialog.showAndGet()) {
                        val confirmed = reviewDialog.getConfirmedTranslations()
                        // Write to file
                        saveTranslations(project, confirmed, targetLang)
                    }
                } else {
                    Messages.showInfoMessage(project, "No translations were generated.", "Info")
                }
            }
        }.queue()
    }

    private fun parseStrings(file: VirtualFile): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(file.inputStream)
            document.documentElement.normalize()
            
            val stringNodes = document.getElementsByTagName("string")
            for (i in 0 until stringNodes.length) {
                val node = stringNodes.item(i) as Element
                val name = node.getAttribute("name")
                val translatable = node.getAttribute("translatable")
                if (translatable != "false") {
                     map[name] = node.textContent
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    private fun getService(name: String): TranslationService {
        return when (name) {
            "OpenAI (ChatGPT)" -> OpenAIService()
            "Gemini" -> GeminiService()
            "Grok (xAI)" -> GrokService()
            "Google Translate" -> GoogleTranslationService()
            "Microsoft Translator" -> MicrosoftTranslationService()
            "DeepL" -> DeepLService()
            "AWS Translate" -> AWSTranslationService()
            else -> throw IllegalArgumentException("Unknown service: $name")
        }
    }

    private fun translateProject(project: Project, service: TranslationService, apiKey: String, targetLang: String, context: String?, selectedKeys: Set<String>, filesToTranslate: List<VirtualFile>, indicator: ProgressIndicator): List<TranslationResult> {
        val results = mutableListOf<TranslationResult>()
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        try {
            for (file in filesToTranslate) {
                if (indicator.isCanceled) break
                indicator.text = "Processing ${file.name}..."
                
                try {
                    val document = builder.parse(file.inputStream)
                    document.documentElement.normalize()

                    val stringNodes = document.getElementsByTagName("string")
                    
                    // Filter nodes
                    val nodesToProcessList = mutableListOf<Element>()
                    for (i in 0 until stringNodes.length) {
                         val node = stringNodes.item(i) as Element
                         if (selectedKeys.contains(node.getAttribute("name"))) {
                             nodesToProcessList.add(node)
                         }
                    }
                    
                    var processedCount = 0
                    for (node in nodesToProcessList) {
                        if (indicator.isCanceled) break
                        val name = node.getAttribute("name")
                        val originalText = node.textContent
                        
                        val translatedText = try {
                            runBlocking { service.translate(originalText, targetLang, context, apiKey) }
                        } catch (e: Exception) {
                            if (e is com.antigravity.localization.services.QuotaExceededException) {
                                // Re-throw to be caught by the outer loop and stop everything
                                throw e
                            }
                            "[Error: ${e.message}]"
                        }
                        
                        val ratio = if (originalText.isNotEmpty()) translatedText.length.toFloat() / originalText.length.toFloat() else 0f
                        results.add(TranslationResult(file, name, originalText, translatedText, ratio))
                        
                        processedCount++
                        indicator.fraction = (processedCount.toDouble() / nodesToProcessList.size)
                    }

                } catch (e: Exception) {
                    if (e is com.antigravity.localization.services.QuotaExceededException) throw e
                    e.printStackTrace()
                }
            }
        } catch (e: com.antigravity.localization.services.QuotaExceededException) {
            // Quota exceeded: Stop everything and notify user on EDT
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(project, e.message, "Quota Exceeded")
            }
        }
        return results
    }

    private fun saveTranslations(project: Project, results: List<TranslationResult>, targetLang: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            // Group by source file
            val grouped = results.groupBy { it.file }

            for ((sourceFile, fileResults) in grouped) {
                try {
                    val valuesDir = sourceFile.parent
                    val resDir = valuesDir.parent
                    val targetValuesDirName = "values-$targetLang"

                    val targetDir = resDir.findChild(targetValuesDirName)
                        ?: resDir.createChildDirectory(this, targetValuesDirName)

                    val targetFile = targetDir.findChild(sourceFile.name)
                    
                    // 1. Read Valid Translations from Results
                    val newTranslations = fileResults.associate { it.key to it.translated }

                    // 2. Read Existing Translations (if any) to preserve unselected strings
                    val existingTranslations = mutableMapOf<String, String>()
                    if (targetFile != null && targetFile.exists()) {
                         try {
                             val factory = DocumentBuilderFactory.newInstance()
                             val builder = factory.newDocumentBuilder()
                             targetFile.inputStream.use { inputStream ->
                                 val doc = builder.parse(inputStream)
                                 doc.documentElement.normalize()
                                 val nodeList = doc.getElementsByTagName("string")
                                 for (i in 0 until nodeList.length) {
                                     val node = nodeList.item(i) as Element
                                     existingTranslations[node.getAttribute("name")] = node.textContent
                                 }
                             }
                         } catch (e: Exception) {
                             // Ignore parse errors, just won't have existing translations
                         }
                    }

                    // 3. Read Source File Line-by-Line and Replace
                    val outputLines = mutableListOf<String>()
                    val stringRegex = Regex("<string\\s+name=\"(.*?)\"(.*?)>(.*?)</string>")
                    val sourceLines = sourceFile.inputStream.bufferedReader().use { it.readLines() }

                    for (line in sourceLines) {
                        val match = stringRegex.find(line)
                        if (match != null) {
                            val key = match.groupValues[1]
                            val attributes = match.groupValues[2] // other attributes like translatable
                            // val content = match.groupValues[3] 

                            var finalValue: String? = null
                            
                            if (newTranslations.containsKey(key)) {
                                finalValue = newTranslations[key]
                            } else if (existingTranslations.containsKey(key)) {
                                finalValue = existingTranslations[key]
                            } else {
                                // Fallback: Use source value if we don't have a translation? 
                                // Actually match.groupValues[3] is the source content.
                                // If we don't translate it, we should output exactly what was there?
                                // Ideally, if it's missing in target, having English is better than missing line.
                                finalValue = match.groupValues[3]
                            }
                            
                            // Reconstruct line preserving indentation ??
                            // The regex match covers the whole tag? No, find() matches the substring.
                            // We need to replace only the content inside the tag, preserving the rest of the line.
                            
                            // Safer approach: Replace the match range in the line
                            if (finalValue != null) {
                                // specific replace of content
                                // We construct the new tag
                                val oldTag = match.value
                                // We want to keep name and attributes exactly as is
                                // So we just replace the content part.
                                // But content might contain regex chars.
                                
                                // Let's use simple string index replacement for the content
                                val contentStart = line.indexOf(">") + 1
                                val contentEnd = line.lastIndexOf("<")
                                
                                if (contentStart > 0 && contentEnd > contentStart) {
                                     val prefix = line.substring(0, contentStart)
                                     val suffix = line.substring(contentEnd)
                                     outputLines.add(prefix + escapeXml(finalValue) + suffix)
                                } else {
                                     // Complex line (maybe multiple tags? or strict formatting failed)
                                     // Fallback to rebuilding tag (might lose exact spacing inside tag but keeps line indentation)
                                     // indent: line.substring(0, line.indexOf("<"))
                                     // But simpler to just add line if we can't parse strictly
                                     outputLines.add(line)
                                }
                            } else {
                                outputLines.add(line)
                            }
                        } else {
                            outputLines.add(line)
                        }
                    }

                    // 4. Write Output
                    val fileToWrite = targetFile ?: targetDir.createChildData(this, sourceFile.name)
                    fileToWrite.setBinaryContent(outputLines.joinToString("\n").toByteArray())

                } catch (e: Exception) {
                    e.printStackTrace()
                    Messages.showErrorDialog(project, "Failed to save ${sourceFile.name}: ${e.message}", "Save Error")
                }
            }
        }
    }
    
    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
    
    private fun findXmlFiles(dir: VirtualFile, list: MutableList<VirtualFile>) {
        for (child in dir.children) {
            if (child.isDirectory) {
                if (child.name != "build" && child.name != ".gradle") {
                    findXmlFiles(child, list)
                }
            } else {
                if (child.parent.name == "values" && (child.name == "strings.xml" || child.name == "arrays.xml")) {
                    list.add(child)
                }
            }
        }
    }
}
