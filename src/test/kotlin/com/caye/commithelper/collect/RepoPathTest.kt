package com.caye.commithelper.collect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Regression guard for the multi-repo diff bug: git must be given repository-relative paths,
 * otherwise a project-relative path such as `repo-name/src/api/x.ts` matches nothing and the
 * model only sees file names.
 */
class RepoPathTest {

    @Test
    fun `a file under the repository root becomes a root-relative git path`() {
        val root = File("/tmp/project")
        val file = File("/tmp/project/src/api/deviceOta.ts")

        assertEquals("src/api/deviceOta.ts", RepoPath.relativePath(root, file))
    }

    @Test
    fun `a nested repository strips its own directory name`() {
        // Multi-repo layout: the project root has no .git, each repository is a child directory.
        val root = File("/tmp/project/iot-platform-business-front-v2")
        val file = File("/tmp/project/iot-platform-business-front-v2/src/api/deviceOta.ts")

        assertEquals("src/api/deviceOta.ts", RepoPath.relativePath(root, file))
    }

    @Test
    fun `a file outside the repository is rejected`() {
        val root = File("/tmp/project/repo-a")
        val file = File("/tmp/project/repo-b/src/x.kt")

        assertNull(RepoPath.relativePath(root, file))
    }

    @Test
    fun `a trailing separator on the root is tolerated`() {
        val root = File("/tmp/project/repo-a/")
        val file = File("/tmp/project/repo-a/src/x.kt")

        assertEquals("src/x.kt", RepoPath.relativePath(root, file))
    }
}
