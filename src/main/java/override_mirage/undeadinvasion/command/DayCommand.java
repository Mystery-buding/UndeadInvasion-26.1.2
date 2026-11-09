package override_mirage.undeadinvasion.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import override_mirage.undeadinvasion.UndeadInvasion;
import override_mirage.undeadinvasion.event.DayCounterEvents;
import override_mirage.undeadinvasion.world.DayCounterData;

/**
 * 天数管理指令。
 *
 * <pre>
 * /undeadinvasion get          查询当前独立天数
 * /undeadinvasion set &lt;Xd&gt;     直接设定天数（下限 1）
 * /undeadinvasion add &lt;Xd&gt;     累加天数（仅接受正整数）
 * /undeadinvasion acidrain ... 立刻下酸雨（见 {@link AcidRainCommand}）
 * /undeadinvasion sulfurrain ... 立刻下硫磺雨
 * /undeadinvasion storm ...     停止 / 查询当前风暴
 * </pre>
 *
 * <p>{@code d} 表示 day，{@code 20d} 与 {@code 20} 都接受。权限等级 2（与 {@code /time} 一致），
 * 修改后会立即同步给所有客户端并重刷已加载亡灵的装备。
 *
 * <p>参数刻意使用原版 {@link StringArgumentType} 而不是自定义 {@code ArgumentType}：
 * 自定义类型必须注册进 {@code ArgumentTypeInfos} 与 {@code COMMAND_ARGUMENT_TYPE} 注册表，
 * 否则服务端给客户端发送命令树时会抛异常，玩家会以「无效的玩家数据」被踢出。
 */
@EventBusSubscriber(modid = UndeadInvasion.MOD_ID)
public final class DayCommand {
    private static final String ROOT = "undeadinvasion";

    private static final SimpleCommandExceptionType ERROR_INVALID_DAY =
            new SimpleCommandExceptionType(Component.translatable("commands.undeadinvasion.day.invalid"));
    private static final SimpleCommandExceptionType ERROR_NOT_POSITIVE =
            new SimpleCommandExceptionType(Component.translatable("commands.undeadinvasion.day.positive"));

    private DayCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal(ROOT)
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("get").executes(DayCommand::queryDay))
                        .then(Commands.literal("set")
                                .then(Commands.argument("day", StringArgumentType.word())
                                        .executes(DayCommand::setDay)))
                        .then(Commands.literal("add")
                                .then(Commands.argument("days", StringArgumentType.word())
                                        .executes(DayCommand::addDays)))
                        .then(AcidRainCommand.subcommand())
                        .then(AcidRainCommand.sulfurSubcommand())
                        .then(AcidRainCommand.stormControlSubcommand()));
    }

    private static int queryDay(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        long day = DayCounterData.dayOf(source.getLevel());
        source.sendSuccess(() -> Component.translatable("commands.undeadinvasion.day.get", day), false);
        return (int) Math.min(day, Integer.MAX_VALUE);
    }

    private static int setDay(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return apply(context, parseDay(StringArgumentType.getString(context, "day")), false);
    }

    private static int addDays(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        long delta = parseDay(StringArgumentType.getString(context, "days"));
        if (delta <= 0L) {
            // 只允许往前推；回退请用 set。
            throw ERROR_NOT_POSITIVE.create();
        }
        return apply(context, delta, true);
    }

    private static int apply(CommandContext<CommandSourceStack> context, long amount, boolean relative) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        DayCounterData data = DayCounterData.get(server);
        data.ensureInitialized(server.overworld().getGameTime());

        if (relative) {
            data.addDays(amount);
        } else {
            data.setDay(amount);
        }

        long day = data.getDay();
        DayCounterEvents.notifyChanged(server, data);
        source.sendSuccess(
                () -> Component.translatable(
                        relative ? "commands.undeadinvasion.day.add" : "commands.undeadinvasion.day.set", day),
                true);
        return (int) Math.min(day, Integer.MAX_VALUE);
    }

    /**
     * 解析 {@code 20d} 或 {@code 20}。
     */
    private static long parseDay(String token) throws CommandSyntaxException {
        String digits = token;
        if (digits.endsWith("d") || digits.endsWith("D")) {
            digits = digits.substring(0, digits.length() - 1);
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw ERROR_INVALID_DAY.create();
        }
    }
}
