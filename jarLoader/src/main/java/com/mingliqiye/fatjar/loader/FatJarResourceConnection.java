package com.mingliqiye.fatjar.loader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

/** {@code fatjar:} URL 的连接实现：读取时按需从类加载器取 entry。 */
final class FatJarResourceConnection extends URLConnection {

    private final NestedJarClassLoader loader;
    private final int rootIndex;
    private final String name;

    FatJarResourceConnection(URL url, NestedJarClassLoader loader, int rootIndex, String name) {
        super(url);
        this.loader = loader;
        this.rootIndex = rootIndex;
        this.name = name;
    }

    @Override
    public void connect() {
        connected = true;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        connect();
        return new ByteArrayInputStream(loader.readResource(rootIndex, name));
    }

    @Override
    public String getContentType() {
        String guessed = guessContentTypeFromName(name);
        return guessed != null ? guessed : "application/octet-stream";
    }

    @Override
    public long getLastModified() {
        return 0L;
    }
}
