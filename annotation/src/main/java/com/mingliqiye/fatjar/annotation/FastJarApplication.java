package com.mingliqiye.fatjar.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在应用的 main 类上，fatjar 插件会扫描它并自动写入 MANIFEST 的 {@code Start-Class}。
 *
 * <pre>
 * &#64;FastJarApplication
 * public final class Application {
 *     public static void main(String[] args) { ... }
 * }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FastJarApplication {
}
