package com.fulcrologic.jxpilot.common.net;

import java.nio.ByteBuffer;

/**
 * The 8-byte header that starts every datagram:
 * <pre>
 *   u16 magic (0x4A58 "JX") | u8 version | u8 packetType | u16 sessionId | u16 reliableAck
 * </pre>
 * {@code sessionId} is 0 until the server assigns one in ACCEPT. {@code reliableAck} is the next
 * reliable sequence number the sender expects from the peer (cumulative ack), see
 * {@link ReliableChannel#ack()}.
 * <p>
 * Use one instance as a reusable reader; {@link #write} is static. Allocation free.
 */
public final class PacketHeader {
    public static final int SIZE = 8;

    private int type;
    private int session;
    private int reliableAck;

    /** Write a header at the buffer's position (relative puts). */
    public static void write(ByteBuffer out, int type, int session, int reliableAck) {
        out.putShort((short) Protocol.MAGIC);
        out.put((byte) Protocol.VERSION);
        out.put((byte) type);
        out.putShort((short) session);
        out.putShort((short) reliableAck);
    }

    /**
     * Read and validate a header at the buffer's position, advancing past it.
     * <p>
     * BEHAVIOR: accepts only the JX magic and this protocol version; unsigned fields are decoded.
     *
     * @return false (position unchanged) if too short, wrong magic or wrong version
     */
    public boolean read(ByteBuffer in) {
        if (in.remaining() < SIZE) {
            return false;
        }
        int p = in.position();
        if ((in.getShort(p) & 0xFFFF) != Protocol.MAGIC || (in.get(p + 2) & 0xFF) != Protocol.VERSION) {
            return false;
        }
        type = in.get(p + 3) & 0xFF;
        session = in.getShort(p + 4) & 0xFFFF;
        reliableAck = in.getShort(p + 6) & 0xFFFF;
        in.position(p + SIZE);
        return true;
    }

    /** {@link PacketType} of the last header read. */
    public int type() { return type; }

    public int session() { return session; }

    public int reliableAck() { return reliableAck; }
}
