package com.david.gps3dar;

import java.util.HashMap;
import java.util.Map;

/** Per-maneuver phases, acknowledged only after the voice engine accepts the utterance. */
public final class NavigationAnnouncements {
    private final Map<String, Integer> phases = new HashMap<>();
    public void reset() { phases.clear(); }
    public int pending(String id, double distance, double speed) {
        if (!Double.isFinite(distance) || distance < 0) return 0;
        speed = Math.max(0, speed);
        int phase = distance <= Math.max(18, Math.min(100, speed * 3)) ? 3
            : distance <= Math.max(85, Math.min(500, speed * 12)) ? 2
            : distance <= Math.max(400, Math.min(1500, speed * 35)) ? 1 : 0;
        return phase > phases.getOrDefault(id, 0) ? phase : 0;
    }
    public void accepted(String id, int phase) { phases.put(id, phase); }
    public void failed(String id, int phase) {
        if (phases.getOrDefault(id, 0) == phase) phases.put(id, phase - 1);
    }
}
