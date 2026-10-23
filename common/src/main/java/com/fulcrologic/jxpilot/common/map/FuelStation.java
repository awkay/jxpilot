package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;

/**
 * A fuel station block ({@code #}). Solid like a wall.
 *
 * @param team closest team in team play (C {@code Find_closest_team}), else {@link Const#TEAM_NOT_SET}
 */
public record FuelStation(int index, int bx, int by, int team) {
    public double x() { return (bx + 0.5) * Const.BLOCK_SZ; }

    public double y() { return (by + 0.5) * Const.BLOCK_SZ; }
}
