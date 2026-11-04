package com.caye.commithelper.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties
import java.util.ResourceBundle

/**
 * Guards the four-locale resource set: the English base bundle must load, every base key must
 * exist in the zh/ja/ko translations, no translation may be blank, and no translation may carry
 * a key the base does not know about.
 *
 * ResourceBundle lookups go through Locale.ROOT on purpose: the test JVM (JBR) resolves a plain
 * Locale.ENGLISH request against the host locale, so the English base file is asserted directly.
 */
class BundleKeyTest {

    private fun loadProperties(path: String): Properties {
        val stream = javaClass.classLoader.getResourceAsStream(path)
        assertNotNull(stream, "missing resource on the classpath: " + path)
        return Properties().apply {
            InputStreamReader(stream!!, StandardCharsets.UTF_8).use { load(it) }
        }
    }

    @Test
    fun `base bundle is loadable and answers a key`() {
        val base = loadProperties("messages/CommitHelperBundle.properties")
        assertEquals("Retry", base.getProperty("notification.retry"))

        val bundle = ResourceBundle.getBundle("messages.CommitHelperBundle", Locale.ROOT)
        assertTrue(bundle.keys.toList().isNotEmpty(), "the base bundle must not be empty")
        assertEquals("Retry", bundle.getString("notification.retry"))

        assertFalse(CommitHelperBundle.message("dialog.preview.title").isBlank())
    }

    @Test
    fun `every base key exists in every translation and no translation is blank`() {
        val base = loadProperties("messages/CommitHelperBundle.properties")
        val baseKeys = base.stringPropertyNames()
        assertTrue(baseKeys.isNotEmpty(), "the base bundle must define keys")

        for (locale in listOf("zh", "ja", "ko")) {
            val propertiesName = "CommitHelperBundle_" + locale + ".properties"
            val translated = loadProperties("messages/" + propertiesName)
            val translatedKeys = translated.stringPropertyNames()
            val missing = baseKeys.filter { it !in translatedKeys }.sorted()
            assertTrue(missing.isEmpty(), "keys missing from " + propertiesName + ": " + missing)
            val unknown = translatedKeys.filter { it !in baseKeys }.sorted()
            assertTrue(unknown.isEmpty(), "keys not present in the base bundle (typo?) in " + propertiesName + ": " + unknown)
            val blank = translatedKeys.filter { translated.getProperty(it).isNullOrBlank() }.sorted()
            assertTrue(blank.isEmpty(), "blank translations in " + propertiesName + ": " + blank)
        }
    }

    @Test
    fun `placeholders survive translation`() {
        val base = loadProperties("messages/CommitHelperBundle.properties")
        val placeholder = Regex("\\{\\d+}")
        for (locale in listOf("zh", "ja", "ko")) {
            val translated = loadProperties("messages/CommitHelperBundle_" + locale + ".properties")
            for (key in base.stringPropertyNames()) {
                val expected = placeholder.findAll(base.getProperty(key).orEmpty()).map { it.value }.toSet()
                if (expected.isEmpty()) continue
                val actual = placeholder.findAll(translated.getProperty(key).orEmpty()).map { it.value }.toSet()
                assertEquals(expected, actual, "placeholder mismatch for " + key + " in _" + locale)
            }
        }
    }
}
