package override_mirage.undeadinvasion.gameplay.ai;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;
import override_mirage.undeadinvasion.config.UndeadConfig;
import override_mirage.undeadinvasion.gameplay.MobBackpack;
import override_mirage.undeadinvasion.gameplay.ZombieSiege;
import override_mirage.undeadinvasion.world.DayCounterData;

/**
 * 沿通向目标的实际路线靠近并破门，不把视线当作能否通行的依据。
 * 长绕路暂时只算到入口外时，步兵先沿有进展的部分路径靠近，再寻找后续入口。
 *
 * <p>骑乘时 getNavigation() 返回坐骑的寻路器，马的宽度无法通过单格门。
 * 因此额外用骑手体型规划入屋路线，实际移动仍由坐骑执行；下马后重新启用步行破门寻路。
 *
 * <p>寻路只用来「找出路线上挡路的木门」。门关着、屋内有台阶或内墙时，
 * 原版寻路返回的是不可达的部分路径（{@code Path#canReach() == false}），
 * 这正需要破门；若要求完整可达，真实村庄里反而永远不会破门。
 */
public final class SiegeBreakDoorGoal extends Goal {
    private static final int DOOR_BREAK_TIME = 240;
    private static final int REPATH_INTERVAL = 20;
    /** 已经确定要拆的门，靠近阶段的重算间隔；比探索性扫描宽，减少重复 A*。 */
    private static final int APPROACH_REPATH_INTERVAL = 40;
    /** 路线里没有门时大幅拉长下次扫描，避免野外亡灵反复做无用的大范围寻路。 */
    private static final int NO_DOOR_SCAN_INTERVAL = 100;
    /** 探索和重算共用每刻额度，防止一群亡灵同刻把主线程吃满。 */
    private static final int PER_TICK_SCAN_BUDGET = 4;
    private static final int APPROACH_TIMEOUT = 400;
    /** 骑兵在只有步兵能通过的地形前停滞时，转为步行继续既定进门路线。 */
    private static final int MOUNT_STALL_TIMEOUT = 40;
    /** 连续多少次重算路线都找不到门才放弃，避免区块加载/目标移动造成的一次抖动打断破门。 */
    private static final int MAX_MISSING_ROUTE_SCANS = 3;
    private static final Object SCAN_BUDGET_LOCK = new Object();
    private static long scanBudgetTick = Long.MIN_VALUE;
    private static int scanBudgetUsed;
    private final Mob mob;
    private final GroundPathNavigation footNavigation;
    private @Nullable LivingEntity target;
    private @Nullable Path plannedRoute;
    private BlockPos approachPos = BlockPos.ZERO;
    private BlockPos doorPos = BlockPos.ZERO;
    private Vec3 pathedTarget = Vec3.ZERO;
    private Vec3 lastMountPosition = Vec3.ZERO;
    private long nextScan;
    private long nextRepath;
    private int approachTicks;
    private int missingRouteScans;
    private int mountStallTicks;
    private int breakTime;
    private int lastBreakProgress = -1;
    private boolean hasDoor;
    private boolean breaking;
    private boolean finished;

    public SiegeBreakDoorGoal(Mob mob) {
        // 读档时骑手可能仍在水生坐骑上。仅执行阶段检查导航，构造时不能要求陆地寻路。
        this.mob = mob;
        this.footNavigation = new GroundPathNavigation(mob, mob.level());
        ZombieSiege.prepareNavigation(this.footNavigation);
        // 抢占冲锋、远程攻击的移动控制，避免在门外反复后撤；举盾仍可独立运行。
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        LivingEntity candidate = this.mob.getTarget();
        if (!ZombieSiege.isEnabled(this.mob) || candidate == null || !candidate.isAlive()
                || !this.mob.getNavigation().canNavigateGround()
                || !EventHooks.canEntityGrief(getServerLevel(this.mob), this.mob)) {
            return false;
        }
        long now = this.mob.level().getGameTime();
        if (now < this.nextScan) {
            return false;
        }
        if (!this.claimScanBudget(now)) {
            // 本刻寻路额度用完：错开重试，别让一群亡灵卡在同一刻排队。
            this.nextScan = now + REPATH_INTERVAL + this.mob.getRandom().nextInt(REPATH_INTERVAL);
            return false;
        }

        this.target = candidate;
        Path route = this.findFootRoute();
        BlockPos door = this.findDoor(route);
        if (door != null && !CommonHooks.canEntityDestroy(getServerLevel(this.mob), door, this.mob)) {
            this.nextScan = now + NO_DOOR_SCAN_INTERVAL;
            return false;
        }
        if (door == null && !this.canAdvanceAlongPartialRoute(route)) {
            // 路线里没有需要拆的门：大幅拉长下次扫描，野外亡灵不再每秒白跑一次 32 格 A*。
            this.nextScan = now + NO_DOOR_SCAN_INTERVAL;
            return false;
        }
        this.nextScan = now + REPATH_INTERVAL;
        this.doorPos = door != null ? door : BlockPos.ZERO;
        this.hasDoor = door != null;
        this.plannedRoute = route;
        this.pathedTarget = candidate.position();
        return true;
    }

    @Override
    public void start() {
        this.breaking = false;
        this.finished = false;
        this.approachTicks = 0;
        this.missingRouteScans = 0;
        this.mountStallTicks = 0;
        this.lastMountPosition = this.mob.getRootVehicle().position();
        this.nextRepath = this.mob.level().getGameTime() + APPROACH_REPATH_INTERVAL;
        if (this.hasDoor && !this.mob.isPassenger()) {
            ZombieSiege.keepOnFoot(this.mob, this.target);
        }
        this.followRoute(this.plannedRoute);
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished && ZombieSiege.isEnabled(this.mob)
                && this.target != null && this.target.isAlive() && this.mob.getTarget() == this.target
                && this.mob.getNavigation().canNavigateGround()
                && (this.hasDoor ? this.isClosedWoodenDoor(this.doorPos)
                        : !this.mob.isPassenger() && !this.mob.getNavigation().isDone())
                && EventHooks.canEntityGrief(getServerLevel(this.mob), this.mob)
                && (this.breaking ? this.breakTime <= DOOR_BREAK_TIME && this.isAtDoor(this.mob.position(), 1.9)
                        : this.approachTicks < APPROACH_TIMEOUT);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (this.target == null || !this.target.isAlive() || this.mob.getTarget() != this.target) {
            this.finished = true;
            return;
        }
        long now = this.mob.level().getGameTime();
        // 破门中只在目标确实移动后核对路线，避免原地重复寻路把破门进度清零。
        if (now >= this.nextRepath && (!this.breaking || this.target.distanceToSqr(this.pathedTarget) >= 4.0)) {
            if (!this.claimScanBudget(now)) {
                this.nextRepath = now + 5 + this.mob.getRandom().nextInt(5);
            } else {
                this.nextRepath = now + APPROACH_REPATH_INTERVAL;
                Path route = this.findFootRoute();
                BlockPos currentDoor = this.findDoor(route);
                this.pathedTarget = this.target.position();
                if (!this.hasDoor) {
                    if (route == null || route.getNodeCount() <= 1) {
                        this.finished = true;
                        return;
                    }
                    if (currentDoor != null) {
                        if (!CommonHooks.canEntityDestroy(getServerLevel(this.mob), currentDoor, this.mob)) {
                            this.finished = true;
                            return;
                        }
                        this.doorPos = currentDoor;
                        this.hasDoor = true;
                        ZombieSiege.keepOnFoot(this.mob, this.target);
                    }
                    this.followRoute(route);
                } else {
                    // 目标已经走出门，或换到了另一条路线时，不再砸旧门。
                    if ((currentDoor != null && !currentDoor.equals(this.doorPos))
                            || (currentDoor == null && route != null && route.canReach())) {
                        this.finished = true;
                        this.nextScan = now;
                        return;
                    }
                    if (currentDoor == null) {
                        if (++this.missingRouteScans >= MAX_MISSING_ROUTE_SCANS && this.mob.getNavigation().isDone()) {
                            this.finished = true;
                            return;
                        }
                    } else {
                        this.missingRouteScans = 0;
                        if (!this.breaking) {
                            this.followRoute(route);
                        }
                    }
                }
            }
        }

        if (!this.hasDoor) {
            this.approachTicks++;
            return;
        }

        if (this.mob.isPassenger()) {
            this.approachTicks++;
            Vec3 mountPosition = this.mob.getRootVehicle().position();
            // 累计位移而不是单 tick 速度，避免把缓慢但正常前进的骑兵误判为卡住。
            if (mountPosition.distanceToSqr(this.lastMountPosition) >= 0.04) {
                this.mountStallTicks = 0;
                this.lastMountPosition = mountPosition;
            } else {
                this.mountStallTicks++;
            }
            if (this.isAtDoor(mountPosition, 2.5) || this.mountStallTicks >= MOUNT_STALL_TIMEOUT) {
                this.mob.getNavigation().stop();
                ZombieSiege.keepOnFoot(this.mob, this.target);
                this.mob.stopRiding();
                // getNavigation() 现在才切回骑手，必须重新开启木门通行。
                ZombieSiege.prepareNavigation(this.mob.getNavigation());
                MobBackpack.sync(this.mob, DayCounterData.dayOf(getServerLevel(this.mob)), this.mob.getRandom());
                this.followRoute(null);
            }
            return;
        }

        if (!this.breaking) {
            this.approachTicks++;
            if (!this.isAtDoor(this.mob.position(), 1.9)) {
                return;
            }
            this.mob.getNavigation().stop();
            this.breaking = true;
            this.breakTime = 0;
            this.lastBreakProgress = -1;
        }
        if (this.breakTime + 1 == DOOR_BREAK_TIME
                && !CommonHooks.canEntityDestroy(getServerLevel(this.mob), this.doorPos, this.mob)) {
            this.finished = true;
            return;
        }
        this.tickDoorBreaking();
    }

    @Override
    public void stop() {
        if (this.breaking) {
            this.mob.level().destroyBlockProgress(this.mob.getId(), this.doorPos, -1);
        }
        this.mob.getNavigation().stop();
        this.breaking = false;
        this.target = null;
        this.plannedRoute = null;
    }

    /** 保留原版 240 tick 的敲门、挥手、裂纹和破坏效果，不依赖限定导航类型的父类构造器。 */
    private void tickDoorBreaking() {
        if (this.mob.getRandom().nextInt(20) == 0) {
            this.mob.level().levelEvent(1019, this.doorPos, 0);
            if (!this.mob.swinging) {
                this.mob.swing(this.mob.getUsedItemHand());
            }
        }
        this.breakTime++;
        int progress = this.breakTime * 10 / DOOR_BREAK_TIME;
        if (progress != this.lastBreakProgress) {
            this.mob.level().destroyBlockProgress(this.mob.getId(), this.doorPos, progress);
            this.lastBreakProgress = progress;
        }
        if (this.breakTime == DOOR_BREAK_TIME) {
            if (this.isClosedWoodenDoor(this.doorPos)) {
                BlockState state = this.mob.level().getBlockState(this.doorPos);
                this.mob.level().removeBlock(this.doorPos, false);
                this.mob.level().levelEvent(1021, this.doorPos, 0);
                this.mob.level().levelEvent(2001, this.doorPos, Block.getId(state));
            }
            this.finished = true;
        }
    }

    /** 全局限流：探索与靠近重算共用额度，避免大量僵尸在同一刻集中做 A*。 */
    private boolean claimScanBudget(long now) {
        synchronized (SCAN_BUDGET_LOCK) {
            if (scanBudgetTick != now) {
                scanBudgetTick = now;
                scanBudgetUsed = 0;
            }
            if (scanBudgetUsed >= PER_TICK_SCAN_BUDGET) {
                return false;
            }
            scanBudgetUsed++;
            return true;
        }
    }

    private @Nullable Path findFootRoute() {
        if (this.target == null) {
            return null;
        }
        // 路程可以长于索敌的直线距离；预算仍随配置半径有界增长。
        ZombieSiege.prepareNavigation(this.footNavigation);
        this.footNavigation.setRequiredPathLength(Math.max(16.0F, UndeadConfig.settings().siegeRange() * 2.0F));
        this.footNavigation.setCanFloat(this.mob.getNavigation().canFloat());
        this.footNavigation.setMaxVisitedNodesMultiplier(1.0F);
        return this.footNavigation.createPath(this.target, 0);
    }

    /** 入口还没被搜索到时，只接管明显优于普通追击的部分路径，不扩大日常寻路预算。 */
    private boolean canAdvanceAlongPartialRoute(@Nullable Path route) {
        if (this.mob.isPassenger() || this.target == null || route == null || route.canReach()
                || route.getNodeCount() <= 1) {
            return false;
        }
        double remaining = Vec3.atBottomCenterOf(route.getEndNode().asBlockPos()).distanceToSqr(this.target.position());
        if (remaining + 4.0 >= this.mob.distanceToSqr(this.target)) {
            return false;
        }
        Path current = this.mob.getNavigation().getPath();
        return current == null || current.isDone() || (!current.canReach()
                && remaining + 4.0 < Vec3.atBottomCenterOf(current.getEndNode().asBlockPos())
                        .distanceToSqr(this.target.position()));
    }

    private @Nullable BlockPos findDoor(@Nullable Path route) {
        // 不要求 canReach()：门是关着的，寻路到屋内目标通常只能给出部分路径，
        // 而「路径被门挡住」正是需要破门的情况。只看这条路线是否经过关闭的木门。
        if (route == null || route.getNodeCount() == 0) {
            return null;
        }
        for (int i = 0; i < route.getNodeCount(); i++) {
            BlockPos pos = route.getNodePos(i);
            // 半砖、台阶和高门槛会让节点位于门的下方，检查整个站立高度。
            if (!this.isClosedWoodenDoor(pos) && this.isClosedWoodenDoor(pos.above())) {
                pos = pos.above();
            }
            if (!this.isClosedWoodenDoor(pos)) {
                continue;
            }
            BlockState state = this.mob.level().getBlockState(pos);
            BlockPos door = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
            if (i == 0 && route.getNodeCount() > 1 && !this.doorBlocksNextStep(door, route)) {
                // 已经站在门所在格的外侧并向外离开时，起点门节点不代表门挡住了新路线。
                continue;
            }
            this.approachPos = route.getNodePos(Math.max(0, i - 1));
            return door;
        }
        return null;
    }

    private boolean doorBlocksNextStep(BlockPos door, Path route) {
        Vec3 from = this.mob.position().add(0.0, 1.0, 0.0);
        Vec3 next = route.getEntityPosAtNode(this.mob, 1).add(0.0, 1.0, 0.0);
        BlockHitResult hit = this.mob.level().clip(new ClipContext(from, next,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.mob));
        return hit.getType() == HitResult.Type.BLOCK
                && (hit.getBlockPos().equals(door) || hit.getBlockPos().equals(door.above()));
    }

    private void followRoute(@Nullable Path footRoute) {
        if (this.mob.isPassenger()) {
            // 接受部分路径，让马靠近门口；寻路失败本身不能作为下马依据。
            var navigation = this.mob.getNavigation();
            ZombieSiege.prepareNavigation(navigation);
            if (!navigation.moveTo(navigation.createPath(this.approachPos, 0), 1.0)) {
                // 刚生成的马可能尚未落地；短暂失败后及时重试，不能一直等到停滞下马。
                this.nextRepath = this.mob.level().getGameTime() + 5;
            }
        } else {
            ZombieSiege.prepareNavigation(this.mob.getNavigation());
            this.mob.getNavigation().moveTo(footRoute != null ? footRoute : this.findFootRoute(), 1.0);
        }
    }

    private boolean isClosedWoodenDoor(BlockPos pos) {
        return DoorBlock.isWoodenDoor(this.mob.level(), pos)
                && !this.mob.level().getBlockState(pos).getValue(DoorBlock.OPEN);
    }

    private boolean isAtDoor(Vec3 position, double distance) {
        Vec3 doorCenter = Vec3.atBottomCenterOf(this.doorPos).add(0.0, 1.0, 0.0);
        Vec3 from = position.add(0.0, 1.0, 0.0);
        if (!from.closerThan(doorCenter, distance)) {
            return false;
        }
        BlockHitResult hit = this.mob.level().clip(new ClipContext(from, doorCenter,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.mob));
        return hit.getType() == HitResult.Type.MISS
                || hit.getBlockPos().equals(this.doorPos) || hit.getBlockPos().equals(this.doorPos.above());
    }
}
