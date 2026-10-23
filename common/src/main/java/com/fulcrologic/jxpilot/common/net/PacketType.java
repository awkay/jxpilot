package com.fulcrologic.jxpilot.common.net;

/** Datagram types (byte 3 of the {@link PacketHeader}). */
public final class PacketType {
    private PacketType() {}

    /** C to S: join request ({@link Msg.Connect}); resent until answered. */
    public static final int CONNECT = 1;
    /** S to C: join accepted ({@link Msg.Accept}); header carries the session id. */
    public static final int ACCEPT = 2;
    /** S to C: join refused ({@link Msg.Reject}). */
    public static final int REJECT = 3;
    /** Either way: reliable channel frames ({@link ReliableChannel}). */
    public static final int RELIABLE = 4;
    /** C to S: control input with redundancy ({@link InputHistory}/{@link InputReader}). */
    public static final int INPUT = 5;
    /** S to C: world snapshot ({@link SnapshotWriter}/{@link SnapshotReader}). */
    public static final int SNAPSHOT = 6;
    /** Either way: leaving ({@link Msg.Disconnect}); send a few times, unreliable. */
    public static final int DISCONNECT = 7;
    /** Either way: header only, keeps the session alive and carries the reliable ack. */
    public static final int ACK = 8;
    /** Either way: RTT probe ({@link Msg.Ping}). */
    public static final int PING = 9;
    /** Either way: RTT probe reply ({@link Msg.Ping}, echoed). */
    public static final int PONG = 10;

    /** Name for logs. */
    public static String name(int t) {
        return switch (t) {
            case CONNECT -> "CONNECT";
            case ACCEPT -> "ACCEPT";
            case REJECT -> "REJECT";
            case RELIABLE -> "RELIABLE";
            case INPUT -> "INPUT";
            case SNAPSHOT -> "SNAPSHOT";
            case DISCONNECT -> "DISCONNECT";
            case ACK -> "ACK";
            case PING -> "PING";
            case PONG -> "PONG";
            default -> "UNKNOWN";
        };
    }
}
