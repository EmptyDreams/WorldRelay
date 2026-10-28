package top.kmar.worldrelay.server.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonWriterTest {

    /** 验证字段按调用顺序输出，各种显式类型和嵌套容器组成预期的紧凑 JSON。 */
    @Test
    void writesOrderedFieldsNestedContainersAndExplicitValues() {
        String json = new JsonWriter().beginObject()
            .name("text").value("世界🌍")
            .name("int").value(7)
            .name("long").value(9007199254740993L)
            .name("double").value(1.25)
            .name("boolean").value(true)
            .name("null").nullValue()
            .name("array").beginArray().beginObject().endObject().beginArray().endArray().value("x").endArray()
            .endObject().finish();

        assertEquals("{\"text\":\"世界🌍\",\"int\":7,\"long\":9007199254740993,\"double\":1.25,"
            + "\"boolean\":true,\"null\":null,\"array\":[{},[],\"x\"]}", json);
        JsonReader reader = new JsonReader(json);
        reader.skipValue();
        reader.finish();
    }

    /** 验证各种基本类型和空容器都能独立作为根值输出，整数边界和负零保持正确表示。 */
    @Test
    void writesAllRootValueTypesAndEmptyContainers() {
        assertEquals("{}", new JsonWriter().beginObject().endObject().finish());
        assertEquals("[]", new JsonWriter().beginArray().endArray().finish());
        assertEquals("\"\"", new JsonWriter().value("").finish());
        assertEquals("\"text\"", new JsonWriter().value("text").finish());
        assertEquals("true", new JsonWriter().value(true).finish());
        assertEquals("false", new JsonWriter().value(false).finish());
        assertEquals("null", new JsonWriter().nullValue().finish());
        assertEquals("-2147483648", new JsonWriter().value(Integer.MIN_VALUE).finish());
        assertEquals("-9223372036854775808", new JsonWriter().value(Long.MIN_VALUE).finish());
        assertEquals("9223372036854775807", new JsonWriter().value(Long.MAX_VALUE).finish());
        assertEquals("0.125", new JsonWriter().value(0.125).finish());
        assertEquals("-0.0", new JsonWriter().value(-0.0).finish());
    }

    /** 验证字段名和值中的特殊字符被正确转义，中文和补充平面字符保持不变且可以读回。 */
    @Test
    void escapesStringsAndFieldNamesWithoutChangingUnicode() {
        String text = "\"\\/\b\f\n\r\t" + (char) 0 + (char) 31 + "中文🌍";
        String json = new JsonWriter().beginObject()
            .name("a\"\n").value(text)
            .endObject().finish();
        assertEquals("{\"a\\\"\\n\":\"\\\"\\\\/\\b\\f\\n\\r\\t\\u0000\\u001f中文🌍\"}", json);

        JsonReader reader = new JsonReader(json);
        reader.beginObject();
        assertEquals("a\"\n", reader.nextName());
        assertEquals(text, reader.nextString());
        reader.endObject();
        reader.finish();
    }

    /** 验证 Writer 拒绝输出 NaN、正无穷和负无穷。 */
    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsNonFiniteNumbers(double value) {
        assertThrows(IllegalArgumentException.class, () -> new JsonWriter().value(value));
    }

    /** 验证有限 double 值写出再读回后保持相同位表示，包括极值和负零。 */
    @Test
    void preservesFiniteDoubleValuesThroughRoundTrip() {
        for (double value : new double[]{Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, Math.PI, -0.0}) {
            JsonReader reader = new JsonReader(new JsonWriter().value(value).finish());
            assertEquals(Double.doubleToLongBits(value), Double.doubleToLongBits(reader.nextDouble()));
            reader.finish();
        }
    }

    /** 验证字段名和值拒绝孤立代理项，Java null 不能隐式作为 JSON 字符串写出。 */
    @Test
    void rejectsIsolatedSurrogatesAndImplicitNulls() {
        for (String invalid : new String[]{
            String.valueOf((char) 0xD800), String.valueOf((char) 0xDC00), (char) 0xD800 + "x"
        }) {
            assertThrows(IllegalArgumentException.class, () -> new JsonWriter().value(invalid));
            assertThrows(IllegalArgumentException.class, () -> new JsonWriter().beginObject().name(invalid));
        }
        assertThrows(NullPointerException.class, () -> new JsonWriter().value(null));
        assertThrows(NullPointerException.class, () -> new JsonWriter().beginObject().name(null));
    }

    /** 验证同一对象内禁止重复字段，不同对象则允许使用相同字段名。 */
    @Test
    void rejectsDuplicateNamesButAllowsNamesInSeparateObjects() {
        JsonWriter duplicate = new JsonWriter().beginObject().name("a").value(1);
        assertThrows(IllegalArgumentException.class, () -> duplicate.name("a"));
        assertEquals("[{\"a\":1},{\"a\":2}]", new JsonWriter().beginArray()
            .beginObject().name("a").value(1).endObject()
            .beginObject().name("a").value(2).endObject()
            .endArray().finish());
    }

    /** 验证错误调用顺序、缺失字段值、多根值和未闭合容器均被拒绝。 */
    @Test
    void rejectsInvalidCallOrderAndIncompleteDocuments() {
        assertThrows(IllegalStateException.class, () -> new JsonWriter().name("outside"));
        assertThrows(IllegalStateException.class, () -> new JsonWriter().beginArray().name("inside-array"));
        assertThrows(IllegalStateException.class, () -> new JsonWriter().beginObject().value(1));
        assertThrows(IllegalStateException.class, () -> new JsonWriter().beginObject().name("a").name("b"));
        assertThrows(IllegalStateException.class, () -> new JsonWriter().beginObject().name("a").endObject());
        assertThrows(IllegalStateException.class, () -> new JsonWriter().beginArray().endObject());
        assertThrows(IllegalStateException.class, () -> new JsonWriter().beginObject().endArray());
        assertThrows(IllegalStateException.class, () -> new JsonWriter().endArray());
        assertThrows(IllegalStateException.class, () -> new JsonWriter().value(1).value(2));
        assertThrows(IllegalStateException.class, () -> new JsonWriter().finish());
        assertThrows(IllegalStateException.class, () -> new JsonWriter().beginArray().finish());
        assertThrows(IllegalStateException.class, () -> new JsonWriter().beginObject().name("a").finish());
    }

    /** 验证默认 64 层及自定义嵌套上限，并拒绝非正数上限。 */
    @Test
    void enforcesDefaultAndCustomNestingLimits() {
        JsonWriter writer = new JsonWriter();
        for (int i = 0; i < 64; i++) {
            writer.beginArray();
        }
        assertThrows(IllegalStateException.class, writer::beginObject);
        writer.value(0);
        for (int i = 0; i < 64; i++) {
            writer.endArray();
        }
        assertEquals("[".repeat(64) + "0" + "]".repeat(64), writer.finish());
        assertEquals("[{}]", new JsonWriter(2).beginArray().beginObject().endObject().endArray().finish());
        assertThrows(IllegalStateException.class, () -> new JsonWriter(1).beginArray().beginObject());
        assertThrows(IllegalArgumentException.class, () -> new JsonWriter(0));
    }

}