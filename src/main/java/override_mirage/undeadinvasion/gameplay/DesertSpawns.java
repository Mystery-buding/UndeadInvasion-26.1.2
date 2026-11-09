package override_mirage.undeadinvasion.gameplay;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;

/**
 * 沙漠群系的刷怪规则：只允许尸壳与焦骸自然生成。
 *
 * <p>判定用「沙漠神殿」生物群系标签，并额外兼容名字里带 {@code desert} 的自定义群系；
 * 只拦截自然生成（{@link EntitySpawnReason#NATURAL}），不影响刷怪笼、指令与刷怪蛋。
 */
public final class DesertSpawns {
    private DesertSpawns() {}

    public static boolean isDesert(LevelAccessor level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        if (biome.is(BiomeTags.HAS_DESERT_PYRAMID)) {
            return true;
        }
        return biome.unwrapKey()
                .map(key -> key.identifier().getPath().contains("desert"))
                .orElse(false);
    }

    /**
     * 是否应该取消这次自然生成：沙漠里刷出普通僵尸或普通骷髅时返回 {@code true}。
     *
     * <p>用 {@code getClass() ==} 精确匹配，避免误伤尸壳、溺尸、僵尸村民、流浪者、焦骸等变种。
     */
    public static boolean shouldCancelNaturalSpawn(Mob mob, LevelAccessor level, BlockPos pos, EntitySpawnReason reason) {
        if (reason != EntitySpawnReason.NATURAL) {
            return false;
        }
        Class<?> type = mob.getClass();
        if (type != Zombie.class && type != Skeleton.class) {
            return false;
        }
        return isDesert(level, pos);
    }
}
