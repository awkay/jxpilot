package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;

/** A race checkpoint; {@code number} is the letter index (A = 0). Only present in race mode. */
public record Checkpoint(int number, int bx, int by) {
    public char letter() { return (char) ('A' + number); }

    public double x() { return (bx + 0.5) * Const.BLOCK_SZ; }

    public double y() { return (by + 0.5) * Const.BLOCK_SZ; }
}
