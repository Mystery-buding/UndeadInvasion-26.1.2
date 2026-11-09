package override_mirage.undeadinvasion.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.world.StormType;

/**
 * 特殊降雨期间铺满<strong>整个加载区域</strong>的飘浮雾霾粒子。
 *
 * <p>复用原版 {@link ParticleTypes#DUST}（速度自带 ×0.1 阻尼，适合漂浮感），
 * 通过 {@link DustParticleOptions} 按当前 {@link StormType} 取色，不注册新粒子类型、
 * 不新增贴图资源。远处的朦胧感由 {@link StormFog} 的雾提供，这里负责空间中可见的飘浮颗粒。
 *
 * <p>酸雨与硫磺雨都会生成，颜色分别取各自的雨色。生成半径取客户端有效视距
 * （区块数 × 16），即所有已加载区块的范围；生成量随半径亚线性增长并设硬上限，
 * 避免高视距下粒子数爆炸。
 */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID, value = Dist.CLIENT)
public final class StormHaze {
    /** 基准生成量（每 tick）与随半径的增量；再夹到硬上限。 */
    private static final int BASE_PER_TICK = 3;
    private static final int PER_TICK_PER_RADIUS = 24;
    private static final int MAX_PER_TICK = 24;

    /** 垂直覆盖：以玩家为中心，向下 / 向上各覆盖的格数。 */
    private static final double BELOW = 24.0;
    private static final double ABOVE = 48.0;

    /** 尘雾放大倍数：越大越蓬松、寿命也越长（合法上限 4.0）。 */
    private static final float SCALE = 3.2F;
    /**
     * 亮度压制：原版 DUST 走 OPAQUE 层且 vertex alpha 固定 1.0，无法直接设半透明，
     * 因此把雨色整体压暗，让密集粒子呈现「烟霾」而不是刺眼的亮色方块云。
     */
    private static final float BRIGHTNESS = 0.75F;

    /** 手动拉长寿命（刻），让雾霾缓慢飘荡而不是一闪而过。 */
    private static final int MIN_LIFETIME = 100;
    private static final int MAX_LIFETIME = 180;
    /** 极慢的水平漂移与微弱上浮。 */
    private static final double DRIFT = 0.01;
    private static final double RISE = 0.004;

    private StormHaze() {}

    /** 当前加载区域的水平半径（格）；视距以区块计。 */
    private static double loadedRadius(Minecraft minecraft) {
        return minecraft.options.getEffectiveRenderDistance() * 16.0;
    }

    /** 按加载半径计算每 tick 生成量（亚线性 + 上限）。 */
    static int particlesPerTick(double radius) {
        return Mth.clamp(BASE_PER_TICK + (int) (radius / PER_TICK_PER_RADIUS), BASE_PER_TICK, MAX_PER_TICK);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        StormType storm = ClientAcidRain.get();
        if (!storm.isActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || minecraft.isPaused()) {
            return;
        }

        double radius = loadedRadius(minecraft);
        int count = particlesPerTick(radius);
        RandomSource random = level.getRandom();
        // 粒子底色用雨色（比雾色亮），再乘 BRIGHTNESS 压暗成烟霾。
        DustParticleOptions options = new DustParticleOptions(storm.color(), SCALE);
        float r = storm.red() * BRIGHTNESS;
        float g = storm.green() * BRIGHTNESS;
        float b = storm.blue() * BRIGHTNESS;
        for (int i = 0; i < count; i++) {
            // 在覆盖整个加载区域的圆盘内均匀取点（sqrt 修正面积分布）。
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * radius;
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            double y = player.getY() - BELOW + random.nextDouble() * (BELOW + ABOVE);

            Particle particle = minecraft.particleEngine.createParticle(
                    options, x, y, z, 0.0, 0.0, 0.0);
            if (particle == null) {
                continue;
            }
            // 生成后重设速度与寿命：原版 dust 的初始速度是随机的，这里改成缓慢漂移。
            particle.setParticleSpeed(
                    (random.nextDouble() - 0.5) * DRIFT,
                    RISE + random.nextDouble() * RISE,
                    (random.nextDouble() - 0.5) * DRIFT);
            particle.setLifetime(MIN_LIFETIME + random.nextInt(MAX_LIFETIME - MIN_LIFETIME + 1));
            // OPAQUE 层的 dust 无法设半透明，压暗颜色来近似烟霾的低对比度。
            if (particle instanceof SingleQuadParticle quad) {
                quad.setColor(r, g, b);
            }
        }
    }
}
