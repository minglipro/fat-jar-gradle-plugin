package com.example.testfatjar.provider;

import com.example.testfatjar.greeting.Greeting;

/**
 * 单独打成一个 jar，最终位于 BOOT-INF/lib，用来验证嵌套 jar 加载与 SPI 合并。
 */
public final class SpanishGreeting implements Greeting {

    @Override
    public String greet() {
        return "Hola desde BOOT-INF/lib";
    }
}
