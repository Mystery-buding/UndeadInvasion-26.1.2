package override_mirage.undeadinvasion.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import override_mirage.undeadinvasion.gameplay.AcidRain;
import override_mirage.undeadinvasion.world.StormType;

/**
 * 特殊降雨管理指令，挂在 {@code /undeadinvasion} 根命令下。
 *
 * <pre>
 * /undeadinvasion acidrain             立刻下酸雨（默认 10 分钟）
 * /undeadinvasion acidrain start [秒]  立刻下酸雨，可指定持续秒数（1–3600）
 * /undeadinvasion sulfurrain           立刻下硫磺雨（默认 10 分钟）
 * /undeadinvasion sulfurrain start [秒] 立刻下硫磺雨，可指定持续秒数
 * /undeadinvasion storm stop           立刻停止当前风暴并放晴
 * /undeadinvasion storm query          查询当前风暴类型与剩余时间
 * </pre>
 *
 * <p>两种雨互斥：开始任一种会覆盖进行中的另一种。权限等级 2（与 {@code /time}、
 * {@link DayCommand} 一致）。手动触发不受天数门槛、当日概率和 {@code doWeatherCycle} 影响，
 * 状态会立即同步给所有在线客户端。
 */
public final class AcidRainCommand {
    private static final int MIN_SECONDS = 1;
    private static final int MAX_SECONDS = 3600;

    private AcidRainCommand() {}

    /** {@code acidrain} 子树。 */
    public static LiteralArgumentBuilder<CommandSourceStack> subcommand() {
        return stormSubcommand("acidrain", StormType.ACID);
    }

    /** {@code sulfurrain} 子树。 */
    public static LiteralArgumentBuilder<CommandSourceStack> sulfurSubcommand() {
        return stormSubcommand("sulfurrain", StormType.SULFUR);
    }

    /** 共用 {@code storm stop/query} 子树。 */
    public static LiteralArgumentBuilder<CommandSourceStack> stormControlSubcommand() {
        return Commands.literal("storm")
                .then(Commands.literal("stop").executes(AcidRainCommand::stop))
                .then(Commands.literal("query").executes(AcidRainCommand::query));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> stormSubcommand(String name, StormType type) {
        return Commands.literal(name)
                // 裸命令等价于 start（使用默认时长）。
                .executes(ctx -> start(ctx.getSource(), type, AcidRain.DEFAULT_DURATION_SECONDS))
                .then(Commands.literal("start")
                        .executes(ctx -> start(ctx.getSource(), type, AcidRain.DEFAULT_DURATION_SECONDS))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(MIN_SECONDS, MAX_SECONDS))
                                .executes(ctx -> start(ctx.getSource(), type,
                                        IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(Commands.literal("stop").executes(AcidRainCommand::stop))
                .then(Commands.literal("query").executes(AcidRainCommand::query));
    }

    private static int start(CommandSourceStack source, StormType type, int seconds) {
        MinecraftServer server = source.getServer();
        AcidRain.start(server, type, seconds * 20);
        String key = type == StormType.SULFUR
                ? "commands.undeadinvasion.sulfur_rain.start"
                : "commands.undeadinvasion.acid_rain.start";
        source.sendSuccess(() -> Component.translatable(key, seconds), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        if (!AcidRain.stop(server)) {
            source.sendFailure(Component.translatable("commands.undeadinvasion.storm.stop.none"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.undeadinvasion.storm.stop"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int query(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        StormType type = AcidRain.activeType(server);
        long remaining = AcidRain.remainingTicks(server);
        if (type == StormType.NONE || remaining <= 0L) {
            source.sendSuccess(
                    () -> Component.translatable("commands.undeadinvasion.storm.query.none"), false);
            return 0;
        }
        long seconds = (remaining + 19L) / 20L;
        String key = type == StormType.SULFUR
                ? "commands.undeadinvasion.storm.query.sulfur"
                : "commands.undeadinvasion.storm.query.acid";
        source.sendSuccess(() -> Component.translatable(key, seconds), false);
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }
}
