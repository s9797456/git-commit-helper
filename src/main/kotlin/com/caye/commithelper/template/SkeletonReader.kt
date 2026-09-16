package com.caye.commithelper.template

import com.caye.commithelper.collect.GitOps
import java.io.File

/**
 * M2: reads the repository commit template and uses it as the output skeleton.
 *
 * Lookup order (first hit wins):
 * 1. `git config --get commit.template` (repo-level first, then global — that is git's own order)
 * 2. `<repoRoot>/.gitmessage`
 * 3. `<repoRoot>/.gitmessage.txt`
 */
object SkeletonReader {

    fun read(gitOps: GitOps?, root: File?): String? {
        gitOps?.commitTemplatePath()?.let { configured ->
            val resolved = resolve(configured, root)
            if (resolved != null) return resolved
        }
        if (root != null) {
            for (name in listOf(".gitmessage", ".gitmessage.txt")) {
                val candidate = File(root, name)
                if (candidate.isFile) return candidate.readText()
            }
        }
        return null
    }

    private fun resolve(pathFromConfig: String, root: File?): String? {
        val file = File(pathFromConfig)
        val resolved = if (file.isAbsolute) file else root?.let { File(it, pathFromConfig) }
        return if (resolved != null && resolved.isFile) resolved.readText() else null
    }
}
