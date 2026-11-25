import org.gradle.api.tasks.compile.JavaCompile

/*
 * fat jar 运行时：launcher + 能读「jar 里的 jar」的 ClassLoader。
 *
 * 这个工程刻意不依赖 Gradle API，就是一个普通 Java 库，
 * 由 fatjar 插件复制到产物 jar 的根目录（对应 Spring Boot 的 org/springframework/boot/loader）。
 */

plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
