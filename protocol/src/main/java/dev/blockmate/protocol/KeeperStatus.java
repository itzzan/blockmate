package dev.blockmate.protocol;

import com.google.gson.annotations.SerializedName;

/** keeper 每 10 秒报一次（细则第 13 节）。desired 是 running 或 stopped。 */
public record KeeperStatus(boolean running, Integer pid, String desired, Long lastStartTs,
                           @SerializedName("restarts_10min") int restarts10min, boolean gaveUp,
                           String keeperVersion) {
}
