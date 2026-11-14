package top.kmar.worldrelay.permissions;

import java.util.Map;

/** 中心全局 OP 配置；账号名绑定既有鉴权身份，Minecraft UUID 由实际登录模式确定。 */
public record OperatorPolicy(long revision, Map<String, Integer> operators) {
    public static final int MAX_ENTRIES = 128;

    public OperatorPolicy {
        if (revision < 0 || operators.size() > MAX_ENTRIES) throw new IllegalArgumentException("Invalid operator policy");
        operators.forEach((name, level) -> {
            if (name == null || name.isBlank() || !name.equals(name.strip()) || name.codePointCount(0, name.length()) > 64
                || name.codePoints().anyMatch(c -> c == ':' || Character.isISOControl(c)
                || Character.getType(c) == Character.SURROGATE) || level == null || level < 1 || level > 4)
                throw new IllegalArgumentException("Invalid operator entry");
        });
        operators = Map.copyOf(operators);
    }
}
