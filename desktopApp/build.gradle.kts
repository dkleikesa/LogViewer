import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.qcmian.logviewer.MainKt"

        // 全内存加载模型：允许 JVM 使用 60% 物理内存（1GB 日志常驻 ≈ 1.2GB 堆）
        // macOS：-Xdock 设置裸 JVM 进程（gradle run 无 app bundle）的 Dock 图标/名称
        if (System.getProperty("os.name")?.lowercase()?.contains("mac") == true) {
            jvmArgs(
                "-XX:MaxRAMPercentage=60.0",
                "-Xdock:icon=${rootDir}/desktopApp/src/main/resources/icons/icon-256.png",
                "-Xdock:name=LogViewer",
            )
        } else {
            jvmArgs("-XX:MaxRAMPercentage=60.0")
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.qcmian.logviewer"
            packageVersion = "1.0.0"
        }
    }
}