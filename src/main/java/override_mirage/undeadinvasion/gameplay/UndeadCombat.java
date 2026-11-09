package override_mirage.undeadinvasion.gameplay;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/** 原版亡灵阵营判定，包含亡灵坐骑，不自动收录其他模组的亡灵。 */
public final class UndeadCombat {
    private UndeadCombat() {}

    public static boolean isFactionMember(@Nullable Entity entity) {
        // 通用亡灵标签也用于亡灵杀手和药水效果，不能单独作为入侵阵营的依据。
        return entity != null
                && isVanillaEntity(entity)
                && entity.is(EntityTypeTags.UNDEAD);
    }

    /** 其他模组注册的活体实体，包括使用通用亡灵标签的生物。 */
    public static boolean isModdedMob(@Nullable Entity entity) {
        return entity instanceof LivingEntity && !isVanillaEntity(entity);
    }

    public static boolean areAllies(@Nullable Entity first, @Nullable Entity second) {
        return isFactionMember(first) && isFactionMember(second);
    }

    private static boolean isVanillaEntity(Entity entity) {
        return Identifier.DEFAULT_NAMESPACE.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace());
    }
}
