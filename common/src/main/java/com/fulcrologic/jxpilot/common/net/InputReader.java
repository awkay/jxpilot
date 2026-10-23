package com.fulcrologic.jxpilot.common.net;

import java.nio.ByteBuffer;

/**
 * Server side flyweight over an INPUT body (see {@link InputHistory}). Typical use: for
 * {@code i = count()-1 .. 0}, apply entries whose {@link #seq(int)} is newer than the last applied.
 */
public final class InputReader {
    private ByteBuffer buf;
    private int base;
    private int latestSeq;
    private int ackTick;
    private int count;

    /**
     * Wrap the body at the buffer's position.
     * <p>
     * BEHAVIOR: rejects bodies shorter than the header, with more than 8 entries, or shorter than the entries.
     *
     * @return false if malformed
     */
    public boolean wrap(ByteBuffer in) {
        int p = in.position();
        if (in.remaining() < InputHistory.HEADER_SIZE) {
            return false;
        }
        latestSeq = in.getInt(p);
        ackTick = in.getInt(p + 4);
        count = in.get(p + 8) & 0xFF;
        if (count > InputHistory.MAX_REDUNDANCY
            || in.remaining() < InputHistory.HEADER_SIZE + count * InputHistory.ENTRY_SIZE) {
            return false;
        }
        buf = in;
        base = p + InputHistory.HEADER_SIZE;
        return true;
    }

    public int latestSeq() { return latestSeq; }

    /** Newest snapshot tick the client has received. */
    public int ackTick() { return ackTick; }

    public int count() { return count; }

    /** Sequence of entry i (0 = newest). */
    public int seq(int i) { return latestSeq - i; }

    /** {@link ControlKey} mask of entry i. */
    public long keys(int i) { return buf.getLong(base + i * InputHistory.ENTRY_SIZE); }

    /** Aim turn of entry i (signed, u16 angle units). */
    public int aimTurn(int i) { return buf.getShort(base + i * InputHistory.ENTRY_SIZE + 8); }
}
