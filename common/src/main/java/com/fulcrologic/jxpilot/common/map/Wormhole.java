package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;

/** A wormhole: {@code @} normal (in and out), {@code (} in only, {@code )} out only. */
public record Wormhole(int index, int bx, int by, Type type) {
    /** Wormhole kind ({@code wormType} in C). */
    public enum Type { NORMAL, IN, OUT }

    public double x() { return (bx + 0.5) * Const.BLOCK_SZ; }

    public double y() { return (by + 0.5) * Const.BLOCK_SZ; }
}
