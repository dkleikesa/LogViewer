rootProject.name = "LogViewer"

// 国内镜像优先，官方仓库放最后兜底（海外网络下会自动落到官方源）
pluginManagement {
    repositories {
        // 阿里云：Gradle 插件门户代理（含 foojay-resolver-convention 等插件标记）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        // 阿里云：Maven Central + Google Maven 代理
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        // 备选镜像：华为云 Central 代理
        maven("https://repo.huaweicloud.com/repository/maven/")
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://repo.huaweicloud.com/repository/maven/")
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":desktopApp")
include(":shared")
include(":webApp")
