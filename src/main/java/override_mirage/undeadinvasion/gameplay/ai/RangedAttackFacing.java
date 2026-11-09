package override_mirage.undeadinvasion.gameplay.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TridentItem;
import override_mirage.undeadinvasion.gameplay.UndeadCombat;

/** 同步远程出手方向与模型朝向，避免弹道已经转向而头部和身体还留在旧方向。 */
public final class RangedAttackFacing {
    private RangedAttackFacing() {}

    public static void faceTarget(Mob mob, LivingEntity target) {
        mob.lookAt(target, 360.0F, 360.0F);
        mob.setYHeadRot(mob.getYRot());
        mob.setYBodyRot(mob.getYRot());
        // 保留本 tick 后续的视线更新，避免 LookControl 再把头转回旧的观察位置。
        mob.getLookControl().setLookAt(target, 360.0F, 360.0F);
    }

    /** 在服务端和客户端都让远程战斗中的身体跟随瞄准方向，而不是横移方向。 */
    public static boolean keepBodyFacingAim(Mob mob) {
        if (!UndeadCombat.isFactionMember(mob) || !mob.isAggressive() || mob.isBlocking()) {
            return false;
        }
        Item weapon = mob.getMainHandItem().getItem();
        if (!(weapon instanceof BowItem || weapon instanceof CrossbowItem || weapon instanceof TridentItem)) {
            return false;
        }
        mob.setYBodyRot(mob.getYHeadRot());
        return true;
    }
}
