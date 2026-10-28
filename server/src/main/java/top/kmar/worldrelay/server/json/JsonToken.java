package top.kmar.worldrelay.server.json;

/** JSON 读取器当前可读取的结构或值。 */
public enum JsonToken {
    BEGIN_OBJECT, END_OBJECT,
    BEGIN_ARRAY, END_ARRAY,
    NAME, STRING, NUMBER, BOOLEAN, NULL,
    END_DOCUMENT
}