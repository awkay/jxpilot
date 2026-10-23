package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;

/** An item ({@code %}) or asteroid ({@code &}) concentrator. */
public record Concentrator(int index, int bx, int by) {
    public double x() { return (bx + 0.5) * Const.BLOCK_SZ; }

    public double y() { return (by + 0.5) * Const.BLOCK_SZ; }
}
