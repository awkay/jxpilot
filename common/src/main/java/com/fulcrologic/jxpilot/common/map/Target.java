package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;

/** A destructible target block ({@code !}); team is the closest base's team in team play. */
public record Target(int index, int bx, int by, int team) {
    public double x() { return (bx + 0.5) * Const.BLOCK_SZ; }

    public double y() { return (by + 0.5) * Const.BLOCK_SZ; }
}
