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
package com.fulcrologic.jxpilot.client.net;

/**
 * The C client's network meters' measurements ({@code netclient.c}), protocol independent: the connection feeds
 * frame numbers, keyboard sends and acks and frame sizes; the HUD's packet meters ({@code painthud.c}
 * {@code Paint_meters}) read {@link #size}, {@link #loss}, {@link #drop} and {@link #lag}.
 * <ul>
 *   <li>Loss and drop ({@code Net_measurement}): one slot per frame of the current one-second window of
 *   {@link #rate} frames; a drawn frame marks its slot DRAW, a dropped (duplicate, out of order) one DROP unless
 *   already drawn; when a frame of a later window arrives the finished window's slots still LOSS (never heard
 *   of) and DROP are counted.</li>
 *   <li>Lag ({@code Net_keyboard_track}, {@code Net_lag_measurement}): up to {@value #KEYBOARD_STORE} keyboard
 *   changes with the frame they were sent at; an ack stores the frame it arrived at; the lag is the mean
 *   ack-minus-send frames of the acked ones.</li>
 *   <li>Size ({@code Handle_self}): the frame datagram size, falling by at most 16 bytes per frame.</li>
 * </ul>
 * Fixed arrays; allocation free.
 */
public final class PacketStats {
    /** C {@code PACKET_LOSS}, {@code PACKET_DROP}, {@code PACKET_DRAW}. */
    public static final int LOSS = 0;
    public static final int DROP = 1;
    public static final int DRAW = 2;
    /** C {@code KEYBOARD_STORE}. */
    public static final int KEYBOARD_STORE = 20;
    /** Stats nobody feeds (a connection without measurements): every value stays 0. */
    public static final PacketStats NONE = new PacketStats();
    /** Largest window (frames per second) measured. */
    public static final int MAX_RATE = 256;

    private final byte[] measure = new byte[MAX_RATE];
    private int rate = 14;
    private long packetLoop;
    private int loss;
    private int drop;

    private final long[] change = new long[KEYBOARD_STORE];
    private final long[] update = new long[KEYBOARD_STORE];
    private final long[] ackTime = new long[KEYBOARD_STORE];
    private int lag;

    private int size;

    public PacketStats() {
        reset(rate);
    }

    /**
     * C {@code Net_init_measurement} and {@code Net_init_lag_measurement} for a game of {@code fps} frames per
     * second.
     * <p>
     * BEHAVIOR: loss, drop, lag and size 0; every slot DRAW (nothing counts until a window has been filled); no
     * keyboard change tracked; the window is {@code fps} frames, clamped to 1..{@link #MAX_RATE}.
     */
    public void reset(int fps) {
        rate = Math.max(1, Math.min(MAX_RATE, fps));
        loss = 0;
        drop = 0;
        packetLoop = 0;
        java.util.Arrays.fill(measure, (byte) DRAW);
        lag = 0;
        java.util.Arrays.fill(change, -1);
        java.util.Arrays.fill(update, -1);
        java.util.Arrays.fill(ackTime, -1);
        size = 0;
    }

    /** Frames per second of the loss/drop window and the lag meter's scale (C {@code FPS}). */
    public int rate() { return rate; }

    /**
     * C {@code Net_measurement(loop, status)}.
     * <p>
     * BEHAVIOR: a frame before the current window is ignored. The first frame only starts the window (at
     * {@code loop - loop % rate}). A frame of a later window counts the finished window (LOSS slots into
     * {@link #loss}, DROP slots into {@link #drop}), resets every slot to LOSS and moves the window to the frame's.
     * BEHAVIOR: the frame's slot takes {@code status} unless it is already DRAW.
     */
    public void measure(long loop, int status) {
        long delta = loop - packetLoop;
        if (delta < 0) {
            return;
        }
        if (delta >= rate) {
            if (packetLoop == 0) {
                packetLoop = loop - loop % rate;
                return;
            }
            packetLoop = loop - loop % rate;
            loss = 0;
            drop = 0;
            for (int i = 0; i < rate; i++) {
                if (measure[i] == LOSS) {
                    loss++;
                } else {
                    if (measure[i] == DROP) {
                        drop++;
                    }
                    measure[i] = LOSS;
                }
            }
            delta = loop - packetLoop;
        }
        if (measure[(int) delta] != DRAW) {
            measure[(int) delta] = (byte) status;
        }
    }

    /** Frames of the last finished window never received (C {@code packet_loss}). */
    public int loss() { return loss; }

    /** Frames of the last finished window received but dropped (C {@code packet_drop}). */
    public int drop() { return drop; }

    /**
     * C {@code Net_keyboard_track}: keyboard change {@code keyChange} was sent at frame {@code loops}.
     * <p>
     * BEHAVIOR: a change already tracked is left alone. Otherwise it takes the first free or acked entry, else
     * the entry with the largest send frame (C's "oldest" search, kept as C wrote it), with no ack yet.
     */
    public void keyboardSent(long keyChange, long loops) {
        for (int i = 0; i < KEYBOARD_STORE; i++) {
            if (change[i] == keyChange) {
                return;
            }
        }
        int ind = -1;
        for (int i = 0; i < KEYBOARD_STORE; i++) {
            if (change[i] == -1 || ackTime[i] != -1) {
                ind = i;
                break;
            }
        }
        if (ind == -1) {
            long maxtime = 0;
            for (int i = 0; i < KEYBOARD_STORE; i++) {
                if (update[i] > maxtime) {
                    ind = i;
                    maxtime = update[i];
                }
            }
        }
        if (ind == -1) {
            return;
        }
        change[ind] = keyChange;
        update[ind] = loops;
        ackTime[ind] = -1;
    }

    /**
     * C {@code Net_lag_measurement}: a frame acking keyboard change {@code keyAck} arrived while frame
     * {@code loops} was the newest drawn.
     * <p>
     * BEHAVIOR: the first unacked entry of that change stores {@code loops} as its ack frame; then, when any entry
     * is acked, the lag is the integer mean of (ack frame - send frame) over the acked entries; otherwise the lag
     * is unchanged.
     */
    public void keyboardAck(long keyAck, long loops) {
        for (int i = 0; i < KEYBOARD_STORE; i++) {
            if (change[i] == keyAck && ackTime[i] == -1) {
                ackTime[i] = loops;
                break;
            }
        }
        int num = 0;
        long sum = 0;
        for (int i = 0; i < KEYBOARD_STORE; i++) {
            if (ackTime[i] != -1) {
                num++;
                sum += ackTime[i] - update[i];
            }
        }
        if (num != 0) {
            lag = (int) (sum / num);
        }
    }

    /** Mean keyboard round trip in frames (C {@code packet_lag}). */
    public int lag() { return lag; }

    /**
     * C {@code Handle_self}'s {@code packet_size}.
     * <p>
     * BEHAVIOR: a size more than 16 bytes below the current one lowers it by 16; any other size replaces it.
     */
    public void frameSize(int bytes) {
        if (bytes + 16 < size) {
            size -= 16;
        } else {
            size = bytes;
        }
    }

    /** Smoothed frame datagram size in bytes (C {@code packet_size}). */
    public int size() { return size; }
}
