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

import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;

import java.nio.ByteBuffer;

/**
 * Receives what a {@link ClientConnection} decodes. All calls happen on the thread that calls
 * {@link ClientConnection#update(long)}; buffers are valid only during the call.
 */
public interface ConnectionListener {
    /** A session was accepted (also after a reconnect): forget all game state of an older session. */
    void onAccepted(int playerId);

    /** WELCOME arrived; the config/map transfer follows. */
    void onWelcome(Msg.Welcome welcome);

    /** The config was received, verified and parsed; the map is built. Snapshots follow. */
    void onConfig(Msg.Welcome welcome, Options options, WorldMap map);

    /**
     * Any other reliable server message, in order ({@link com.fulcrologic.jxpilot.common.net.ReliableType}).
     *
     * @param payload positioned after the type byte, limit at the message end
     */
    void onReliable(int type, ByteBuffer payload);

    /**
     * A SNAPSHOT body (after the packet header), only while {@link ConnectionState#PLAYING}.
     *
     * @param nowNanos arrival time
     */
    void onSnapshot(ByteBuffer body, long nowNanos);

    /** The state changed; {@code status} is a human-readable line for the HUD (valid during the call). */
    void onStateChange(ConnectionState state, CharSequence status);

    /**
     * The connection's input and server-state feed for the own-ship predictor, given once when the connection is
     * made (JXPilot protocol v2 only; an XPilot connection never calls it).
     */
    default void predictionSource(com.fulcrologic.jxpilot.client.game.predict.PredictionSource source) {
    }
}
