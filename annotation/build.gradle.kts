import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile

/*
 * 只放注解的最小模块：业务工程 compileOnly 依赖它即可使用 @FastJarApplication。
 * group/version 继承根构建的 allprojects 配置。
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
                name.set("fatjar annotations")
                description.set("Annotations scanned by the fatjar Gradle plugin.")
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
