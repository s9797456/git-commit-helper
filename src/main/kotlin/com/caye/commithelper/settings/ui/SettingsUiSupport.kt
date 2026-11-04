package com.caye.commithelper.settings.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.Scrollable

/**
 * Small shared helpers so the settings pages (General / LLM / Commit Content / Project Defaults)
 * look and behave the same. Everything is plain Swing plus the platform look-and-feel primitives,
 * which keeps the pages compatible with the plugin's 2024.1+ floor.
 */
internal object SettingsUi {

    /** A bold section heading with some breathing room above it. */
    fun section(title: String): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(14)
        add(JBLabel("<html><b>" + title + "</b></html>"), BorderLayout.WEST)
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
    }

    /** Grey explanatory text below a control. */
    fun hint(text: String): JComponent = JBLabel("<html><i>" + text + "</i></html>").apply {
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
        border = JBUI.Borders.emptyLeft(4)
    }

    fun leftAligned(component: JComponent): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(component)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        }

    /** Label on the left, control on the right; fixed-height row for a vertical BoxLayout. */
    fun row(label: String, component: JComponent): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        val labelComponent = JBLabel(label)
        labelComponent.preferredSize = Dimension(JBUI.scale(170), labelComponent.preferredSize.height)
        panel.add(labelComponent, BorderLayout.WEST)
        panel.add(component, BorderLayout.CENTER)
        panel.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        return panel
    }

    /** A left-aligned strip of controls (buttons next to a combo box). */
    fun controls(vararg components: JComponent): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            components.forEach { add(it) }
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        }

    fun textArea(rows: Int, columns: Int, editable: Boolean = true): JBTextArea =
        JBTextArea(rows, columns).apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = editable
            font = JTextField().font
            margin = JBUI.insets(4)
        }

    /** A wrapped text area in a scroll pane with a fixed height inside the page column. */
    fun scrolling(area: JComponent, height: Int, width: Int = 760): JScrollPane =
        JBScrollPane(area).apply {
            preferredSize = Dimension(JBUI.scale(width), JBUI.scale(height))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(height + 8))
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }

    /**
     * Wraps page content in the scroll pane the settings dialog sizes itself against. The
     * horizontal policy is NEVER: the page tracks the available width instead of growing a
     * horizontal scrollbar when the IDE window is narrow.
     */
    fun page(content: JComponent, width: Int = 880, height: Int = 640): JScrollPane =
        JBScrollPane(content).apply {
            preferredSize = Dimension(JBUI.scale(width), JBUI.scale(height))
            minimumSize = Dimension(JBUI.scale(420), JBUI.scale(320))
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }

    fun column(): JPanel = WidthTrackingPanel()

    fun strut(height: Int): Component = Box.createVerticalStrut(JBUI.scale(height))
}

/**
 * Vertical page column that reports "track viewport width" to the enclosing JBScrollPane. Without
 * this the viewport keeps the column at its preferred width and the settings tabs get a wide blank
 * strip on the right when the IDE window grows.
 */
private class WidthTrackingPanel : JPanel(), Scrollable {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == javax.swing.SwingConstants.VERTICAL) visibleRect.height.coerceAtLeast(1) else visibleRect.width.coerceAtLeast(1)

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}

/** Renders objects with a friendly label in combo boxes (enums and library entries). */
internal class NamedRenderer(private val label: (Any?) -> String?) : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        if (component is JLabel) component.text = label(value)
        return component
    }
}
