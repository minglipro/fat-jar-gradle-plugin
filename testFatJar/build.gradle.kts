import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile

/*
 * 示例应用：验证 fatjar 插件。
 *
 * - 业务代码 + EnglishGreeting 在业务 classes 前缀下
 * - SpanishGreeting 单独打成一个 jar，进依赖前缀，用来验证：
 *   1) 嵌套 jar 里的类能被自定义 ClassLoader 加载
 *   2) 两个 jar 里同名的 META-INF/services 文件都能被 getResources 合并出来（SPI 不丢实现）
 * - 插件不会自动加 task：这里显式 register 了默认 task 和自定义前缀的 task
 */

plugins {
    id("com.mingliqiye.fatjar")
}

tasks.withType<JavaCompile>().configureEach {
    // 多版本 jar 的 versioned 部分用 21 编译，其余用 17
    if (name == "compileMrVersionJava") {
        options.release.set(21)
    } else {
        options.release.set(17)
    }
}

// 第二个 source set，单独打一个「依赖 jar」，最终落在依赖前缀下
val libProvider = sourceSets.create("libProvider")

val libProviderJar = tasks.register<Jar>("libProviderJar") {
    group = "build"
    description = "Builds the provider library that is nested inside the fat jar."
    archiveBaseName.set("testfatjar-provider")
    archiveVersion.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(libProvider.output)
}

// 多版本 jar：base 与 versioned 各一份 Probe，用来验证 META-INF/versions/21 优先于 base
val mrBase = sourceSets.create("mrBase")
val mrVersion = sourceSets.create("mrVersion")

val mrJar = tasks.register<Jar>("mrJar") {
    group = "build"
    description = "Builds a multi-release jar that is nested inside BOOT-INF/lib."
    archiveBaseName.set("testfatjar-mr")
    archiveVersion.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(mrBase.output)
    into("META-INF/versions/21") {
        from(mrVersion.output)
    }
    manifest {
        attributes("Multi-Release" to "true")
    }
}

dependencies {
    "libProviderImplementation"(sourceSets.main.get().output)

    // 不需要手写 fatJarLoader：插件会自动用 :jarLoader 工程（或已发布的 loader 坐标）

    // 让 libProvider / mrJar 进入 runtimeClasspath，从而被放进嵌套依赖目录
    runtimeOnly(files(libProviderJar))
    runtimeOnly(files(mrJar))
}

fatJar {
    // 下面都是默认值，写出来方便按需修改
    classesPrefix.set("BOOT-INF/classes/")
    libPrefix.set("BOOT-INF/lib/")
    // archiveFileName 默认 <project>-<version>-fat.jar，这里不覆盖

    // 插件不自动建 task，由使用方决定名字和配置
    register("fatJar")

    // 第二个 task：验证 classesPrefix / libPrefix / manifest 都可以按 task 覆盖
    register("fatJarCustom") {
        classesPrefix.set("APP-INF/classes")
        libPrefix.set("APP-INF/lib")
        archiveFile.set(layout.buildDirectory.file("libs/testFatJar-custom-prefix.jar"))
        manifestAttributes.put("X-Fat-Jar-Example", "custom-prefix")
    }
}
