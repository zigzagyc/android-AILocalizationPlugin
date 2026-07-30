package com.antigravity.localization

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import org.w3c.dom.Element
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

object LayoutPreviewRenderer {

    fun captureLayoutScreenshots(
        project: Project,
        layoutFiles: List<VirtualFile>,
        targetLang: String,
        translations: Map<String, String>
    ): List<File> {
        val savedFiles = mutableListOf<File>()
        val basePath = project.basePath ?: return savedFiles
        val outputDir = File(basePath, "screenshots/$targetLang")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        // Try native Android Studio Layoutlib via reflection first
        val nativeSaved = tryNativeLayoutlibRender(project, layoutFiles, targetLang, outputDir, translations)
        if (nativeSaved.isNotEmpty()) {
            return nativeSaved
        }

        // Fallback to Advanced Custom Layout Renderer with Full String Dictionary Resolution
        val projectStrings = loadProjectStrings(project, targetLang)
        val combinedStrings = mutableMapOf<String, String>()
        combinedStrings.putAll(projectStrings)
        combinedStrings.putAll(translations)

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        for (layoutFile in layoutFiles) {
            try {
                val doc = builder.parse(layoutFile.inputStream)
                doc.documentElement.normalize()

                val width = 420
                val height = 750
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                val g2d = image.createGraphics()

                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                // Background phone container
                g2d.color = Color(242, 244, 247)
                g2d.fillRect(0, 0, width, height)

                // Top Status Bar
                g2d.color = Color(48, 63, 159)
                g2d.fillRect(0, 0, width, 24)

                // Top App Toolbar
                g2d.color = Color(63, 81, 181)
                g2d.fillRect(0, 24, width, 56)
                g2d.color = Color.WHITE
                g2d.font = Font("SansSerif", Font.BOLD, 16)
                val headerTitle = formatTitle(layoutFile.nameWithoutExtension) + " ($targetLang)"
                g2d.drawString(headerTitle, 16, 58)

                // Render Root Element & Children
                val root = doc.documentElement
                renderNode(g2d, root, 16, 92, width - 32, combinedStrings)

                g2d.dispose()

                val outputFile = File(outputDir, "${layoutFile.nameWithoutExtension}.png")
                ImageIO.write(image, "png", outputFile)
                savedFiles.add(outputFile)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return savedFiles
    }

    @Suppress("UNUSED_PARAMETER")
    private fun tryNativeLayoutlibRender(
        project: Project,
        layoutFiles: List<VirtualFile>,
        targetLang: String,
        outputDir: File,
        translations: Map<String, String>
    ): List<File> {
        val list = mutableListOf<File>()
        try {
            val renderServiceClass = Class.forName("com.android.tools.idea.rendering.RenderService")
            val getInstanceMethod = renderServiceClass.getMethod("getInstance", Project::class.java)
            val renderService = getInstanceMethod.invoke(null, project)
            if (renderService != null) {
                // Native RenderService is present in Android Studio!
                // Reflection invocation can be expanded if needed
            }
        } catch (e: Throwable) {
            // Android Studio Layoutlib not loaded in current environment, proceed to Custom Renderer
        }
        return list
    }

    private fun loadProjectStrings(project: Project, targetLang: String): Map<String, String> {
        val stringsMap = mutableMapOf<String, String>()
        val basePath = project.basePath ?: return stringsMap
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return stringsMap

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        fun scanDir(file: VirtualFile) {
            if (file.isDirectory) {
                for (child in file.children) scanDir(child)
            } else if (file.name == "strings.xml") {
                val parentName = file.parent?.name ?: ""
                val isTarget = parentName == "values-$targetLang"
                val isBase = parentName == "values"

                if (isTarget || isBase) {
                    try {
                        val doc = builder.parse(file.inputStream)
                        doc.documentElement.normalize()
                        val nodes = doc.getElementsByTagName("string")
                        for (i in 0 until nodes.length) {
                            val elem = nodes.item(i) as Element
                            val name = elem.getAttribute("name")
                            val text = elem.textContent
                            if (name.isNotBlank() && text != null) {
                                // Target language overrides base language strings
                                if (isTarget || !stringsMap.containsKey(name)) {
                                    stringsMap[name] = text
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        scanDir(baseDir)
        return stringsMap
    }

    private fun resolveStringValue(attrValue: String, stringDict: Map<String, String>): String {
        if (attrValue.isBlank()) return ""

        if (attrValue.startsWith("@string/")) {
            val key = attrValue.removePrefix("@string/")
            val found = stringDict[key]
            if (found != null && found.isNotBlank()) {
                return found
            }
            return formatKeyToTitle(key)
        }

        if (attrValue.startsWith("@android:string/")) {
            val sysKey = attrValue.removePrefix("@android:string/")
            return when (sysKey.lowercase()) {
                "ok" -> "OK"
                "cancel" -> "Cancel"
                "yes" -> "Yes"
                "no" -> "No"
                "save" -> "Save"
                "delete" -> "Delete"
                "search" -> "Search"
                "settings" -> "Settings"
                "copy" -> "Copy"
                "paste" -> "Paste"
                "selectAll" -> "Select All"
                else -> formatKeyToTitle(sysKey)
            }
        }

        return attrValue
    }

    private fun renderNode(
        g2d: Graphics2D,
        element: Element,
        x: Int,
        y: Int,
        maxWidth: Int,
        stringDict: Map<String, String>
    ): Int {
        var currentY = y
        val tagName = element.tagName
        val orientation = element.getAttribute("android:orientation").ifBlank { "vertical" }

        val textAttr = element.getAttribute("android:text")
        val hintAttr = element.getAttribute("android:hint")
        val rawText = textAttr.ifBlank { hintAttr }
        val text = resolveStringValue(rawText, stringDict)

        val isContainer = tagName.endsWith("Layout") || tagName.contains("CardView") || tagName.contains("ScrollView") || tagName == "FrameLayout" || tagName == "Toolbar"

        if (isContainer) {
            // Container background card styling
            if (tagName.contains("CardView") || tagName.contains("ConstraintLayout") || tagName.contains("LinearLayout")) {
                g2d.color = Color.WHITE
                g2d.fillRoundRect(x, currentY, maxWidth, 40, 8, 8)
                g2d.color = Color(225, 228, 232)
                g2d.drawRoundRect(x, currentY, maxWidth, 40, 8, 8)
                currentY += 8
            }

            val childNodes = element.childNodes
            val childElements = mutableListOf<Element>()
            for (i in 0 until childNodes.length) {
                val item = childNodes.item(i)
                if (item is Element) childElements.add(item)
            }

            if (orientation == "horizontal" && childElements.size > 1) {
                val childWidth = (maxWidth - (childElements.size - 1) * 8) / childElements.size
                var currentX = x
                var maxY = currentY
                for (child in childElements) {
                    val endY = renderNode(g2d, child, currentX, currentY, childWidth, stringDict)
                    if (endY > maxY) maxY = endY
                    currentX += childWidth + 8
                }
                currentY = maxY + 8
            } else {
                for (child in childElements) {
                    currentY = renderNode(g2d, child, x + 8, currentY, maxWidth - 16, stringDict)
                }
                currentY += 8
            }
            return currentY
        }

        // View Component Rendering
        when {
            tagName.contains("Button") || tagName.endsWith("Button") -> {
                val font = Font("SansSerif", Font.BOLD, 14)
                g2d.font = font
                val lines = wrapText(text.ifBlank { "Button" }, g2d.fontMetrics, maxWidth - 24)
                val paddingV = 10
                val lineHeight = g2d.fontMetrics.height
                val viewHeight = maxOf(44, lines.size * lineHeight + paddingV * 2)

                g2d.color = Color(33, 150, 243)
                g2d.fillRoundRect(x, currentY, maxWidth, viewHeight, 8, 8)

                g2d.color = Color.WHITE
                val metrics = g2d.fontMetrics
                var textY = currentY + paddingV + metrics.ascent
                for (line in lines) {
                    val textWidth = metrics.stringWidth(line)
                    val textX = x + (maxWidth - textWidth) / 2
                    g2d.drawString(line, maxOf(x + 8, textX), textY)
                    textY += lineHeight
                }
                currentY += viewHeight + 12
            }
            tagName.contains("TextView") || tagName.endsWith("TextView") -> {
                val isTitle = element.getAttribute("android:textSize").contains("sp") && parseSp(element.getAttribute("android:textSize")) > 16
                val fontSize = if (isTitle) 17 else 14
                val fontStyle = if (isTitle || element.getAttribute("android:textStyle") == "bold") Font.BOLD else Font.PLAIN
                val font = Font("SansSerif", fontStyle, fontSize)
                g2d.font = font

                val lines = wrapText(text.ifBlank { "Text View" }, g2d.fontMetrics, maxWidth - 16)
                val lineHeight = g2d.fontMetrics.height
                val viewHeight = lines.size * lineHeight + 12

                g2d.color = Color.WHITE
                g2d.fillRoundRect(x, currentY, maxWidth, viewHeight, 4, 4)
                g2d.color = Color(230, 232, 236)
                g2d.drawRoundRect(x, currentY, maxWidth, viewHeight, 4, 4)

                g2d.color = Color(33, 33, 33)
                val metrics = g2d.fontMetrics
                var textY = currentY + 6 + metrics.ascent
                for (line in lines) {
                    g2d.drawString(line, x + 8, textY)
                    textY += lineHeight
                }
                currentY += viewHeight + 10
            }
            tagName.contains("EditText") -> {
                val font = Font("SansSerif", Font.ITALIC, 14)
                g2d.font = font
                val displayText = if (hintAttr.isNotBlank()) "Hint: $text" else text.ifBlank { "Input field" }
                val lines = wrapText(displayText, g2d.fontMetrics, maxWidth - 24)
                val lineHeight = g2d.fontMetrics.height
                val viewHeight = maxOf(44, lines.size * lineHeight + 14)

                g2d.color = Color.WHITE
                g2d.fillRoundRect(x, currentY, maxWidth, viewHeight, 4, 4)
                g2d.color = Color(180, 185, 192)
                g2d.drawRoundRect(x, currentY, maxWidth, viewHeight, 4, 4)

                g2d.color = Color(110, 115, 125)
                val metrics = g2d.fontMetrics
                var textY = currentY + 7 + metrics.ascent
                for (line in lines) {
                    g2d.drawString(line, x + 10, textY)
                    textY += lineHeight
                }
                currentY += viewHeight + 10
            }
            tagName.contains("CheckBox") || tagName.contains("RadioButton") || tagName.contains("Switch") -> {
                val font = Font("SansSerif", Font.PLAIN, 14)
                g2d.font = font
                val lines = wrapText(text.ifBlank { "Toggle Option" }, g2d.fontMetrics, maxWidth - 36)
                val lineHeight = g2d.fontMetrics.height
                val viewHeight = maxOf(32, lines.size * lineHeight + 8)

                // Toggle icon
                g2d.color = Color(33, 150, 243)
                g2d.drawRoundRect(x + 4, currentY + 6, 18, 18, 4, 4)

                g2d.color = Color(33, 33, 33)
                val metrics = g2d.fontMetrics
                var textY = currentY + 4 + metrics.ascent
                for (line in lines) {
                    g2d.drawString(line, x + 30, textY)
                    textY += lineHeight
                }
                currentY += viewHeight + 8
            }
            tagName.contains("ImageView") || tagName.contains("ImageButton") -> {
                val iconHeight = 60
                g2d.color = Color(220, 224, 230)
                g2d.fillRoundRect(x, currentY, maxWidth, iconHeight, 6, 6)
                g2d.color = Color(160, 165, 175)
                g2d.drawRoundRect(x, currentY, maxWidth, iconHeight, 6, 6)

                g2d.font = Font("SansSerif", Font.PLAIN, 12)
                val metrics = g2d.fontMetrics
                val label = "[Image Placeholder]"
                val labelX = x + (maxWidth - metrics.stringWidth(label)) / 2
                g2d.drawString(label, labelX, currentY + 34)
                currentY += iconHeight + 10
            }
            else -> {
                currentY += 10
            }
        }

        return currentY
    }

    private fun wrapText(text: String, metrics: FontMetrics, maxWidth: Int): List<String> {
        if (text.isBlank()) return listOf("")
        if (maxWidth <= 20) return listOf(text)

        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (metrics.stringWidth(testLine) <= maxWidth) {
                currentLine = testLine
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                }
                currentLine = word
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }
        return if (lines.isEmpty()) listOf(text) else lines
    }

    private fun formatKeyToTitle(key: String): String {
        return key.replace("_", " ")
            .replace("-", " ")
            .split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }

    private fun formatTitle(name: String): String {
        return name.replace("activity_", "")
            .replace("fragment_", "")
            .replace("view_", "")
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }
    }

    private fun parseSp(spAttr: String): Int {
        val clean = spAttr.removeSuffix("sp").removeSuffix("dp").trim()
        return clean.toIntOrNull() ?: 14
    }
}
