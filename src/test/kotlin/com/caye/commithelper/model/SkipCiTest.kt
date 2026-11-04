package com.caye.commithelper.model

import com.caye.commithelper.settings.SkipCiPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Skip CI markers are applied locally after rendering; the model never sees them. */
class SkipCiTest {

    private val marker = "[skip ci]"
    private val request = SkipCiRequest(marker)

    @Test
    fun `the default placement is end of message`() {
        assertEquals(SkipCiPosition.END, SkipCiRequest(marker).position)
    }

    @Test
    fun `END appends the marker after a blank line`() {
        val message = "feat: add pagination\n\n- add page parameter\n"
        val rendered = SkipCiApplier.apply(message, request)

        assertEquals("feat: add pagination\n\n- add page parameter\n\n[skip ci]\n", rendered)
    }

    @Test
    fun `SUBJECT_PREFIX puts the marker in front of the first line`() {
        val message = "feat: add pagination\n\n- add page parameter\n"
        val rendered = SkipCiApplier.apply(message, SkipCiRequest(marker, SkipCiPosition.SUBJECT_PREFIX))

        assertEquals("[skip ci] feat: add pagination\n\n- add page parameter\n", rendered)
        assertTrue(rendered.startsWith("[skip ci] feat: add pagination"))
    }

    @Test
    fun `an already present marker is not duplicated`() {
        val message = "feat: add pagination\n\n[skip ci]\n"
        assertEquals(message, SkipCiApplier.apply(message, request))

        val prefixed = "[skip ci] feat: add pagination\n"
        assertEquals(prefixed, SkipCiApplier.apply(prefixed, SkipCiRequest(marker, SkipCiPosition.SUBJECT_PREFIX)))
    }

    @Test
    fun `the marker is trimmed before use`() {
        val rendered = SkipCiApplier.apply("feat: x\n", SkipCiRequest("  [skip ci]  "))
        assertTrue(rendered.endsWith("[skip ci]\n"))
        assertFalse(rendered.contains("  [skip ci]"))
    }

    @Test
    fun `a blank message becomes only the marker`() {
        assertEquals("[skip ci]\n", SkipCiApplier.apply("", request))
        assertEquals("[skip ci]\n", SkipCiApplier.apply("   \n\n", request))
        assertEquals(
            "[skip ci]\n",
            SkipCiApplier.apply("   \n\n", SkipCiRequest(marker, SkipCiPosition.SUBJECT_PREFIX)),
        )
    }

    @Test
    fun `a null request is a no-op`() {
        val message = "feat: x\n\nbody\n"
        assertEquals(message, SkipCiApplier.apply(message, null))
    }

    @Test
    fun `a blank marker is a no-op`() {
        val message = "feat: x\n\nbody\n"
        assertEquals(message, SkipCiApplier.apply(message, SkipCiRequest("   ")))
        assertEquals(message, SkipCiApplier.apply(message, SkipCiRequest("")))
    }

    @Test
    fun `the message body is preserved and only trailing whitespace is normalised`() {
        val message = "  feat: x  \n\nbody with spaces  \n\n\n"
        val rendered = SkipCiApplier.apply(message, request)
        assertTrue(rendered.startsWith("  feat: x  "))
        assertTrue(rendered.contains("body with spaces"))
        assertTrue(rendered.endsWith("[skip ci]\n"))
    }
}
