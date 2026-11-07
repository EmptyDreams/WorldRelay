package top.kmar.worldrelay.server.world;

import java.time.ZoneId;
import java.util.Objects;
import java.util.Properties;

/** 常规保留数量是软目标；空间紧张时仍始终保护当前权威快照。 */
public record RetentionConfig(int recentVersions, int dailyBackupDays, int temporaryHours,
                              long minimumFreeBytes, int sweepIntervalMinutes, ZoneId timeZone) {

    public RetentionConfig {
        if (recentVersions < 1 || dailyBackupDays < 0 || temporaryHours < 1 || minimumFreeBytes < 0 || sweepIntervalMinutes < 1)
            throw new IllegalArgumentException("Invalid retention policy");
        Objects.requireNonNull(timeZone);
    }

    public static RetentionConfig defaults() {
        return new RetentionConfig(5, 7, 24, 1024L * 1024 * 1024, 60, ZoneId.systemDefault());
    }

    public static RetentionConfig load(Properties properties) {
        RetentionConfig defaults = defaults();
        try {
            return new RetentionConfig(
                Integer.parseInt(properties.getProperty("retention.recent-versions", "5").strip()),
                Integer.parseInt(properties.getProperty("retention.daily-backup-days", "7").strip()),
                Integer.parseInt(properties.getProperty("retention.temporary-hours", "24").strip()),
                Long.parseLong(properties.getProperty("retention.minimum-free-bytes", Long.toString(defaults.minimumFreeBytes)).strip()),
                Integer.parseInt(properties.getProperty("retention.sweep-interval-minutes", "60").strip()),
                ZoneId.of(properties.getProperty("retention.time-zone", defaults.timeZone.getId()).strip()));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid retention configuration", failure);
        }
    }

}
