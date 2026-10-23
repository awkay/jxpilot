package com.fulcrologic.jxpilot.common.net;

/**
 * Wire protocol constants. All multi-byte values are big-endian (Java {@link java.nio.ByteBuffer} default).
 * See {@code docs/PROTOCOL.md} for the full format.
 */
public final class Protocol {
    private Protocol() {}

    /** First two bytes of every datagram: ASCII "JX". */
    public static final int MAGIC = 0x4A58;
    /** Protocol version; bump on incompatible changes. */
    public static final int VERSION = 1;
    /** Maximum datagram payload in bytes. */
    public static final int MAX_PACKET = 1200;
    /** Default server UDP port (XPilot contact port). */
    public static final int DEFAULT_PORT = 15345;
    /** "No player/object" id on the wire. */
    public static final int NO_ID = 0xFFFF;
    /** "No team"/"any team" on the wire. */
    public static final int NO_TEAM = 0xFF;
    /** Client resends CONNECT this often until ACCEPT/REJECT arrives. */
    public static final long CONNECT_RESEND_MS = 250;
    /** Either side drops the session after this long without receiving anything. */
    public static final long TIMEOUT_MS = 10_000;
    /** Initial reliable resend timeout, before any RTT sample. */
    public static final long RELIABLE_RESEND_MS = 150;
    /** Floor of the adaptive reliable resend timeout. */
    public static final long RELIABLE_MIN_RTO_MS = 50;
    /** Ceiling of the adaptive reliable resend timeout. */
    public static final long RELIABLE_MAX_RTO_MS = 2000;
    /**
     * Default reliable window (messages in flight per direction). With 1100-byte slots a channel
     * preallocates 2 * 32 * 1100 B = 70 KB. 32 x 1 KB chunks per round trip moves a compressed map
     * (typically 5-40 KB) in a few round trips, so no deeper window is needed for map transfer.
     */
    public static final int RELIABLE_WINDOW = 32;
    /** Default radar rate: the RADAR section is sent in every 3rd snapshot (10 Hz at 30 Hz snapshots). */
    public static final int DEFAULT_RADAR_DIVISOR = 3;
    /** Default snapshot rate divisor: one snapshot every 2nd 60 Hz sim tick (30 Hz). */
    public static final int DEFAULT_SNAPSHOT_DIVISOR = 2;
    /** Max bytes of one reliable message including its type byte. */
    public static final int MAX_RELIABLE_MESSAGE = 1100;
    /** Bytes of map/config data per DATA_CHUNK message. */
    public static final int DATA_CHUNK_SIZE = 1024;
}
