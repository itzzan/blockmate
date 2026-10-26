package dev.blockmate.companion;

/**
 * 背包第 i 格（0–8 快捷栏，9–35 主背包）在各个容器菜单里是第几个格子（任务单 14）。
 * 每种菜单先排自己的格子，然后是主背包 27 格，最后是快捷栏 9 格。
 */
public final class ContainerSlots {

    /** 背包菜单：0 成品，1–4 合成格，5–8 盔甲，9–35 主背包，36–44 快捷栏。 */
    public static final int INVENTORY_MENU_START = 9;
    /** 工作台菜单：0 成品，1–9 合成格，10–36 主背包，37–45 快捷栏。 */
    public static final int CRAFTING_MENU_START = 10;
    /** 熔炉菜单：0 原料，1 燃料，2 成品，3–29 主背包，30–38 快捷栏。 */
    public static final int FURNACE_MENU_START = 3;

    private ContainerSlots() {
    }

    public static int slotFor(int inventoryIndex, int invStart) {
        return inventoryIndex < 9 ? invStart + 27 + inventoryIndex : invStart + (inventoryIndex - 9);
    }
}
