package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;

/**
 * Preallocated ring buffers of things the simulation produced for the network layer: chat/game
 * messages, discrete game events, roster changes and spark/debris bursts. The sim only appends; the
 * net layer reads by sequence number, so several clients can consume the same events. Allocation free.
 */
public final class Events {
    /** Messages kept (a slow consumer loses the oldest). */
    public static final int MESSAGES = 128;
    public static final int GAME_EVENTS = 256;
    public static final int ROSTER = 256;
    /** Bursts kept: enough for several ticks of a large fight. */
    public static final int BURSTS = 4096;

    public static final int ROSTER_JOIN = 1;
    public static final int ROSTER_LEAVE = 2;
    public static final int ROSTER_TEAM = 3;
    public static final int ROSTER_BASE = 4;
    public static final int ROSTER_THRUST = 5;

    // ---- messages
    public final int[] msgKind = new int[MESSAGES];
    /** Sender player id or NO_ID. */
    public final int[] msgFrom = new int[MESSAGES];
    /** Recipient player id for private/team messages, NO_ID for everyone; for team chat the team. */
    public final int[] msgTo = new int[MESSAGES];
    public final StringBuilder[] msgText = new StringBuilder[MESSAGES];
    private long msgSeq;

    // ---- game events (Msg.GameEvent kinds)
    public final int[] evKind = new int[GAME_EVENTS];
    public final int[] evA = new int[GAME_EVENTS];
    public final int[] evB = new int[GAME_EVENTS];
    public final float[] evValue = new float[GAME_EVENTS];
    private long evSeq;

    // ---- roster changes
    public final int[] rosterKind = new int[ROSTER];
    /** Player slot (JOIN/TEAM/THRUST/BASE) or player id (LEAVE). */
    public final int[] rosterA = new int[ROSTER];
    public final int[] rosterB = new int[ROSTER];
    private long rosterSeq;

    // ---- bursts
    public final DebrisBurst[] burst = new DebrisBurst[BURSTS];
    public final int[] burstKind = new int[BURSTS];
    public final int[] burstColor = new int[BURSTS];
    public final int[] burstTick = new int[BURSTS];
    private long burstSeq;

    public Events() {
        for (int i = 0; i < MESSAGES; i++) {
            msgText[i] = new StringBuilder(Const.MSG_LEN);
        }
        for (int i = 0; i < BURSTS; i++) {
            burst[i] = new DebrisBurst();
        }
    }

    /**
     * Start a message; append the text to the returned builder (already cleared).
     * <p>
     * BEHAVIOR: each call takes the next ring slot and advances {@link #messageSeq()}.
     */
    public StringBuilder message(int kind, int from, int to) {
        int i = (int) (msgSeq++ & (MESSAGES - 1));
        msgKind[i] = kind;
        msgFrom[i] = from;
        msgTo[i] = to;
        StringBuilder sb = msgText[i];
        sb.setLength(0);
        return sb;
    }

    /** Sequence number of the next message (messages {@code [seq-MESSAGES, seq)} are readable). */
    public long messageSeq() {
        return msgSeq;
    }

    /** Ring index of a message sequence number. */
    public static int msgIndex(long seq) {
        return (int) (seq & (MESSAGES - 1));
    }

    /** Record a game event. */
    public void gameEvent(int kind, int a, int b, float value) {
        int i = (int) (evSeq++ & (GAME_EVENTS - 1));
        evKind[i] = kind;
        evA[i] = a;
        evB[i] = b;
        evValue[i] = value;
    }

    public long gameEventSeq() {
        return evSeq;
    }

    public static int evIndex(long seq) {
        return (int) (seq & (GAME_EVENTS - 1));
    }

    /** Record a roster change. */
    public void roster(int kind, int a, int b) {
        int i = (int) (rosterSeq++ & (ROSTER - 1));
        rosterKind[i] = kind;
        rosterA[i] = a;
        rosterB[i] = b;
    }

    public long rosterSeq() {
        return rosterSeq;
    }

    public static int rosterIndex(long seq) {
        return (int) (seq & (ROSTER - 1));
    }

    /** Take the next burst slot for {@code tick}; the caller fills the returned parameters. */
    public DebrisBurst burst(int kind, int color, int tick) {
        int i = (int) (burstSeq++ & (BURSTS - 1));
        burstKind[i] = kind;
        burstColor[i] = color;
        burstTick[i] = tick;
        return burst[i];
    }

    public long burstSeq() {
        return burstSeq;
    }

    public static int burstIndex(long seq) {
        return (int) (seq & (BURSTS - 1));
    }
}
