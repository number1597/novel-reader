pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            // 直接把插件 id 映射到真实模块坐标，跳过 Gradle Plugin Portal 的 marker 校验，
            // 便于在内网/代理环境下使用本地已缓存的插件制品。
            if (requested.id.id == "org.jetbrains.intellij.platform") {
                useModule("org.jetbrains.intellij.platform:intellij-platform-gradle-plugin:${requested.version}")
            }
        }
    }
}

rootProject.name = "novel-reader"
