import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// 国内镜像：JS/Wasm 的 Node 发行包默认从 nodejs.org/dist 下载，改走 npmmirror。
// 该属性在新版 Kotlin 插件里标了 deprecation(ERROR)，但仍是唯一可改下载源的入口。
@Suppress("DEPRECATION_ERROR")
private fun Project.useChinaNodeMirror() {
    the<NodeJsRootExtension>().nodeDownloadBaseUrl = "https://cdn.npmmirror.com/binaries/node"
}

rootProject.plugins.withType<NodeJsRootPlugin> {
    rootProject.useChinaNodeMirror()
}

allprojects {
    // npm 在 build/js 下执行，工作区根目录的 .npmrc 不生效，用环境变量注入镜像源
    tasks.configureEach {
        (this as? org.gradle.process.ProcessForkOptions)?.environment("npm_config_registry", "https://registry.npmmirror.com")
    }
}
