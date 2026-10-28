package top.kmar.worldrelay.server.json;

import java.util.*;

/**
 * 按顺序读取一份字符串形式的 JSON，不构建通用对象树。
 * 数据类负责识别字段及业务校验；读取根值后必须调用 finish()。
 * 实例不保证线程安全；解析失败后应丢弃该实例。
 */
public final class JsonReader {

    public static final int DEFAULT_MAX_CHARS = 1_048_576;
    public static final int DEFAULT_MAX_DEPTH = 64;

    private final String input;
    private final int maxDepth;
    private final Deque<Context> contexts = new ArrayDeque<>();
    private int position;
    private boolean rootRead;
    private JsonToken token;
    private String tokenText;
    private int tokenPosition;

    public JsonReader(String input) {
        this(input, DEFAULT_MAX_CHARS, DEFAULT_MAX_DEPTH);
    }

    public JsonReader(String input, int maxChars, int maxDepth) {
        this.input = Objects.requireNonNull(input, "input");
        if (maxChars < 1 || maxDepth < 1) {
            throw new IllegalArgumentException("JSON 长度和嵌套上限必须为正整数");
        }
        if (input.length() > maxChars) {
            throw new JsonParseException("JSON 超出长度上限", maxChars);
        }
        this.maxDepth = maxDepth;
    }

    /** 查看下一个 token；重复调用不会消费字段或值。 */
    public JsonToken peek() {
        if (token != null) {
            return token;
        }
        whitespace();
        if (contexts.isEmpty()) {
            if (rootRead) {
                if (position != input.length()) {
                    throw error("根值之后存在多余内容");
                }
                tokenPosition = position;
                return token = JsonToken.END_DOCUMENT;
            }
            return token = valueToken();
        }
        Context context = contexts.peek();
        return token = switch (context.state) {
            case EMPTY_ARRAY -> at(']') ? punctuation(JsonToken.END_ARRAY) : valueToken();
            case ARRAY_NEXT -> {
                if (at(']')) {
                    yield punctuation(JsonToken.END_ARRAY);
                }
                character(',');
                whitespace();
                yield valueToken();
            }
            case EMPTY_OBJECT -> at('}') ? punctuation(JsonToken.END_OBJECT) : nameToken();
            case OBJECT_NEXT -> {
                if (at('}')) {
                    yield punctuation(JsonToken.END_OBJECT);
                }
                character(',');
                whitespace();
                yield nameToken();
            }
            case OBJECT_VALUE -> {
                character(':');
                whitespace();
                yield valueToken();
            }
        };
    }

    public void beginObject() {
        begin(JsonToken.BEGIN_OBJECT, State.EMPTY_OBJECT);
    }

    public void endObject() {
        expect(JsonToken.END_OBJECT);
        contexts.pop();
        clearToken();
    }

    public void beginArray() {
        begin(JsonToken.BEGIN_ARRAY, State.EMPTY_ARRAY);
    }

    public void endArray() {
        expect(JsonToken.END_ARRAY);
        contexts.pop();
        clearToken();
    }

    /** 仅用于遍历当前对象的字段或数组的元素。 */
    public boolean hasNext() {
        if (contexts.isEmpty() || contexts.peek().state == State.OBJECT_VALUE) {
            throw new IllegalStateException("hasNext() 只能用于容器中尚未读取字段名或元素的位置");
        }
        JsonToken next = peek();
        return next != JsonToken.END_OBJECT && next != JsonToken.END_ARRAY;
    }

    public String nextName() {
        expect(JsonToken.NAME);
        String name = tokenText;
        //noinspection DataFlowIssue
        if (!contexts.peek().names.add(name)) {
            throw new JsonParseException("对象存在重复字段", tokenPosition);
        }
        //noinspection DataFlowIssue
        contexts.peek().state = State.OBJECT_VALUE;
        clearToken();
        return name;
    }

    public String nextString() {
        expect(JsonToken.STRING);
        String value = tokenText;
        consumeValue();
        return value;
    }

    public int nextInt() {
        integerToken();
        int value;
        try {
            value = Integer.parseInt(tokenText);
        } catch (NumberFormatException failure) {
            throw new JsonParseException("整数超出 int 范围", tokenPosition);
        }
        consumeValue();
        return value;
    }

    public long nextLong() {
        integerToken();
        long value;
        try {
            value = Long.parseLong(tokenText);
        } catch (NumberFormatException failure) {
            throw new JsonParseException("整数超出 long 范围", tokenPosition);
        }
        consumeValue();
        return value;
    }

    public double nextDouble() {
        expect(JsonToken.NUMBER);
        double value = Double.parseDouble(tokenText);
        if (!Double.isFinite(value)) {
            throw new JsonParseException("数字超出有限 double 范围", tokenPosition);
        }
        consumeValue();
        return value;
    }

    public boolean nextBoolean() {
        expect(JsonToken.BOOLEAN);
        boolean value = tokenText.equals("true");
        consumeValue();
        return value;
    }

    public void nextNull() {
        expect(JsonToken.NULL);
        consumeValue();
    }

    /** 跳过一个完整值，包含语法、重复字段和嵌套深度检查，不转换数字。 */
    public void skipValue() {
        JsonToken first = peek();
        if (first == JsonToken.NAME || first == JsonToken.END_OBJECT
            || first == JsonToken.END_ARRAY || first == JsonToken.END_DOCUMENT) {
            throw new JsonParseException("此处需要 JSON 值", tokenPosition);
        }
        int initialDepth = contexts.size();
        // 使用显式容器栈，避免跳过深层未知字段时递归消耗 Java 调用栈。
        do {
            switch (peek()) {
                case BEGIN_OBJECT -> beginObject();
                case END_OBJECT -> endObject();
                case BEGIN_ARRAY -> beginArray();
                case END_ARRAY -> endArray();
                case NAME -> nextName();
                case STRING, NUMBER, BOOLEAN, NULL -> consumeValue();
                case END_DOCUMENT -> throw error("JSON 值尚未结束");
            }
        } while (contexts.size() > initialDepth);
    }

    /** 确认已经消费唯一根值、关闭所有容器，并且没有尾随内容。 */
    public void finish() {
        if (!contexts.isEmpty()) {
            throw error("JSON 容器尚未结束");
        }
        if (!rootRead) {
            throw error("尚未读取完整根值");
        }
        expect(JsonToken.END_DOCUMENT);
    }

    private void begin(JsonToken expected, State state) {
        expect(expected);
        if (contexts.size() >= maxDepth) {
            throw new JsonParseException("JSON 超出嵌套上限", tokenPosition);
        }
        consumeValue();
        contexts.push(new Context(state));
    }

    private void expect(JsonToken expected) {
        JsonToken actual = peek();
        if (actual != expected) {
            throw new JsonParseException("需要 " + expected + "，实际为 " + actual, tokenPosition);
        }
    }

    private void integerToken() {
        expect(JsonToken.NUMBER);
        if (tokenText.indexOf('.') >= 0 || tokenText.indexOf('e') >= 0 || tokenText.indexOf('E') >= 0) {
            throw new JsonParseException("此处需要整数形式的数字", tokenPosition);
        }
    }

    private void consumeValue() {
        if (contexts.isEmpty()) {
            rootRead = true;
        } else {
            Context context = contexts.peek();
            context.state = context.state == State.OBJECT_VALUE ? State.OBJECT_NEXT : State.ARRAY_NEXT;
        }
        clearToken();
    }

    private void clearToken() {
        token = null;
        tokenText = null;
    }

    private JsonToken valueToken() {
        tokenPosition = position;
        if (position == input.length()) {
            throw error("缺少 JSON 值");
        }
        char current = input.charAt(position);
        return switch (current) {
            case '{' -> punctuation(JsonToken.BEGIN_OBJECT);
            case '[' -> punctuation(JsonToken.BEGIN_ARRAY);
            case '"' -> {
                tokenText = string();
                yield JsonToken.STRING;
            }
            case 't' -> literal("true", JsonToken.BOOLEAN);
            case 'f' -> literal("false", JsonToken.BOOLEAN);
            case 'n' -> literal("null", JsonToken.NULL);
            default -> {
                if (current != '-' && !digit(current)) {
                    throw error("非法的 JSON 值");
                }
                tokenText = number();
                yield JsonToken.NUMBER;
            }
        };
    }

    private JsonToken nameToken() {
        tokenPosition = position;
        if (!at('"')) {
            throw error("对象字段名必须为字符串");
        }
        tokenText = string();
        return JsonToken.NAME;
    }

    private JsonToken punctuation(JsonToken result) {
        tokenPosition = position++;
        return result;
    }

    private JsonToken literal(String text, JsonToken result) {
        if (!input.startsWith(text, position)) {
            throw error("非法的 JSON 字面量");
        }
        position += text.length();
        delimiter();
        tokenText = text;
        return result;
    }

    private String number() {
        int start = position;
        if (at('-')) {
            position++;
        }
        if (at('0')) {
            position++;
        } else {
            if (position == input.length() || input.charAt(position) < '1' || input.charAt(position) > '9') {
                throw error("数字缺少整数部分");
            }
            digits();
        }
        if (at('.')) {
            position++;
            digits();
        }
        if (at('e') || at('E')) {
            position++;
            if (at('+') || at('-')) {
                position++;
            }
            digits();
        }
        delimiter();
        return input.substring(start, position);
    }

    private void digits() {
        int start = position;
        while (position < input.length() && digit(input.charAt(position))) {
            position++;
        }
        if (position == start) {
            throw error("此处需要十进制数字");
        }
    }

    private void delimiter() {
        if (position < input.length()) {
            char next = input.charAt(position);
            if (!whitespace(next) && next != ',' && next != ']' && next != '}') {
                throw error("字面量之后缺少分隔符");
            }
        }
    }

    private String string() {
        character('"');
        StringBuilder value = new StringBuilder();
        int pendingHighSurrogate = -1;
        while (position < input.length()) {
            int sourcePosition = position;
            char current = input.charAt(position++);
            if (current == '"') {
                if (pendingHighSurrogate >= 0) {
                    throw new JsonParseException("字符串包含孤立的高代理项", pendingHighSurrogate);
                }
                return value.toString();
            }
            if (current < 0x20) {
                throw new JsonParseException("字符串包含未转义的控制字符", sourcePosition);
            }
            if (current == '\\') {
                if (position == input.length()) {
                    throw error("字符串转义尚未结束");
                }
                current = switch (input.charAt(position++)) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> unicode();
                    default -> throw new JsonParseException("非法的字符串转义", position - 1);
                };
            }
            if (pendingHighSurrogate >= 0) {
                if (!Character.isLowSurrogate(current)) {
                    throw new JsonParseException("字符串包含孤立的高代理项", pendingHighSurrogate);
                }
                pendingHighSurrogate = -1;
            } else if (Character.isHighSurrogate(current)) {
                pendingHighSurrogate = sourcePosition;
            } else if (Character.isLowSurrogate(current)) {
                throw new JsonParseException("字符串包含孤立的低代理项", sourcePosition);
            }
            value.append(current);
        }
        throw error("字符串尚未结束");
    }

    private char unicode() {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            if (position == input.length()) {
                throw error("Unicode 转义不足四位");
            }
            char current = input.charAt(position++);
            int hex;
            if (current >= '0' && current <= '9') {
                hex = current - '0';
            } else if (current >= 'a' && current <= 'f') {
                hex = current - 'a' + 10;
            } else if (current >= 'A' && current <= 'F') {
                hex = current - 'A' + 10;
            } else {
                throw new JsonParseException("Unicode 转义包含非法十六进制字符", position - 1);
            }
            value = (value << 4) | hex;
        }
        return (char) value;
    }

    private void character(char expected) {
        if (!at(expected)) {
            throw error("缺少字符 " + expected);
        }
        position++;
    }

    private boolean at(char expected) {
        return position < input.length() && input.charAt(position) == expected;
    }

    private void whitespace() {
        while (position < input.length() && whitespace(input.charAt(position))) {
            position++;
        }
    }

    private static boolean whitespace(char character) {
        return character == ' ' || character == '\t' || character == '\r' || character == '\n';
    }

    private static boolean digit(char character) {
        return character >= '0' && character <= '9';
    }

    private JsonParseException error(String message) {
        return new JsonParseException(message, position);
    }

    private enum State {
        EMPTY_ARRAY, ARRAY_NEXT, EMPTY_OBJECT, OBJECT_NEXT, OBJECT_VALUE
    }

    private static final class Context {

        private State state;
        private final Set<String> names;

        private Context(State state) {
            this.state = state;
            names = state == State.EMPTY_OBJECT ? new HashSet<>() : null;
        }

    }

}