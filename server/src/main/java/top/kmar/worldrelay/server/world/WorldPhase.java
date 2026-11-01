package top.kmar.worldrelay.server.world;

/** 持久化阶段；离线是展示状态，不是所有权阶段。 */
public enum WorldPhase {
    FREE, PREPARING, STARTING, RUNNING, STOPPING, COMMITTING
}
