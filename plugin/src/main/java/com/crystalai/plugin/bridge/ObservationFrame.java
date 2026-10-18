package com.crystalai.plugin.bridge;

import javax.annotation.Nullable;

/** Where a fight takes place, as far as the observation needs it (a training arena or a duel). */
public interface ObservationFrame {

    /** Horizontal centre {x, z} of the fight site, or null without one (flat arenas). */
    @Nullable double[] siteCenter();

    /** Leaving this horizontal distance from the centre loses; 0 when there is no boundary. */
    double boundaryRadius();

    /** Ticks since the fight started. */
    int episodeTick();
}
