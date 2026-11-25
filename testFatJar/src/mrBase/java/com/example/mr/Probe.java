package com.example.mr;

/**
 * 多版本 jar 的基础版本（运行时低于 21 时使用）。
 */
public final class Probe {

    private Probe() {
    }

    public static String value() {
        return "base";
    }
}
