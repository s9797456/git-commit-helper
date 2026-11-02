package com.caye.commithelper.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.vcs.ui.CommitMessage
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Grows the commit panel so a generated multi-line message is visible instead of being clipped
 * behind the editor scrollbar, and makes sure the first line is in view.
 *
 * The platform itself has no auto-grow logic here: `NonModalCommitPanel` pins its preferred and
 * minimum height to 120 px and the height comes from the enclosing [Splitter]. So when the plugin
 * writes text programmatically nothing moves. The user typed their (custom) message box in a fixed
 * height, and the generated body was therefore cut off at the bottom / showed a scrollbar.
 *
 * Everything runs on the EDT in an `invokeLater` so the layout that follows the document change is
 * already done. Failure to find a splitter (a future UI rewrite) is non-fatal: the text is still
 * written and the caret is still revealed.
 */
object CommitBoxSizing {

    /** The commit panel may take at most this share of the enclosing splitter. */
    internal const val MAX_FRACTION = 0.70f

    /** Schedules the resize/reveal after the write has been laid out. */
    fun afterWrite(component: JComponent?) {
        if (component == null) return
        ApplicationManager.getApplication().invokeLater { apply(component) }
    }

    private fun apply(component: JComponent) {
        val message = findCommitMessage(component) ?: return
        revealTop(message)

        val field = message.editorField
        val editor = field.editor ?: return
        if (field.height <= 0) return

        val contentHeight = editor.contentComponent.preferredSize.height + field.insets.top + field.insets.bottom
        val extra = contentHeight - field.height
        if (extra <= 0) return

        val splitter = findVerticalSplitter(message) ?: return
        val directChild = directChildOf(message, splitter) ?: return
        val messageInFirst = directChild === splitter.firstComponent
        if (!messageInFirst && directChild !== splitter.secondComponent) return

        val total = splitter.height
        if (total <= splitter.dividerWidth + 1) return

        val newProportion = targetProportion(
            totalSize = total,
            dividerWidth = splitter.dividerWidth,
            currentProportion = splitter.proportion,
            messageInFirst = messageInFirst,
            extraPixels = extra + 2,
            minProportion = splitter.minimumProportion,
            maxProportion = splitter.maximumProportion,
        ) ?: return
        splitter.proportion = newProportion
        splitter.revalidate()
        splitter.repaint()
    }

    /** Reveals the subject line and forces a repaint (guards against the clipped/ghosted paint). */
    private fun revealTop(message: CommitMessage) {
        val editor = message.editorField.editor ?: return
        editor.caretModel.moveToOffset(0)
        editor.scrollingModel.scrollVertically(0)
        message.editorField.revalidate()
        message.editorField.repaint()
        message.repaint()
    }

    /**
     * Pure sizing math, unit-tested without a UI:
     * returns the proportion of the first splitter component after adding [extraPixels] to the
     * message panel, or null when no growth is needed/possible.
     */
    internal fun targetProportion(
        totalSize: Int,
        dividerWidth: Int,
        currentProportion: Float,
        messageInFirst: Boolean,
        extraPixels: Int,
        minProportion: Float,
        maxProportion: Float,
    ): Float? {
        val available = totalSize - dividerWidth
        if (available <= 1) return null
        val currentChild = if (messageInFirst) available * currentProportion else available * (1f - currentProportion)
        val targetChild = currentChild + extraPixels
        val cappedChild = targetChild.coerceAtMost(available * MAX_FRACTION)
        if (cappedChild <= currentChild + 1f) return null
        val newProportion = if (messageInFirst) cappedChild / available else 1f - cappedChild / available
        return newProportion.coerceIn(minProportion, maxProportion)
    }

    private fun findCommitMessage(component: JComponent): CommitMessage? {
        var current: Component? = component
        while (current != null) {
            if (current is CommitMessage) return current
            current = current.parent
        }
        return null
    }

    /** The nearest vertical splitter above the commit message (inner splitters are skipped). */
    private fun findVerticalSplitter(component: Component): Splitter? {
        var parent: Container? = component.parent
        while (parent != null) {
            if (parent is Splitter && parent.isVertical) return parent
            parent = parent.parent
        }
        return null
    }

    private fun directChildOf(descendant: Component, ancestor: Container): Component? {
        var current: Component? = descendant
        while (current != null && current.parent !== ancestor) {
            current = current.parent
        }
        return current
    }
}
