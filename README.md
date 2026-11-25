# fat-jar

自研的 Spring Boot 风格嵌套 fat jar：一个 Gradle 插件 + 一个能读「jar 里的 jar」的 ClassLoader，
打出来的 jar 可以直接 `java -jar` 运行。

## 三个模块

| 模块 | 作用 |
|---|---|
| `jarLoader/` | 运行时。`JarLauncher`（真正的 `Main-Class`）+ `NestedJarClassLoader`（按需加载）+ `ZipReader`（随机读 ZIP 中央目录）+ 自定义 `fatjar:` 资源协议。不依赖 Gradle API。 |
| `plugin/` | Gradle 插件（included build）。提供 `fatJar` 扩展与 `FatJarTask`，**不自动创建 task**；launcher 依赖也由插件自动接线。 |
| `testFatJar/` | 示例应用。验证嵌套 jar 类加载、资源读取、SPI 合并和自定义前缀。 |

`plugin` 以 included build 接入（根 `settings.gradle.kts` 的 `pluginManagement.includeBuild("plugin")`），
因为 Gradle 的 `plugins {}` 只能从仓库 / buildSrc / included build 解析插件，不能直接引用同构建内的兄弟工程。

## 产物结构

```
META-INF/MANIFEST.MF      Main-Class      = com.mingliqiye.fatjar.loader.JarLauncher
                          Start-Class     = 业务 main 类
                          Fat-Jar-Classes = 业务 classes 前缀（默认 BOOT-INF/classes/）
                          Fat-Jar-Lib     = 依赖 jar 前缀（默认 BOOT-INF/lib/）
                          其它自定义 manifest 属性
<launcher 包>/*.class                     launcher 与 ClassLoader（固定放 jar 根目录）
<classesPrefix>/...                       业务 class 与资源
<libPrefix>/<dep>.jar                     依赖 jar，整包嵌套（STORED 不压缩，可随机定位）
```

launcher 读 MANIFEST → 用 `NestedJarClassLoader` 把所有根（classes 目录 + 每个嵌套 jar）
读进内存建索引 → 反射调用 `Start-Class#main`，并把线程上下文类加载器换成它。
**前缀是从 MANIFEST 读的**，所以 `classesPrefix` / `libPrefix` 改了也能正常加载。
产物就是一个普通可执行 jar，不额外生成校验索引。

## 使用

插件不注册 task（要自己 `register`），也**不需要手写 `fatJarLoader` 依赖**：

```kotlin
plugins {
    application
    id("com.mingliqiye.fatjar")
}

// 不需要 dependencies { fatJarLoader(...) }：
// 插件会自动用当前构建里的 :jarLoader 工程；没有该工程时退回
// com.mingliqiye.gradle.plugin.fatjar:jarLoader:<plugin version>

fatJar {
    // 全局默认值（均可按 task 覆盖）
    startClass.set("com.example.Main")        // 默认取 application.mainClass
    classesPrefix.set("BOOT-INF/classes")     // 末尾斜杠可省
    libPrefix.set("BOOT-INF/lib")
    archiveFileName.set("app-fat.jar")        // 默认 <project.name>-fat.jar

    register("fatJar")                        // task 名、数量由使用方决定

    // 再打一个不同前缀 / 不同 manifest 的版本也完全可以
    register("fatJarCustom") {
        classesPrefix.set("APP-INF/classes")
        libPrefix.set("APP-INF/lib")
        archiveFile.set(layout.buildDirectory.file("libs/app-custom.jar"))
        manifestAttributes.put("X-Example", "custom")
    }
}
```

```bash
./gradlew fatJar
java -jar build/libs/<name>-fat.jar
```

## 可自定义项

- task 名与数量：`fatJar.register("任意名字")`；
- `autoLoaderDependency`、`loaderNotation`（默认自动加 loader 依赖，可改坐标或关掉自己加）；
- `startClass`、`launcherClass`；
- `classesPrefix`、`libPrefix`（写进 MANIFEST，由 launcher 读取）；
- `archiveFile` / `archiveFileName`；
- `manifestAttributes`（额外 manifest 属性，同名覆盖默认值）；
- `loaderJars`、`appClasses`、`appResources`、`runtimeLibs`（直接换掉默认接线）；
- `FatJarTask` 是 public 的，也可以完全手工 `tasks.register<FatJarTask>(...)` 自己接。

## 版本

版本只在仓库根的 [gradle.properties](gradle.properties) 里维护：

```properties
version=1.0.0
```

- 根构建的子工程（`jarLoader`、`testFatJar`）直接继承；
- `plugin` 是 composite build，不继承父构建的 `gradle.properties`，所以
  [plugin/build.gradle.kts](plugin/build.gradle.kts) 会显式读一次根文件，保持同一版本
  （插件 jar 的 `Implementation-Version` 也来自这里，默认 loader 坐标跟着走）。

## 构建/运行（示例）

```bash
./gradlew :testFatJar:fatJar :testFatJar:fatJarCustom
java -jar testFatJar/build/libs/testFatJar-fat.jar
java -jar testFatJar/build/libs/testFatJar-custom-prefix.jar
```

预期输出：`greetings found : 2`（一个来自业务 classes 前缀，一个来自嵌套依赖 jar），
`main class loader : com.mingliqiye.fatjar.loader.NestedJarClassLoader`。
custom 版本的 jar 结构是 `APP-INF/classes` + `APP-INF/lib`，同样能跑。

## 几个关键点

- **`findResources` 不能去重**：同名 `META-INF/services` 必须把每个根都返回，否则 SPI 丢实现（这是摊平式 fat jar 的经典坑）。
- **业务 classes 根排第一**：同名类以业务代码为准；父加载器用 `ClassLoader.getPlatformClassLoader()`，避免 JDK 与应用类互相污染。
- **资源 URL 必须能 openStream**：自定义 `fatjar:/<root>/<name>` 协议，否则 `ServiceLoader`、`getResourceAsStream` 都会挂。
- **`getResource` 要重写**：默认实现是父加载器优先，这里业务资源优先。
- **嵌套依赖 jar 支持 Multi-Release**：jar 的 manifest 声明 `Multi-Release: true` 时，类/资源按运行时版本
  选 `META-INF/versions/N/` 里不超过当前版本的最高一份；`META-INF/` 自身不参与版本化。
- **依赖 jar 以 STORED 嵌套**：不压缩、字节连续，loader 启动时只解析中央目录，`loadClass`/`openStream`
  时再按 offset 读；这样嵌套 jar 不需要整体驻留堆内存。旧格式（DEFLATED 嵌套）会退化为整体读入，仅作兼容。
- 解压复制依赖目录容易多套一层（如 `BOOT-INF/classes/classes`），表现为「代码改了没生效」——本项目不这么做。
- 固定时间戳，保证同样的输入产出同样的字节（可复现构建）。
- Kotlin 块注释是嵌套的，注释文本里不要出现「斜杠 + 星号」的组合，否则会把后面的代码一起吞掉（本项目踩过）。

## 局限

- 嵌套依赖 jar 以 STORED（不压缩）写入，fat jar 会比全压缩方案大一些——这是随机定位的代价。
- 资源 `openStream()` 仍会把该 entry 完整读进内存，类加载也一次读一个 class；但不再预读整个嵌套 jar。
- 入口是 `JarLauncher`：`java -jar` 或 `java -cp <fat.jar> com.mingliqiye.fatjar.loader.JarLauncher` 都能启动；
  但不能直接 `java -cp` 跑业务 main（业务类在嵌套前缀里）。与 Spring Boot 自家 launcher 不兼容。
- 多版本只覆盖嵌套依赖 jar；`BOOT-INF/classes` 不是标准多版本布局，放在里面的 `META-INF/versions` 不会被识别。
- 签名 jar、`module-info` 仍未处理；不支持加密 / 分卷 zip。
