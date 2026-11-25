package com.mingliqiye.fatjar.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.plugins.JavaApplication;

/**
 * 把业务工程打成 Spring Boot 风格的嵌套 fat jar。
 *
 * <p>插件本身<b>不注册 task</b>：只创建 {@code fatJar} 扩展与 {@code fatJarLoader} 依赖配置，
 * 由使用方在构建脚本里显式 {@code register}，这样才能对每个 task 单独定制入口、前缀、
 * 产物名和 manifest。</p>
 *
 * <pre>
 * plugins {
 *     application
 *     id("com.mingliqiye.fatjar")
 * }
 *
 * dependencies {
 *     fatJarLoader("com.mingliqiye.gradle.plugin.fatjar:jarLoader:1.0.0")
 * }
 *
 * fatJar {
 *     register("fatJar")
 * }
 *
 * // ./gradlew fatJar && java -jar build/libs/&lt;name&gt;-fat.jar
 * </pre>
 */
public class FatJarPlugin implements Plugin<Project> {

    public static final String EXTENSION_NAME = "fatJar";
    public static final String LOADER_CONFIGURATION_NAME = "fatJarLoader";
    public static final String DEFAULT_LAUNCHER_CLASS = "com.mingliqiye.fatjar.loader.JarLauncher";

    /** loader 的 maven 坐标（未发布前本地构建会优先用 :jarLoader 工程）。 */
    public static final String LOADER_GROUP = "com.mingliqiye.gradle.plugin.fatjar";
    public static final String LOADER_NAME = "jarLoader";
    private static final String LOADER_FALLBACK_VERSION = "1.0.0";

    /** 嵌套 jar 内业务 classes 的默认前缀。 */
    public static final String CLASSES_PREFIX = "BOOT-INF/classes/";

    /** 嵌套 jar 内依赖 jar 的默认前缀。 */
    public static final String LIB_PREFIX = "BOOT-INF/lib/";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");

        FatJarExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, FatJarExtension.class, project);

        extension.getLauncherClass().convention(DEFAULT_LAUNCHER_CLASS);
        extension.getArchiveFileName().convention(project.provider(() -> project.getName() + "-fat.jar"));
        extension.getClassesPrefix().convention(CLASSES_PREFIX);
        extension.getLibPrefix().convention(LIB_PREFIX);
        extension.getAutoLoaderDependency().convention(true);
        extension.getLoaderNotation().convention(project.provider(() -> {
            if (project.getRootProject().findProject(":jarLoader") != null) {
                return ":jarLoader";
            }
            return defaultLoaderCoordinates();
        }));
        extension.getStartClass().convention(project.provider(() -> {
            JavaApplication application = project.getExtensions().findByType(JavaApplication.class);
            return application == null ? "" : application.getMainClass().getOrElse("");
        }));

        Configuration loader = project.getConfigurations().create(LOADER_CONFIGURATION_NAME);
        loader.setDescription("Launcher and nested jar class loader classes that are copied into the fat jar root.");
        loader.setCanBeConsumed(false);
        loader.setCanBeResolved(true);
        loader.attributes(attributes -> {
            attributes.attribute(Usage.USAGE_ATTRIBUTE,
                    project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME));
            attributes.attribute(Category.CATEGORY_ATTRIBUTE,
                    project.getObjects().named(Category.class, Category.LIBRARY));
            attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    project.getObjects().named(LibraryElements.class, LibraryElements.JAR));
        });
    }

    /**
     * 默认 loader 坐标：版本优先取插件 jar 的 {@code Implementation-Version}，
     * 取不到时退回 1.0.0。
     */
    public static String defaultLoaderCoordinates() {
        String version = FatJarPlugin.class.getPackage() == null
                ? null
                : FatJarPlugin.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = LOADER_FALLBACK_VERSION;
        }
        return LOADER_GROUP + ":" + LOADER_NAME + ":" + version;
    }
}
