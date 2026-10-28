package dev.blockmate.server.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图纸（任务单 22）：knowledge/blueprints/<名字>.yaml 的内容，相对坐标，随 build_portal 命令整份发给模组。
 * 现在只有 nether_portal 一张。
 */
public record Blueprint(String block, List<List<Integer>> frame, List<List<Integer>> corners, List<List<Integer>> inside,
                        List<Integer> light, List<Integer> enter) {

    /** 解析 yaml；缺 block 或 frame 为空抛 IllegalArgumentException。 */
    public static Blueprint parse(String yamlText) {
        Object loaded = new Yaml().load(yamlText == null ? "" : yamlText);
        if (!(loaded instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("图纸不是映射");
        }
        String block = m.get("block") == null ? "" : Knowledge.normalizeId(String.valueOf(m.get("block")));
        List<List<Integer>> frame = cells(m.get("frame"));
        if (block.isEmpty() || frame.isEmpty()) {
            throw new IllegalArgumentException("图纸缺 block 或 frame");
        }
        return new Blueprint(block, frame, cells(m.get("corners")), cells(m.get("inside")), cell(m.get("light")), cell(m.get("enter")));
    }

    private static List<List<Integer>> cells(Object o) {
        List<List<Integer>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object item : l) {
                List<Integer> c = cell(item);
                if (c != null) {
                    out.add(c);
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<Integer> cell(Object o) {
        if (!(o instanceof List<?> l) || l.size() != 3) {
            return null;
        }
        List<Integer> c = new ArrayList<>();
        for (Object v : l) {
            c.add(Integer.parseInt(String.valueOf(v).strip()));
        }
        return List.copyOf(c);
    }

    /** 变成 build_portal 的 args（JSON 键和 Command.BuildPortalArgs 一致）。 */
    public JsonObject toArgs() {
        JsonObject o = new JsonObject();
        o.addProperty("block", block);
        o.add("frame", arr(frame));
        o.add("corners", arr(corners));
        o.add("inside", arr(inside));
        if (light != null) {
            o.add("light", arr1(light));
        }
        if (enter != null) {
            o.add("enter", arr1(enter));
        }
        return o;
    }

    private static JsonArray arr(List<List<Integer>> cells) {
        JsonArray a = new JsonArray();
        for (List<Integer> c : cells) {
            a.add(arr1(c));
        }
        return a;
    }

    private static JsonArray arr1(List<Integer> c) {
        JsonArray a = new JsonArray();
        for (Integer v : c) {
            a.add(v);
        }
        return a;
    }
}
