package com.fulcrologic.jxpilot.common.net;

/**
 * Control keys sent from client to server as a 64-bit pressed-state mask, mirroring
 * {@code keys_t} in {@code src/common/keys.h} (KEY_LOCK_NEXT = 1 ... KEY_DEFLECTOR = 64).
 * Bit index = {@code xpilotId - 1} = {@link #ordinal()}, so all 64 game keys fit in a {@code long}.
 * <p>
 * {@link #defaultKeys} holds the default X11 keysym names from {@code src/client/default.c}
 * (space separated); clients map them to their own key codes. Client-only actions (talk macros,
 * ID mode, message toggles...) are not part of the wire mask.
 * <p>
 * The server derives press/release edges by comparing consecutive masks (as XPilot does).
 */
public enum ControlKey {
    LOCK_NEXT(1, "Next Right"),
    LOCK_PREV(2, "Prior Left"),
    LOCK_CLOSE(3, "Select Up"),
    CHANGE_HOME(4, "Home h"),
    SHIELD(5, "space Caps_Lock"),
    FIRE_SHOT(6, "Return Linefeed"),
    FIRE_MISSILE(7, "backslash"),
    FIRE_TORPEDO(8, "quoteright"),
    TOGGLE_NUCLEAR(9, "n"),
    FIRE_HEAT(10, "semicolon"),
    DROP_MINE(11, "Tab"),
    DETACH_MINE(12, "bracketright"),
    TURN_LEFT(13, "a"),
    TURN_RIGHT(14, "s"),
    SELF_DESTRUCT(15, "End"),
    LOSE_ITEM(16, "KP_Delete KP_Decimal"),
    PAUSE(17, "Pause"),
    TANK_DETACH(18, "r"),
    TANK_NEXT(19, "e"),
    TANK_PREV(20, "w"),
    TOGGLE_VELOCITY(21, "v"),
    TOGGLE_CLUSTER(22, "c"),
    SWAP_SETTINGS(23, "Escape"),
    REFUEL(24, "f Control_L Control_R"),
    CONNECTOR(25, "Control_L"),
    INCREASE_POWER(26, "KP_Multiply"),
    DECREASE_POWER(27, "KP_Divide"),
    INCREASE_TURNSPEED(28, "KP_Add"),
    DECREASE_TURNSPEED(29, "KP_Subtract"),
    THRUST(30, "Shift_R Shift_L"),
    CLOAK(31, "Delete BackSpace"),
    ECM(32, "bracketleft"),
    DROP_BALL(33, "d"),
    TRANSPORTER(34, "t"),
    TALK(35, "m"),
    FIRE_LASER(36, "slash"),
    LOCK_NEXT_CLOSE(37, "Down"),
    TOGGLE_COMPASS(38, "KP_7"),
    TOGGLE_MINI(39, "x"),
    TOGGLE_SPREAD(40, "z"),
    TOGGLE_POWER(41, "b"),
    TOGGLE_AUTOPILOT(42, "h"),
    TOGGLE_LASER(43, "l"),
    EMERGENCY_THRUST(44, "j"),
    TRACTOR_BEAM(45, "comma"),
    PRESSOR_BEAM(46, "period"),
    CLEAR_MODIFIERS(47, "k"),
    LOAD_MODIFIERS_1(48, "1"),
    LOAD_MODIFIERS_2(49, "2"),
    LOAD_MODIFIERS_3(50, "3"),
    LOAD_MODIFIERS_4(51, "4"),
    SELECT_ITEM(52, "KP_0 KP_Insert"),
    PHASING(53, "p"),
    REPAIR(54, "f"),
    TOGGLE_IMPLOSION(55, "i"),
    REPROGRAM(56, "quoteleft"),
    LOAD_LOCK_1(57, "5"),
    LOAD_LOCK_2(58, "6"),
    LOAD_LOCK_3(59, "7"),
    LOAD_LOCK_4(60, "8"),
    EMERGENCY_SHIELD(61, "g"),
    HYPERJUMP(62, "q"),
    DETONATE_MINES(63, "equal"),
    DEFLECTOR(64, "o");

    private static final ControlKey[] VALUES = values();

    /** The C {@code keys_t} value. */
    public final int xpilotId;
    /** Default X11 keysym names, space separated (may be shared by several keys, as in XPilot). */
    public final String defaultKeys;

    ControlKey(int xpilotId, String defaultKeys) {
        this.xpilotId = xpilotId;
        this.defaultKeys = defaultKeys;
    }

    /** Bit mask for this key. */
    public long mask() {
        return 1L << ordinal();
    }

    /** True if this key is set in {@code keys}. */
    public boolean isDown(long keys) {
        return (keys & (1L << ordinal())) != 0;
    }

    /** Set or clear this key in {@code keys}. */
    public long set(long keys, boolean down) {
        return down ? keys | (1L << ordinal()) : keys & ~(1L << ordinal());
    }

    /** True if the key went from up to down between two masks. */
    public boolean pressed(long prev, long now) {
        long m = 1L << ordinal();
        return (prev & m) == 0 && (now & m) != 0;
    }

    /** True if the key went from down to up between two masks. */
    public boolean released(long prev, long now) {
        long m = 1L << ordinal();
        return (prev & m) != 0 && (now & m) == 0;
    }

    /** Key for a bit index 0..63, or null. */
    public static ControlKey ofBit(int bit) {
        return bit >= 0 && bit < VALUES.length ? VALUES[bit] : null;
    }

    /** Key for a C {@code keys_t} value, or null. */
    public static ControlKey ofXpilotId(int id) {
        return ofBit(id - 1);
    }
}
