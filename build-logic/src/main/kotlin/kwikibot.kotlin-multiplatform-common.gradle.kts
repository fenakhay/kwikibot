import java.util.zip.GZIPInputStream
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("kwikibot.code-quality")
    id("kwikibot.dokka")
}

val libs = the<LibrariesForLibs>()

kotlin {
    explicitApi()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        configureEach {
            if (name.endsWith("Test")) {
                languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.io.core)
        }
    }
}

val testResources = layout.projectDirectory.dir("src/commonTest/resources")
val preparedResources = layout.buildDirectory.dir("test-resources")

val prepareTestResources =
    tasks.register("prepareTestResources") {
        description = "Copies the test fixtures where every platform's tests read them, unpacking any .gz."
        group = "verification"

        val source = testResources
        val target = preparedResources
        inputs
            .files(fileTree(source))
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .withPropertyName("resources")
        outputs.dir(target)

        doLast {
            val out = target.get().asFile
            out.deleteRecursively()
            out.mkdirs()
            val root = source.asFile
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative.endsWith(".gz")) {
                    val unpacked = out.resolve(relative.removeSuffix(".gz"))
                    unpacked.parentFile.mkdirs()
                    GZIPInputStream(file.inputStream()).use { input ->
                        unpacked.outputStream().use { input.copyTo(it) }
                    }
                } else {
                    file.copyTo(out.resolve(relative))
                }
            }
        }
    }

val generatedTestSource = layout.buildDirectory.dir("generated/test-files/kotlin")
val testPackage = "com.fenakhay.kwikibot." + project.name.removePrefix("kwikibot-").replace("-", "")

val generateTestFiles =
    tasks.register("generateTestFiles") {
        description = "Writes the helpers every platform's tests use to reach fixtures and scratch directories."
        group = "build"

        val output = generatedTestSource
        val directory = preparedResources.get().asFile.invariantSeparatorsPath
        val pkg = testPackage
        inputs.property("directory", directory)
        inputs.property("package", pkg)
        outputs.dir(output)

        doLast {
            val target = output.get().asFile.resolve(pkg.replace('.', '/'))
            target.mkdirs()
            target
                .resolve("TestFiles.kt")
                .writeText(
                    """
                    package $pkg

                    import kotlin.uuid.Uuid
                    import kotlinx.io.buffered
                    import kotlinx.io.files.Path
                    import kotlinx.io.files.SystemFileSystem
                    import kotlinx.io.files.SystemTemporaryDirectory
                    import kotlinx.io.readString

                    internal val TEST_RESOURCES: Path = Path("$directory")

                    internal fun testResource(name: String): String =
                        SystemFileSystem.source(Path(TEST_RESOURCES, name)).buffered().use { it.readString() }

                    internal fun temporaryDirectory(prefix: String): Path {
                        val directory = Path(SystemTemporaryDirectory, "${'$'}prefix-${'$'}{Uuid.random()}")
                        SystemFileSystem.createDirectories(directory)
                        return directory
                    }

                    internal fun filesUnder(directory: Path): List<Path> =
                        SystemFileSystem.list(directory).flatMap { child ->
                            if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) filesUnder(child)
                            else listOf(child)
                        }
                    """
                        .trimIndent() + "\n"
                )
        }
    }

val hasTests = layout.projectDirectory.dir("src/commonTest").asFile.isDirectory

if (hasTests) {
    kotlin.sourceSets.commonTest {
        kotlin.srcDir(generateTestFiles)
    }
}

tasks.withType<AbstractTestTask>().configureEach {
    dependsOn(prepareTestResources)
}
