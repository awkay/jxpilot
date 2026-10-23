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
package com.fulcrologic.jxpilot.common.net;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * One direction-pair of the small reliable, ordered message channel (one instance per peer on
 * each side). Allocation free in steady state.
 * <p>
 * Sending: {@link #begin(int)} returns a buffer with the message type already written; put the payload
 * and call {@link #end()}. Messages go into the in-flight window; when it is full they wait in an
 * overflow queue (pooled buffers) and move into the window as acks free slots, so callers never see
 * "window full". {@link #write(ByteBuffer, long)} packs unacknowledged messages that are new or due for
 * resend into the body of a {@link PacketType#RELIABLE} datagram. Feed the peer's cumulative ack (from
 * every {@link PacketHeader}) to {@link #onAck(int, long)}.
 * <p>
 * The resend timeout adapts to the round-trip time as in RFC 6298 (smoothed RTT + 4 * RTT variance,
 * Karn's rule: only messages sent once are sampled), clamped to [minRto, maxRto].
 * <p>
 * Receiving: {@link #read(ByteBuffer, ReliableHandler)} parses a RELIABLE body, buffers out-of-order
 * messages within the window and delivers in order. Put {@link #ack()} in every outgoing header.
 * <p>
 * RELIABLE body: {@code u8 count, count * (u16 seq, u16 len, len bytes)} where the first byte of
 * each message is its {@link ReliableType}. Sequence numbers are 16-bit and wrap.
 */
public final class ReliableChannel {
    private final int window;
    private final int mask;
    private final int maxMessage;
    private final long initialRto;
    private final long minRto;
    private final long maxRto;
    private long rto;
    private long srtt = -1;
    private long rttvar;

    private final ByteBuffer[] tx;
    private final long[] txSent;
    private final int[] txSends;
    private int txBase;
    private int txNext;
    private boolean building;
    private ByteBuffer overflowBuilding;
    private final ArrayDeque<ByteBuffer> overflow = new ArrayDeque<>();
    private final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>();

    private final ByteBuffer[] rx;
    private final boolean[] rxHave;
    private int rxNext;
    private boolean ackPending;

    /**
     * @param window       max messages in flight (power of two, at most 16384); memory is
     *                     2 * window * maxMessage bytes plus any overflow
     * @param maxMessage   max bytes per message including the type byte
     * @param initialRtoMs resend timeout before any RTT sample
     * @param minRtoMs     lower bound of the adaptive resend timeout
     * @param maxRtoMs     upper bound of the adaptive resend timeout
     */
    public ReliableChannel(int window, int maxMessage, long initialRtoMs, long minRtoMs, long maxRtoMs) {
        if (Integer.bitCount(window) != 1 || window > 16384) {
            throw new IllegalArgumentException("window must be a power of two <= 16384");
        }
        this.window = window;
        this.mask = window - 1;
        this.maxMessage = maxMessage;
        this.initialRto = initialRtoMs * 1_000_000L;
        this.minRto = minRtoMs * 1_000_000L;
        this.maxRto = maxRtoMs * 1_000_000L;
        this.rto = initialRto;
        tx = new ByteBuffer[window];
        rx = new ByteBuffer[window];
        for (int i = 0; i < window; i++) {
            tx[i] = ByteBuffer.allocate(maxMessage);
            rx[i] = ByteBuffer.allocate(maxMessage);
        }
        txSent = new long[window];
        txSends = new int[window];
        rxHave = new boolean[window];
    }

    /** Channel with a fixed resend timeout (min = max = initial); handy for tests. */
    public ReliableChannel(int window, int maxMessage, long resendMillis) {
        this(window, maxMessage, resendMillis, resendMillis, resendMillis);
    }

    /** Channel with {@link Protocol} defaults (adaptive resend timeout). */
    public ReliableChannel() {
        this(Protocol.RELIABLE_WINDOW, Protocol.MAX_RELIABLE_MESSAGE, Protocol.RELIABLE_RESEND_MS,
            Protocol.RELIABLE_MIN_RTO_MS, Protocol.RELIABLE_MAX_RTO_MS);
    }

    /**
     * Reset all state (new session).
     * <p>
     * BEHAVIOR: forgets queued, overflowing and buffered messages, sequences, and RTT estimates.
     */
    public void reset() {
        txBase = 0;
        txNext = 0;
        rxNext = 0;
        building = false;
        overflowBuilding = null;
        while (!overflow.isEmpty()) {
            pool.addLast(overflow.pollFirst());
        }
        ackPending = false;
        rto = initialRto;
        srtt = -1;
        rttvar = 0;
        for (int i = 0; i < window; i++) {
            rxHave[i] = false;
            txSends[i] = 0;
        }
    }

    // ---------------------------------------------------------------- sending

    /** Free slots in the in-flight window. */
    public int freeSlots() {
        return window - (txNext - txBase);
    }

    /** Messages in the window, not yet acknowledged. */
    public int unacked() {
        return txNext - txBase;
    }

    /** Messages waiting in the overflow queue for window slots. */
    public int overflowing() {
        return overflow.size();
    }

    /** Overflow buffers kept for reuse (allocation-free steady state). */
    int pooledBuffers() {
        return pool.size();
    }

    /** Current resend timeout in nanoseconds. */
    public long resendTimeoutNanos() {
        return rto;
    }

    /**
     * Start a message. Write the payload into the returned buffer (relative puts) then call {@link #end()}.
     * <p>
     * BEHAVIOR: returns a cleared buffer of maxMessage bytes with the type byte written.
     * BEHAVIOR: uses the next window slot, or an overflow buffer when the window is full or older
     * messages are still overflowing (order is preserved).
     * BEHAVIOR: throws IllegalStateException when a message is already open.
     */
    public ByteBuffer begin(int type) {
        if (building) {
            throw new IllegalStateException("message already open");
        }
        ByteBuffer b;
        if (freeSlots() == 0 || !overflow.isEmpty()) {
            b = pool.isEmpty() ? ByteBuffer.allocate(maxMessage) : pool.pollFirst();
            overflowBuilding = b;
        } else {
            b = tx[txNext & mask];
            overflowBuilding = null;
        }
        b.clear();
        b.put((byte) type);
        building = true;
        return b;
    }

    /**
     * Abandon the message started by {@link #begin(int)} (e.g. its payload did not fit).
     * <p>
     * BEHAVIOR: nothing is queued and a new {@link #begin(int)} is accepted; no-op when nothing is open.
     */
    public void abort() {
        if (overflowBuilding != null) {
            pool.addLast(overflowBuilding);
            overflowBuilding = null;
        }
        building = false;
    }

    /**
     * Finish the message started by {@link #begin(int)} and queue it.
     * <p>
     * BEHAVIOR: queues into the window or at the tail of the overflow queue; throws when nothing is open.
     */
    public void end() {
        if (!building) {
            throw new IllegalStateException("no open message");
        }
        building = false;
        if (overflowBuilding != null) {
            overflowBuilding.flip();
            overflow.addLast(overflowBuilding);
            overflowBuilding = null;
            return;
        }
        int slot = txNext & mask;
        tx[slot].flip();
        txSends[slot] = 0;
        txNext++;
    }

    /** Move overflowing messages into free window slots, oldest first. */
    private void pump() {
        while (!overflow.isEmpty() && freeSlots() > 0) {
            ByteBuffer src = overflow.pollFirst();
            int slot = txNext & mask;
            ByteBuffer dst = tx[slot];
            dst.clear();
            dst.put(0, src, 0, src.limit());
            dst.limit(src.limit());
            txSends[slot] = 0;
            txNext++;
            pool.addLast(src);
        }
    }

    /**
     * BEHAVIOR: true if a window message was never sent or its last send is at least the resend timeout ago.
     */
    public boolean wantsSend(long nowNanos) {
        for (int s = txBase; s != txNext; s++) {
            int slot = s & mask;
            if (txSends[slot] == 0 || nowNanos - txSent[slot] >= rto) {
                return true;
            }
        }
        return false;
    }

    /**
     * Write a RELIABLE body with as many due messages as fit in {@code out}.
     * <p>
     * BEHAVIOR: first moves overflowing messages into free window slots.
     * BEHAVIOR: includes, oldest first, messages never sent or whose last send is at least the resend
     * timeout ago, up to 255 and while they fit; records the send time and count.
     * BEHAVIOR: writes nothing (buffer untouched) when nothing is due or fits.
     *
     * @return number of messages written (0 means nothing was written)
     */
    public int write(ByteBuffer out, long nowNanos) {
        pump();
        if (out.remaining() < 1 + 4 + 1) {
            return 0;
        }
        int countPos = out.position();
        int count = 0;
        out.put((byte) 0);
        for (int s = txBase; s != txNext && count < 255; s++) {
            int slot = s & mask;
            if (txSends[slot] > 0 && nowNanos - txSent[slot] < rto) {
                continue;
            }
            ByteBuffer m = tx[slot];
            int len = m.limit();
            if (out.remaining() < 4 + len) {
                break;
            }
            out.putShort((short) s);
            out.putShort((short) len);
            out.put(out.position(), m, 0, len);
            out.position(out.position() + len);
            txSends[slot]++;
            txSent[slot] = nowNanos;
            count++;
        }
        if (count == 0) {
            out.position(countPos);
            return 0;
        }
        out.put(countPos, (byte) count);
        return count;
    }

    /**
     * Process the peer's cumulative ack (next sequence it expects).
     * <p>
     * BEHAVIOR: releases all window messages before the ack (16-bit wrapping); ignores acks beyond what
     * was sent and stale acks. Freed slots are refilled from the overflow queue by the next {@link #write}.
     * BEHAVIOR: if the newest released message was sent exactly once, its age is an RTT sample:
     * first sample srtt = R, rttvar = R/2; then rttvar = (3 rttvar + |srtt - R|)/4, srtt = (7 srtt + R)/8;
     * timeout = clamp(srtt + 4 rttvar, minRto, maxRto).
     */
    public void onAck(int ack16, long nowNanos) {
        int diff = (ack16 - txBase) & 0xFFFF;
        if (diff == 0 || diff > txNext - txBase) {
            return;
        }
        int newest = (txBase + diff - 1) & mask;
        if (txSends[newest] == 1) {
            sample(nowNanos - txSent[newest]);
        }
        txBase += diff;
    }

    private void sample(long r) {
        if (srtt < 0) {
            srtt = r;
            rttvar = r / 2;
        } else {
            rttvar = (3 * rttvar + Math.abs(srtt - r)) / 4;
            srtt = (7 * srtt + r) / 8;
        }
        long t = srtt + 4 * rttvar;
        rto = t < minRto ? minRto : (t > maxRto ? maxRto : t);
    }

    // ---------------------------------------------------------------- receiving

    /** Value for the {@link PacketHeader} reliableAck field: next sequence expected from the peer. */
    public int ack() {
        return rxNext & 0xFFFF;
    }

    /** BEHAVIOR: true once after messages were received (send an ACK packet if otherwise idle). */
    public boolean consumeAckPending() {
        boolean p = ackPending;
        ackPending = false;
        return p;
    }

    /**
     * Parse a RELIABLE body at the buffer's position and deliver new in-order messages.
     * <p>
     * BEHAVIOR: the expected sequence is delivered at once, followed by any buffered successors.
     * BEHAVIOR: sequences 1..window-1 ahead are buffered (once); older or farther ones are ignored.
     * BEHAVIOR: a truncated body, zero length or length beyond the body returns -1.
     * The handler gets the message type and a buffer positioned after the type byte with its limit at
     * the message end; it must not keep the buffer.
     *
     * @return number of messages delivered, or -1 if the body is malformed
     */
    public int read(ByteBuffer in, ReliableHandler handler) {
        if (in.remaining() < 1) {
            return -1;
        }
        int count = in.get() & 0xFF;
        int delivered = 0;
        int savedLimit = in.limit();
        for (int k = 0; k < count; k++) {
            if (in.remaining() < 4) {
                return -1;
            }
            int seq = in.getShort() & 0xFFFF;
            int len = in.getShort() & 0xFFFF;
            if (len < 1 || len > in.remaining()) {
                return -1;
            }
            int start = in.position();
            int d = (seq - rxNext) & 0xFFFF;
            ackPending = true;
            if (d == 0) {
                in.limit(start + len);
                int type = in.get() & 0xFF;
                handler.onReliableMessage(type, in);
                in.limit(savedLimit);
                rxNext++;
                delivered++;
                delivered += drainBuffered(handler);
            } else if (d < window) {
                int slot = (rxNext + d) & mask;
                if (!rxHave[slot] && len <= maxMessage) {
                    ByteBuffer b = rx[slot];
                    b.clear();
                    b.put(0, in, start, len);
                    b.limit(len);
                    rxHave[slot] = true;
                }
            }
            in.position(start + len);
        }
        return delivered;
    }

    private int drainBuffered(ReliableHandler handler) {
        int n = 0;
        while (rxHave[rxNext & mask]) {
            int slot = rxNext & mask;
            rxHave[slot] = false;
            ByteBuffer b = rx[slot];
            b.position(0);
            int type = b.get() & 0xFF;
            handler.onReliableMessage(type, b);
            rxNext++;
            n++;
        }
        return n;
    }
}
