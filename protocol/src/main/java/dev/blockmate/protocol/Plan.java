package dev.blockmate.protocol;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * hub → ui 的"计划"（任务单 9，guide-2 第 1.1 节）：一句话拆成的若干步和当前进度，网页用它画进度条。
 * current 是正在做的那一步的 index，整个计划结束后为 null。每一步变化时 hub 整条重发。
 */
public record Plan(String planId, String goal, List<Step> steps, Integer current) {

    public record Step(int index, CommandName command, JsonObject args, PlanStepState state) {
    }
}
