package com.mingliqiye.fatjar.loader;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * fat jar 的真正入口（对应 Spring Boot 的 {@code JarLauncher}）。
 *
 * <p>插件在 MANIFEST 里写入：</p>
 *
 * <pre>
 * Main-Class : com.mingliqiye.fatjar.loader.JarLauncher
 * Start-Class: 业务 main 类
 * </pre>
 *
 * <p>流程：定位自身 jar → 读 MANIFEST → 创建 {@link NestedJarClassLoader} →
 * 设置线程上下文类加载器 → 反射调用 {@code Start-Class#main(String[])}。</p>
 */
public final class JarLauncher {

    public static final String START_CLASS_ATTRIBUTE = "Start-Class";
    public static final String CLASSES_ATTRIBUTE = "Fat-Jar-Classes";
    public static final String LIB_ATTRIBUTE = "Fat-Jar-Lib";
    public static final String DEFAULT_CLASSES_PREFIX = "BOOT-INF/classes/";
    public static final String DEFAULT_LIB_PREFIX = "BOOT-INF/lib/";

    private JarLauncher() {
    }

    public static void main(String[] args) {
        try {
            launch(args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Application main method failed", cause);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to start fat jar", throwable);
        }
    }

    private static void launch(String[] args) throws Exception {
        Path archive = locateArchive();
        String startClass;
        String classesPrefix;
        String libPrefix;

        try (JarFile jar = new JarFile(archive.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                throw new IllegalStateException("No META-INF/MANIFEST.MF found in " + archive);
            }
            Attributes attributes = manifest.getMainAttributes();
            startClass = attributes.getValue(START_CLASS_ATTRIBUTE);
            if (startClass == null || startClass.isBlank()) {
                throw new IllegalStateException(
                        "MANIFEST of " + archive + " has no " + START_CLASS_ATTRIBUTE
                                + " attribute; was this jar built by the fatjar plugin?");
            }
            classesPrefix = withTrailingSlash(
                    attributes.getValue(CLASSES_ATTRIBUTE), DEFAULT_CLASSES_PREFIX);
            libPrefix = withTrailingSlash(attributes.getValue(LIB_ATTRIBUTE), DEFAULT_LIB_PREFIX);
        }

        NestedJarClassLoader classLoader = NestedJarClassLoader.create(archive, classesPrefix, libPrefix);

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(classLoader);
        try {
            Class<?> applicationClass = Class.forName(startClass, false, classLoader);
            Method main = applicationClass.getDeclaredMethod("main", String[].class);
            main.setAccessible(true);
            main.invoke(null, (Object) args);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static String withTrailingSlash(String value, String fallback) {
        String prefix = value == null || value.isBlank() ? fallback : value;
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private static Path locateArchive() throws Exception {
        String override = System.getProperty("fatjar.archive");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }

        CodeSource codeSource = JarLauncher.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new IllegalStateException(
                    "Cannot determine the fat jar location: JarLauncher was not loaded from a jar file");
        }
        URI location = codeSource.getLocation().toURI();
        Path archive = Path.of(location);
        if (!Files.isRegularFile(archive)) {
            throw new IllegalStateException("JarLauncher was not loaded from a jar file: " + archive);
        }
        return archive;
    }
}
