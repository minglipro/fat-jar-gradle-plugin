import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile

/*
 * fatjar Gradle 插件。
 *
 * 插件 id : com.mingliqiye.fatjar
 * 坐标    : com.mingliqiye.gradle.plugin.fatjar:plugin:<version>
 *
 * 版本来自仓库根的 gradle.properties：composite build 不会继承父构建的
 * gradle.properties，所以这里显式读一次，保持与根工程同版本。
 *
 * 产出物：把业务工程打成一个 Spring Boot 风格的嵌套 fat jar
 *         （BOOT-INF/classes + BOOT-INF/lib 下的依赖 jar + 根目录 launcher），
 *         入口由自定义 JarLauncher 接管，可 java -jar 直接运行。
 *
 * 注意：Kotlin 的块注释是嵌套的，注释文本里不要出现斜杠加星号的组合，
 * 否则会把后面的代码一起注释掉（本文件最初就踩过这个坑）。
 */

plugins {
    `java-gradle-plugin`
}

val sharedProperties = Properties()
val sharedPropertiesFile = layout.projectDirectory.file("../gradle.properties").asFile
if (sharedPropertiesFile.isFile) {
    sharedPropertiesFile.inputStream().use(sharedProperties::load)
}

group = "com.mingliqiye.gradle.plugin.fatjar"
version = sharedProperties.getProperty("version") ?: "1.0.0"

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

gradlePlugin {
    plugins {
        create("fatJar") {
            id = "com.mingliqiye.fatjar"
            implementationClass = "com.mingliqiye.fatjar.gradle.FatJarPlugin"
            displayName = "Fat JAR plugin"
            description = "Builds an executable nested fat JAR with a self-contained launcher and class loader."
        }
    }
}

// 让插件能在运行时读到自己的版本，用于默认 loader 坐标
tasks.jar {
    manifest {
        attributes(mapOf("Implementation-Version" to version))
    }
}
