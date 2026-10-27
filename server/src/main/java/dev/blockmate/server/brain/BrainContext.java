package dev.blockmate.server.brain;

import dev.blockmate.protocol.Snapshot;
import dev.blockmate.server.memory.Episode;
import dev.blockmate.server.memory.StoredMessage;
import dev.blockmate.server.memory.StoredPlace;
import dev.blockmate.server.memory.StoredPlan;

import java.util.List;

/**
 * 每次问模型时附带的东西：最新快照、是否在线、已确认偏好、最近日记、最近对话、最近任务、地点，
 * 任务单 17 加通关进度那一句（progressLine，后台算的）和附近的地标（landmarks，已经格式化成"村庄（西北 60 格）"）。
 */
public record BrainContext(Snapshot snapshot, boolean executorOnline, List<String> activePreferences,
                           List<Episode> episodes, List<StoredMessage> history, List<StoredPlan> recentPlans,
                           List<StoredPlace> places, String progressLine, List<String> landmarks) {

    /** 任务单 13–16 的写法（没有进度和地标）。 */
    public BrainContext(Snapshot snapshot, boolean executorOnline, List<String> activePreferences,
                        List<Episode> episodes, List<StoredMessage> history, List<StoredPlan> recentPlans,
                        List<StoredPlace> places) {
        this(snapshot, executorOnline, activePreferences, episodes, history, recentPlans, places, "", List.of());
    }

    /** 任务单 10 的写法（没有地点）。 */
    public BrainContext(Snapshot snapshot, boolean executorOnline, List<String> activePreferences,
                        List<Episode> episodes, List<StoredMessage> history, List<StoredPlan> recentPlans) {
        this(snapshot, executorOnline, activePreferences, episodes, history, recentPlans, List.of());
    }

    /** 旧写法（没有最近任务），测试和第一阶段代码继续用。 */
    public BrainContext(Snapshot snapshot, boolean executorOnline, List<String> activePreferences,
                        List<Episode> episodes, List<StoredMessage> history) {
        this(snapshot, executorOnline, activePreferences, episodes, history, List.of(), List.of());
    }
}
