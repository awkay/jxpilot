package com.fulcrologic.jxpilot.common;

/** Item types, ordinal equals the C {@code ITEM_*} value in {@code src/common/item.h}. */
public enum Item {
    FUEL, WIDEANGLE, REARSHOT, AFTERBURNER, CLOAK, SENSOR, TRANSPORTER, TANK, MINE, MISSILE, ECM,
    LASER, EMERGENCY_THRUST, TRACTOR_BEAM, AUTOPILOT, EMERGENCY_SHIELD, DEFLECTOR, HYPERJUMP, PHASING,
    MIRROR, ARMOR;

    /** Number of item types ({@code NUM_ITEMS}). */
    public static final int COUNT = 21;

    private static final Item[] VALUES = values();

    /** Item for a wire id, or null. */
    public static Item of(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }

    /** Bit for item masks. */
    public int bit() {
        return 1 << ordinal();
    }
}
