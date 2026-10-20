package dev.blockmate.server.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** 物品 id → 中文名（细则第 9 节），表在 resources/items_zh.json，没映射的显示 id 去掉前缀。 */
public final class ItemNames {

    private final Map<String, String> names;

    public ItemNames(Map<String, String> names) {
        this.names = Map.copyOf(names);
    }

    public static ItemNames load() {
        Map<String, String> m = new HashMap<>();
        try (InputStream in = ItemNames.class.getResourceAsStream("/items_zh.json")) {
            if (in != null) {
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                o.entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsString()));
            }
        } catch (Exception ignored) {
            // 没有映射表也能跑，只是显示 id
        }
        return new ItemNames(m);
    }

    public String name(String id) {
        if (id == null) {
            return "";
        }
        String n = names.get(id);
        if (n != null) {
            return n;
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    public int size() {
        return names.size();
    }
}
