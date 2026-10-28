package dev.blockmate.companion;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
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
 * 自保与保护（任务单 19 / 20）：每 tick 跑在执行器之前。
 * 19：8 格内名单上的怪，被打或到 3 格内就开打；血少举盾后退、吃一口往主人跑；苦力怕只躲；30 秒打不完放弃。
 * 20：保护模式以主人为中心 8 格找怪，走到主人和怪中间拦下；主人血少顶在前面举盾；主人正在打的不抢；
 *     "打那只 xx"（hunt）16 格内找这一种、跑出 24 格算打完、60 秒超时；有弓箭且目标 6 格外用弓。
 * 打架期间执行器暂停（它只看 {@link #active()}；fight 任务除外），打完发 combat 事件。全部在游戏线程上。
 */
public final class Combat {

    private enum Phase { FIGHT, FLEE, SHIELD_BACK, EAT, RUN, GUARD, SHOOT }

    /** "打那只"的进展，执行器每 tick 看。 */
    public enum HuntState { NONE, SEARCHING, FIGHTING, DONE_OK, NOT_FOUND, TIMEOUT, GAVE_UP }

    /** 武器放快捷栏第 8 格（下标 7），食物第 7 格（下标 6）；执行器用第 9 格放方块，不冲突。 */
    static final int WEAPON_HOTBAR_SLOT = 7;
    static final int FOOD_HOTBAR_SLOT = 6;
    private static final int GOAL_REFRESH_TICKS = 10;
    private static final int RUN_MAX_TICKS = 200;
    private static final int EAT_TIMEOUT_TICKS = 120;
    private static final int HEALTH_WINDOW_TICKS = 200;
    private static final int SHOT_COOLDOWN_TICKS = 10;
    private static final double SCAN_RADIUS = 24.0;

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
    private boolean useKeyHeld;
    private boolean eating;
    private boolean ateThisFight;
    private int eatStart;
    private int foodBefore;
    private String eatItem;
    private boolean gaveUp;
    private boolean weaponChecked;
    private boolean protecting;
    private int chargeStart;
    private int shotCooldownUntil;
    private LivingEntity target;
    private String targetKind = "";
    private final ArrayDeque<int[]> healthLog = new ArrayDeque<>(); // {tick, damage x100}
    // 任务单 20："打那只"
    private String huntKind;
    private int huntStart;
    private int huntKilled;
    private HuntState huntState = HuntState.NONE;

    public Combat(BiConsumer<MessageType, Object> send) {
        this.send = send;
    }

    public boolean active() {
        return active;
    }

    /** 盾或弓正按着使用键：执行器暂停时别把它松掉。 */
    public boolean holdingUseKey() {
        return useKeyHeld;
    }

    // ---------------- "打那只"（任务单 20） ----------------

    public void hunt(String kind, int tick) {
        huntKind = Rules.normalize(kind);
        huntStart = tick;
        huntKilled = 0;
        huntState = HuntState.SEARCHING;
        CompanionMod.LOG.info(CompanionMod.TAG + "hunt {} start", huntKind);
    }

    public HuntState huntState() {
        return huntState;
    }

    public int huntKilled() {
        return huntKilled;
    }

    /** 任务被换掉 / 结束：不再找这一种；已经打起来的架按自保规则打完。 */
    public void cancelHunt() {
        huntKind = null;
        huntState = HuntState.NONE;
    }

    private boolean hunting() {
        return huntKind != null && (huntState == HuntState.SEARCHING || huntState == HuntState.FIGHTING);
    }

    private void finishHunt(HuntState how) {
        CompanionMod.LOG.info(CompanionMod.TAG + "hunt {} -> {} killed={}", huntKind, how, huntKilled);
        huntState = how;
        huntKind = null;
    }

    /** "停"命令或开关关掉：收手，不发 danger，发一条 combat(stopped)。 */
    public void stop(Minecraft mc, LocalPlayer me, int tick, String why) {
        if (active) {
            CompanionMod.LOG.info(CompanionMod.TAG + "combat stop: {}", why);
            end(mc, me, tick, "stopped");
        }
    }

    /** 每 tick 调一次；返回 true 时执行器这一 tick 不动（任务暂停；fight 任务例外，由执行器自己判断）。 */
    public boolean tick(Minecraft mc, LocalPlayer me, Rules rules, int tick) {
        if (mc.level == null || me == null) {
            return false;
        }
        if (me.isDeadOrDying()) {
            if (active) {
                end(mc, me, tick, "died");
            }
            if (hunting()) {
                finishHunt(HuntState.GAVE_UP);
            }
            lastHealth = -1;
            return false;
        }
        trackHealth(me, tick);
        if ("off".equals(rules.combatMode())) {
            if (active) {
                end(mc, me, tick, "stopped");
            }
            if (hunting()) {
                finishHunt(HuntState.GAVE_UP);
            }
            return false;
        }
        // ---- 感知 ----
        boolean night = Survival.isNight(mc.level.getDayTime() % 24000);
        Entity attacker = lastAttacker(me);
        Player owner = findOwner(mc, me);
        boolean protectMode = "protect".equals(rules.combatMode()) && owner != null;
        boolean ownerLow = owner != null && owner.getHealth() <= CombatLogic.OWNER_LOW_HEALTH;
        LivingEntity nearestHostile = null;
        double hostileDist = Double.NaN;
        LivingEntity nearestFlee = null;
        double fleeDist = Double.NaN;
        LivingEntity ownerThreat = null;
        double ownerThreatDist = Double.NaN;
        LivingEntity huntTarget = null;
        double huntDist = Double.NaN;
        boolean attackedByHostile = false;
        double huntRadius = huntState == HuntState.FIGHTING ? CombatLogic.HUNT_LOSE_RADIUS : CombatLogic.HUNT_RADIUS;
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(SCAN_RADIUS))) {
            if (e == me || !e.isAlive()) {
                continue;
            }
            double d = me.distanceTo(e);
            boolean attackedMe = attacker == e;
            String typeId = typeId(e);
            CombatLogic.Kind kind = CombatLogic.classify(typeId, night, attackedMe, rules);
            if (kind == CombatLogic.Kind.HOSTILE) {
                if (hunting() && d <= huntRadius && CombatLogic.huntMatches(typeId, huntKind) && (huntTarget == null || d < huntDist)) {
                    huntTarget = e;
                    huntDist = d;
                }
                if (d <= CombatLogic.ENGAGE_RADIUS) {
                    if (attackedMe) {
                        attackedByHostile = true;
                    }
                    if (nearestHostile == null || d < hostileDist) {
                        nearestHostile = e;
                        hostileDist = d;
                    }
                }
                if (protectMode) {
                    double dOwner = owner.distanceTo(e);
                    if (dOwner <= CombatLogic.PROTECT_RADIUS
                            && !CombatLogic.ownerIsFighting(dOwner, owner.swinging, owner.getHealth())
                            && (ownerThreat == null || dOwner < ownerThreatDist)) {
                        ownerThreat = e;
                        ownerThreatDist = dOwner;
                    }
                }
            } else if (kind == CombatLogic.Kind.FLEE && d <= CombatLogic.ENGAGE_RADIUS) {
                if (nearestFlee == null || d < fleeDist) {
                    nearestFlee = e;
                    fleeDist = d;
                }
            }
        }
        // ---- "打那只"的找与丢 ----
        if (huntState == HuntState.SEARCHING) {
            if (huntTarget != null) {
                huntState = HuntState.FIGHTING;
            } else if (tick - huntStart > CombatLogic.HUNT_SEARCH_GRACE_TICKS) {
                finishHunt(HuntState.NOT_FOUND);
            }
        }
        if (huntState == HuntState.FIGHTING && tick - huntStart > CombatLogic.HUNT_TIMEOUT_TICKS) {
            finishHunt(HuntState.TIMEOUT);
        }
        // ---- 开打 / 开躲 ----
        if (!active) {
            boolean flee = nearestFlee != null && fleeDist <= CombatLogic.FLEE_TRIGGER;
            boolean fight = nearestHostile != null && CombatLogic.shouldEngage(hostileDist, attackedByHostile);
            if (flee) {
                begin(tick, nearestFlee, true, false);
            } else if (huntState == HuntState.FIGHTING && huntTarget != null) {
                begin(tick, huntTarget, false, false);
            } else if (fight) {
                begin(tick, nearestHostile, false, false);
            } else if (ownerThreat != null) {
                begin(tick, ownerThreat, false, true);
            } else {
                return false;
            }
        }
        boolean threatAround = nearestHostile != null || (nearestFlee != null && fleeDist <= CombatLogic.FLEE_DISTANCE)
                || (protecting && ownerThreat != null) || (hunting() && huntTarget != null);
        if (threatAround) {
            lastThreatTick = tick;
        }
        if (target != null && !target.isAlive()) {
            killed++;
            if (hunting() && CombatLogic.huntMatches(targetKind, huntKind)) {
                huntKilled++;
            }
            CompanionMod.LOG.info(CompanionMod.TAG + "combat target {} down", targetKind);
            target = null;
        }
        if (target == null) {
            LivingEntity next = hunting() ? huntTarget : protecting && ownerThreat != null ? ownerThreat : nearestHostile;
            if (next != null) {
                target = next;
                targetKind = typeId(next);
            }
        }
        if (huntState == HuntState.FIGHTING && huntTarget == null) {
            finishHunt(HuntState.DONE_OK); // 这一种都死了或跑出 24 格
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
        boolean bowAndArrow = hasBow(me) && hasArrow(me);
        boolean canSee = target != null && me.hasLineOfSight(target);
        CombatLogic.Situation s = new CombatLogic.Situation(me.getHealth(), me.getOffhandItem().getItem() instanceof ShieldItem,
                !Executor.foods(me).isEmpty(), threatDist, fleeDist, tick - startTick, damageLast10s(tick), tick - lastShieldTick,
                ateThisFight, bowAndArrow, canSee, protecting, ownerLow);
        CombatLogic.Action action = CombatLogic.decide(s);
        if (action != CombatLogic.Action.SHOOT && phase == Phase.SHOOT) {
            stopCharging(mc, me);
        }
        switch (action) {
            case FLEE -> fleeFrom(mc, me, nearestFlee, tick);
            case GIVE_UP -> giveUp(mc, me, tick);
            case EAT_RUN -> beginEat(mc, me, rules, tick);
            case SHIELD_BACK -> shieldBack(mc, me, tick);
            case GUARD -> guard(mc, me, owner, tick);
            case ATTACK -> attack(mc, me, rules, night, attacker, tick);
            case SHOOT -> shoot(mc, me, tick);
            case APPROACH -> approach(mc, me, owner, tick);
            case NONE -> {
                if (phase == Phase.FLEE) {
                    phase = Phase.FIGHT;
                }
            }
        }
        return true;
    }

    // ---------------- 各个动作 ----------------

    private void begin(int tick, LivingEntity first, boolean fleeing, boolean protect) {
        active = true;
        startTick = tick;
        phaseStart = tick;
        lastThreatTick = tick;
        killed = 0;
        damageTaken = 0;
        gaveUp = false;
        ateThisFight = false;
        weaponChecked = false;
        protecting = protect;
        phase = fleeing ? Phase.FLEE : Phase.FIGHT;
        target = fleeing ? null : first;
        targetKind = first == null ? "" : typeId(first);
        BaritoneBridge.cancelEverything();
        CompanionMod.LOG.info(CompanionMod.TAG + "combat start target={} flee={} protect={} hunt={} health={}", targetKind, fleeing, protect,
                huntKind, first == null ? 0 : first.getHealth());
    }

    private void attack(Minecraft mc, LocalPlayer me, Rules rules, boolean night, Entity attacker, int tick) {
        if (target == null) {
            return;
        }
        if (!weaponChecked || CombatLogic.isBow(Executor.idOf(me.getMainHandItem()))) {
            selectWeapon(mc, me);
            weaponChecked = true;
        }
        if (phase != Phase.FIGHT && phase != Phase.GUARD) {
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

    /** 任务单 20：用弓——换到弓，按住使用键蓄力 ≥ 20 tick，瞄怪的身体中心松开。 */
    private void shoot(Minecraft mc, LocalPlayer me, int tick) {
        if (target == null) {
            return;
        }
        Vec3 aim = target.position().add(0, target.getBbHeight() * 0.6, 0);
        me.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
        if (phase != Phase.SHOOT) {
            if (tick < shotCooldownUntil) {
                return;
            }
            if (!selectBow(mc, me)) {
                return;
            }
            BaritoneBridge.cancelEverything();
            phase = Phase.SHOOT;
            chargeStart = tick;
            mc.options.keyUse.setDown(true);
            useKeyHeld = true;
            mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
            return;
        }
        if (tick - chargeStart >= CombatLogic.BOW_CHARGE_TICKS && me.isUsingItem()) {
            mc.options.keyUse.setDown(false);
            useKeyHeld = false;
            mc.gameMode.releaseUsingItem(me);
            shotCooldownUntil = tick + SHOT_COOLDOWN_TICKS;
            phase = Phase.FIGHT;
            CompanionMod.LOG.info(CompanionMod.TAG + "combat shot at {} ({} ticks)", targetKind, tick - chargeStart);
        } else if (tick - chargeStart > CombatLogic.BOW_CHARGE_TICKS * 3) {
            stopCharging(mc, me); // 没拉起来（没箭？）就算了，下一 tick 会走近战
        }
    }

    private void stopCharging(Minecraft mc, LocalPlayer me) {
        if (phase == Phase.SHOOT) {
            mc.options.keyUse.setDown(false);
            useKeyHeld = false;
            if (me.isUsingItem()) {
                mc.gameMode.releaseUsingItem(me);
            }
            phase = Phase.FIGHT;
            weaponChecked = false;
        }
    }

    private void approach(Minecraft mc, LocalPlayer me, Player owner, int tick) {
        if (target == null) {
            return;
        }
        phase = Phase.FIGHT;
        if (tick - lastGoalTick < GOAL_REFRESH_TICKS) {
            return;
        }
        IBaritone b = BaritoneBridge.baritone();
        if (b == null) {
            return;
        }
        if (protecting && owner != null) {
            // 任务单 20：走到主人和怪的连线上离主人四成处拦下
            double[] p = CombatLogic.interceptPoint(owner.getX(), owner.getY(), owner.getZ(), target.getX(), target.getY(), target.getZ(), 0.4);
            b.getCustomGoalProcess().setGoalAndPath(new GoalNear(BlockPos.containing(p[0], p[1], p[2]), 1));
        } else {
            b.getCustomGoalProcess().setGoalAndPath(new GoalNear(target.blockPosition(), 1));
        }
        lastGoalTick = tick;
    }

    /** 任务单 20：主人血少——站到主人和怪之间（离主人 15%），举盾挡着；怪到跟前照样挥。 */
    private void guard(Minecraft mc, LocalPlayer me, Player owner, int tick) {
        if (owner == null || target == null) {
            return;
        }
        if (phase != Phase.GUARD) {
            phase = Phase.GUARD;
            phaseStart = tick;
            CompanionMod.LOG.info(CompanionMod.TAG + "combat guarding owner (owner health {})", owner.getHealth());
        }
        if (tick - lastGoalTick >= GOAL_REFRESH_TICKS) {
            IBaritone b = BaritoneBridge.baritone();
            if (b != null) {
                double[] p = CombatLogic.interceptPoint(owner.getX(), owner.getY(), owner.getZ(), target.getX(), target.getY(), target.getZ(), 0.15);
                b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(BlockPos.containing(p[0], p[1], p[2])));
                lastGoalTick = tick;
            }
        }
        me.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        if (me.getOffhandItem().getItem() instanceof ShieldItem && !useKeyHeld) {
            if (CombatLogic.isBow(Executor.idOf(me.getMainHandItem()))) {
                selectWeapon(mc, me);
            }
            mc.options.keyUse.setDown(true);
            useKeyHeld = true;
            mc.gameMode.useItem(me, InteractionHand.OFF_HAND);
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
        if (CombatLogic.isBow(Executor.idOf(me.getMainHandItem()))) {
            selectWeapon(mc, me); // 主手拿着弓的话使用键会先拉弓，先换回剑
        }
        mc.options.keyUse.setDown(true);
        useKeyHeld = true;
        mc.gameMode.useItem(me, InteractionHand.OFF_HAND);
        if (target != null) {
            setGoalAway(me, target.position(), 3, tick);
        }
        CompanionMod.LOG.info(CompanionMod.TAG + "combat shield up, backing off (health {})", me.getHealth());
    }

    private void lowerShield(Minecraft mc, LocalPlayer me) {
        if (useKeyHeld) {
            mc.options.keyUse.setDown(false);
            useKeyHeld = false;
            if (me.isUsingItem()) {
                mc.gameMode.releaseUsingItem(me);
            }
        }
    }

    private void beginEat(Minecraft mc, LocalPlayer me, Rules rules, int tick) {
        Optional<Survival.Food> pick = Survival.pick(Executor.foods(me), rules.avoidFood(), null);
        if (pick.isEmpty()) {
            ateThisFight = true;
            return;
        }
        lowerShield(mc, me);
        stopCharging(mc, me);
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
            useKeyHeld = true;
            mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
            return;
        }
        boolean done = me.getFoodData().getFoodLevel() > foodBefore || !eatItem.equals(Executor.idOf(me.getMainHandItem()));
        if (done || tick - eatStart > EAT_TIMEOUT_TICKS) {
            mc.options.keyUse.setDown(false);
            useKeyHeld = false;
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
        stopCharging(mc, me);
        phase = Phase.RUN;
        phaseStart = tick;
        lastGoalTick = -1000;
        if (hunting()) {
            finishHunt(HuntState.GAVE_UP);
        }
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
        String hand = Executor.idOf(me.getMainHandItem());
        int bestRank = CombatLogic.isBow(hand) ? -1 : CombatLogic.weaponRank(hand);
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
            if (CombatLogic.isBow(hand)) {
                selectEmptyOrAnyHotbar(me); // 没剑：至少别拿着弓挥
            }
            return;
        }
        toHotbar(mc, me, best);
        CompanionMod.LOG.info(CompanionMod.TAG + "combat weapon {}", Executor.idOf(items.get(best)));
    }

    private boolean selectBow(Minecraft mc, LocalPlayer me) {
        if (CombatLogic.isBow(Executor.idOf(me.getMainHandItem()))) {
            return true;
        }
        var items = me.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            if (CombatLogic.isBow(Executor.idOf(items.get(i)))) {
                toHotbar(mc, me, i);
                CompanionMod.LOG.info(CompanionMod.TAG + "combat bow out");
                return true;
            }
        }
        return false;
    }

    private void toHotbar(Minecraft mc, LocalPlayer me, int index) {
        if (index < 9) {
            me.getInventory().selected = index;
        } else {
            mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId,
                    ContainerSlots.slotFor(index, ContainerSlots.INVENTORY_MENU_START), WEAPON_HOTBAR_SLOT, ClickType.SWAP, me);
            me.getInventory().selected = WEAPON_HOTBAR_SLOT;
        }
    }

    private static void selectEmptyOrAnyHotbar(LocalPlayer me) {
        var items = me.getInventory().items;
        for (int i = 0; i < 9; i++) {
            if (!CombatLogic.isBow(Executor.idOf(items.get(i)))) {
                me.getInventory().selected = i;
                return;
            }
        }
    }

    private static boolean hasBow(LocalPlayer me) {
        for (ItemStack st : me.getInventory().items) {
            if (!st.isEmpty() && CombatLogic.isBow(Executor.idOf(st))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasArrow(LocalPlayer me) {
        for (ItemStack st : me.getInventory().items) {
            if (!st.isEmpty() && CombatLogic.isArrow(Executor.idOf(st))) {
                return true;
            }
        }
        return false;
    }

    private void end(Minecraft mc, LocalPlayer me, int tick, String outcome) {
        lowerShield(mc, me);
        stopCharging(mc, me);
        if (eating) {
            mc.options.keyUse.setDown(false);
            useKeyHeld = false;
            if (me.isUsingItem()) {
                mc.gameMode.releaseUsingItem(me);
            }
            eating = false;
        }
        BaritoneBridge.cancelEverything();
        int ms = (tick - startTick) * 50;
        int dmg = Math.round(damageTaken);
        CompanionMod.LOG.info(CompanionMod.TAG + "combat end target={} outcome={} ms={} damage={} killed={} protect={}", targetKind, outcome,
                ms, dmg, killed, protecting);
        send.accept(MessageType.EVENT, Event.combat(targetKind.isEmpty() ? "unknown" : targetKind, outcome, ms, dmg));
        if (huntState == HuntState.FIGHTING) {
            finishHunt("killed".equals(outcome) || "fled".equals(outcome) ? HuntState.DONE_OK : HuntState.GAVE_UP);
        }
        active = false;
        phase = Phase.FIGHT;
        target = null;
        targetKind = "";
        gaveUp = false;
        protecting = false;
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
