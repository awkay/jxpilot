package com.fulcrologic.jxpilot.client.map;

import com.fulcrologic.jxpilot.common.Const;

/** Map state with everything alive, full fuel, no team and a settable frame counter (map viewer). */
public final class FullMapState implements MapState {
    private long loops;

    @Override public double fuel(int i) { return Const.MAX_STATION_FUEL; }

    @Override public boolean cannonAlive(int i) { return true; }

    @Override public boolean targetAlive(int i) { return true; }

    @Override public double targetDamage(int i) { return Const.TARGET_DAMAGE; }

    @Override public int selfTeam() { return Const.TEAM_NOT_SET; }

    @Override public int nextCheckpoint() { return 0; }

    @Override public long loops() { return loops; }

    /** Set the frame counter. */
    public void setLoops(long loops) { this.loops = loops; }
}
