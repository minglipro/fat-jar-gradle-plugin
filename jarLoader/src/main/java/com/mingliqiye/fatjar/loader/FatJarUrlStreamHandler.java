package com.mingliqiye.fatjar.loader;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code fatjar:} 协议的全局处理器。
 *
 * <p>类加载器构造资源 URL 时会显式传入本处理器；同时通过
 * {@link FatJarUrlHandlerProvider}（{@code java.net.spi.URLStreamHandlerProvider}）
 * 注册到 JVM，这样 {@code URL#toURI() -> URI#toURL()} 也能重新找到它——
 * Log4j2、Spring 等库会走这条路径。</p>
 *
 * <p>URL 形式：{@code fatjar:/<loaderToken>/<rootIndex>/<entryName>}。
 * token 用于把 URL 关联回具体的类加载器实例。</p>
 */
final class FatJarUrlStreamHandler extends URLStreamHandler {

    static final FatJarUrlStreamHandler INSTANCE = new FatJarUrlStreamHandler();

    private static final Map<String, NestedJarClassLoader> LOADERS = new ConcurrentHashMap<>();

    private FatJarUrlStreamHandler() {
    }

    static String register(NestedJarClassLoader loader) {
        String token = UUID.randomUUID().toString();
        LOADERS.put(token, loader);
        return token;
    }

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        String path = url.getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        int firstSeparator = path.indexOf('/');
        int secondSeparator = firstSeparator < 0 ? -1 : path.indexOf('/', firstSeparator + 1);
        if (firstSeparator <= 0 || secondSeparator <= firstSeparator + 1) {
            throw new IOException("Malformed fatjar URL: " + url);
        }
        String token = path.substring(0, firstSeparator);
        int rootIndex;
        try {
            rootIndex = Integer.parseInt(path.substring(firstSeparator + 1, secondSeparator));
        } catch (NumberFormatException ex) {
            throw new IOException("Malformed fatjar URL: " + url, ex);
        }
        String name = path.substring(secondSeparator + 1);
        NestedJarClassLoader loader = LOADERS.get(token);
        if (loader == null) {
            throw new IOException("Fat jar class loader is no longer available: " + url);
        }
        return new FatJarResourceConnection(url, loader, rootIndex, name);
    }

    @Override
    protected int hashCode(URL url) {
        return url.toString().hashCode();
    }

    @Override
    protected boolean equals(URL first, URL second) {
        return first.toString().equals(second.toString());
    }
}
