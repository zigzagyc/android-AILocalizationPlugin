package com.antigravity.localization

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.w3c.dom.Element
import java.awt.Color
import java.awt.Font
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

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        for (layoutFile in layoutFiles) {
            try {
                val doc = builder.parse(layoutFile.inputStream)
                doc.documentElement.normalize()
                
                val width = 400
                val height = 700
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                val g2d = image.createGraphics()

                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                // Background phone container
                g2d.color = Color(245, 245, 247)
                g2d.fillRect(0, 0, width, height)

                // Top app bar / header
                g2d.color = Color(63, 81, 181)
                g2d.fillRect(0, 0, width, 56)
                g2d.color = Color.WHITE
                g2d.font = Font("SansSerif", Font.BOLD, 16)
                g2d.drawString("Preview (${layoutFile.nameWithoutExtension}) - [$targetLang]", 16, 34)

                // Render XML Root Element and Children recursively
                var currentY = 70
                val root = doc.documentElement
                currentY = renderElement(g2d, root, 16, currentY, width - 32, translations)

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

    private fun renderElement(
        g2d: Graphics2D,
        element: Element,
        x: Int,
        y: Int,
        maxWidth: Int,
        translations: Map<String, String>
    ): Int {
        var currentY = y
        val tagName = element.tagName

        val textAttr = element.getAttribute("android:text")
        var resolvedText = ""

        if (textAttr.startsWith("@string/")) {
            val key = textAttr.removePrefix("@string/")
            resolvedText = translations[key] ?: textAttr
        } else if (textAttr.isNotBlank()) {
            resolvedText = textAttr
        }

        val hintAttr = element.getAttribute("android:hint")
        if (hintAttr.startsWith("@string/")) {
            val key = hintAttr.removePrefix("@string/")
            if (resolvedText.isBlank()) {
                resolvedText = "Hint: " + (translations[key] ?: hintAttr)
            }
        }

        val layoutWidthAttr = element.getAttribute("android:layout_width")
        val viewHeight = 44

        when {
            tagName.contains("Button") || tagName.endsWith("Button") -> {
                g2d.color = Color(33, 150, 243)
                g2d.fillRoundRect(x, currentY, maxWidth, viewHeight, 8, 8)

                g2d.color = Color.WHITE
                g2d.font = Font("SansSerif", Font.BOLD, 14)
                val metrics = g2d.fontMetrics
                val textWidth = metrics.stringWidth(resolvedText)
                val textX = x + (maxWidth - textWidth) / 2
                val textY = currentY + ((viewHeight - metrics.height) / 2) + metrics.ascent

                // Clip text if it exceeds width constraint
                if (layoutWidthAttr.endsWith("dp") && layoutWidthAttr != "wrap_content") {
                    val fixedWidthPx = parseDp(layoutWidthAttr) ?: maxWidth
                    if (textWidth > fixedWidthPx) {
                        g2d.color = Color(220, 50, 50) // Red text warning
                    }
                }

                g2d.drawString(resolvedText.ifBlank { "Button" }, maxOf(x + 8, textX), textY)
                currentY += viewHeight + 12
            }
            tagName.contains("TextView") || tagName.endsWith("TextView") -> {
                g2d.color = Color(250, 250, 250)
                g2d.fillRoundRect(x, currentY, maxWidth, viewHeight, 4, 4)

                g2d.color = Color(200, 200, 200)
                g2d.drawRoundRect(x, currentY, maxWidth, viewHeight, 4, 4)

                g2d.color = Color(33, 33, 33)
                g2d.font = Font("SansSerif", Font.PLAIN, 14)
                val metrics = g2d.fontMetrics
                val textY = currentY + ((viewHeight - metrics.height) / 2) + metrics.ascent

                val isTooLong = resolvedText.length > 30 && (layoutWidthAttr.endsWith("dp") || layoutWidthAttr == "wrap_content")
                if (isTooLong) {
                    g2d.color = Color(180, 40, 40) // Warning red for long strings
                }

                g2d.drawString(resolvedText.ifBlank { "TextView" }, x + 12, textY)
                currentY += viewHeight + 12
            }
            tagName.contains("EditText") -> {
                g2d.color = Color.WHITE
                g2d.fillRoundRect(x, currentY, maxWidth, viewHeight, 4, 4)

                g2d.color = Color(180, 180, 180)
                g2d.drawRoundRect(x, currentY, maxWidth, viewHeight, 4, 4)

                g2d.color = Color(120, 120, 120)
                g2d.font = Font("SansSerif", Font.ITALIC, 14)
                val metrics = g2d.fontMetrics
                val textY = currentY + ((viewHeight - metrics.height) / 2) + metrics.ascent
                g2d.drawString(resolvedText.ifBlank { "EditText Input" }, x + 12, textY)
                currentY += viewHeight + 12
            }
            else -> {
                // Recursive processing for containers (LinearLayout, RelativeLayout, ConstraintLayout, FrameLayout)
                val childNodes = element.childNodes
                for (i in 0 until childNodes.length) {
                    val child = childNodes.item(i)
                    if (child is Element) {
                        currentY = renderElement(g2d, child, x, currentY, maxWidth, translations)
                    }
                }
            }
        }
        return currentY
    }

    private fun parseDp(dpAttr: String): Int? {
        val clean = dpAttr.removeSuffix("dp").trim()
        return clean.toIntOrNull()?.let { it * 2 } // Approximate dp to pixel scale for preview
    }
}
