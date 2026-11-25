package com.example.mr;

/**
 * 多版本 jar 的 Java 21+ 版本（放在 META-INF/versions/21 下）。
 */
public final class Probe {

    private Probe() {
    }

    public static String value() {
        return "versioned";
    }
}
