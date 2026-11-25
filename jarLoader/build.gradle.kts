import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile

/*
 * fat jar 运行时：launcher + 能读「jar 里的 jar」的 ClassLoader。
 *
 * 这个工程刻意不依赖 Gradle API，就是一个普通 Java 库，
 * 由 fatjar 插件复制到产物 jar 的根目录（对应 Spring Boot 的 org/springframework/boot/loader）。
 */

plugins {
    `java-library`
    `maven-publish`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

java {
    withSourcesJar()
}

// 发布目标：由环境提供（init 脚本 / CI），或通过 -PrepoUrl / REPO_URL 指定；凭据走外部参数
val repoUrl = providers.gradleProperty("repoUrl")
    .orElse(providers.environmentVariable("REPO_URL"))

val repoUsername = providers.gradleProperty("repoUsername")
    .orElse(providers.environmentVariable("REPO_USERNAME"))
val repoPassword = providers.gradleProperty("repoPassword")
    .orElse(providers.environmentVariable("REPO_PASSWORD"))

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("fatjar loader")
                description.set("Launcher and nested jar class loader runtime for the fatjar Gradle plugin.")
            }
        }
    }
    // 已有发布仓库（由 init 脚本 / CI 注入）时不再重复添加；否则用 repoUrl 指定的仓库。
    val publishRepositories = repositories
    if (publishRepositories.isEmpty() && repoUrl.isPresent) {
        publishRepositories.maven {
            name = "private"
            url = uri(repoUrl.get())
            val repoUser = repoUsername.orNull
            val repoPass = repoPassword.orNull
            if (repoUser != null && repoPass != null) {
                credentials {
                    username = repoUser
                    password = repoPass
                }
            }
        }
    }
}
