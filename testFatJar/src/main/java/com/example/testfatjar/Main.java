package com.example.testfatjar;

import com.example.testfatjar.greeting.Greeting;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 示例入口。
 *
 * <p>通过打印类加载器、ServiceLoader 结果和资源内容，验证：
 * 业务类由自定义 NestedJarClassLoader 加载、嵌套 jar 里的类能加载、
 * 两个 jar 里的 META-INF/services 被正确合并、资源 URL 能 openStream()。</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("main class loader : " + Main.class.getClassLoader());
        System.out.println("main code source  : " + Main.class.getProtectionDomain().getCodeSource());

        List<Greeting> greetings = new ArrayList<>();
        for (Greeting greeting : ServiceLoader.load(Greeting.class, Main.class.getClassLoader())) {
            greetings.add(greeting);
        }
        System.out.println("greetings found   : " + greetings.size());
        for (Greeting greeting : greetings) {
            System.out.println("  - " + greeting.greet());
        }

        Class<?> provider = Class.forName(
                "com.example.testfatjar.provider.SpanishGreeting", true, Main.class.getClassLoader());
        System.out.println("provider loader   : " + provider.getClassLoader());

        Class<?> probe = Class.forName("com.example.mr.Probe", true, Main.class.getClassLoader());
        String probeValue = (String) probe.getMethod("value").invoke(null);
        System.out.println("multi-release     : " + probeValue);
        String expectedProbe = Runtime.version().feature() >= 21 ? "versioned" : "base";
        if (!expectedProbe.equals(probeValue)) {
            throw new IllegalStateException(
                    "Expected multi-release Probe=" + expectedProbe + " but got " + probeValue);
        }

        System.out.println("app resource      : " + read("app-resource.txt"));
        System.out.println("lib resource      : " + read("lib-resource.txt"));

        if (greetings.size() != 2) {
            throw new IllegalStateException(
                    "Expected 2 greetings (one in BOOT-INF/classes, one in BOOT-INF/lib) but found "
                            + greetings.size());
        }
        if (provider.getClassLoader() == Main.class.getClassLoader()) {
            // 关键验证：provider 来自嵌套 jar，和业务类由同一个自定义 ClassLoader 加载
            System.out.println("OK: nested jar class loaded by the fat jar class loader");
        } else {
            throw new IllegalStateException("Unexpected provider class loader: " + provider.getClassLoader());
        }
    }

    private static String read(String name) throws Exception {
        try (InputStream in = Main.class.getClassLoader().getResourceAsStream(name)) {
            return in == null ? "<missing>" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
