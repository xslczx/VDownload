pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // PREFER_PROJECT：允许全局 init 脚本（如 ~/.gradle/init.gradle.kts 的阿里云镜像）注入仓库并优先生效；
    // 没有全局脚本的环境仍然走下面的 google()/mavenCentral()，两种环境都能构建。
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VDownload"
include(":app")
