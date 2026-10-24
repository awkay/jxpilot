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
package com.fulcrologic.jxpilot.server.net;

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;

import java.nio.ByteBuffer;

/**
 * What the network layer needs from the simulation. The network layer owns sockets, sessions,
 * reliability and the tick counter. The host owns players, world state and reliable game events.
 * <p>
 * Threading: every method is called from the single server loop thread, never concurrently.
 * Hot path: {@link #input}, {@link #tick}, {@link #viewX}, {@link #viewY}, {@link #fillSnapshot}
 * and {@link #drainEvents} run every tick and must not allocate in steady state.
 * <p>
 * Player ids are 0..65534 ({@code Protocol.NO_ID} = 65535 is reserved) and identify the player
 * in snapshots and reliable messages. A connection's viewer id is its player id.
 */
public interface GameHost {

    /** What the host may ask of the network layer. */
    @FunctionalInterface
    interface Sessions {
        /**
         * Drop the connection of {@code playerId} now (C {@code Destroy_connection}): it gets DISCONNECT
         * (kicked) with {@code reason}, and the host gets the usual {@link #leave} before this returns.
         * Nothing happens when no connection has that player. Call it from {@link #join} or {@link #tick}
         * only, never while a client message is being delivered.
         */
        void kick(int playerId, CharSequence reason);
    }

    /** Called once by the network layer, before any other method, with its session control. */
    default void attach(Sessions sessions) {
    }

    /**
     * A client asks to join (on its first CONNECT, or a CONNECT with a new nonce from the same address).
     * <p>
     * The client has not yet proven that it receives at its source address (a CONNECT can be spoofed).
     * The network layer sends it only ACCEPT until a packet carrying the session id arrives; if none
     * arrives within a few seconds the player gets {@link #leave} without ever having sent anything,
     * so do not announce the player to others before READY. On a reconnect (new nonce, same address)
     * this new join happens first and the old player's {@link #leave} follows only if it succeeded.
     *
     * @param name  player name, non-empty; valid only during the call (copy it)
     * @param team  preferred team 0-9, or {@code Protocol.NO_TEAM} for any
     * @param shape ship shape string, empty for the default ship; valid only during the call
     * @return the new player id (0..65534), or {@code -reason} with a {@link Msg.Reject} REASON_* code
     *     to refuse. The player is not spawned until {@link #clientMessage} delivers READY.
     */
    int join(CharSequence name, int team, CharSequence shape);

    /** The team assigned to a joined player ({@code Protocol.NO_TEAM} when there are no teams). */
    int team(int playerId);

    /**
     * The player is gone: it quit, timed out, or was replaced by a reconnect. Called exactly once
     * per successful {@link #join}. The id may be reused by a later join.
     */
    void leave(int playerId);

    /**
     * A reliable client-to-server message, in order: READY (spawn the player), CHAT, SETTINGS,
     * CHANGE_TEAM, SHAPE, or a type unknown to this version (ignore it).
     *
     * @param type    {@code ReliableType} value
     * @param payload positioned after the type byte, limited to the message; decode with the
     *                {@link Msg} codecs; valid only during the call. A truncated payload may throw
     *                {@link java.nio.BufferUnderflowException}: the network layer catches any
     *                RuntimeException, counts the message as malformed and moves on to the next one
     *                (the message is not redelivered).
     */
    void clientMessage(int playerId, int type, ByteBuffer payload);

    /**
     * One control input, called for each new input sequence in order, oldest first, with no
     * duplicates. Lost inputs beyond the client's redundancy are skipped. Several inputs may
     * arrive between ticks; derive press/release edges by comparing consecutive masks. An input the
     * host throws on is counted as malformed and not offered again.
     *
     * @param seq     the client's input sequence number (increasing)
     * @param keys    {@code ControlKey} mask
     * @param aimTurn pointer-control turn in u16 angle units (0 = none)
     */
    void input(int playerId, int seq, long keys, int aimTurn);

    /**
     * Advance the simulation one C frame (the server ticks at the map's framesPerSecond, so dt = 1).
     * {@code tick} counts from 1 and is the number written into snapshots, so tick-seeded effects
     * (thrust sparks) match on clients.
     */
    void tick(int tick);

    /** True when the game wants the server to stop (C {@code End_game} after the gameDuration shutdown delay). */
    default boolean shutdownDue() {
        return false;
    }

    /** X of the viewer's view centre (world pixels), used for the snapshot header. */
    double viewX(int viewerId);

    /** Y of the viewer's view centre (world pixels), used for the snapshot header. */
    double viewY(int viewerId);

    /**
     * Write one snapshot part's sections for this viewer. Before the call the network layer has
     * written the packet and snapshot headers, using {@link #viewX}/{@link #viewY}. Write records
     * while {@code w.hasRoom()}, highest priority first. Return true when this part is full and more
     * remains. The network layer then calls again with {@code part + 1}, up to a fixed number of
     * parts, and the host resumes where it stopped. Parts for one viewer are requested consecutively
     * before the next viewer's.
     *
     * @param radar true when this snapshot should include the RADAR section (every radarDivisor-th)
     * @return true if another part is needed
     */
    boolean fillSnapshot(int viewerId, int part, boolean radar, SnapshotWriter w);

    /**
     * Queue the full reliable game state for a client that has just received the map:
     * PLAYER_JOIN for every player (FLAG_SELF for the viewer), BASE_OWNER, SCORE, TEAM_SCORE, and so
     * on, using {@code Msg.*.send(out)}. Called once per connection, after the config transfer is
     * queued. Events that happened earlier are covered by this state. {@link #drainEvents} then
     * reports changes after this point. Snapshots start only once the client has acknowledged everything
     * up to and including this state; events queued afterwards do not hold them back.
     */
    void initialState(int viewerId, ReliableChannel out);

    /**
     * Queue reliable events for this viewer since its previous drain (or its
     * {@link #initialState}): PLAYER_JOIN/LEAVE/TEAM, SCORE, TEAM_SCORE, MESSAGE (chat), BASE_OWNER,
     * GAME_EVENT (kills etc.) and PLAYER_THRUST, using {@code Msg.*.send(out)}. Called once per tick for
     * every connection whose initial state has been sent. The channel never refuses a message (it
     * queues overflow), so write everything; a client that stops acknowledging is dropped (with
     * {@link #leave}) once its backlog exceeds a limit or its oldest message waits past the timeout.
     */
    void drainEvents(int viewerId, ReliableChannel out);
}
