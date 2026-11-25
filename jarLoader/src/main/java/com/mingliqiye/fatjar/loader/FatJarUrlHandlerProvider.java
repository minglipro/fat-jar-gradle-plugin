package com.mingliqiye.fatjar.loader;

import java.net.URLStreamHandler;
import java.net.spi.URLStreamHandlerProvider;

/**
 * 通过 {@code java.net.spi.URLStreamHandlerProvider} SPI 向 JVM 注册 {@code fatjar} 协议。
 *
 * <p>这样不依赖 {@code URL#setURLStreamHandlerFactory}（JVM 只允许设置一次），
 * 也不会和其他库抢工厂。</p>
 */
public final class FatJarUrlHandlerProvider extends URLStreamHandlerProvider {

    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        return "fatjar".equals(protocol) ? FatJarUrlStreamHandler.INSTANCE : null;
    }
}
