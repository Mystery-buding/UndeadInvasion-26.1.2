package override_mirage.undeadinvasion.gameplay.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import override_mirage.undeadinvasion.gameplay.MobMounts;
import override_mirage.undeadinvasion.world.DayCounterData;

/**
 * 亡灵主动寻找坐骑。
 *
 * <p>每 5 秒扫描一次周围 16 格：僵尸找僵尸马、骷髅找骷髅马、溺尸找僵尸鹦鹉螺，
 * 尸壳/焦骸则去找骆驼尸壳。找不到就什么都不做，继续下次扫描。
 *
 * <p>不占用任何 AI 控制位，与移动/攻击目标并行运行，只负责上坐骑这一件事。
 */
public final class MountSeekGoal extends Goal {
    /** 扫描间隔（tick）：100 tick = 5 秒。 */
    private static final int INTERVAL = 100;

    private final Mob mob;
    private int cooldown;

    public MountSeekGoal(Mob mob) {
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        return this.mob.isAlive() && !this.mob.isPassenger() && !this.mob.isVehicle();
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public void tick() {
        if (this.cooldown > 0) {
            this.cooldown--;
            return;
        }
        this.cooldown = INTERVAL;

        if (this.mob.level() instanceof ServerLevel level) {
            MobMounts.tryAutoMount(level, this.mob, DayCounterData.dayOf(level));
        }
    }
}
