package com.caye.commithelper.split

import com.caye.commithelper.model.ChangeKind
import com.caye.commithelper.model.FileChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffHunkParserTest {

    private val diff = """
        diff --git a/src/App.kt b/src/App.kt
        index 1111111..2222222 100644
        --- a/src/App.kt
        +++ b/src/App.kt
        @@ -1,3 +1,3 @@
         class App {
        -    val old = 1
        +    val new = 1
         }
        @@ -10,3 +10,3 @@
         fun second() {
        -    val old = 2
        +    val new = 2
         }
    """.trimIndent()

    @Test
    fun `splits a file diff into independent hunks`() {
        val hunks = DiffHunkParser.parse(
            listOf(FileChange("src/App.kt", ChangeKind.MODIFIED, 2, 2, diff)),
        )
        assertEquals(2, hunks.size)
        assertTrue(hunks.all { it.filePath == "src/App.kt" })
        assertEquals(hunks.size, hunks.map { it.id }.distinct().size)
        assertTrue(hunks.all { it.fileHeader.contains("diff --git") })
        assertTrue(hunks[0].summary.contains("@@"))
    }

    @Test
    fun `patch uses only the selected hunks`() {
        val hunks = DiffHunkParser.parse(
            listOf(FileChange("src/App.kt", ChangeKind.MODIFIED, 2, 2, diff)),
        )
        val patch = DiffHunkParser.patch(listOf(hunks.first()))
        assertTrue(patch.contains("class App"))
        assertTrue(patch.contains("+    val new = 1"))
        assertTrue(!patch.contains("fun second"))
    }

    @Test
    fun `empty diff does not invent hunks`() {
        assertTrue(DiffHunkParser.parse(listOf(FileChange("a", ChangeKind.MODIFIED))).isEmpty())
    }
}
