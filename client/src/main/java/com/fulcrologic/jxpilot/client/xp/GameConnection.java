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

import com.fulcrologic.jxpilot.client.net.ConnectionState;

/**
 * A client connection to a game server, independent of the wire protocol: the native JXPilot protocol
 * ({@link NativeGameConnection} over {@link com.fulcrologic.jxpilot.client.net.ClientConnection}) or
 * the original XPilot 4.5.5 protocol ({@link XpConnection}). Both feed the same
 * {@link com.fulcrologic.jxpilot.client.net.ConnectionListener} (normally
 * {@link com.fulcrologic.jxpilot.client.game.ClientGame}), so everything above the connection is
 * shared. Open one with {@link GameConnections#open}.
 * <p>
 * Per client frame, on one thread: {@link #update(long)}, {@link #sendInput(long, int, long)},
 * {@link #flush(long)}. Time is any monotonic nanosecond clock.
 */
public interface GameConnection extends AutoCloseable {
    /** Which wire protocol. */
    ConnectTarget.Protocol protocol();

    /** Begin connecting. */
    void start(long nowNanos);

    /** Receive and process everything waiting, run timers. */
    void update(long nowNanos);

    /**
     * This frame's keys.
     *
     * @param keys    {@link com.fulcrologic.jxpilot.common.net.ControlKey} mask
     * @param aimTurn pointer turn in u16 angle units (0 = none); not supported by every protocol
     * @return a non-zero input sequence when something was sent
     */
    int sendInput(long keys, int aimTurn, long nowNanos);

    /** Send anything still queued (reliable data, acks). */
    void flush(long nowNanos);

    /**
     * Send a chat line (XPilot "name:" / "team:" prefixes are handled by the server).
     *
     * @return false when not connected or the line could not be queued
     */
    boolean sendChat(CharSequence text, long nowNanos);

    /**
     * The player's control settings changed (C {@code Send_power}, {@code Send_turnspeed} ... after a
     * power/turn-speed key). They are also what the next (re)join sends.
     *
     * @return true when they were sent now (else they go with the next join)
     */
    boolean sendSettings(com.fulcrologic.jxpilot.common.net.Msg.Settings settings, long nowNanos);

    /**
     * The world area the game view shows changed, in world pixels (C {@code Send_display} after a resize or
     * scale change: draw size times scaleFactor). It is also what the next (re)join sends. Protocols without it
     * ignore it (the original protocol sends its fixed login size).
     *
     * @return true when it was sent now (else it goes with the next join)
     */
    default boolean sendDisplay(int viewWidth, int viewHeight, long nowNanos) {
        return false;
    }

    /**
     * The C client's modifier banks ({@code modifierBank1..4}, C {@code modBankStr}): what every (re)join sends
     * (C {@code Send_modifier_bank} at login). Protocols without them ignore it.
     *
     * @param banks four texts (bank 0..3)
     */
    default void setModifierBanks(String[] banks) { }

    /**
     * The player whose view the server sends (C {@code eyesId}; another player while spectating), or
     * {@link com.fulcrologic.jxpilot.common.net.Protocol#NO_ID} when the protocol does not say (the native protocol).
     */
    default int eyesId() { return com.fulcrologic.jxpilot.common.net.Protocol.NO_ID; }

    /**
     * Ask the server for its message of the day again (C {@code Net_ask_for_motd} from the MOTD menu entry with
     * {@code refreshMotd}, or when none arrived yet). Protocols without it ignore it.
     *
     * @return true when the request went out
     */
    default boolean requestMotd(long nowNanos) {
        return false;
    }

    /** Leave the game; the state becomes CLOSED. */
    void disconnect(long nowNanos);

    ConnectionState state();

    /** Human-readable status line. */
    CharSequence status();

    /** Datagrams received from the server. */
    long packetsReceived();

    long packetsSent();

    /** Datagrams or packets dropped as malformed or unknown. */
    long malformed();

    /** Snapshots delivered to the listener. */
    long snapshotsReceived();

    /** Release sockets (after {@link #disconnect}). */
    @Override
    void close();
}
