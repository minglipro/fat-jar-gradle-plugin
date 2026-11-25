package com.mingliqiye.fatjar.gradle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 极简 class 文件扫描器：不加载类、不依赖 ASM，只解析常量池、方法和类级注解。
 *
 * <p>用于在打包阶段发现 {@code @FatJarApplication} 标记的入口类。
 * 支持 {@code RuntimeVisibleAnnotations} / {@code RuntimeInvisibleAnnotations}
 * （即 RUNTIME 和 CLASS 两种保留策略）。</p>
 */
final class ClassFileScanner {

    private static final int MAGIC = 0xCAFEBABE;

    private ClassFileScanner() {
    }

    static ScannedClass scan(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        try {
            if (buffer.getInt() != MAGIC) {
                throw new IllegalArgumentException("Not a class file");
            }
            buffer.getShort(); // minor
            buffer.getShort(); // major

            int constantPoolCount = buffer.getShort() & 0xFFFF;
            String[] utf8 = new String[constantPoolCount];
            int[] classNames = new int[constantPoolCount];
            for (int index = 1; index < constantPoolCount; index++) {
                int tag = buffer.get() & 0xFF;
                switch (tag) {
                    case 1 -> {
                        int length = buffer.getShort() & 0xFFFF;
                        byte[] text = new byte[length];
                        buffer.get(text);
                        utf8[index] = new String(text, StandardCharsets.UTF_8);
                    }
                    case 3, 4 -> buffer.getInt();
                    case 5, 6 -> {
                        buffer.getLong();
                        index++;
                    }
                    case 7 -> classNames[index] = buffer.getShort() & 0xFFFF;
                    case 8, 16, 19, 20 -> buffer.getShort();
                    case 9, 10, 11, 12, 17, 18 -> buffer.getInt();
                    case 15 -> {
                        buffer.get();
                        buffer.getShort();
                    }
                    default -> throw new IllegalArgumentException("Unknown constant pool tag " + tag);
                }
            }

            buffer.getShort(); // access flags
            int thisClass = buffer.getShort() & 0xFFFF;
            buffer.getShort(); // super class
            int interfaceCount = buffer.getShort() & 0xFFFF;
            buffer.position(buffer.position() + interfaceCount * 2);

            String className = utf8[classNames[thisClass]];
            if (className == null) {
                throw new IllegalArgumentException("Class name is missing in constant pool");
            }

            skipMembers(buffer); // fields

            boolean hasMain = false;
            int methodCount = buffer.getShort() & 0xFFFF;
            for (int method = 0; method < methodCount; method++) {
                int access = buffer.getShort() & 0xFFFF;
                int nameIndex = buffer.getShort() & 0xFFFF;
                int descriptorIndex = buffer.getShort() & 0xFFFF;
                skipAttributes(buffer);
                String name = utf8[nameIndex];
                String descriptor = utf8[descriptorIndex];
                if ("main".equals(name)
                        && "([Ljava/lang/String;)V".equals(descriptor)
                        && (access & 0x0001) != 0
                        && (access & 0x0008) != 0) {
                    hasMain = true;
                }
            }

            Set<String> annotations = new LinkedHashSet<>();
            int attributeCount = buffer.getShort() & 0xFFFF;
            for (int attribute = 0; attribute < attributeCount; attribute++) {
                int nameIndex = buffer.getShort() & 0xFFFF;
                int length = buffer.getInt();
                int end = buffer.position() + length;
                String attributeName = utf8[nameIndex];
                if ("RuntimeVisibleAnnotations".equals(attributeName)
                        || "RuntimeInvisibleAnnotations".equals(attributeName)) {
                    readAnnotations(buffer, utf8, annotations);
                }
                buffer.position(end);
            }
            return new ScannedClass(className, annotations, hasMain);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Cannot parse class file", ex);
        }
    }

    private static void skipMembers(ByteBuffer buffer) {
        int count = buffer.getShort() & 0xFFFF;
        for (int member = 0; member < count; member++) {
            buffer.position(buffer.position() + 6); // access, name, descriptor
            skipAttributes(buffer);
        }
    }

    private static void skipAttributes(ByteBuffer buffer) {
        int count = buffer.getShort() & 0xFFFF;
        for (int attribute = 0; attribute < count; attribute++) {
            buffer.position(buffer.position() + 2); // attribute name index
            int length = buffer.getInt();
            buffer.position(buffer.position() + length);
        }
    }

    private static void readAnnotations(ByteBuffer buffer, String[] utf8, Set<String> annotations) {
        int count = buffer.getShort() & 0xFFFF;
        for (int annotation = 0; annotation < count; annotation++) {
            int typeIndex = buffer.getShort() & 0xFFFF;
            annotations.add(utf8[typeIndex]);
            int pairCount = buffer.getShort() & 0xFFFF;
            for (int pair = 0; pair < pairCount; pair++) {
                buffer.position(buffer.position() + 2); // element name index
                skipElementValue(buffer);
            }
        }
    }

    private static void skipElementValue(ByteBuffer buffer) {
        int tag = buffer.get() & 0xFF;
        switch (tag) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> buffer.position(buffer.position() + 2);
            case 'e' -> buffer.position(buffer.position() + 4);
            case '@' -> {
                buffer.position(buffer.position() + 2); // annotation type index
                int pairCount = buffer.getShort() & 0xFFFF;
                for (int pair = 0; pair < pairCount; pair++) {
                    buffer.position(buffer.position() + 2);
                    skipElementValue(buffer);
                }
            }
            case '[' -> {
                int count = buffer.getShort() & 0xFFFF;
                for (int element = 0; element < count; element++) {
                    skipElementValue(buffer);
                }
            }
            default -> throw new IllegalArgumentException("Unknown annotation element tag " + tag);
        }
    }

    /** 扫描结果：内部类名（用 / 分隔）、类级注解描述符、是否声明 public static void main。 */
    record ScannedClass(String className, Set<String> annotations, boolean hasMain) {
    }
}
