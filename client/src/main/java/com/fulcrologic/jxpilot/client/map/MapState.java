package com.fulcrologic.jxpilot.client.map;

/**
 * Changing per-object map state the symbol renderer needs (from the server in a game; constant in
 * the map viewer). Indices are those of the {@code WorldMap} object lists.
 */
public interface MapState {
    /** Fuel in station {@code i}, 0..{@code Const.MAX_STATION_FUEL}. */
    double fuel(int i);

    /** Cannon {@code i} is alive (dead cannons are not drawn). */
    boolean cannonAlive(int i);

    /** Target {@code i} is alive (dead targets are not drawn). */
    boolean targetAlive(int i);

    /** Remaining target strength, {@code Const.TARGET_DAMAGE} when undamaged. */
    double targetDamage(int i);

    /** The viewing player's team, or {@code Const.TEAM_NOT_SET}. Own targets/treasures draw blue. */
    int selfTeam();

    /** Index of the next race checkpoint (drawn filled). */
    int nextCheckpoint();

    /** Client frame counter ({@code loops} in C) driving wormhole and concentrator animation. */
    long loops();
}
