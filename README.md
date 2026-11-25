# fatjar

一个自研的、Spring Boot 风格的**嵌套 fat jar** Gradle 插件：把应用和依赖打成一个可执行 jar，
依赖以嵌套 jar 原样保留，启动时按需读取，不把整个依赖树读进内存。

## 特性

- **嵌套 fat jar**：业务类在 `BOOT-INF/classes`，依赖 jar 整包放在 `BOOT-INF/lib`，
  不摊平、不合并，`META-INF/services` 不会互相覆盖。
- **可执行**：自带 `JarLauncher` 和 `NestedJarClassLoader`，`java -jar app-fat.jar` 直接运行。
- **懒加载**：自研 `ZipReader` 解析 ZIP 中央目录（支持 ZIP64），启动时只读目录；
  `loadClass` / `openStream` 时才按 offset 读取对应 entry，嵌套 jar 不需要整体驻留堆内存。
- **Multi-Release 支持**：嵌套 jar 声明 `Multi-Release: true` 时，按运行时版本选择
  `META-INF/versions/N` 中不超过当前版本的最高一份。
- **入口自动装配**：主类标 `@FastJarApplication` 即可，插件扫描编译产物自动写入 `Start-Class`。
- **可定制**：task 名、入口、classes/lib 前缀、产物名、manifest 属性、loader 依赖等都可配。

> 本项目不是 Spring Boot 的替代品，也不兼容 Spring Boot 自己的 `JarLauncher`；
> 布局相似，定位是可学习、可改造的自研实现。

## 要求

- JDK 17+（构建与运行）
- Gradle 9.x（仓库自带 wrapper）

## 快速开始

从源码构建并运行示例：

```bash
git clone <repo-url> fat-jar
cd fat-jar

./gradlew :testFatJar:fatJar
java -jar testFatJar/build/libs/testFatJar-fat.jar
```

预期输出包含：

```
main class loader : com.mingliqiye.fatjar.loader.NestedJarClassLoader
greetings found   : 2
  - Hello from BOOT-INF/classes
  - Hola desde BOOT-INF/lib
multi-release     : versioned
```

## 在自己的项目中使用

目前还没有发布到公共仓库，可以先发布到 Maven 本地仓库：

```bash
./gradlew :annotation:publishToMavenLocal :jarLoader:publishToMavenLocal
./gradlew -p plugin publishToMavenLocal
```

消费项目：

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    application
    id("com.mingliqiye.fatjar") version "1.1.1"
}

repositories {
    mavenLocal()
}

fatJar {
    // 插件不会自动创建 task，需要自己注册
    register("fatJar")
}
```

入口有两种方式，二选一：

```java
import com.mingliqiye.fatjar.annotation.FastJarApplication;

@FastJarApplication
public final class Application {
    public static void main(String[] args) {
        System.out.println("hello");
    }
}
```

或者显式指定：

```kotlin
fatJar {
    startClass.set("com.example.Application")
    register("fatJar")
}
```

然后：

```bash
./gradlew fatJar
java -jar build/libs/<project-name>-fat.jar
```

## 产物结构

```
META-INF/MANIFEST.MF      Main-Class      = com.mingliqiye.fatjar.loader.JarLauncher
                          Start-Class     = 业务入口类
                          Fat-Jar-Classes = 业务 classes 前缀（默认 BOOT-INF/classes/）
                          Fat-Jar-Lib     = 依赖 jar 前缀（默认 BOOT-INF/lib/）
com/mingliqiye/fatjar/loader/*.class     launcher 与 ClassLoader（固定在 jar 根目录）
BOOT-INF/classes/...                     业务 class 与资源
BOOT-INF/lib/*.jar                       依赖 jar（STORED 不压缩，可随机定位）
```

启动流程：

1. JVM 以 `JarLauncher` 为 `Main-Class` 启动；
2. launcher 读取 MANIFEST，解析 `Start-Class` 及前缀；
3. `NestedJarClassLoader` 解析外层与嵌套 jar 的中央目录，建立轻量索引；
4. 反射调用 `Start-Class#main`，并把线程上下文类加载器切换成它。

## 模块

| 模块 | 说明 |
|---|---|
| `annotation/` | `@FastJarApplication` 注解，只在编译期需要。 |
| `jarLoader/` | 运行时：`JarLauncher`、`NestedJarClassLoader`、`ZipReader`、`fatjar:` 资源协议。 |
| `plugin/` | Gradle 插件，提供 `fatJar` 扩展与 `FatJarTask`。 |
| `testFatJar/` | 示例应用，覆盖嵌套 jar、SPI 合并、自定义前缀、Multi-Release 与注解入口。 |

`plugin` 以 composite build 的形式接入根构建（`pluginManagement { includeBuild("plugin") }`），
因为 Gradle 的 `plugins {}` 只能从仓库、`buildSrc` 或 included build 解析插件。

## 任务与配置

插件不自动注册 task，由使用方决定名字和数量：

```kotlin
fatJar {
    register("fatJar")
    register("fatJarCustom") {
        classesPrefix.set("APP-INF/classes")
        libPrefix.set("APP-INF/lib")
        archiveFile.set(layout.buildDirectory.file("libs/app-custom.jar"))
        manifestAttributes.put("X-Example", "custom")
    }
}
```

常用属性：

| 属性 | 默认值 | 说明 |
|---|---|---|
| `startClass` | 空 | 显式入口，优先级最高 |
| `startClassAnnotation` | `com.mingliqiye.fatjar.annotation.FastJarApplication` | 入口注解，置空关闭扫描 |
| `launcherClass` | `com.mingliqiye.fatjar.loader.JarLauncher` | MANIFEST 的 `Main-Class` |
| `classesPrefix` | `BOOT-INF/classes/` | 业务 classes 前缀，末尾斜杠可省 |
| `libPrefix` | `BOOT-INF/lib/` | 依赖 jar 前缀 |
| `archiveFileName` | `<project.name>-fat.jar` | 产物文件名，输出到 `build/libs` |
| `manifestAttributes` | 空 | 追加/覆盖 manifest 属性 |
| `autoLoaderDependency` | `true` | 自动接线 loader 与注解依赖 |
| `loaderNotation` | `:jarLoader` 或发布坐标 | loader 依赖写法 |

单个 task 还可以直接覆盖 `startClass`、`classesPrefix`、`libPrefix`、`archiveFile`、
`loaderJars`、`appClasses`、`appResources`、`runtimeLibs` 等属性。

## 入口扫描原理

`FatJarTask` 使用自研的 `ClassFileScanner` 解析编译产物的 class 文件，读取类级
`RuntimeVisibleAnnotations` / `RuntimeInvisibleAnnotations`，不加载类、不依赖 ASM：

- 找到唯一带指定注解、且声明了 `public static void main(String[])` 的类 → 写入 `Start-Class`；
- 多个类带同一注解，或注解类没有合法 main → 构建失败并列出类名；
- 注解不存在时回退到 `application.mainClass`。

## 设计说明

- **不摊平依赖**：摊平会覆盖同名 `META-INF/services` 文件，导致 SPI 丢实现；嵌套布局没有这个问题。
- **资源查找不去重**：`findResources` 返回所有根的同名资源，`ServiceLoader` 才能看到所有 provider。
- **父加载器用 platform loader**：避免 JDK 类、应用类和依赖类互相污染。
- **`fatjar:` 资源协议**：资源 URL 支持 `openStream()`，否则 `getResourceAsStream` / `ServiceLoader` 会失败。
- **依赖 jar 用 STORED**：不压缩换取可随机定位；这是“按需读取”的代价，产物会变大一些。
- **固定时间戳**：相同输入产出相同字节，构建可复现。

## 已知限制

- 嵌套依赖 jar 以 STORED 写入，fat jar 比全压缩方案大。
- 单次 `openStream()` 会把该 entry 完整读入内存；类加载一次读一个 class，但不会预读整个嵌套 jar。
- 与 Spring Boot 的 `JarLauncher` 布局相似但不兼容。
- Multi-Release 只覆盖嵌套依赖 jar；`BOOT-INF/classes` 不是标准多版本布局。
- 不处理签名 jar、`module-info`；不支持加密 / 分卷 ZIP（支持 ZIP64）。

## 开发者备注

- 版本统一在根 [gradle.properties](gradle.properties) 中维护；`plugin` 是 composite build，
  会显式读取该文件。
- 构建示例：`./gradlew :testFatJar:fatJar :testFatJar:fatJarCustom`。
- 发布相关配置使用 `maven-publish`，仓库地址与凭据通过 `repoUrl` / `REPO_URL` 等外部参数提供，
  不要提交凭据。

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

Copyright 2026 minglipro.
