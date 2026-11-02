package com.caye.commithelper.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommitBoxSizingTest {

    private fun grow(messageInFirst: Boolean = false, extra: Int = 200): Float? = CommitBoxSizing.targetProportion(
        totalSize = 1000,
        dividerWidth = 6,
        currentProportion = if (messageInFirst) 0.3f else 0.4f,
        messageInFirst = messageInFirst,
        extraPixels = extra,
        minProportion = 0.05f,
        maxProportion = 0.95f,
    )

    @Test
    fun `grows the second component and shrinks the first proportion`() {
        val newProportion = grow(messageInFirst = false, extra = 100)

        assertTrue(newProportion != null && newProportion < 0.4f, "expected a smaller first share, got $newProportion")
        // available = 994; current second = 596.4; +100 hits the 70% cap (695.8) -> first share 0.3
        assertEquals(0.3f, newProportion!!, 0.01f)
    }

    @Test
    fun `grows the first component by increasing its proportion`() {
        val newProportion = grow(messageInFirst = true, extra = 100)

        assertTrue(newProportion != null && newProportion > 0.3f, "expected a bigger first share, got $newProportion")
        // available = 994; current first = 298.2; target = 398.2 -> share 0.4006
        assertEquals(398.2f / 994f, newProportion!!, 0.01f)
    }

    @Test
    fun `does nothing when the content already fits`() {
        assertNull(grow(extra = 0))
        assertNull(grow(extra = -50))
    }

    @Test
    fun `caps the panel at seventy percent of the splitter`() {
        val newProportion = grow(messageInFirst = false, extra = 10_000)!!

        val available = 994f
        val cappedSecond = available * CommitBoxSizing.MAX_FRACTION
        assertEquals(1f - cappedSecond / available, newProportion, 0.001f)
        assertTrue(newProportion >= 0.05f)
    }

    @Test
    fun `respects the splitter minimum and maximum proportions`() {
        val newProportion = CommitBoxSizing.targetProportion(
            totalSize = 1000,
            dividerWidth = 6,
            currentProportion = 0.4f,
            messageInFirst = false,
            extraPixels = 10_000,
            minProportion = 0.35f,
            maxProportion = 0.95f,
        )

        assertEquals(0.35f, newProportion)
    }

    @Test
    fun `refuses to compute with a degenerate splitter size`() {
        assertNull(
            CommitBoxSizing.targetProportion(
                totalSize = 6,
                dividerWidth = 6,
                currentProportion = 0.4f,
                messageInFirst = false,
                extraPixels = 100,
                minProportion = 0.05f,
                maxProportion = 0.95f,
            ),
        )
    }
}
