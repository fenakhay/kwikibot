import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec

val libs = the<LibrariesForLibs>()

plugins.withType<NodeJsPlugin> {
    the<NodeJsEnvSpec>().apply {
        version.set(libs.versions.node)
        downloadBaseUrl.set(null as String?)
    }
}

plugins.withType<YarnPlugin> {
    the<YarnRootEnvSpec>().downloadBaseUrl.set(null as String?)
}
