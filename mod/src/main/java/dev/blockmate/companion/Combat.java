package dev.blockmate.companion;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import dev.blockmate.protocol.Event;
import dev.blockmate.protocol.MessageType;
import dev.blockmate.protocol.Pos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * 自保（任务单 19）：每 tick 跑在执行器之前。感知 8 格内名单上的怪，被打或怪到 3 格内就开打；
 * 血少举盾后退、吃一口往主人跑；苦力怕只躲；30 秒打不完或血掉得太快就放弃往主人跑。
 * 打架期间执行器暂停（它只看 {@link #active()}），打完发 combat 事件。全部在游戏线程上。
 */
public final class Combat {

    private enum Phase { FIGHT, FLEE, SHIELD_BACK, EAT, RUN }

    /** 武器放快捷栏第 8 格（下标 7），食物第 7 格（下标 6）；执行器用第 9 格放方块，不冲突。 */
    static final int WEAPON_HOTBAR_SLOT = 7;
    static final int FOOD_HOTBAR_SLOT = 6;
    private static final int GOAL_REFRESH_TICKS = 10;
    private static final int RUN_MAX_TICKS = 200;
    private static final int EAT_TIMEOUT_TICKS = 120;
    private static final int HEALTH_WINDOW_TICKS = 200;

    private final BiConsumer<MessageType, Object> send;

    private boolean active;
    private Phase phase = Phase.FIGHT;
    private int startTick;
    private int phaseStart;
    private int lastThreatTick;
    private int lastGoalTick = -1000;
    private int lastShieldTick = -1000;
    private int killed;
    private float lastHealth = -1;
    private float damageTaken;
    private boolean shieldUp;
    private boolean eating;
    private boolean ateThisFight;
    private int eatStart;
    private int foodBefore;
    private String eatItem;
    private boolean gaveUp;
    private boolean weaponChecked;
    private LivingEntity target;
    private String targetKind = "";
    private final ArrayDeque<int[]> healthLog = new ArrayDeque<>(); // {tick, damage x100}

    public Combat(BiConsumer<MessageType, Object> send) {
        this.send = send;
    }

    public boolean active() {
        return active;
    }

    /** "停"命令或开关关掉：收手，不发 danger，发一条 combat(stopped)。 */
    public void stop(Minecraft mc, LocalPlayer me, int tick, String why) {
        if (active) {
            CompanionMod.LOG.info(CompanionMod.TAG + "combat stop: {}", why);
            end(mc, me, tick, "stopped");
        }
    }

    /** 每 tick 调一次；返回 true 时执行器这一 tick 不动（任务暂停）。 */
    public boolean tick(Minecraft mc, LocalPlayer me, Rules rules, int tick) {
        if (mc.level == null || me == null) {
            return false;
        }
        if (me.isDeadOrDying()) {
            if (active) {
                end(mc, me, tick, "died");
            }
            lastHealth = -1;
            return false;
        }
        trackHealth(me, tick);
        if ("off".equals(rules.combatMode())) {
            if (active) {
                end(mc, me, tick, "stopped");
            }
            return false;
        }
        // ---- 感知 ----
        boolean night = Survival.isNight(mc.level.getDayTime() % 24000);
        Entity attacker = lastAttacker(me);
        LivingEntity nearestHostile = null;
        double hostileDist = Double.NaN;
        LivingEntity nearestFlee = null;
        double fleeDist = Double.NaN;
        boolean attackedByHostile = false;
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(CombatLogic.ENGAGE_RADIUS))) {
            if (e == me || !e.isAlive()) {
                continue;
            }
            double d = me.distanceTo(e);
            if (d > CombatLogic.ENGAGE_RADIUS) {
                continue;
            }
            boolean attackedMe = attacker == e;
            CombatLogic.Kind kind = CombatLogic.classify(typeId(e), night, attackedMe, rules);
            if (kind == CombatLogic.Kind.HOSTILE) {
                if (attackedMe) {
                    attackedByHostile = true;
                }
                if (nearestHostile == null || d < hostileDist) {
                    nearestHostile = e;
                    hostileDist = d;
                }
            } else if (kind == CombatLogic.Kind.FLEE) {
                if (nearestFlee == null || d < fleeDist) {
                    nearestFlee = e;
                    fleeDist = d;
                }
            }
        }
        // ---- 开打 / 开躲 ----
        if (!active) {
            boolean flee = nearestFlee != null && fleeDist <= CombatLogic.FLEE_TRIGGER;
            boolean fight = nearestHostile != null && CombatLogic.shouldEngage(hostileDist, attackedByHostile);
            if (!flee && !fight) {
                return false;
            }
            begin(tick, fight ? nearestHostile : nearestFlee, flee && !fight);
        }
        if (nearestHostile != null || (nearestFlee != null && fleeDist <= CombatLogic.FLEE_DISTANCE)) {
            lastThreatTick = tick;
        }
        if (target != null && !target.isAlive()) {
            killed++;
            CompanionMod.LOG.info(CompanionMod.TAG + "combat target {} down", targetKind);
            target = null;
        }
        if (target == null && nearestHostile != null) {
            target = nearestHostile;
            targetKind = typeId(nearestHostile);
        }
        // ---- 打完了没 ----
        boolean calm = tick - lastThreatTick >= CombatLogic.CALM_TICKS;
        if (phase == Phase.RUN) {
            if ((calm && !eating) || tick - phaseStart > RUN_MAX_TICKS) {
                end(mc, me, tick, gaveUp ? "gave_up" : killed > 0 ? "killed" : "fled");
                return false;
            }
        } else if (calm && !eating) {
            end(mc, me, tick, killed > 0 ? "killed" : "fled");
            return false;
        }
        // ---- 这一 tick 做什么 ----
        if (eating) {
            tickEat(mc, me, tick);
            return true;
        }
        if (phase == Phase.RUN) {
            runToSafety(mc, me, tick);
            return true;
        }
        if (phase == Phase.SHIELD_BACK) {
            if (tick - phaseStart >= CombatLogic.SHIELD_BACK_TICKS) {
                lowerShield(mc, me);
                phase = Phase.FIGHT;
            } else {
                return true;
            }
        }
        double threatDist = target == null ? Double.NaN : me.distanceTo(target);
        CombatLogic.Situation s = new CombatLogic.Situation(me.getHealth(), me.getOffhandItem().getItem() instanceof ShieldItem,
                !Executor.foods(me).isEmpty(), threatDist, fleeDist, tick - startTick, damageLast10s(tick), tick - lastShieldTick,
                ateThisFight);
        CombatLogic.Action action = CombatLogic.decide(s);
        switch (action) {
            case FLEE -> fleeFrom(mc, me, nearestFlee, tick);
            case GIVE_UP -> giveUp(mc, me, tick);
            case EAT_RUN -> beginEat(mc, me, rules, tick);
            case SHIELD_BACK -> shieldBack(mc, me, tick);
            case ATTACK -> attack(mc, me, rules, night, attacker, tick);
            case APPROACH -> approach(mc, me, tick);
            case NONE -> {
                if (phase == Phase.FLEE) {
                    phase = Phase.FIGHT;
                }
            }
        }
        return true;
    }

    // ---------------- 各个动作 ----------------

    private void begin(int tick, LivingEntity first, boolean fleeing) {
        active = true;
        startTick = tick;
        phaseStart = tick;
        lastThreatTick = tick;
        killed = 0;
        damageTaken = 0;
        gaveUp = false;
        ateThisFight = false;
        weaponChecked = false;
        phase = fleeing ? Phase.FLEE : Phase.FIGHT;
        target = fleeing ? null : first;
        targetKind = first == null ? "" : typeId(first);
        BaritoneBridge.cancelEverything();
        CompanionMod.LOG.info(CompanionMod.TAG + "combat start target={} flee={} health={}", targetKind, fleeing, first == null ? 0 : first.getHealth());
    }

    private void attack(Minecraft mc, LocalPlayer me, Rules rules, boolean night, Entity attacker, int tick) {
        if (target == null) {
            return;
        }
        if (!weaponChecked) {
            selectWeapon(mc, me);
            weaponChecked = true;
        }
        if (phase != Phase.FIGHT) {
            phase = Phase.FIGHT;
        }
        me.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        if (me.getAttackStrengthScale(0.5f) < CombatLogic.SWING_STRENGTH) {
            return;
        }
        // 每一次挥击前都再过一遍名单（铁规矩 5）
        CombatLogic.Kind kind = CombatLogic.classify(targetKind, night, attacker == target, rules);
        boolean allowed = kind == CombatLogic.Kind.HOSTILE;
        CompanionMod.LOG.info(CompanionMod.TAG + "combat target={} allowed={}", targetKind, allowed);
        if (!allowed) {
            target = null;
            return;
        }
        mc.gameMode.attack(me, target);
        me.swing(InteractionHand.MAIN_HAND);
        me.resetAttackStrengthTicker();
    }

    private void approach(Minecraft mc, LocalPlayer me, int tick) {
        if (target == null) {
            return;
        }
        phase = Phase.FIGHT;
        if (tick - lastGoalTick < GOAL_REFRESH_TICKS) {
            return;
        }
        IBaritone b = BaritoneBridge.baritone();
        if (b != null) {
            b.getCustomGoalProcess().setGoalAndPath(new GoalNear(target.blockPosition(), 1));
            lastGoalTick = tick;
        }
    }

    private void fleeFrom(Minecraft mc, LocalPlayer me, LivingEntity from, int tick) {
        if (phase != Phase.FLEE) {
            phase = Phase.FLEE;
            phaseStart = tick;
            CompanionMod.LOG.info(CompanionMod.TAG + "combat flee from {}", from == null ? "?" : typeId(from));
        }
        if (from == null || tick - lastGoalTick < GOAL_REFRESH_TICKS) {
            return;
        }
        setGoalAway(me, from.position(), CombatLogic.FLEE_DISTANCE, tick);
    }

    private void shieldBack(Minecraft mc, LocalPlayer me, int tick) {
        phase = Phase.SHIELD_BACK;
        phaseStart = tick;
        lastShieldTick = tick;
        mc.gameMode.useItem(me, InteractionHand.OFF_HAND);
        shieldUp = true;
        if (target != null) {
            setGoalAway(me, target.position(), 3, tick);
        }
        CompanionMod.LOG.info(CompanionMod.TAG + "combat shield up, backing off (health {})", me.getHealth());
    }

    private void lowerShield(Minecraft mc, LocalPlayer me) {
        if (shieldUp) {
            if (me.isUsingItem()) {
                mc.gameMode.releaseUsingItem(me);
            }
            shieldUp = false;
        }
    }

    private void beginEat(Minecraft mc, LocalPlayer me, Rules rules, int tick) {
        Optional<Survival.Food> pick = Survival.pick(Executor.foods(me), rules.avoidFood(), null);
        if (pick.isEmpty()) {
            ateThisFight = true;
            return;
        }
        lowerShield(mc, me);
        BaritoneBridge.cancelEverything();
        int src = pick.get().inventoryIndex();
        if (src < 9) {
            me.getInventory().selected = src;
        } else {
            mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId,
                    ContainerSlots.slotFor(src, ContainerSlots.INVENTORY_MENU_START), FOOD_HOTBAR_SLOT, ClickType.SWAP, me);
            me.getInventory().selected = FOOD_HOTBAR_SLOT;
        }
        eating = true;
        ateThisFight = true;
        eatItem = pick.get().id();
        eatStart = tick;
        foodBefore = me.getFoodData().getFoodLevel();
        phase = Phase.EAT;
        CompanionMod.LOG.info(CompanionMod.TAG + "combat eating {} (health {})", eatItem, me.getHealth());
    }

    private void tickEat(Minecraft mc, LocalPlayer me, int tick) {
        if (tick - eatStart < 3) {
            return;
        }
        if (tick - eatStart == 3) {
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
            return;
        }
        boolean done = me.getFoodData().getFoodLevel() > foodBefore || !eatItem.equals(Executor.idOf(me.getMainHandItem()));
        if (done || tick - eatStart > EAT_TIMEOUT_TICKS) {
            mc.options.keyUse.setDown(false);
            if (me.isUsingItem()) {
                mc.gameMode.releaseUsingItem(me);
            }
            eating = false;
            weaponChecked = false;
            CompanionMod.LOG.info(CompanionMod.TAG + "combat ate={} food={} -> running to safety", done, me.getFoodData().getFoodLevel());
            phase = Phase.RUN;
            phaseStart = tick;
            lastGoalTick = -1000;
        }
    }

    private void giveUp(Minecraft mc, LocalPlayer me, int tick) {
        gaveUp = true;
        lowerShield(mc, me);
        phase = Phase.RUN;
        phaseStart = tick;
        lastGoalTick = -1000;
        send.accept(MessageType.EVENT, Event.danger("overwhelmed", Observer.pos(me), Observer.dim(mc)));
        CompanionMod.LOG.info(CompanionMod.TAG + "combat overwhelmed after {} ticks, damage {} -> running", tick - startTick, damageTaken);
    }

    /** 往主人跑；主人不在就往出生点跑（家在后台，模组不知道）。 */
    private void runToSafety(Minecraft mc, LocalPlayer me, int tick) {
        if (tick - lastGoalTick < GOAL_REFRESH_TICKS * 2) {
            return;
        }
        IBaritone b = BaritoneBridge.baritone();
        if (b == null) {
            return;
        }
        Player owner = findOwner(mc, me);
        BlockPos goal = owner != null ? owner.blockPosition() : mc.level.getSharedSpawnPos();
        b.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, 2));
        lastGoalTick = tick;
    }

    private void setGoalAway(LocalPlayer me, Vec3 from, double distance, int tick) {
        IBaritone b = BaritoneBridge.baritone();
        if (b == null) {
            return;
        }
        double dx = me.getX() - from.x;
        double dz = me.getZ() - from.z;
        double len = Math.hypot(dx, dz);
        if (len < 0.01) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        int gx = (int) Math.round(me.getX() + dx / len * distance);
        int gz = (int) Math.round(me.getZ() + dz / len * distance);
        b.getCustomGoalProcess().setGoalAndPath(new GoalXZ(gx, gz));
        lastGoalTick = tick;
    }

    private void selectWeapon(Minecraft mc, LocalPlayer me) {
        var items = me.getInventory().items;
        int best = -1;
        int bestRank = CombatLogic.weaponRank(Executor.idOf(me.getMainHandItem()));
        for (int i = 0; i < items.size(); i++) {
            ItemStack st = items.get(i);
            if (st.isEmpty()) {
                continue;
            }
            int r = CombatLogic.weaponRank(Executor.idOf(st));
            if (r > bestRank) {
                bestRank = r;
                best = i;
            }
        }
        if (best < 0) {
            return;
        }
        if (best < 9) {
            me.getInventory().selected = best;
        } else {
            mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId,
                    ContainerSlots.slotFor(best, ContainerSlots.INVENTORY_MENU_START), WEAPON_HOTBAR_SLOT, ClickType.SWAP, me);
            me.getInventory().selected = WEAPON_HOTBAR_SLOT;
        }
        CompanionMod.LOG.info(CompanionMod.TAG + "combat weapon {}", Executor.idOf(items.get(best)));
    }

    private void end(Minecraft mc, LocalPlayer me, int tick, String outcome) {
        lowerShield(mc, me);
        if (eating) {
            mc.options.keyUse.setDown(false);
            if (me.isUsingItem()) {
                mc.gameMode.releaseUsingItem(me);
            }
            eating = false;
        }
        BaritoneBridge.cancelEverything();
        int ms = (tick - startTick) * 50;
        int dmg = Math.round(damageTaken);
        CompanionMod.LOG.info(CompanionMod.TAG + "combat end target={} outcome={} ms={} damage={} killed={}", targetKind, outcome, ms, dmg, killed);
        send.accept(MessageType.EVENT, Event.combat(targetKind.isEmpty() ? "unknown" : targetKind, outcome, ms, dmg));
        active = false;
        phase = Phase.FIGHT;
        target = null;
        targetKind = "";
        gaveUp = false;
    }

    // ---------------- 小工具 ----------------

    private void trackHealth(LocalPlayer me, int tick) {
        float h = me.getHealth();
        if (lastHealth >= 0 && h < lastHealth) {
            float d = lastHealth - h;
            damageTaken += d;
            healthLog.addLast(new int[]{tick, Math.round(d * 100)});
        }
        lastHealth = h;
        while (!healthLog.isEmpty() && tick - healthLog.peekFirst()[0] > HEALTH_WINDOW_TICKS) {
            healthLog.pollFirst();
        }
    }

    private float damageLast10s(int tick) {
        int sum = 0;
        for (int[] e : healthLog) {
            if (tick - e[0] <= HEALTH_WINDOW_TICKS) {
                sum += e[1];
            }
        }
        return sum / 100f;
    }

    /** 刚打过我们的那个东西（受伤动画还在放的时候才算）。 */
    private static Entity lastAttacker(LocalPlayer me) {
        if (me.hurtTime <= 0) {
            return null;
        }
        DamageSource src = me.getLastDamageSource();
        return src == null ? null : src.getEntity();
    }

    static String typeId(Entity e) {
        var key = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        return key == null ? "" : key.toString();
    }

    private static Player findOwner(Minecraft mc, LocalPlayer me) {
        String owner = ClientConfig.OWNER_NAME.get();
        for (Player p : mc.level.players()) {
            if (p != me && owner.equals(p.getGameProfile().getName())) {
                return p;
            }
        }
        return null;
    }

}
