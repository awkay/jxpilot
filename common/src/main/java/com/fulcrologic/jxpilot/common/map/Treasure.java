package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;

/**
 * A treasure (ball home). {@code *} starts with a ball, {@code ^} is empty.
 * Team is the closest base's team in team play.
 */
public record Treasure(int index, int bx, int by, int team, boolean empty) {
    public double x() { return (bx + 0.5) * Const.BLOCK_SZ; }

    public double y() { return (by + 0.5) * Const.BLOCK_SZ; }
}
