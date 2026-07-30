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

    // Material 3 (Material You) Palette Defaults
    private val M3_PRIMARY = Color(103, 80, 164)        // #6750A4
    private val M3_ON_PRIMARY = Color(255, 255, 255)   // #FFFFFF
    private val M3_PRIMARY_CONTAINER = Color(234, 221, 255) // #EADDFF
    private val M3_SURFACE = Color(254, 247, 255)       // #FEF7FF
    private val M3_SURFACE_CONTAINER = Color(243, 237, 247) // #F3EDF7
    private val M3_OUTLINE = Color(121, 116, 126)       // #79747E
    private val M3_OUTLINE_VARIANT = Color(202, 196, 206) // #CAC4CF
    private val M3_TEXT_PRIMARY = Color(29, 27, 32)    // #1D1B20
    private val M3_TEXT_SECONDARY = Color(73, 69, 79)  // #49454F

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

        // Load project resources & string dictionary
        val projectStrings = loadProjectStrings(project, targetLang)
        val projectColors = loadProjectColors(project)
        val combinedStrings = mutableMapOf<String, String>()
        combinedStrings.putAll(projectStrings)
        combinedStrings.putAll(translations)

        val primaryColor = projectColors["colorPrimary"] ?: M3_PRIMARY
        val surfaceColor = projectColors["colorSurface"] ?: M3_SURFACE
        val cardColor = projectColors["colorSurfaceContainer"] ?: M3_SURFACE_CONTAINER

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

                // Material You Phone Surface Background
                g2d.color = surfaceColor
                g2d.fillRect(0, 0, width, height)

                // Top Status Bar (Dark Primary)
                g2d.color = Color(maxOf(0, primaryColor.red - 30), maxOf(0, primaryColor.green - 30), maxOf(0, primaryColor.blue - 30))
                g2d.fillRect(0, 0, width, 24)

                // Material 3 TopAppBar
                g2d.color = primaryColor
                g2d.fillRect(0, 24, width, 56)
                g2d.color = M3_ON_PRIMARY
                g2d.font = Font("SansSerif", Font.BOLD, 18)
                
                // Back arrow vector icon
                g2d.stroke = java.awt.BasicStroke(2f)
                g2d.drawLine(16, 52, 28, 52)
                g2d.drawLine(16, 52, 22, 46)
                g2d.drawLine(16, 52, 22, 58)

                val headerTitle = formatTitle(layoutFile.nameWithoutExtension) + " ($targetLang)"
                g2d.drawString(headerTitle, 40, 58)

                // Render Root Element & Children
                val root = doc.documentElement
                renderNode(g2d, root, 16, 96, width - 32, combinedStrings, primaryColor, cardColor)

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
            }
        } catch (e: Throwable) {
            // Proceed to Custom Renderer
        }
        return list
    }

    private fun loadProjectColors(project: Project): Map<String, Color> {
        val colorsMap = mutableMapOf<String, Color>()
        val basePath = project.basePath ?: return colorsMap
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return colorsMap

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        fun scanColors(file: VirtualFile) {
            if (file.isDirectory) {
                for (child in file.children) scanColors(child)
            } else if (file.name == "colors.xml") {
                try {
                    val doc = builder.parse(file.inputStream)
                    doc.documentElement.normalize()
                    val nodes = doc.getElementsByTagName("color")
                    for (i in 0 until nodes.length) {
                        val elem = nodes.item(i) as Element
                        val name = elem.getAttribute("name")
                        val hex = elem.textContent?.trim() ?: ""
                        if (name.isNotBlank() && hex.startsWith("#")) {
                            parseColorHex(hex)?.let { colorsMap[name] = it }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        scanColors(baseDir)
        return colorsMap
    }

    private fun parseColorHex(hex: String): Color? {
        return try {
            val clean = hex.removePrefix("#")
            when (clean.length) {
                6 -> Color(clean.substring(0, 2).toInt(16), clean.substring(2, 4).toInt(16), clean.substring(4, 6).toInt(16))
                8 -> Color(clean.substring(2, 4).toInt(16), clean.substring(4, 6).toInt(16), clean.substring(6, 8).toInt(16))
                3 -> Color(clean.substring(0, 1).repeat(2).toInt(16), clean.substring(1, 2).repeat(2).toInt(16), clean.substring(2, 3).repeat(2).toInt(16))
                else -> null
            }
        } catch (e: Exception) {
            null
        }
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
                "selectall" -> "Select All"
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
        stringDict: Map<String, String>,
        primaryColor: Color,
        cardColor: Color
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
            // Material 3 Card Container Styling (16dp rounded corners)
            if (tagName.contains("CardView") || tagName.contains("ConstraintLayout") || tagName.contains("LinearLayout")) {
                g2d.color = cardColor
                g2d.fillRoundRect(x, currentY, maxWidth, 48, 16, 16)
                g2d.color = M3_OUTLINE_VARIANT
                g2d.drawRoundRect(x, currentY, maxWidth, 48, 16, 16)
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
                    val endY = renderNode(g2d, child, currentX, currentY, childWidth, stringDict, primaryColor, cardColor)
                    if (endY > maxY) maxY = endY
                    currentX += childWidth + 8
                }
                currentY = maxY + 8
            } else {
                for (child in childElements) {
                    currentY = renderNode(g2d, child, x + 8, currentY, maxWidth - 16, stringDict, primaryColor, cardColor)
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
                val paddingV = 12
                val lineHeight = g2d.fontMetrics.height
                val viewHeight = maxOf(48, lines.size * lineHeight + paddingV * 2)

                // Material 3 Full Pill Shape Button
                g2d.color = primaryColor
                g2d.fillRoundRect(x, currentY, maxWidth, viewHeight, viewHeight, viewHeight)

                g2d.color = M3_ON_PRIMARY
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
                val fontSize = if (isTitle) 18 else 14
                val fontStyle = if (isTitle || element.getAttribute("android:textStyle") == "bold") Font.BOLD else Font.PLAIN
                val font = Font("SansSerif", fontStyle, fontSize)
                g2d.font = font

                val lines = wrapText(text.ifBlank { "Text View" }, g2d.fontMetrics, maxWidth - 16)
                val lineHeight = g2d.fontMetrics.height
                val viewHeight = lines.size * lineHeight + 12

                // Material 3 Surface Card
                g2d.color = cardColor
                g2d.fillRoundRect(x, currentY, maxWidth, viewHeight, 12, 12)
                g2d.color = M3_OUTLINE_VARIANT
                g2d.drawRoundRect(x, currentY, maxWidth, viewHeight, 12, 12)

                g2d.color = if (isTitle) M3_TEXT_PRIMARY else M3_TEXT_SECONDARY
                val metrics = g2d.fontMetrics
                var textY = currentY + 6 + metrics.ascent
                for (line in lines) {
                    g2d.drawString(line, x + 12, textY)
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
                val viewHeight = maxOf(48, lines.size * lineHeight + 14)

                // Material 3 Filled TextField Surface with Active Indicator Line
                g2d.color = cardColor
                g2d.fillRoundRect(x, currentY, maxWidth, viewHeight, 8, 8)
                
                // Bottom Indicator Line in Primary Color
                g2d.color = primaryColor
                g2d.fillRect(x, currentY + viewHeight - 3, maxWidth, 3)

                g2d.color = M3_TEXT_SECONDARY
                val metrics = g2d.fontMetrics
                var textY = currentY + 7 + metrics.ascent
                for (line in lines) {
                    g2d.drawString(line, x + 12, textY)
                    textY += lineHeight
                }
                currentY += viewHeight + 10
            }
            tagName.contains("CheckBox") || tagName.contains("RadioButton") || tagName.contains("Switch") -> {
                val font = Font("SansSerif", Font.PLAIN, 14)
                g2d.font = font
                val lines = wrapText(text.ifBlank { "Toggle Option" }, g2d.fontMetrics, maxWidth - 44)
                val lineHeight = g2d.fontMetrics.height
                val viewHeight = maxOf(36, lines.size * lineHeight + 8)

                // Material 3 Pill Switch Track Icon
                g2d.color = M3_PRIMARY_CONTAINER
                g2d.fillRoundRect(x + 4, currentY + 6, 32, 20, 20, 20)
                g2d.color = primaryColor
                g2d.fillOval(x + 18, currentY + 8, 16, 16)

                g2d.color = M3_TEXT_PRIMARY
                val metrics = g2d.fontMetrics
                var textY = currentY + 4 + metrics.ascent
                for (line in lines) {
                    g2d.drawString(line, x + 44, textY)
                    textY += lineHeight
                }
                currentY += viewHeight + 8
            }
            tagName.contains("ImageView") || tagName.contains("ImageButton") -> {
                val iconHeight = 60
                g2d.color = M3_PRIMARY_CONTAINER
                g2d.fillRoundRect(x, currentY, maxWidth, iconHeight, 12, 12)
                g2d.color = primaryColor
                g2d.drawRoundRect(x, currentY, maxWidth, iconHeight, 12, 12)

                g2d.font = Font("SansSerif", Font.BOLD, 12)
                val metrics = g2d.fontMetrics
                val label = "[Material Image Surface]"
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
