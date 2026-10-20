package dev.blockmate.server.brain;

import dev.blockmate.protocol.Snapshot;
import dev.blockmate.server.memory.Episode;
import dev.blockmate.server.memory.StoredMessage;

import java.util.List;

/** 每次问模型时附带的东西：最新快照、是否在线、已确认偏好、最近日记、最近对话。 */
public record BrainContext(Snapshot snapshot, boolean executorOnline, List<String> activePreferences,
                           List<Episode> episodes, List<StoredMessage> history) {
}
