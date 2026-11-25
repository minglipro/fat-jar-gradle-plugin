package com.mingliqiye.fatjar.loader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * 读取 fat jar 内部嵌套资源的类加载器。
 *
 * <p>fat jar 的布局（由 fatjar Gradle 插件写入）：</p>
 *
 * <pre>
 * META-INF/MANIFEST.MF        (Main-Class = JarLauncher, Start-Class = 业务入口)
 * BOOT-INF/classes/...        业务类与资源（摊平成普通目录）
 * BOOT-INF/lib/*.jar          依赖 jar（STORED 整包嵌套，可随机定位）
 * </pre>
 *
 * <p>类加载器本身不预读内容：依赖 {@link ZipReader} 解析中央目录，真正
 * {@code loadClass} / {@code openStream} 时再按 offset 读取对应 entry。
 * 因此几百 MB 的 fat jar 也不会把嵌套 jar 全部驻留堆内存。</p>
 *
 * <p>注意两个容易踩的点：</p>
 * <ul>
 *   <li>{@code findResources} 必须返回<b>所有</b>根里的同名资源，不能去重，
 *       否则 {@code META-INF/services} 会丢实现；</li>
 *   <li>{@code getResource}/{@code getResources} 要覆盖，默认实现是父加载器优先，
 *       这里业务资源优先于父加载器（父加载器只用到平台类）。</li>
 * </ul>
 */
public final class NestedJarClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    /** 当前运行时版本，用于多版本 jar（META-INF/versions/N）的解析。 */
    private static final int RUNTIME_VERSION = Runtime.version().feature();

    private final List<Root> roots;
    private final String urlToken;

    private NestedJarClassLoader(List<Root> roots) {
        super("fat-jar", ClassLoader.getPlatformClassLoader());
        this.roots = List.copyOf(roots);
        this.urlToken = FatJarUrlStreamHandler.register(this);
    }

    /**
     * 打开 fat jar，建立 classes 根和所有嵌套 jar 根（只解析中央目录，不读内容）。
     *
     * @param archive        fat jar 路径
     * @param classesPrefix  业务 classes 目录前缀，例如 {@code BOOT-INF/classes/}
     * @param libPrefix      嵌套 jar 目录前缀，例如 {@code BOOT-INF/lib/}
     */
    public static NestedJarClassLoader create(Path archive, String classesPrefix, String libPrefix)
            throws IOException {
        ZipReader.ChannelSource fatJarSource;
        ZipReader fatJar;
        try {
            fatJarSource = ZipReader.ChannelSource.open(archive);
            fatJar = ZipReader.open(fatJarSource);
        } catch (IOException ex) {
            throw new IOException("Cannot open fat jar " + archive, ex);
        }

        List<Root> roots = new ArrayList<>();
        // 业务 classes 永远排第一：同名类以业务代码为准
        roots.add(new Root(fatJar, classesPrefix, false, PackageMetadata.from(fatJar)));

        List<String> nestedJars = new ArrayList<>();
        for (String name : fatJar.names()) {
            if (name.startsWith(libPrefix) && name.endsWith(".jar") && fatJar.contains(name)) {
                nestedJars.add(name);
            }
        }
        Collections.sort(nestedJars);
        for (String name : nestedJars) {
            ZipReader.Entry entry = fatJar.entry(name);
            ZipReader.DataSource source;
            if (entry != null && entry.method() == ZipReader.STORED) {
                // STORED：直接定位文件区间，嵌套 jar 无需整体进内存
                source = new ZipReader.ChannelSource(
                        fatJarSource.channel(), fatJar.dataOffset(name), entry.uncompressedSize());
            } else {
                // 旧格式（DEFLATED）退化为整体读入，仅用于兼容
                source = new ZipReader.ByteArraySource(fatJar.read(name));
            }
            ZipReader nested = ZipReader.open(source);
            PackageMetadata metadata = PackageMetadata.from(nested);
            // 嵌套 jar 自己声明 Multi-Release 时才启用版本化条目
            roots.add(new Root(nested, "", metadata.multiRelease(), metadata));
        }
        return new NestedJarClassLoader(roots);
    }

    /** 一个根自己的 manifest 元数据，用于定义包信息（Package.getImplementationVersion 等）。 */
    private record PackageMetadata(String specTitle, String specVersion, String specVendor,
            String implTitle, String implVersion, String implVendor, boolean multiRelease) {

        private static final PackageMetadata EMPTY =
                new PackageMetadata(null, null, null, null, null, null, false);

        private static PackageMetadata from(ZipReader reader) {
            if (!reader.contains("META-INF/MANIFEST.MF")) {
                return EMPTY;
            }
            try (InputStream in = new ByteArrayInputStream(reader.read("META-INF/MANIFEST.MF"))) {
                Attributes attributes = new Manifest(in).getMainAttributes();
                return new PackageMetadata(
                        attributes.getValue("Specification-Title"),
                        attributes.getValue("Specification-Version"),
                        attributes.getValue("Specification-Vendor"),
                        attributes.getValue("Implementation-Title"),
                        attributes.getValue("Implementation-Version"),
                        attributes.getValue("Implementation-Vendor"),
                        Boolean.parseBoolean(attributes.getValue("Multi-Release")));
            } catch (IOException ex) {
                return EMPTY;
            }
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        for (Root root : roots) {
            if (!root.contains(path)) {
                continue;
            }
            try {
                byte[] bytes = root.read(path);
                ensurePackage(name, root);
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException ex) {
                throw new ClassNotFoundException(name, ex);
            }
        }
        throw new ClassNotFoundException(name);
    }

    /** 按根自己的 manifest 定义包信息，保证 Package#getImplementationVersion() 等可用。 */
    private void ensurePackage(String className, Root root) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot < 0) {
            return;
        }
        String packageName = className.substring(0, lastDot);
        if (getDefinedPackage(packageName) != null) {
            return;
        }
        PackageMetadata metadata = root.metadata;
        try {
            definePackage(packageName,
                    metadata.specTitle(), metadata.specVersion(), metadata.specVendor(),
                    metadata.implTitle(), metadata.implVersion(), metadata.implVendor(), null);
        } catch (IllegalArgumentException ignored) {
            // 并发加载同一个包时可能已被其他线程定义
        }
    }

    @Override
    protected URL findResource(String name) {
        String normalized = normalize(name);
        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i).contains(normalized)) {
                return createUrl(i, normalized);
            }
        }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        String normalized = normalize(name);
        List<URL> urls = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i).contains(normalized)) {
                urls.add(createUrl(i, normalized));
            }
        }
        return Collections.enumeration(urls);
    }

    @Override
    public URL getResource(String name) {
        URL resource = findResource(name);
        if (resource != null) {
            return resource;
        }
        ClassLoader parent = getParent();
        return parent == null ? null : parent.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (Enumeration<URL> resources = findResources(name); resources.hasMoreElements(); ) {
            urls.add(resources.nextElement());
        }
        ClassLoader parent = getParent();
        if (parent != null) {
            for (Enumeration<URL> resources = parent.getResources(name); resources.hasMoreElements(); ) {
                URL url = resources.nextElement();
                if (!urls.contains(url)) {
                    urls.add(url);
                }
            }
        }
        return Collections.enumeration(urls);
    }

    private static String normalize(String name) {
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private URL createUrl(int rootIndex, String name) {
        try {
            return new URL(null, "fatjar:/" + urlToken + "/" + rootIndex + "/" + name,
                    FatJarUrlStreamHandler.INSTANCE);
        } catch (MalformedURLException ex) {
            throw new IllegalStateException("Cannot create resource URL for " + name, ex);
        }
    }

    /** 供 {@link FatJarResourceConnection} 按需读取资源内容。 */
    byte[] readResource(int rootIndex, String name) throws IOException {
        return roots.get(rootIndex).read(name);
    }

    /**
     * 一个资源根：带前缀的外层 zip，或一个嵌套 jar。
     *
     * <p>多版本语义（JEP 238）：manifest 声明 {@code Multi-Release: true} 时，
     * 优先使用 {@code META-INF/versions/N/} 下不超过运行时版本的最高版本；
     * {@code META-INF/} 自身不参与版本化。</p>
     */
    private static final class Root {

        private final ZipReader reader;
        private final String prefix;
        private final boolean multiRelease;
        private final PackageMetadata metadata;

        private Root(ZipReader reader, String prefix, boolean multiRelease, PackageMetadata metadata) {
            this.reader = reader;
            this.prefix = prefix;
            this.multiRelease = multiRelease;
            this.metadata = metadata;
        }

        private String resolve(String path) {
            if (multiRelease && !path.startsWith("META-INF/")) {
                for (int version = RUNTIME_VERSION; version >= 9; version--) {
                    String candidate = prefix + "META-INF/versions/" + version + "/" + path;
                    if (reader.contains(candidate)) {
                        return candidate;
                    }
                }
            }
            String entry = prefix + path;
            return reader.contains(entry) ? entry : null;
        }

        private boolean contains(String path) {
            return resolve(path) != null;
        }

        private byte[] read(String path) throws IOException {
            String entry = resolve(path);
            if (entry == null) {
                throw new IOException("No such entry: " + path);
            }
            return reader.read(entry);
        }
    }
}
