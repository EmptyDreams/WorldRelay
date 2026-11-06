package top.kmar.mc.worldreplay.client;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 共享世界同步时间的显示规则；自然日按客户端本地时区判断。 */
final class WorldSyncTime {

    private static final String PREFIX = "worldrelay.shared_worlds.";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private WorldSyncTime() {
    }

    static Component format(@Nullable Instant committedAt, Instant now, ZoneId zone) {
        if (committedAt == null) {
            return Component.translatable(PREFIX + "never_committed");
        }
        Duration elapsed = Duration.between(committedAt, now);
        var date = committedAt.atZone(zone).toLocalDate();
        if (elapsed.compareTo(Duration.ofHours(6)) > 0 && !date.equals(now.atZone(zone).toLocalDate())) {
            return Component.literal(DATE.format(date));
        }
        if (elapsed.compareTo(Duration.ofHours(1)) <= 0) {
            // 服务端时钟稍快时也显示“刚刚”，避免出现负数分钟。
            long minutes = Math.max(0, Math.round(elapsed.toMillis() / 60_000.0));
            return minutes == 0 ? Component.translatable(PREFIX + "just_now")
                : Component.translatable(PREFIX + "minutes_ago", minutes);
        }
        return Component.translatable(PREFIX + "hours_ago", Math.round(elapsed.toMillis() / 3_600_000.0));
    }

}
