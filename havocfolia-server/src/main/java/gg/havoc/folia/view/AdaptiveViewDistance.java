package gg.havoc.folia.view;

import gg.havoc.folia.config.HavocConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a high view distance usable by trading it away only where it actually hurts.
 *
 * <p>View distance 32 is affordable; simulation distance 32 is not. Sending chunks is bandwidth
 * and serialisation work that scales with area, whereas <em>ticking</em> them scales with area
 * <em>and</em> entity count and lands squarely on the owning region thread. So the intended
 * configuration is a large {@code view-distance} against a modest {@code simulation-distance},
 * and this class defends that: when a region's tick time climbs, players in that region lose
 * view distance until it recovers.
 *
 * <p>Hysteresis matters more than the thresholds. Adjusting on every tick makes clients unload
 * and reload the same ring of chunks forever, which costs more than the tick time saved — hence
 * the separate shrink/grow thresholds, a minimum dwell between changes, and a one-step-at-a-time
 * rule.
 */
public final class AdaptiveViewDistance {

    private static final Map<UUID, State> PLAYERS = new ConcurrentHashMap<>();

    private AdaptiveViewDistance() {
    }

    private static final class State {
        volatile int current;
        volatile long lastChangeTick;

        State(int current, long tick) {
            this.current = current;
            this.lastChangeTick = tick;
        }
    }

    /**
     * Decides the view distance a player should have right now.
     *
     * <p>Called from the owning region's tick, so {@code regionMspt} is that region's own tick
     * time rather than a global average — which is the entire point under Folia. A laggy mob farm
     * in one region must not shrink the view distance of players on the other side of the world.
     *
     * @param player     player id
     * @param regionMspt most recent mean tick time of the region that owns this player
     * @param currentTick monotonically increasing tick counter
     * @return the view distance to apply, or -1 when nothing should change
     */
    public static int evaluate(UUID player, double regionMspt, long currentTick) {
        HavocConfig.ViewDistance cfg = HavocConfig.get().viewDistance;
        if (!cfg.adaptive || cfg.minViewDistance >= cfg.maxViewDistance) {
            return -1;
        }

        State state = PLAYERS.computeIfAbsent(player, id -> new State(cfg.maxViewDistance, currentTick));
        if (currentTick - state.lastChangeTick < cfg.adjustIntervalTicks) {
            return -1;
        }

        int desired = state.current;
        if (regionMspt > cfg.shrinkAboveMspt && state.current > cfg.minViewDistance) {
            desired = state.current - 1;
        } else if (regionMspt < cfg.growBelowMspt && state.current < cfg.maxViewDistance) {
            desired = state.current + 1;
        }

        if (desired == state.current) {
            return -1;
        }
        state.current = desired;
        state.lastChangeTick = currentTick;
        return desired;
    }

    public static int currentFor(UUID player) {
        State state = PLAYERS.get(player);
        return state == null ? HavocConfig.get().viewDistance.maxViewDistance : state.current;
    }

    public static void forget(UUID player) {
        PLAYERS.remove(player);
    }

    public static void reset() {
        PLAYERS.clear();
    }

    public static int trackedPlayers() {
        return PLAYERS.size();
    }
}
