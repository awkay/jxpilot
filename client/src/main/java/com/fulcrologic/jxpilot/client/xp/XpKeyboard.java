/*
 * JXPilot, a Java reimplementation of XPilot, a multiplayer gravity war game.
 * Copyright (C) 2026 Tony Kay and the JXPilot contributors.
 *
 * Derived from XPilot 4.5.5, Copyright (C) 1991-2002 by Bjørn Stabell,
 * Ken Ronny Schouten, Bert Gijsbers and Dick Balaska. JXPilot is an
 * independent derivative port: the original XPilot authors were not involved
 * in it and do not endorse it. Ported to Java and modified starting in 2026;
 * see the git history for the changes.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <https://www.gnu.org/licenses/>.
 */
package com.fulcrologic.jxpilot.client.xp;

import java.nio.ByteBuffer;

/**
 * The C client's keyboard state protocol ({@code Send_keyboard}, {@code Net_key_change},
 * {@code Receive_start} and {@code Net_input} in {@code client/netclient.c}): the client numbers each
 * change of its key set ({@code last_keyboard_change}) and sends {@code PKT_KEYBOARD %ld change} plus
 * the {@value XpProto#KEYBOARD_SIZE}-byte key bit vector; the server echoes the newest change it has
 * in every {@code PKT_START}; until then the client resends at most once per received frame.
 * <p>
 * Keys are a JXPilot {@link com.fulcrologic.jxpilot.common.net.ControlKey} mask (bit = keys_t - 1);
 * the C vector has bit {@code keys_t} set ({@code BITV_SET}). Allocation free.
 */
public final class XpKeyboard {
    private long keys;
    private int change;
    private int ack;
    private int lastUpdate;

    /** BEHAVIOR: no keys, change and ack 0 (new connection). */
    public void reset() {
        keys = 0;
        change = 0;
        ack = 0;
        lastUpdate = 0;
    }

    /** Current ControlKey mask. */
    public long keys() { return keys; }

    /** C {@code last_keyboard_change}. */
    public int change() { return change; }

    /** C {@code last_keyboard_ack}. */
    public int ack() { return ack; }

    /** Frame number of the last send (C {@code last_keyboard_update}). */
    public int lastUpdate() { return lastUpdate; }

    /**
     * BEHAVIOR: a different mask becomes a new change (returns true); the same mask changes nothing.
     */
    public boolean setKeys(long mask) {
        if (mask == keys) {
            return false;
        }
        keys = mask;
        change++;
        return true;
    }

    /** C {@code Net_key_change}: BEHAVIOR: a new change number for the same keys (forces a send). */
    public void forceChange() {
        change++;
    }

    /**
     * The keyboard ack from PKT_START.
     * <p>
     * BEHAVIOR: an ack newer than the last one and not beyond the last change is taken; one beyond the
     * last change returns false (C "Premature keyboard ack", the frame is dropped); older acks are
     * ignored.
     */
    public boolean onAck(int keyAck) {
        if (keyAck - ack > 0) {
            if (keyAck - change > 0) {
                return false;
            }
            ack = keyAck;
        }
        return true;
    }

    /** BEHAVIOR: true while the server has not acked the last change. */
    public boolean unacked() {
        return ack != change;
    }

    /**
     * C {@code Net_input}: BEHAVIOR: a resend is due when the last change is unacked and it was not
     * already sent during frame {@code lastLoops}.
     */
    public boolean resendDue(int lastLoops) {
        return unacked() && lastUpdate - lastLoops < 0;
    }

    /**
     * C {@code Send_keyboard}: {@code PKT_KEYBOARD %ld change} and the bit vector (14 bytes).
     * <p>
     * BEHAVIOR: remembers {@code lastLoops} as the send frame.
     */
    public void write(ByteBuffer out, int lastLoops) {
        XpWriter.u8(out, XpProto.PKT_KEYBOARD);
        XpWriter.s32(out, change);
        for (int i = 0; i < XpProto.KEYBOARD_SIZE; i++) {
            out.put((byte) vectorByte(keys, i));
        }
        lastUpdate = lastLoops;
    }

    /** Bytes {@link #write} needs. */
    public static final int SIZE = 1 + 4 + XpProto.KEYBOARD_SIZE;

    /**
     * Byte {@code i} of the C key vector for a ControlKey mask.
     * <p>
     * BEHAVIOR: ControlKey bit b (keys_t b + 1) is C vector bit b + 1: byte (b + 1) / 8, bit (b + 1) % 8.
     */
    public static int vectorByte(long mask, int i) {
        // C bit n = mask bit n - 1, so the vector is the mask shifted left by one, 72 bits wide.
        if (i == 0) {
            return (int) (mask << 1) & 0xFF;
        }
        return (int) (mask >>> (8 * i - 1)) & 0xFF;
    }
}
