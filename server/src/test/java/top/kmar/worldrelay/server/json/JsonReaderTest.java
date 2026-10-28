package top.kmar.worldrelay.server.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class JsonReaderTest {

    /** 验证已知字段按显式类型读取，跳过嵌套未知字段后仍能正确读取后续字段。 */
    @Test
    void readsKnownFieldsAndSkipsNestedUnknownFields() {
        JsonReader reader = new JsonReader("""
            {
              "epoch": 9007199254740993,
              "unknown": {"items": [null, true, {"number": 1e9999}, 999999999999999999999999999999]},
              "name": "世界",
              "enabled": false,
              "optional": null,
              "items": [1, 2]
            }
            """);
        assertEquals(JsonToken.BEGIN_OBJECT, reader.peek());
        assertEquals(JsonToken.BEGIN_OBJECT, reader.peek());
        reader.beginObject();
        assertTrue(reader.hasNext());
        assertTrue(reader.hasNext());
        assertEquals(JsonToken.NAME, reader.peek());
        assertEquals("epoch", reader.nextName());
        assertEquals(9007199254740993L, reader.nextLong());
        assertEquals("unknown", reader.nextName());
        reader.skipValue();
        assertEquals("name", reader.nextName());
        assertEquals("世界", reader.nextString());
        assertEquals("enabled", reader.nextName());
        assertFalse(reader.nextBoolean());
        assertEquals("optional", reader.nextName());
        assertEquals(JsonToken.NULL, reader.peek());
        reader.nextNull();
        assertEquals("items", reader.nextName());
        reader.beginArray();
        assertTrue(reader.hasNext());
        assertEquals(1, reader.nextInt());
        assertTrue(reader.hasNext());
        assertTrue(reader.hasNext());
        assertEquals(2, reader.nextInt());
        assertFalse(reader.hasNext());
        reader.endArray();
        assertFalse(reader.hasNext());
        reader.endObject();
        reader.finish();
        assertEquals(JsonToken.END_DOCUMENT, reader.peek());
    }

    /** 验证各种合法根值均可完整跳过，超出 Java 数值范围的数字也不会触发数值转换。 */
    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "[]", "true", "false", "null", "0", "-12", "0.5", "1E+3", "\"\"",
        " \t\r\n[{},[],\"世界🌍\"] \n", "[{\"a\":1},{\"a\":2}]", "1e999999", "-9999999999999999999999999999999"
    })
    void skipsAnyValidRootWithoutConvertingItsNumbers(String json) {
        JsonReader reader = new JsonReader(json);
        reader.skipValue();
        reader.finish();
    }

    /** 验证 int 读取拒绝溢出、小数、指数和非数字类型，不执行隐式类型转换。 */
    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "1.0", "1e0", "\"1\"", "true", "null"})
    void rejectsInvalidIntTypesFormsAndOverflow(String json) {
        assertThrows(JsonParseException.class, () -> new JsonReader(json).nextInt());
    }

    /** 验证 long 读取拒绝溢出、小数、指数和字符串数字。 */
    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "-9223372036854775809", "1.0", "1e0", "\"1\""})
    void rejectsInvalidLongTypesFormsAndOverflow(String json) {
        assertThrows(JsonParseException.class, () -> new JsonReader(json).nextLong());
    }

    /** 验证 double 读取拒绝溢出产生的非有限结果及非数字类型。 */
    @ParameterizedTest
    @ValueSource(strings = {"1e309", "-1e309", "\"1.5\"", "null", "true"})
    void rejectsNonFiniteDoublesAndTypeCoercion(String json) {
        assertThrows(JsonParseException.class, () -> new JsonReader(json).nextDouble());
    }

    /** 验证字符串、布尔值和 null 必须使用对应方法读取，不能互相隐式转换。 */
    @Test
    void requiresExplicitStringBooleanAndNullTypes() {
        assertThrows(JsonParseException.class, () -> new JsonReader("null").nextString());
        assertThrows(JsonParseException.class, () -> new JsonReader("1").nextString());
        assertThrows(JsonParseException.class, () -> new JsonReader("\"true\"").nextBoolean());
        assertThrows(JsonParseException.class, () -> new JsonReader("\"null\"").nextNull());
        assertThrows(JsonParseException.class, () -> new JsonReader("true").nextNull());
    }

    /** 验证标准字符串转义，以及原始字符、Unicode 转义和混合形式的合法代理对均正确解码。 */
    @Test
    void decodesStringEscapesAndUnicodePairs() {
        JsonReader reader = new JsonReader("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0000\\u001f中文\\uD83C\\uDF0D\"");
        assertEquals("\"\\/\b\f\n\r\t" + (char) 0 + (char) 31 + "中文🌍", reader.nextString());
        reader.finish();
        for (String json : new String[]{
            "\"🌍\"", "\"\\uD83C" + (char) 0xDF0D + "\"", "\"" + (char) 0xD83C + "\\uDF0D\""
        }) {
            JsonReader unicode = new JsonReader(json);
            assertEquals("🌍", unicode.nextString());
            unicode.finish();
        }
    }

    /** 验证跳过值时仍拒绝非法语法、重复字段、错误转义及孤立的 Unicode 代理项。 */
    @ParameterizedTest
    @ValueSource(strings = {
        "", " ", "{", "[", "{\"a\"}", "{\"a\":}", "{\"a\" 1}", "{\"a\":1,}", "{\"a\":1 \"b\":2}",
        "[1,]", "[,1]", "[1 2]", "[1,,2]", "{a:1}", "{'a':1}", "true false", "nullx",
        "True", "NaN", "Infinity", "+1", "01", "-01", "-", ".1", "1.", "1e", "1e+", "0x10",
        "[1:2]", "{}x", "[]]", "/*comment*/null", "[null//comment\n]", "\uFEFFnull",
        "\"\\q\"", "\"\\u12\"", "\"\\u12xz\"", "\"\\u１２３４\"", "\"unterminated",
        "\"\\uD800\"", "\"\\uDC00\"", "\"\\uD800x\"", "\"\\uD800\\uD800\"",
        "{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}", "{\"unknown\":{\"x\":1,\"x\":2}}"
    })
    void rejectsMalformedJsonEvenWhenSkipping(String json) {
        JsonReader reader = new JsonReader(json);
        assertThrows(JsonParseException.class, () -> {
            reader.skipValue();
            reader.finish();
        }, json);
    }

    /** 验证字符串中的未转义控制字符和原始孤立代理项均被拒绝。 */
    @Test
    void rejectsRawControlCharactersAndIsolatedSurrogates() {
        for (char invalid : new char[]{0, 1, '\n', 31, (char) 0xD800, (char) 0xDC00}) {
            JsonReader reader = new JsonReader("\"" + invalid + "\"");
            assertThrows(JsonParseException.class, reader::skipValue, Integer.toHexString(invalid));
        }
    }

    /** 验证 finish() 拒绝未消费根值或未闭合容器，并检查容器遍历及跳过值的调用位置。 */
    @Test
    void finishRequiresAConsumedRootAndClosedContainers() {
        JsonReader unconsumed = new JsonReader("null");
        unconsumed.peek();
        assertThrows(JsonParseException.class, unconsumed::finish);
        JsonReader open = new JsonReader("{}");
        open.beginObject();
        assertThrows(JsonParseException.class, open::finish);
        assertThrows(IllegalStateException.class, () -> new JsonReader("[]").hasNext());
        assertThrows(JsonParseException.class, () -> new JsonReader("{}").endObject());
        JsonReader field = new JsonReader("{\"a\":1}");
        field.beginObject();
        assertThrows(JsonParseException.class, field::skipValue);
        field.nextName();
        assertThrows(IllegalStateException.class, field::hasNext);
    }

    /** 验证默认及自定义长度、深度上限的边界，并确认深层未知容器可通过显式栈跳过。 */
    @Test
    void enforcesLengthAndDepthBoundariesAndAllowsExplicitLimits() {
        String atLimit = "\"" + "a".repeat(JsonReader.DEFAULT_MAX_CHARS - 2) + "\"";
        JsonReader reader = new JsonReader(atLimit);
        reader.skipValue();
        reader.finish();
        assertThrows(JsonParseException.class, () -> new JsonReader(atLimit + " "));
        assertThrows(JsonParseException.class, () -> new JsonReader("[] ", 2, 1));
        JsonReader exact = new JsonReader("[]", 2, 1);
        exact.skipValue();
        exact.finish();
        JsonReader depth64 = new JsonReader(nestedArrays(64));
        depth64.skipValue();
        depth64.finish();
        assertThrows(JsonParseException.class, () -> new JsonReader(nestedArrays(65)).skipValue());
        assertThrows(JsonParseException.class, () -> new JsonReader("[[]]", 4, 1).skipValue());

        // 自定义较大上限时，跳过容器仍使用显式栈，不依赖递归调用。
        JsonReader deep = new JsonReader(nestedArrays(2048), 5000, 2048);
        deep.skipValue();
        deep.finish();
        assertThrows(IllegalArgumentException.class, () -> new JsonReader("[]", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new JsonReader("[]", 2, 0));
    }

    private static String nestedArrays(int depth) {
        return "[".repeat(depth) + "0" + "]".repeat(depth);
    }

}