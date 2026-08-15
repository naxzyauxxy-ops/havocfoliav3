package gg.havoc.folia;

import gg.havoc.folia.antifreecam.AntiFreecamController;
import gg.havoc.folia.antifreecam.PresetBlocks;
import gg.havoc.folia.antifreecam.RefreshQueue;
import gg.havoc.folia.config.HavocConfig;
import gg.havoc.folia.scheduler.AffinityManager;
import gg.havoc.folia.view.AdaptiveViewDistance;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Fork entry point: branding, lifecycle and the registry of per-world controllers.
 *
 * <p>Everything the patches call lives here, so the surgical diffs into upstream files stay
 * one-liners. When upstream moves a method, only the hook has to be re-anchored — the logic it
 * calls does not change.
 */
public final class HavocFolia {

    private static final Logger LOGGER = Logger.getLogger("HavocFolia");

    /** Folia names its region workers with this prefix; used to find them for pinning. */
    public static final String REGION_THREAD_PREFIX = "Region Scheduler Thread";

    private static final Map<Level, AntiFreecamController> CONTROLLERS = new ConcurrentHashMap<>();
    private static volatile Path configPath = Path.of("havocfolia.yml");

    private HavocFolia() {
    }

    // ------------------------------------------------------------------ branding

    public static String name() {
        return manifest("Brand-Name", "HavocFolia");
    }

    public static String version() {
        return manifest("Implementation-Version", "dev");
    }

    public static String commit() {
        return manifest("Git-Commit", "unknown");
    }

    public static String upstream() {
        return "CanvasMC / Folia";
    }

    public static String brandLine() {
        return name() + " " + version() + " (" + commit() + ")";
    }

    private static String manifest(String key, String fallback) {
        try {
            Package pkg = HavocFolia.class.getPackage();
            String value = switch (key) {
                case "Brand-Name" -> pkg.getImplementationTitle();
                case "Implementation-Version" -> pkg.getImplementationVersion();
                default -> null;
            };
            return value == null || value.isBlank() ? fallback : value;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called once, as early as possible, before any world loads. */
    public static void bootstrap(Path serverDirectory) {
        configPath = serverDirectory.resolve("havocfolia.yml");
        HavocConfig config = HavocConfig.load(configPath);
        PresetBlocks.rebuildOcclusionTable();

        LOGGER.info("[HavocFolia] " + brandLine() + " on " + upstream());
        LOGGER.info("[HavocFolia] AntiFreecam "
            + (config.antiFreecam.enabled
                ? "enabled (depth " + config.antiFreecam.maxDepth + ", surface Y " + config.antiFreecam.surfaceY + ")"
                : "disabled"));

        if (config.antiFreecam.enabled && isPaperAntiXrayEnabled()) {
            LOGGER.warning("[HavocFolia] Paper anti-xray is also enabled. AntiFreecam already hides"
                + " everything anti-xray would obfuscate, so you are paying for two passes."
                + " Set anti-xray enabled: false in paper-world-defaults.yml.");
        }
    }

    /** Called once the region schedulers exist, so their threads can be found and pinned. */
    public static void onSchedulerStart() {
        HavocConfig.Scheduler cfg = HavocConfig.get().scheduler;
        if ("AFFINITY".equals(cfg.mode)) {
            AffinityManager.get().apply(REGION_THREAD_PREFIX);
        } else {
            LOGGER.info("[HavocFolia] Region scheduler running in " + cfg.mode + " mode.");
        }
    }

    public static void reloadConfig() {
        HavocConfig.load(configPath);
        PresetBlocks.rebuildOcclusionTable();
        AdaptiveViewDistance.reset();
        LOGGER.info("[HavocFolia] Configuration reloaded.");
    }

    // ------------------------------------------------------------------ per-world controllers

    /**
     * Builds the block controller for a world. The patch in {@code ServerLevel} calls this
     * instead of Paper's anti-xray selection.
     */
    public static AntiFreecamController createController(Level level) {
        AntiFreecamController controller = new AntiFreecamController(level);
        CONTROLLERS.put(level, controller);
        return controller;
    }

    public static AntiFreecamController controllerFor(ServerLevel level) {
        return CONTROLLERS.get(level);
    }

    public static void forgetLevel(Level level) {
        CONTROLLERS.remove(level);
        RefreshQueue.clear(level);
    }

    private static boolean isPaperAntiXrayEnabled() {
        try {
            Class<?> config = Class.forName("io.papermc.paper.configuration.WorldConfiguration");
            return config != null && Boolean.getBoolean("paper.antiXray");
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
