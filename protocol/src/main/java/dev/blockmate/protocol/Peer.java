package dev.blockmate.protocol;

import com.google.gson.annotations.SerializedName;

/** 信封里的 from / to：四个参与方（细则第 2 节）。 */
public enum Peer {
    @SerializedName("hub") HUB("hub"),
    @SerializedName("mc") MC("mc"),
    @SerializedName("ui") UI("ui"),
    @SerializedName("keeper") KEEPER("keeper");

    private final String wire;

    Peer(String wire) {
        this.wire = wire;
    }

    /** JSON 里的写法。 */
    public String wire() {
        return wire;
    }
}
