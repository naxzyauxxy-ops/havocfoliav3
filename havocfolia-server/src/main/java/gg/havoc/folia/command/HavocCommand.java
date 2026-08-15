package gg.havoc.folia.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import gg.havoc.folia.HavocFolia;
import gg.havoc.folia.antifreecam.AntiFreecamController;
import gg.havoc.folia.config.HavocConfig;
import gg.havoc.folia.scheduler.AffinityManager;
import gg.havoc.folia.util.Format;
import gg.havoc.folia.view.AdaptiveViewDistance;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;

/**
 * The whole admin surface: one root command, six subcommands, no plugin required.
 *
 * <p>Kept deliberately small. Everything here answers a question an operator actually asks while
 * something is wrong — which region is slow, is pinning on, is AntiFreecam costing me anything —
 * rather than duplicating what Paper's own tooling already does well.
 *
 * <p>Every subcommand reads state that is either immutable or atomically published, so none of
 * them need to hop onto a region thread to produce an answer. That matters on Folia: a command
 * that scheduled work onto each region and waited would deadlock the moment one region was the
 * thing you were trying to diagnose.
 */
public final class HavocCommand {

    private HavocCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        HavocConfig.Commands cfg = HavocConfig.get().commands;
        if (!cfg.enabled) {
            return;
        }
        String perm = cfg.permissionPrefix;

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("havoc")
            .requires(src -> src.hasPermission(3) || src.getBukkitSender().hasPermission(perm + ".admin"));

        root.then(Commands.literal("version").executes(HavocCommand::version));
        root.then(Commands.literal("reload").executes(HavocCommand::reload));
        root.then(Commands.literal("tps").executes(HavocCommand::tps));
        root.then(Commands.literal("regions").executes(HavocCommand::regions));
        root.then(Commands.literal("affinity")
            .executes(HavocCommand::affinityStatus)
            .then(Commands.literal("reapply").executes(HavocCommand::affinityReapply)));
        root.then(Commands.literal("freecam")
            .executes(HavocCommand::freecamStatus)
            .then(Commands.argument("action", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    builder.suggest("status");
                    builder.suggest("reset");
                    return builder.buildFuture();
                })
                .executes(HavocCommand::freecamAction)));

        dispatcher.register(root);
        dispatcher.register(Commands.literal("hf").redirect(dispatcher.getRoot().getChild("havoc")));
    }

    private static int version(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Format.info(HavocFolia.brandLine()), false);
        src.sendSuccess(() -> Format.field("Upstream", HavocFolia.upstream()), false);
        src.sendSuccess(() -> Format.field("Scheduler", HavocConfig.get().scheduler.mode
            + (AffinityManager.get().isActive() ? " (pinned)" : "")), false);
        src.sendSuccess(() -> Format.field("AntiFreecam",
            HavocConfig.get().antiFreecam.enabled ? "enabled" : "disabled"), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        try {
            HavocFolia.reloadConfig();
            src.sendSuccess(() -> Format.ok("Reloaded havocfolia.yml."), true);
            src.sendSuccess(() -> Format.field("Note",
                "scheduler.worker-threads and view-distance caps need a restart."), false);
            return 1;
        } catch (RuntimeException ex) {
            src.sendFailure(Format.error("Reload failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static int tps(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        RegionReport report = RegionReport.capture(src.getServer());

        src.sendSuccess(() -> Format.info("Region tick summary"), false);
        src.sendSuccess(() -> Format.field("Regions", Integer.toString(report.regionCount())), false);
        src.sendSuccess(() -> Format.info("  worst mspt ").append(Format.mspt(report.worstMspt()))
            .append(net.kyori.adventure.text.Component.text("   median "))
            .append(Format.mspt(report.medianMspt())), false);
        src.sendSuccess(() -> Format.info("  worst tps  ").append(Format.tps(report.worstTps()))
            .append(net.kyori.adventure.text.Component.text("   players "))
            .append(net.kyori.adventure.text.Component.text(report.playerCount())), false);

        if (report.worstMspt() > 45.0D) {
            src.sendSuccess(() -> Format.warn("A region is over budget — /havoc regions shows which."), false);
        }
        return 1;
    }

    private static int regions(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        RegionReport report = RegionReport.capture(src.getServer());

        src.sendSuccess(() -> Format.info("Busiest regions"), false);
        report.busiest(8).forEach(row -> src.sendSuccess(() ->
            Format.info(String.format("  %-18s ", row.world()))
                .append(Format.mspt(row.mspt()))
                .append(net.kyori.adventure.text.Component.text(
                    String.format("  chunks %-5d players %-3d  @ %d,%d",
                        row.chunkCount(), row.playerCount(), row.centerChunkX(), row.centerChunkZ()))), false));

        if (report.regionCount() == 0) {
            src.sendSuccess(() -> Format.warn("No active regions — is anyone online?"), false);
        }
        return 1;
    }

    private static int affinityStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        AffinityManager affinity = AffinityManager.get();
        HavocConfig.Scheduler cfg = HavocConfig.get().scheduler;

        src.sendSuccess(() -> Format.info("Affinity scheduler"), false);
        src.sendSuccess(() -> Format.field("Mode", cfg.mode), false);
        src.sendSuccess(() -> Format.field("Strategy", cfg.pinStrategy), false);
        src.sendSuccess(() -> Format.field("cpu-set", cfg.cpuSet.isBlank() ? "(auto)" : cfg.cpuSet), false);
        src.sendSuccess(() -> Format.field("Active", Boolean.toString(affinity.isActive())), false);

        if (affinity.lastError() != null) {
            src.sendSuccess(() -> Format.warn(affinity.lastError()), false);
        }
        Map<String, Integer> map = affinity.pinMap();
        if (map.isEmpty()) {
            src.sendSuccess(() -> Format.field("Pins", "none"), false);
        } else {
            map.forEach((thread, cpu) -> src.sendSuccess(() -> Format.field("  " + thread, "cpu " + cpu), false));
        }
        return 1;
    }

    private static int affinityReapply(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        int pinned = AffinityManager.get().apply(HavocFolia.REGION_THREAD_PREFIX);
        if (pinned > 0) {
            src.sendSuccess(() -> Format.ok("Re-pinned " + pinned + " region workers."), true);
            return pinned;
        }
        src.sendFailure(Format.error("Nothing was pinned. " +
            String.valueOf(AffinityManager.get().lastError())));
        return 0;
    }

    private static int freecamStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        HavocConfig.AntiFreecam cfg = HavocConfig.get().antiFreecam;

        src.sendSuccess(() -> Format.info("AntiFreecam"), false);
        src.sendSuccess(() -> Format.field("Enabled", Boolean.toString(cfg.enabled)), false);
        src.sendSuccess(() -> Format.field("Max depth", cfg.maxDepth + " blocks"), false);
        src.sendSuccess(() -> Format.field("Surface Y", Integer.toString(cfg.surfaceY)), false);

        long chunks = 0L;
        long hidden = 0L;
        double micros = 0.0D;
        int levels = 0;
        for (ServerLevel level : src.getServer().getAllLevels()) {
            AntiFreecamController controller = HavocFolia.controllerFor(level);
            if (controller == null) {
                continue;
            }
            chunks += controller.chunksProcessed();
            hidden += controller.blocksHidden();
            micros += controller.averageMicrosPerChunk();
            levels++;
        }
        final long fChunks = chunks;
        final long fHidden = hidden;
        final double fAvg = levels == 0 ? 0.0D : micros / levels;

        src.sendSuccess(() -> Format.field("Chunks processed", Long.toString(fChunks)), false);
        src.sendSuccess(() -> Format.field("Blocks hidden", Long.toString(fHidden)), false);
        src.sendSuccess(() -> Format.field("Avg cost", String.format("%.1f us/chunk", fAvg)), false);
        src.sendSuccess(() -> Format.field("View distance tracked", 
            Integer.toString(AdaptiveViewDistance.trackedPlayers()) + " players"), false);
        return 1;
    }

    private static int freecamAction(CommandContext<CommandSourceStack> ctx) {
        String action = StringArgumentType.getString(ctx, "action");
        CommandSourceStack src = ctx.getSource();

        if ("reset".equalsIgnoreCase(action)) {
            src.getServer().getAllLevels().forEach(level -> {
                AntiFreecamController controller = HavocFolia.controllerFor(level);
                if (controller != null) {
                    controller.resetStats();
                }
            });
            src.sendSuccess(() -> Format.ok("AntiFreecam counters reset."), true);
            return 1;
        }
        return freecamStatus(ctx);
    }
}
