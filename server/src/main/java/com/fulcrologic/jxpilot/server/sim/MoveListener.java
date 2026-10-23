package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.server.world.Player;

/** Effects of wall movement that belong to the game rules (crashes, targets, bounce sparks). */
public interface MoveListener {
    /** A pool object crashed (C {@code Object_crash}); {@code ms.crash} says into what. */
    void objectCrashed(int h, MoveState ms);

    /** A player crashed or entered a wormhole (C {@code Player_crash}); {@code pt} is the hull point. */
    void playerCrashed(Player p, MoveState ms, int pt, boolean turning);

    /**
     * A player survived a wall bounce that cost fuel (Move_player): make the spark burst and, when the
     * bounce was off a target, damage it.
     *
     * @param wallDir RES direction of the wall normal
     * @param cost    C {@code cost} (unscaled fuel units before the drain multiplier)
     * @param target  target index or -1
     */
    void playerBounced(Player p, int wallDir, long cost, int target);
}
