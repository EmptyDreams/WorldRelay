package top.kmar.worldrelay.server.json;

import java.util.*;

/** 显式构造一份紧凑 JSON 字符串；不进行对象映射，也不保证线程安全。 */
public final class JsonWriter {

    public static final int DEFAULT_MAX_DEPTH = 64;

    private final int maxDepth;
    private final StringBuilder output = new StringBuilder();
    private final Deque<Context> contexts = new ArrayDeque<>();
    private boolean rootWritten;

    public JsonWriter() {
        this(DEFAULT_MAX_DEPTH);
    }

    public JsonWriter(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("JSON 嵌套上限必须为正整数");
        }
        this.maxDepth = maxDepth;
    }

    public JsonWriter beginObject() {
        return begin(true);
    }

    public JsonWriter endObject() {
        Context context = context(true);
        if (context.expectingValue) {
            throw new IllegalStateException("对象字段缺少值");
        }
        contexts.pop();
        output.append('}');
        return this;
    }

    public JsonWriter beginArray() {
        return begin(false);
    }

    public JsonWriter endArray() {
        context(false);
        contexts.pop();
        output.append(']');
        return this;
    }

    public JsonWriter name(String name) {
        Context context = context(true);
        if (context.expectingValue) {
            throw new IllegalStateException("前一个对象字段尚未写入值");
        }
        String quoted = quote(name);
        if (!context.names.add(name)) {
            throw new IllegalArgumentException("对象存在重复字段");
        }
        if (context.values > 0) {
            output.append(',');
        }
        output.append(quoted).append(':');
        context.expectingValue = true;
        return this;
    }

    public JsonWriter value(String value) {
        String quoted = quote(value);
        beforeValue();
        output.append(quoted);
        return this;
    }

    public JsonWriter value(int value) {
        return value((long) value);
    }

    public JsonWriter value(long value) {
        beforeValue();
        output.append(value);
        return this;
    }

    public JsonWriter value(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON 不支持 NaN 或 Infinity");
        }
        beforeValue();
        output.append(value);
        return this;
    }

    public JsonWriter value(boolean value) {
        beforeValue();
        output.append(value);
        return this;
    }

    public JsonWriter nullValue() {
        beforeValue();
        output.append("null");
        return this;
    }

    /** 仅当唯一根值已经写完、所有容器均闭合时返回结果。 */
    public String finish() {
        if (!rootWritten || !contexts.isEmpty()) {
            throw new IllegalStateException("JSON 文档尚未完整写入");
        }
        return output.toString();
    }

    private JsonWriter begin(boolean object) {
        if (contexts.size() >= maxDepth) {
            throw new IllegalStateException("JSON 超出嵌套上限");
        }
        beforeValue();
        output.append(object ? '{' : '[');
        contexts.push(new Context(object));
        return this;
    }

    private Context context(boolean object) {
        if (contexts.isEmpty() || contexts.peek().object != object) {
            throw new IllegalStateException(object ? "当前位置不在对象中" : "当前位置不在数组中");
        }
        return contexts.peek();
    }

    private void beforeValue() {
        if (contexts.isEmpty()) {
            if (rootWritten) {
                throw new IllegalStateException("只能写入一个 JSON 根值");
            }
            rootWritten = true;
            return;
        }
        Context context = contexts.peek();
        if (context.object) {
            if (!context.expectingValue) {
                throw new IllegalStateException("写入对象值前必须先写字段名");
            }
            context.expectingValue = false;
        } else if (context.values > 0) {
            output.append(',');
        }
        context.values++;
    }

    private static String quote(String value) {
        Objects.requireNonNull(value, "JSON 字符串不能为 null，请使用 nullValue()");
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("字符串包含孤立的高代理项");
                }
                quoted.append(character).append(value.charAt(++i));
                continue;
            }
            if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("字符串包含孤立的低代理项");
            }
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20) {
                        quoted.append("\\u00")
                            .append(Character.forDigit(character >> 4, 16))
                            .append(Character.forDigit(character & 15, 16));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    private static final class Context {

        private final boolean object;
        private final Set<String> names;
        private int values;
        private boolean expectingValue;

        private Context(boolean object) {
            this.object = object;
            names = object ? new HashSet<>() : null;
        }

    }

}