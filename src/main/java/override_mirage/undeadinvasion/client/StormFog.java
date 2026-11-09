package override_mirage.undeadinvasion.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.world.StormType;

/**
 * 特殊降雨的「雾霾」：让整个加载区域被对应颜色的雾笼罩。
 *
 * <p>粒子只能覆盖局部空间，真正覆盖整个视距的朦胧感必须由雾来提供。这里在
 * {@link ViewportEvent.RenderFog} 里直接改 {@link FogData}：
 * <ul>
 *   <li>把雾色改成当前风暴的雾霾色（酸雨苔绿 / 硫磺雨明黄）；</li>
 *   <li>把环境雾的起止距离拉近，让近处就开始起雾、到加载区域中段完全吞没远景；</li>
 *   <li>同步收紧渲染距离雾，避免原版视距雾把染色雾「推远」。</li>
 * </ul>
 *
 * <p>注意 {@code RenderFog#setNearPlaneDistance/setFarPlaneDistance} 改的是
 * {@code environmentalStart/End}，而 shader 同时还会读 {@code renderDistanceStart/End}，
 * 所以四者都要写。只在大气雾（不在水/岩浆/细雪里）且存在特殊降雨时介入。
 */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID, value = Dist.CLIENT)
public final class StormFog {
    /** 多近开始起雾（格）：贴脸仍有清晰视野，几格外开始泛色。 */
    private static final float FOG_START = 6.0F;
    /**
     * 雾完全吞没的距离 = 加载半径 × 该比例，并夹在上下限内：
     * 既让雾覆盖到加载区域深处，又避免高视距下把雾推得太远而失去朦胧感。
     */
    private static final float FOG_END_RATIO = 0.7F;
    private static final float FOG_END_MIN = 48.0F;
    private static final float FOG_END_MAX = 176.0F;

    private StormFog() {}

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getType() != FogType.ATMOSPHERIC) {
            return;
        }
        StormType storm = ClientAcidRain.get();
        if (!storm.isActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        // 加载区域半径（格）：有效视距是区块数，乘 16。
        float radius = minecraft.options.getEffectiveRenderDistance() * 16.0F;
        applyFog(event.getFogData(), storm, radius);
    }

    /**
     * 把指定风暴的雾霾参数写入 {@link FogData}；拆成纯逻辑方法便于在无世界环境下验证。
     *
     * @param storm  当前特殊降雨类型
     * @param radius 当前加载区域的水平半径（格）
     */
    static void applyFog(FogData fog, StormType storm, float radius) {
        float end = Mth.clamp(radius * FOG_END_RATIO, FOG_END_MIN, FOG_END_MAX);
        float start = Math.min(FOG_START, end - 8.0F);

        fog.color.set(storm.fogRed(), storm.fogGreen(), storm.fogBlue(), 1.0F);
        // 环境雾：近距离起雾、到 end 完全吞没。
        fog.environmentalStart = start;
        fog.environmentalEnd = end;
        // 渲染距离雾：同步收紧，否则原版视距雾仍会在更远处才生效。
        fog.renderDistanceStart = start;
        fog.renderDistanceEnd = end;
        // 天空与云也在同一距离内被雾吃掉，避免远处露出清晰的天空色。
        fog.skyEnd = end;
        fog.cloudEnd = end;
    }
}
