package com.mingliqiye.fatjar.gradle;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * {@code fatJar} 扩展：只提供默认值与 task 工厂，<b>不会自动创建任何 task</b>。
 *
 * <p>用法（Kotlin DSL）：</p>
 *
 * <pre>
 * fatJar {
 *     classesPrefix.set("APP-INF/classes")   // 默认 BOOT-INF/classes/
 *     libPrefix.set("APP-INF/lib")           // 默认 BOOT-INF/lib/
 *
 *     register("fatJar") {                   // task 名由调用方决定
 *         startClass.set("com.example.Main")
 *     }
 * }
 * </pre>
 *
 * <p>{@code register} 会自动接好业务 classes、runtimeClasspath、launcher 依赖（{@code fatJarLoader}）
 * 和默认文件名；launcher 依赖也可以用 {@code loaderNotation} 改，或设
 * {@code autoLoaderDependency=false} 完全自己加。传给最后参数的 Action 可以对单个 task 做任意覆盖
 * （含 archiveFile、prefix、manifest 属性、索引）。</p>
 */
public abstract class FatJarExtension {

    private final Project project;

    public FatJarExtension(Project project) {
        this.project = project;
    }

    /** 业务入口（MANIFEST 的 {@code Start-Class}）；不设置时取 {@code application.mainClass}。 */
    public abstract Property<String> getStartClass();

    /** launcher 类（MANIFEST 的 {@code Main-Class}），默认 {@code com.mingliqiye.fatjar.loader.JarLauncher}。 */
    public abstract Property<String> getLauncherClass();

    /** 业务 class/资源在 fat jar 内的前缀，默认 {@code BOOT-INF/classes/}，末尾斜杠可省略。 */
    public abstract Property<String> getClassesPrefix();

    /** 依赖 jar 在 fat jar 内的前缀，默认 {@code BOOT-INF/lib/}，末尾斜杠可省略。 */
    public abstract Property<String> getLibPrefix();

    /** 产物文件名，默认 {@code <project.name>-fat.jar}，输出到 {@code build/libs}。 */
    public abstract Property<String> getArchiveFileName();

    /**
     * 是否由插件自动往 {@code fatJarLoader} 配置里添加 launcher 依赖，默认 true。
     * 设为 false 时由使用方自己往该配置里加依赖。
     */
    public abstract Property<Boolean> getAutoLoaderDependency();

    /**
     * loader 依赖的写法：工程路径（如 {@code :jarLoader}）或 maven 坐标。
     * 默认：当前构建里有 {@code :jarLoader} 工程就用它，否则用
     * {@code com.mingliqiye.gradle.plugin.fatjar:jarLoader:<plugin version>}。
     */
    public abstract Property<String> getLoaderNotation();

    private boolean loaderDependencyAdded;

    /** 用默认配置注册一个 fat jar task。 */
    public TaskProvider<FatJarTask> register(String name) {
        return register(name, task -> {
        });
    }

    /** 注册 fat jar task，并允许调用方做任意覆盖。 */
    public TaskProvider<FatJarTask> register(String name, Action<? super FatJarTask> configureAction) {
        addLoaderDependencyIfNeeded();
        return project.getTasks().register(name, FatJarTask.class, task -> {
            SourceSet main = project.getExtensions()
                    .getByType(SourceSetContainer.class)
                    .getByName("main");

            task.setGroup("build");
            task.setDescription("Assembles an executable nested fat jar.");

            // 默认接线；后面 configureAction 里的设置会覆盖这些约定
            task.getLoaderJars().from(
                    project.getConfigurations().getByName(FatJarPlugin.LOADER_CONFIGURATION_NAME));
            task.getAppClasses().from(main.getOutput().getClassesDirs());
            task.getAppResources().from(project.provider(() -> {
                File resourcesDir = main.getOutput().getResourcesDir();
                return resourcesDir == null ? Collections.emptyList() : List.of(resourcesDir);
            }));
            task.getRuntimeLibs().from(project.getConfigurations().getByName("runtimeClasspath"));
            task.getStartClass().convention(getStartClass());
            task.getLauncherClass().convention(getLauncherClass());
            task.getClassesPrefix().convention(getClassesPrefix());
            task.getLibPrefix().convention(getLibPrefix());
            task.getArchiveFile().set(project.getLayout().getBuildDirectory()
                    .file(getArchiveFileName().map(fileName -> "libs/" + fileName)));

            configureAction.execute(task);
        });
    }

    /**
     * register 时按需把 launcher 依赖加进 {@code fatJarLoader}，避免使用方手写
     * {@code add("fatJarLoader", project(":jarLoader"))}。
     */
    private void addLoaderDependencyIfNeeded() {
        if (loaderDependencyAdded || !getAutoLoaderDependency().getOrElse(true)) {
            return;
        }
        String notation = getLoaderNotation().getOrElse("").trim();
        if (notation.isEmpty()) {
            return;
        }
        loaderDependencyAdded = true;
        if (notation.startsWith(":")) {
            project.getDependencies().add(
                    FatJarPlugin.LOADER_CONFIGURATION_NAME,
                    project.getDependencies().project(Map.of("path", notation)));
        } else {
            project.getDependencies().add(FatJarPlugin.LOADER_CONFIGURATION_NAME, notation);
        }
    }
}
