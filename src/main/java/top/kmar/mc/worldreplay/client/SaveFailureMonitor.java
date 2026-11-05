package top.kmar.mc.worldreplay.client;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** 原版部分存档 IO 异常只记录 ERROR；出现错误时保守地阻止自动发布，保留可恢复的工作副本。 */
final class SaveFailureMonitor extends AbstractAppender {

    private volatile boolean failed;

    SaveFailureMonitor() {
        super("WorldRelaySaveMonitor", null, null, true, Property.EMPTY_ARRAY);
    }

    void attach() {
        start();
        ((Logger) LogManager.getRootLogger()).addAppender(this);
    }

    void detach() {
        ((Logger) LogManager.getRootLogger()).removeAppender(this);
        stop();
    }

    boolean failed() {
        return failed;
    }

    @Override
    public void append(LogEvent event) {
        if (event.getLevel().isMoreSpecificThan(Level.ERROR)) failed = true;
    }

}