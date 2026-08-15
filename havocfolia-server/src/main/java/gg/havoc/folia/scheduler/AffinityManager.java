package gg.havoc.folia.scheduler;

import gg.havoc.folia.config.HavocConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Optional CPU pinning for Folia region workers.
 *
 * <p><b>Why.</b> Folia's region schedulers are long-lived threads that each own a working set of
 * chunks. When the OS migrates one between cores, its L1/L2 goes cold and the next tick pays for
 * it. On a busy box that shows up as tick-time variance rather than a lower average — the p99 is
 * what players feel. Pinning each worker to a fixed CPU keeps the working set resident.
 *
 * <p><b>How, without JNI.</b> There is no {@code sched_setaffinity} in the JDK. Rather than ship a
 * native library, this uses a property of the Linux JVM: it sets each OS thread's {@code comm} to
 * the Java thread name, so {@code /proc/self/task/<tid>/comm} identifies the thread and
 * {@code taskset -cp <cpu> <tid>} pins it. That has real limits, documented below, and every one
 * of them degrades to "no pinning" rather than to something broken.
 *
 * <p><b>Limits.</b> {@code comm} is truncated to 15 characters, so thread names must be unique
 * within their first 15; the manager checks this and refuses to pin an ambiguous set rather than
 * pinning the wrong thread. Linux only. Requires {@code util-linux} for {@code taskset}. Inside a
 * container the pin is relative to the cgroup's allowed CPU set, so pinning to a CPU the cgroup
 * does not own fails and is logged. Pinning is a tuning knob and is off by default.
 */
public final class AffinityManager {

    private static final Logger LOGGER = Logger.getLogger("HavocFolia");
    private static final Path TASK_DIR = Path.of("/proc/self/task");
    private static final int COMM_LIMIT = 15;

    private static final AffinityManager INSTANCE = new AffinityManager();

    private final Map<String, Integer> pinned = new ConcurrentHashMap<>();
    private volatile boolean active;
    private volatile String lastError;

    private AffinityManager() {
    }

    public static AffinityManager get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active;
    }

    public String lastError() {
        return lastError;
    }

    /** Immutable view of the current thread -> cpu map, for {@code /havoc affinity}. */
    public Map<String, Integer> pinMap() {
        return new TreeMap<>(pinned);
    }

    /**
     * Applies the configured strategy to every live thread whose name starts with
     * {@code threadNamePrefix} (Folia's region workers).
     *
     * @return number of threads successfully pinned
     */
    public int apply(String threadNamePrefix) {
        HavocConfig.Scheduler cfg = HavocConfig.get().scheduler;
        if (!"AFFINITY".equals(cfg.mode)) {
            active = false;
            return 0;
        }
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            fail("CPU pinning is Linux-only; scheduler.mode=AFFINITY ignored on this platform.");
            return 0;
        }
        if (!Files.isDirectory(TASK_DIR)) {
            fail("/proc/self/task is not readable; cannot map Java threads to OS tids.");
            return 0;
        }

        List<Thread> workers = liveThreads(threadNamePrefix);
        if (workers.isEmpty()) {
            fail("No threads matching '" + threadNamePrefix + "' were running yet.");
            return 0;
        }
        if (!namesUniqueWithinCommLimit(workers)) {
            fail("Region worker names collide within the first " + COMM_LIMIT
                + " characters, so they cannot be told apart in /proc. Refusing to pin.");
            return 0;
        }

        Map<String, Integer> tids = readTids();
        List<List<Integer>> topology = readTopology();
        List<Integer> explicit = parseCpuSet(cfg.cpuSet);
        PinStrategy strategy = PinStrategy.parse(cfg.pinStrategy);
        List<Integer> assignment = strategy.assign(topology, explicit, workers.size(), cfg.reservedCores);

        if (assignment.isEmpty()) {
            fail("Pin strategy " + strategy + " produced no usable CPUs (cpu-set='" + cfg.cpuSet + "').");
            return 0;
        }

        int ok = 0;
        Map<String, Integer> applied = new LinkedHashMap<>();
        for (int i = 0; i < workers.size(); i++) {
            Thread worker = workers.get(i);
            String comm = truncate(worker.getName());
            Integer tid = tids.get(comm);
            if (tid == null) {
                continue; // Thread exited between listing and pinning; harmless.
            }
            int cpu = assignment.get(i);
            if (taskset(tid, cpu)) {
                applied.put(worker.getName(), cpu);
                ok++;
            }
        }

        pinned.clear();
        pinned.putAll(applied);
        active = ok > 0;

        if (ok > 0) {
            lastError = null;
            if (cfg.logPinMap) {
                LOGGER.info("[HavocFolia] Pinned " + ok + "/" + workers.size()
                    + " region workers using " + strategy + ".");
                applied.forEach((name, cpu) -> LOGGER.info("[HavocFolia]   " + name + " -> cpu " + cpu));
            }
        } else {
            fail("taskset did not pin any thread; is util-linux installed and does the cgroup allow these CPUs?");
        }
        return ok;
    }

    private void fail(String message) {
        active = false;
        lastError = message;
        LOGGER.warning("[HavocFolia] " + message);
    }

    private static List<Thread> liveThreads(String prefix) {
        List<Thread> matched = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith(prefix)) {
                matched.add(t);
            }
        }
        matched.sort(java.util.Comparator.comparing(Thread::getName));
        return matched;
    }

    private static boolean namesUniqueWithinCommLimit(List<Thread> threads) {
        return threads.stream().map(t -> truncate(t.getName())).distinct().count() == threads.size();
    }

    private static String truncate(String name) {
        return name.length() <= COMM_LIMIT ? name : name.substring(0, COMM_LIMIT);
    }

    /** Maps the truncated comm of every OS thread in this process to its tid. */
    private static Map<String, Integer> readTids() {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (var stream = Files.list(TASK_DIR)) {
            stream.forEach(dir -> {
                try {
                    int tid = Integer.parseInt(dir.getFileName().toString());
                    String comm = Files.readString(dir.resolve("comm"), StandardCharsets.UTF_8).trim();
                    map.putIfAbsent(comm, tid);
                } catch (IOException | RuntimeException ignored) {
                    // Threads come and go while we walk /proc; skipping is correct.
                }
            });
        } catch (IOException ex) {
            LOGGER.warning("[HavocFolia] Could not enumerate /proc/self/task: " + ex.getMessage());
        }
        return map;
    }

    /** Physical core id -> logical CPUs, read from sysfs. Falls back to one CPU per core. */
    private static List<List<Integer>> readTopology() {
        Map<String, List<Integer>> byCore = new LinkedHashMap<>();
        int cpus = Runtime.getRuntime().availableProcessors();
        for (int cpu = 0; cpu < cpus; cpu++) {
            Path idFile = Path.of("/sys/devices/system/cpu/cpu" + cpu + "/topology/core_id");
            Path pkgFile = Path.of("/sys/devices/system/cpu/cpu" + cpu + "/topology/physical_package_id");
            String key;
            try {
                key = Files.readString(pkgFile).trim() + ":" + Files.readString(idFile).trim();
            } catch (IOException ex) {
                key = "cpu" + cpu; // No sysfs topology (container?) — treat every CPU as its own core.
            }
            byCore.computeIfAbsent(key, k -> new ArrayList<>()).add(cpu);
        }
        return new ArrayList<>(byCore.values());
    }

    /** Parses "2-7,10,12-15" into a flat CPU list. */
    static List<Integer> parseCpuSet(String spec) {
        List<Integer> cpus = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return cpus;
        }
        for (String part : spec.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                int dash = token.indexOf('-');
                if (dash > 0) {
                    int lo = Integer.parseInt(token.substring(0, dash).trim());
                    int hi = Integer.parseInt(token.substring(dash + 1).trim());
                    for (int c = Math.min(lo, hi); c <= Math.max(lo, hi); c++) {
                        cpus.add(c);
                    }
                } else {
                    cpus.add(Integer.parseInt(token));
                }
            } catch (NumberFormatException ex) {
                LOGGER.warning("[HavocFolia] Ignoring unparseable cpu-set entry '" + token + "'.");
            }
        }
        return cpus;
    }

    private static boolean taskset(int tid, int cpu) {
        try {
            Process p = new ProcessBuilder("taskset", "-cp", Integer.toString(cpu), Integer.toString(tid))
                .redirectErrorStream(true)
                .start();
            boolean done = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
