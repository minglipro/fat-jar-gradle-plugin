package com.mingliqiye.fatjar.gradle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * 手工组装嵌套 fat jar。task 不会自动注册，一般由 {@code fatJar.register(...)} 创建。
 *
 * <p>结构（prefix 均可配置）：</p>
 *
 * <pre>
 * META-INF/MANIFEST.MF      Main-Class / Start-Class / Fat-Jar-Classes / Fat-Jar-Lib
 * &lt;launcher 包&gt;/*.class     从 loaderJars 复制到根目录（固定位置，不参与自定义）
 * &lt;classesPrefix&gt;...        业务 class 与资源
 * &lt;libPrefix&gt;&lt;dep&gt;.jar      依赖 jar，整包嵌套且以 STORED 方式写入（可随机定位）
 * </pre>
 *
 * <p>不直接复用 Gradle 的 {@code Jar} task，是因为嵌套 jar 只能作为「一个文件 entry」
 * 原样写入，不能被 Gradle 当作文件树展开；同时 manifest 必须第一个写入。</p>
 */
public abstract class FatJarTask extends DefaultTask {

    public static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";
    public static final String START_CLASS_ATTRIBUTE = "Start-Class";
    public static final String CLASSES_ATTRIBUTE = "Fat-Jar-Classes";
    public static final String LIB_ATTRIBUTE = "Fat-Jar-Lib";

    /** 固定时间戳，保证同样的输入产出同样的字节（可复现构建）。 */
    private static final long FIXED_TIME = 315532800000L;

    /** launcher/classloader 类所在文件（jar 或 classes 目录），来自 fatJarLoader 配置。 */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getLoaderJars();

    /** 业务 class 输出目录。 */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getAppClasses();

    /** 业务资源输出目录。 */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getAppResources();

    /** runtimeClasspath；只有其中的 .jar 文件会进嵌套 jar。 */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getRuntimeLibs();

    @Input
    @Optional
    public abstract Property<String> getStartClass();

    /** 扫描入口类用的注解全限定名；空字符串表示不扫描。 */
    @Input
    @Optional
    public abstract Property<String> getStartClassAnnotation();

    /** {@code application.mainClass} 的兜底值。 */
    @Input
    @Optional
    public abstract Property<String> getApplicationMainClass();

    @Input
    public abstract Property<String> getLauncherClass();

    /** 业务 class/资源前缀，默认 {@code BOOT-INF/classes/}。 */
    @Input
    public abstract Property<String> getClassesPrefix();

    /** 依赖 jar 前缀，默认 {@code BOOT-INF/lib/}。 */
    @Input
    public abstract Property<String> getLibPrefix();

    /** 额外的 manifest 属性；同名时会覆盖默认属性。 */
    @Input
    public abstract MapProperty<String, String> getManifestAttributes();

    @Internal("Represented as part of archiveFile")
    public abstract Property<String> getArchiveBaseName();

    @Internal("Represented as part of archiveFile")
    public abstract Property<String> getArchiveAppendix();

    @Internal("Represented as part of archiveFile")
    public abstract Property<String> getArchiveVersion();

    @Internal("Represented as part of archiveFile")
    public abstract Property<String> getArchiveExtension();

    @Internal("Represented as part of archiveFile")
    public abstract Property<String> getArchiveClassifier();

    @OutputFile
    public abstract RegularFileProperty getArchiveFile();

    /**
     * 按 Gradle 归档任务的命名规则拼产物名：
     * {@code baseName[-appendix][-version][-classifier][.extension]}，
     * 空段和 {@code unspecified} 版本会自动省略。
     */
    public static String archiveFileName(String baseName, String appendix, String version,
            String classifier, String extension) {
        StringBuilder name = new StringBuilder(baseName == null ? "" : baseName.trim());
        appendPart(name, appendix);
        if (hasText(version) && !"unspecified".equals(version.trim())) {
            appendPart(name, version);
        }
        appendPart(name, classifier);
        if (hasText(extension)) {
            name.append('.').append(extension.trim());
        }
        return name.toString();
    }

    private static void appendPart(StringBuilder name, String part) {
        if (hasText(part)) {
            name.append('-').append(part.trim());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @TaskAction
    public void assemble() throws IOException {
        String startClass = trimToNull(getStartClass().getOrNull());
        if (startClass == null) {
            startClass = findAnnotatedStartClass();
        }
        if (startClass == null) {
            startClass = trimToNull(getApplicationMainClass().getOrNull());
        }
        if (startClass == null) {
            String annotation = getStartClassAnnotation()
                    .getOrElse(FatJarPlugin.DEFAULT_START_CLASS_ANNOTATION);
            throw new GradleException(
                    "No start class configured. Set `fatJar.startClass`, annotate the main class with @"
                            + annotation + ", or configure `application.mainClass`.");
        }
        String launcherClass = getLauncherClass().get();
        String classesPrefix = normalizePrefix(
                getClassesPrefix().getOrElse(FatJarPlugin.CLASSES_PREFIX), "classesPrefix");
        String libPrefix = normalizePrefix(
                getLibPrefix().getOrElse(FatJarPlugin.LIB_PREFIX), "libPrefix");

        File loader = findLoader(launcherClass);
        File archive = getArchiveFile().get().getAsFile();
        Path archivePath = archive.toPath();
        if (archivePath.getParent() != null) {
            Files.createDirectories(archivePath.getParent());
        }

        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Main-Class", launcherClass);
        attributes.putValue(START_CLASS_ATTRIBUTE, startClass);
        attributes.putValue(CLASSES_ATTRIBUTE, classesPrefix);
        attributes.putValue(LIB_ATTRIBUTE, libPrefix);
        attributes.putValue("Created-By", "fatjar gradle plugin");
        getManifestAttributes().getOrElse(Collections.emptyMap())
                .forEach(attributes::putValue);

        Set<String> written = new HashSet<>();
        byte[] manifestBytes = manifestToBytes(manifest);

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archivePath))) {
            writeBytes(jar, MANIFEST_ENTRY, manifestBytes);
            written.add(MANIFEST_ENTRY);
            addLoader(jar, written, loader);
            for (File classes : getAppClasses().getFiles()) {
                addDirectory(jar, written, classes, classesPrefix);
            }
            for (File resources : getAppResources().getFiles()) {
                addDirectory(jar, written, resources, classesPrefix);
            }

            List<File> libs = new ArrayList<>();
            for (File file : getRuntimeLibs().getFiles()) {
                if (file.isFile() && file.getName().endsWith(".jar")) {
                    libs.add(file);
                }
            }
            libs.sort(Comparator.comparing(File::getName));
            for (File lib : libs) {
                addStoredFile(jar, written, libPrefix + lib.getName(), lib);
            }
        }
        getLogger().lifecycle("Fat jar written to {}", archive);
    }

    private static String normalizePrefix(String value, String propertyName) {
        String prefix = value.trim();
        if (prefix.isEmpty()) {
            throw new GradleException("`fatJar." + propertyName + "` must not be empty");
        }
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 扫描编译产物，找唯一一个带指定注解、且声明了 {@code public static void main(String[])} 的类。
     * 找不到返回 null；多个或注解类没有 main 时直接报错。
     */
    private String findAnnotatedStartClass() throws IOException {
        String annotation = trimToNull(getStartClassAnnotation()
                .getOrElse(FatJarPlugin.DEFAULT_START_CLASS_ANNOTATION));
        if (annotation == null) {
            return null;
        }
        String descriptor = "L" + annotation.replace('.', '/') + ";";
        List<String> candidates = new ArrayList<>();
        List<String> withoutMain = new ArrayList<>();

        for (File classes : getAppClasses().getFiles()) {
            Path root = classes.toPath();
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> classFiles;
            try (Stream<Path> stream = Files.walk(root)) {
                classFiles = stream.filter(path -> path.toString().endsWith(".class"))
                        .sorted()
                        .toList();
            }
            for (Path classFile : classFiles) {
                byte[] bytes = Files.readAllBytes(classFile);
                ClassFileScanner.ScannedClass scanned;
                try {
                    scanned = ClassFileScanner.scan(bytes);
                } catch (RuntimeException ex) {
                    throw new GradleException("Cannot scan class file " + classFile, ex);
                }
                if (!scanned.annotations().contains(descriptor)) {
                    continue;
                }
                String name = scanned.className().replace('/', '.');
                if (scanned.hasMain()) {
                    candidates.add(name);
                } else {
                    withoutMain.add(name);
                }
            }
        }

        if (candidates.size() > 1) {
            throw new GradleException(
                    "More than one class annotated with @" + annotation + ": " + candidates);
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (!withoutMain.isEmpty()) {
            throw new GradleException("Class annotated with @" + annotation
                    + " must declare public static void main(String[]): " + withoutMain);
        }
        return null;
    }

    private File findLoader(String launcherClass) {
        Set<File> loaderFiles = getLoaderJars().getFiles();
        if (loaderFiles.isEmpty()) {
            throw new GradleException(
                    "No loader configured. Either keep `fatJar.autoLoaderDependency` enabled "
                            + "(default: uses the :jarLoader project or the published loader coordinates), "
                            + "or set `fatJar.loaderNotation` / add the dependency to the `"
                            + FatJarPlugin.LOADER_CONFIGURATION_NAME + "` configuration yourself.");
        }
        String launcherEntry = launcherClass.replace('.', '/') + ".class";
        for (File candidate : loaderFiles) {
            if (containsEntry(candidate, launcherEntry)) {
                return candidate;
            }
        }
        throw new GradleException("None of the `" + FatJarPlugin.LOADER_CONFIGURATION_NAME
                + "` files contains " + launcherEntry + ": " + loaderFiles);
    }

    private static boolean containsEntry(File file, String entryName) {
        if (file.isDirectory()) {
            return Files.isRegularFile(file.toPath().resolve(entryName));
        }
        try (JarFile jar = new JarFile(file)) {
            return jar.getJarEntry(entryName) != null;
        } catch (IOException ex) {
            return false;
        }
    }

    private void addLoader(JarOutputStream jar, Set<String> written, File loader) throws IOException {
        if (loader.isDirectory()) {
            addDirectory(jar, written, loader, "");
            return;
        }
        try (JarFile source = new JarFile(loader)) {
            List<JarEntry> entries = new ArrayList<>();
            for (Enumeration<JarEntry> enumeration = source.entries(); enumeration.hasMoreElements(); ) {
                JarEntry entry = enumeration.nextElement();
                if (!entry.isDirectory()) {
                    entries.add(entry);
                }
            }
            entries.sort(Comparator.comparing(JarEntry::getName));
            for (JarEntry entry : entries) {
                String name = entry.getName();
                if (isLoaderMetadata(name) || !written.add(name)) {
                    continue;
                }
                JarEntry output = new JarEntry(name);
                output.setTime(FIXED_TIME);
                jar.putNextEntry(output);
                try (InputStream in = source.getInputStream(entry)) {
                    in.transferTo(jar);
                }
                jar.closeEntry();
            }
        }
    }

    private static boolean isLoaderMetadata(String name) {
        if (name.equalsIgnoreCase(MANIFEST_ENTRY) || name.equals("module-info.class")) {
            return true;
        }
        if (name.startsWith("META-INF/versions/")) {
            return true;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".DSA")
                        || upper.endsWith(".RSA") || upper.endsWith(".EC"));
    }

    private void addDirectory(JarOutputStream jar, Set<String> written, File directory, String prefix)
            throws IOException {
        Path root = directory.toPath();
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
            addFile(jar, written, prefix + relative, file.toFile());
        }
    }

    private void addFile(JarOutputStream jar, Set<String> written, String name, File file) throws IOException {
        if (!written.add(name)) {
            getLogger().debug("Skipping duplicate entry {}", name);
            return;
        }
        JarEntry entry = new JarEntry(name);
        entry.setTime(FIXED_TIME);
        jar.putNextEntry(entry);
        try (InputStream in = Files.newInputStream(file.toPath())) {
            in.transferTo(jar);
        }
        jar.closeEntry();
    }

    /**
     * 以 STORED 方式写入依赖 jar：不压缩，字节在 fat jar 里连续，
     * 运行时才能按中央目录的 offset 随机读，而不必整体载入内存。
     */
    private void addStoredFile(JarOutputStream jar, Set<String> written, String name, File file)
            throws IOException {
        if (!written.add(name)) {
            getLogger().debug("Skipping duplicate entry {}", name);
            return;
        }
        long size = Files.size(file.toPath());
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
        }
        JarEntry entry = new JarEntry(name);
        entry.setMethod(JarEntry.STORED);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setCrc(crc.getValue());
        entry.setTime(FIXED_TIME);
        jar.putNextEntry(entry);
        try (InputStream in = Files.newInputStream(file.toPath())) {
            in.transferTo(jar);
        }
        jar.closeEntry();
    }

    private void writeBytes(JarOutputStream jar, String name, byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(FIXED_TIME);
        jar.putNextEntry(entry);
        jar.write(bytes);
        jar.closeEntry();
    }

    private static byte[] manifestToBytes(Manifest manifest) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manifest.write(out);
        return out.toByteArray();
    }
}
