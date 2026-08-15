package gg.havoc.folia.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.yaml.snakeyaml.Yaml;

/**
 * Central configuration for every HavocFolia feature.
 *
 * <p>Design notes for Folia: config is read from <em>every region thread</em>, on hot paths
 * (chunk packet assembly runs once per chunk per player). So the config is stored as an
 * immutable snapshot behind a single {@link AtomicReference}. Readers do one volatile read
 * and then touch only final fields — no locks, no map lookups, no megamorphic call sites.
 * Reload swaps the whole snapshot atomically; in-flight ticks finish on the old snapshot,
 * which is fine because every field is independently valid.
 */
public final class HavocConfig {

    private static final Logger LOGGER = Logger.getLogger("HavocFolia");
    private static final AtomicReference<HavocConfig> CURRENT = new AtomicReference<>(defaults());

    public static HavocConfig get() {
        return CURRENT.get();
    }

    // ------------------------------------------------------------------ sections

    public final AntiFreecam antiFreecam;
    public final Scheduler scheduler;
    public final ViewDistance viewDistance;
    public final Optimizations optimizations;
    public final Commands commands;

    private HavocConfig(AntiFreecam af, Scheduler sched, ViewDistance vd, Optimizations opt, Commands cmd) {
        this.antiFreecam = af;
        this.scheduler = sched;
        this.viewDistance = vd;
        this.optimizations = opt;
        this.commands = cmd;
    }

    /** AntiFreecam: hide block data a legitimate client could not possibly need. */
    public static final class AntiFreecam {
        public final boolean enabled;
        /** How many blocks past the nearest air pocket we still send real data for. */
        public final int maxDepth;
        /** Never obfuscate at or above this Y — cheap early-out for surface chunks. */
        public final int surfaceY;
        /** Skip work entirely for chunks further than this (in chunks) from the viewer. */
        public final int maxChunkDistance;
        /** Bypass permission — ops/staff see the real world. */
        public final String bypassPermission;
        /** Re-send a small neighbourhood when a block change could reveal a cavity. */
        public final boolean updateOnBlockChange;
        public final int updateRadius;
        /** Per-dimension replacement block (namespaced id). */
        public final Map<String, String> presetBlocks;
        /** Block ids that are never obfuscated (e.g. barriers used by builds). */
        public final List<String> ignoredBlocks;

        AntiFreecam(boolean enabled, int maxDepth, int surfaceY, int maxChunkDistance, String bypassPermission,
                    boolean updateOnBlockChange, int updateRadius,
                    Map<String, String> presetBlocks, List<String> ignoredBlocks) {
            this.enabled = enabled;
            this.maxDepth = clamp(maxDepth, 0, 32);
            this.surfaceY = clamp(surfaceY, -64, 320);
            this.maxChunkDistance = clamp(maxChunkDistance, 1, 64);
            this.bypassPermission = bypassPermission;
            this.updateOnBlockChange = updateOnBlockChange;
            this.updateRadius = clamp(updateRadius, 0, 8);
            this.presetBlocks = Map.copyOf(presetBlocks);
            this.ignoredBlocks = List.copyOf(ignoredBlocks);
        }
    }

    /** Region scheduler tuning, including the optional AFFINITY mode. */
    public static final class Scheduler {
        /** PARALLEL (upstream default) or AFFINITY (pin region workers to cores). */
        public final String mode;
        /** 0 = auto (cores - reservedCores). */
        public final int workerThreads;
        /** Cores held back for GC, Netty and the OS. */
        public final int reservedCores;
        /** SPREAD, COMPACT, SMT_PAIRED or ISOLATED. */
        public final String pinStrategy;
        /** Explicit CPU list, e.g. "2-7,10-15". Overrides pinStrategy when non-empty. */
        public final String cpuSet;
        /** Raise region worker thread priority (best effort; needs privileges on Linux). */
        public final boolean elevatePriority;
        /** Log the resulting thread -> cpu map at startup. */
        public final boolean logPinMap;

        Scheduler(String mode, int workerThreads, int reservedCores, String pinStrategy,
                  String cpuSet, boolean elevatePriority, boolean logPinMap) {
            this.mode = mode.toUpperCase(Locale.ROOT);
            this.workerThreads = Math.max(0, workerThreads);
            this.reservedCores = clamp(reservedCores, 0, 8);
            this.pinStrategy = pinStrategy.toUpperCase(Locale.ROOT);
            this.cpuSet = cpuSet;
            this.elevatePriority = elevatePriority;
            this.logPinMap = logPinMap;
        }
    }

    /** Large view distance support with per-region back-pressure. */
    public static final class ViewDistance {
        public final int maxViewDistance;
        public final int minViewDistance;
        public final boolean adaptive;
        /** Region MSPT above which we start shrinking view distance for its players. */
        public final double shrinkAboveMspt;
        /** Region MSPT below which we grow back. */
        public final double growBelowMspt;
        /** Ticks between adjustments — must be slow or players see chunk churn. */
        public final int adjustIntervalTicks;
        /** Chunks sent per player per tick while catching up. */
        public final int maxChunkSendRate;

        ViewDistance(int max, int min, boolean adaptive, double shrinkAbove, double growBelow,
                     int interval, int sendRate) {
            this.maxViewDistance = clamp(max, 2, 32);
            this.minViewDistance = clamp(min, 2, 32);
            this.adaptive = adaptive;
            this.shrinkAboveMspt = shrinkAbove;
            this.growBelowMspt = growBelow;
            this.adjustIntervalTicks = Math.max(20, interval);
            this.maxChunkSendRate = clamp(sendRate, 1, 200);
        }
    }

    /** Toggles for the denser optimisations. Each one is a documented trade-off. */
    public static final class Optimizations {
        public final boolean fastChunkSerialization;
        public final boolean cacheBlockStatePalettes;
        public final boolean skipEmptySectionPackets;
        public final boolean lazyEntityTracker;
        public final boolean throttleFailedSpawnAttempts;
        public final boolean deferHopperSearches;
        public final int hopperCooldownTicks;

        Optimizations(boolean fastChunkSerialization, boolean cacheBlockStatePalettes,
                      boolean skipEmptySectionPackets, boolean lazyEntityTracker,
                      boolean throttleFailedSpawnAttempts, boolean deferHopperSearches,
                      int hopperCooldownTicks) {
            this.fastChunkSerialization = fastChunkSerialization;
            this.cacheBlockStatePalettes = cacheBlockStatePalettes;
            this.skipEmptySectionPackets = skipEmptySectionPackets;
            this.lazyEntityTracker = lazyEntityTracker;
            this.throttleFailedSpawnAttempts = throttleFailedSpawnAttempts;
            this.deferHopperSearches = deferHopperSearches;
            this.hopperCooldownTicks = clamp(hopperCooldownTicks, 0, 40);
        }
    }

    public static final class Commands {
        public final boolean enabled;
        public final String permissionPrefix;

        Commands(boolean enabled, String permissionPrefix) {
            this.enabled = enabled;
            this.permissionPrefix = permissionPrefix;
        }
    }

    // ------------------------------------------------------------------ loading

    public static HavocConfig defaults() {
        return new HavocConfig(
            new AntiFreecam(true, 6, 63, 12, "havocfolia.antifreecam.bypass", true, 2,
                Map.of("minecraft:overworld", "minecraft:stone",
                       "minecraft:the_nether", "minecraft:netherrack",
                       "minecraft:the_end", "minecraft:end_stone"),
                List.of("minecraft:barrier", "minecraft:bedrock")),
            new Scheduler("PARALLEL", 0, 2, "SPREAD", "", false, true),
            new ViewDistance(32, 6, true, 45.0D, 25.0D, 100, 12),
            new Optimizations(true, true, true, true, true, true, 8),
            new Commands(true, "havocfolia.command")
        );
    }

    /**
     * Loads {@code havocfolia.yml}, writing the bundled default file if absent.
     * Any malformed value falls back to its default rather than failing the boot —
     * a server that refuses to start because of one bad key is worse than one that
     * starts with a warning.
     */
    @SuppressWarnings("unchecked")
    public static HavocConfig load(Path file) {
        HavocConfig defaults = defaults();
        Map<String, Object> root = Map.of();
        try {
            if (Files.notExists(file)) {
                writeDefault(file);
                LOGGER.info("[HavocFolia] Wrote default configuration to " + file);
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Object parsed = new Yaml().load(reader);
                if (parsed instanceof Map<?, ?> map) {
                    root = (Map<String, Object>) map;
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.warning("[HavocFolia] Could not read havocfolia.yml (" + ex.getMessage()
                + ") — continuing with built-in defaults.");
            CURRENT.set(defaults);
            return defaults;
        }

        Map<String, Object> af = section(root, "anti-freecam");
        Map<String, Object> sc = section(root, "scheduler");
        Map<String, Object> vd = section(root, "view-distance");
        Map<String, Object> op = section(root, "optimizations");
        Map<String, Object> cm = section(root, "commands");

        HavocConfig loaded = new HavocConfig(
            new AntiFreecam(
                bool(af, "enabled", defaults.antiFreecam.enabled),
                integer(af, "max-depth", defaults.antiFreecam.maxDepth),
                integer(af, "surface-y", defaults.antiFreecam.surfaceY),
                integer(af, "max-chunk-distance", defaults.antiFreecam.maxChunkDistance),
                string(af, "bypass-permission", defaults.antiFreecam.bypassPermission),
                bool(af, "update-on-block-change", defaults.antiFreecam.updateOnBlockChange),
                integer(af, "update-radius", defaults.antiFreecam.updateRadius),
                stringMap(af, "preset-blocks", defaults.antiFreecam.presetBlocks),
                stringList(af, "ignored-blocks", defaults.antiFreecam.ignoredBlocks)),
            new Scheduler(
                string(sc, "mode", defaults.scheduler.mode),
                integer(sc, "worker-threads", defaults.scheduler.workerThreads),
                integer(sc, "reserved-cores", defaults.scheduler.reservedCores),
                string(sc, "pin-strategy", defaults.scheduler.pinStrategy),
                string(sc, "cpu-set", defaults.scheduler.cpuSet),
                bool(sc, "elevate-priority", defaults.scheduler.elevatePriority),
                bool(sc, "log-pin-map", defaults.scheduler.logPinMap)),
            new ViewDistance(
                integer(vd, "max-view-distance", defaults.viewDistance.maxViewDistance),
                integer(vd, "min-view-distance", defaults.viewDistance.minViewDistance),
                bool(vd, "adaptive", defaults.viewDistance.adaptive),
                dbl(vd, "shrink-above-mspt", defaults.viewDistance.shrinkAboveMspt),
                dbl(vd, "grow-below-mspt", defaults.viewDistance.growBelowMspt),
                integer(vd, "adjust-interval-ticks", defaults.viewDistance.adjustIntervalTicks),
                integer(vd, "max-chunk-send-rate", defaults.viewDistance.maxChunkSendRate)),
            new Optimizations(
                bool(op, "fast-chunk-serialization", defaults.optimizations.fastChunkSerialization),
                bool(op, "cache-block-state-palettes", defaults.optimizations.cacheBlockStatePalettes),
                bool(op, "skip-empty-section-packets", defaults.optimizations.skipEmptySectionPackets),
                bool(op, "lazy-entity-tracker", defaults.optimizations.lazyEntityTracker),
                bool(op, "throttle-failed-spawn-attempts", defaults.optimizations.throttleFailedSpawnAttempts),
                bool(op, "defer-hopper-searches", defaults.optimizations.deferHopperSearches),
                integer(op, "hopper-cooldown-ticks", defaults.optimizations.hopperCooldownTicks)),
            new Commands(
                bool(cm, "enabled", defaults.commands.enabled),
                string(cm, "permission-prefix", defaults.commands.permissionPrefix))
        );

        if (loaded.viewDistance.minViewDistance > loaded.viewDistance.maxViewDistance) {
            LOGGER.warning("[HavocFolia] min-view-distance > max-view-distance; adaptive scaling disabled.");
        }
        CURRENT.set(loaded);
        return loaded;
    }

    private static void writeDefault(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (var in = HavocConfig.class.getResourceAsStream("/havocfolia.yml")) {
            if (in != null) {
                Files.copy(in, file);
                return;
            }
        }
        Files.writeString(file, "# HavocFolia — bundled default resource missing; see docs/TUNING.md\n",
            StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Object v = root.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean b ? b : def;
    }

    private static int integer(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : def;
    }

    private static double dbl(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private static String string(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v instanceof String s ? s : def;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> m, String k, List<String> def) {
        Object v = m.get(k);
        return v instanceof List<?> l ? (List<String>) l.stream().map(String::valueOf).toList() : def;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Map<String, Object> m, String k, Map<String, String> def) {
        Object v = m.get(k);
        if (!(v instanceof Map<?, ?> raw)) return def;
        return ((Map<Object, Object>) raw).entrySet().stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue())));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
