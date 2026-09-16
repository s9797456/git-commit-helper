import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType").get(),
            providers.gradleProperty("platformVersion").get(),
        )
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
        zipSigner()
    }
    // Pure-logic tests never touch platform classes; Gson comes from the platform at runtime,
    // so the tests that do use it get it from Maven Central explicitly.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.google.code.gson:gson:2.11.0")
    // BasePlatformTestCase is JUnit 3 style; the vintage engine lets the JUnit Platform run it
    // alongside the plain JUnit 5 logic tests.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// The since-build floor is 2024.1, which runs on JVM 17: force both compilers to 17 even
// though Gradle itself runs on the JDK 21 toolchain.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.test {
    useJUnitPlatform()
}

// The verifier defaults to ~/.pluginVerifier, which is not writable in every environment
// (sandboxed builds, locked-down CI). Keep its home inside the project instead.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask>().configureEach {
    systemProperty(
        "plugin.verifier.home.dir",
        layout.projectDirectory.dir(".verifier-home").asFile.absolutePath,
    )
}

// `verifyPluginSignature` reads the output of `signPlugin`, but the plugin does not declare that
// wiring itself: Gradle fails the build with a "uses this output ... without declaring an explicit
// or implicit dependency" validation problem unless it is stated here. It also does not inherit
// the certificate chain from the `signing` extension in 2.19.0, so wire it explicitly as well.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask>().configureEach {
    dependsOn("signPlugin")
    // Unlike `signPlugin`, this task does not decode the Base64 form used by the environment
    // variables: it writes the value verbatim into a .pem file, and the ZIP signer then reports
    // "No certificate data found". Decode it here, falling back to a raw PEM value.
    certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN").map { encoded ->
        runCatching { String(Base64.getDecoder().decode(encoded.trim())) }.getOrDefault(encoded)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.caye.commithelper"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }

        vendor {
            // Must stay in sync with the <vendor> element in src/main/resources/META-INF/plugin.xml:
            // that element is what actually ends up in the shipped plugin.xml, this block is for
            // the Marketplace publication metadata.
            name = "me-tool"
            email = "593259523@qq.com"
            url = providers.gradleProperty("pluginVendorUrl")
        }

        // Marketplace renders these two as HTML, so they are kept as HTML sources rather than
        // derived from the Markdown README.
        description = providers.fileContents(
            layout.projectDirectory.file("marketplace/description.html"),
        ).asText
        changeNotes = providers.fileContents(
            layout.projectDirectory.file("marketplace/change-notes.html"),
        ).asText
    }

    pluginVerification {
        ides {
            // Acceptance criteria: the since-build floor, an intermediate, and the local IDE.
            // IntellijIdeaCommunity stopped being published after 2025.3 (253); newer releases
            // only exist as the unified `IntellijIdea` distribution.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
