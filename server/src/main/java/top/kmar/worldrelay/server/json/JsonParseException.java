package top.kmar.worldrelay.server.json;

import java.io.Serial;

/** JSON 语法或类型错误；位置是原始字符串中从 0 开始的 UTF-16 字符偏移。 */
public final class JsonParseException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;
    private final int position;

    public JsonParseException(String message, int position) {
        super(message + "（位置 " + position + "）");
        this.position = position;
    }

    /** 供解析器调用方按 UTF-16 偏移定位错误；HTTP 接口仅返回统一错误，不公开解析细节。 */
    @SuppressWarnings("unused")
    public int position() {
        return position;
    }

}
