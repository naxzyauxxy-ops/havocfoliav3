package gg.havoc.folia.antifreecam;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coalesces "this block change may have revealed hidden terrain" events.
 *
 * <p>Mining a tunnel fires one block change per tick per player. Re-sending a 5x5x5
 * neighbourhood for each one would be a self-inflicted packet storm, so changes are batched
 * per chunk section and flushed at most once per configured interval, on the region thread
 * that owns the section.
 */
public final class RefreshQueue {

    private static final Map<Level, RefreshQueue> QUEUES = new ConcurrentHashMap<>();

    private final Level level;
    private final Map<Long, AtomicBoolean> pending = new ConcurrentHashMap<>();

    private RefreshQueue(Level level) {
        this.level = level;
    }

    public static RefreshQueue forLevel(Level level) {
        return QUEUES.computeIfAbsent(level, RefreshQueue::new);
    }

    public static void clear(Level level) {
        QUEUES.remove(level);
    }

    /** Marks the sections touched by a radius around {@code pos} as needing a resend. */
    public void enqueue(BlockPos pos, int radius) {
        int minX = (pos.getX() - radius) >> 4;
        int maxX = (pos.getX() + radius) >> 4;
        int minZ = (pos.getZ() - radius) >> 4;
        int maxZ = (pos.getZ() + radius) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                pending.computeIfAbsent(key(x, z), k -> new AtomicBoolean(false)).set(true);
            }
        }
    }

    /**
     * Flushes on the owning region thread. Called from the region tick hook, so every chunk
     * touched here is already owned by the calling thread — no cross-region access.
     */
    public void flushOwnedRegion(java.util.function.LongPredicate ownedByCaller,
                                 java.util.function.LongConsumer resend) {
        if (pending.isEmpty()) {
            return;
        }
        pending.entrySet().removeIf(entry -> {
            long chunkKey = entry.getKey();
            if (!ownedByCaller.test(chunkKey)) {
                return false; // Another region owns it; leave it for that thread.
            }
            if (entry.getValue().compareAndSet(true, false)) {
                resend.accept(chunkKey);
            }
            return true;
        });
    }

    public int pendingCount() {
        return pending.size();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
