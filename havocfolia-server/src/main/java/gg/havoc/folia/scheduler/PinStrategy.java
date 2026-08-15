package gg.havoc.folia.scheduler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How region worker threads are mapped onto CPUs.
 *
 * <p>The right answer depends on the box. On a desktop-class chip with SMT, pinning two region
 * workers onto the two hyperthreads of one physical core roughly halves their effective
 * throughput, so SPREAD is usually correct. On a dedicated host where you have already isolated
 * cores for the JVM, ISOLATED plus an explicit {@code cpu-set} gives the flattest tick times.
 */
public enum PinStrategy {

    /** One worker per physical core, walking cores in order and skipping SMT siblings. */
    SPREAD,
    /** Fill every logical CPU in order — maximum thread count, more contention per core. */
    COMPACT,
    /** Deliberately pair two workers per physical core; only useful for IO-heavy region loads. */
    SMT_PAIRED,
    /** Use exactly the CPUs listed in {@code cpu-set} and nothing else. */
    ISOLATED;

    public static PinStrategy parse(String raw) {
        for (PinStrategy s : values()) {
            if (s.name().equalsIgnoreCase(raw)) {
                return s;
            }
        }
        return SPREAD;
    }

    /**
     * Produces the CPU assignment for {@code threads} workers.
     *
     * @param topology  physical core -> logical CPUs on that core
     * @param explicit  CPUs parsed from {@code cpu-set}, empty when unset
     * @param reserved  logical CPUs to hold back for GC, Netty and the OS
     * @return one CPU id per worker, in worker order
     */
    public List<Integer> assign(List<List<Integer>> topology, List<Integer> explicit,
                                int threads, int reserved) {
        List<Integer> pool = new ArrayList<>();

        if (this == ISOLATED || !explicit.isEmpty()) {
            pool.addAll(explicit);
        } else {
            switch (this) {
                case SPREAD -> {
                    for (List<Integer> core : topology) {
                        if (!core.isEmpty()) {
                            pool.add(core.get(0));
                        }
                    }
                    // If we need more workers than physical cores, start using SMT siblings.
                    for (List<Integer> core : topology) {
                        for (int i = 1; i < core.size(); i++) {
                            pool.add(core.get(i));
                        }
                    }
                }
                case SMT_PAIRED -> {
                    for (List<Integer> core : topology) {
                        pool.addAll(core);
                    }
                }
                default -> { // COMPACT
                    Set<Integer> all = new LinkedHashSet<>();
                    topology.forEach(all::addAll);
                    pool.addAll(all.stream().sorted().toList());
                }
            }
            // Reserve from the front: CPU 0 usually carries the most interrupt load.
            int drop = Math.min(reserved, Math.max(0, pool.size() - 1));
            pool = new ArrayList<>(pool.subList(drop, pool.size()));
        }

        if (pool.isEmpty()) {
            return List.of();
        }
        List<Integer> assignment = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            assignment.add(pool.get(i % pool.size()));
        }
        return assignment;
    }
}
