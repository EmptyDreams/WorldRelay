package top.kmar.worldrelay.server.world;

/** 可安全返回客户端的协调失败；不包含凭据、文件内容或内部路径。 */
public final class WorldAccessException extends Exception {

    private final int status;
    private final String code;

    public WorldAccessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

}