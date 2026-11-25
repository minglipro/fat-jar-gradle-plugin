/*
 * fat-jar —— Spring Boot 风格的嵌套 fat jar 构建实验。
 *
 * 结构说明：
 *  - jarLoader   : 运行时（launcher + 自定义 ClassLoader），会被复制进 fat jar 根目录
 *  - plugin      : Gradle 插件，负责产出嵌套 fat jar
 *  - testFatJar  : 示例应用，用插件打出来的 jar 可以 java -jar 直接跑
 *
 * plugin 作为独立构建（included build）接入，是因为 Gradle 的 plugins {} 块
 * 只能从仓库 / buildSrc / included build 解析插件，不能直接引用同一构建内的兄弟工程。
 */

pluginManagement {
    includeBuild("plugin")
    repositories {
        maven {
            url = uri("https://repository.mingliqiye.com/repository/maven-public/")
        }
        gradlePluginPortal()
    }
}

rootProject.name = "fat-jar"

include("jarLoader")
include("testFatJar")
