package com.example.testfatjar.greeting;

/**
 * 位于 BOOT-INF/classes 的实现，服务声明在 src/main/resources/META-INF/services 下。
 */
public final class EnglishGreeting implements Greeting {

    @Override
    public String greet() {
        return "Hello from BOOT-INF/classes";
    }
}
