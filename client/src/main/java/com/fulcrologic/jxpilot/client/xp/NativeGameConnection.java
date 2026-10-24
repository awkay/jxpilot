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

import com.fulcrologic.jxpilot.client.net.ClientConnection;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.net.Transport;
import com.fulcrologic.jxpilot.common.net.Msg;

/**
 * {@link GameConnection} over the native JXPilot {@link ClientConnection} (pure delegation, plus chat
 * as a reliable CHAT message).
 */
public final class NativeGameConnection implements GameConnection {
    private final ClientConnection conn;
    private final Transport transport;
    private final Msg.Text chat = new Msg.Text();

    /** @param transport closed by {@link #close()} (may be null when the caller owns it) */
    public NativeGameConnection(ClientConnection conn, Transport transport) {
        this.conn = conn;
        this.transport = transport;
    }

    /** The wrapped connection (CONNECT fields, reliable channel, statistics). */
    public ClientConnection connection() { return conn; }

    @Override
    public ConnectTarget.Protocol protocol() { return ConnectTarget.Protocol.JXPILOT; }

    @Override
    public void start(long nowNanos) { conn.start(nowNanos); }

    @Override
    public void update(long nowNanos) { conn.update(nowNanos); }

    @Override
    public int sendInput(long keys, int aimTurn, long nowNanos) { return conn.sendInput(keys, aimTurn, nowNanos); }

    @Override
    public void flush(long nowNanos) { conn.flush(nowNanos); }

    /**
     * BEHAVIOR: queues a reliable CHAT while JOINING or PLAYING (sent by the next flush); false
     * otherwise or for empty text.
     */
    @Override
    public boolean sendChat(CharSequence text, long nowNanos) {
        ConnectionState s = conn.state();
        if (text.length() == 0 || s != ConnectionState.JOINING && s != ConnectionState.PLAYING) {
            return false;
        }
        chat.kind = Msg.Text.KIND_CHAT;
        chat.text.setLength(0);
        chat.text.append(text);
        chat.sendChat(conn.channel());
        return true;
    }

    /**
     * BEHAVIOR: copies the settings into the connection's SETTINGS (sent before READY on every join); while
     * JOINING or PLAYING also queues them as one reliable SETTINGS now (true), unless they equal the ones
     * already sent in this state (false: one key press, one message).
     */
    @Override
    public boolean sendSettings(Msg.Settings settings, long nowNanos) {
        copy(settings, conn.settings);
        ConnectionState s = conn.state();
        if (s != ConnectionState.JOINING && s != ConnectionState.PLAYING) {
            haveSent = false;
            return false;
        }
        if (haveSent && same(conn.settings, sent)) {
            return false;
        }
        conn.settings.send(conn.channel());
        copy(conn.settings, sent);
        haveSent = true;
        return true;
    }

    private final Msg.Settings sent = new Msg.Settings();
    private boolean haveSent;

    static boolean same(Msg.Settings a, Msg.Settings b) {
        return a.power == b.power && a.turnSpeed == b.turnSpeed && a.turnResistance == b.turnResistance
            && a.altPower == b.altPower && a.altTurnSpeed == b.altTurnSpeed
            && a.altTurnResistance == b.altTurnResistance && a.flags == b.flags;
    }

    /**
     * BEHAVIOR: stores the size in the connection's DISPLAY (sent before READY on every join); while PLAYING
     * also queues it as a reliable DISPLAY now (true). (While JOINING the join itself sends it.)
     */
    @Override
    public boolean sendDisplay(int viewWidth, int viewHeight, long nowNanos) {
        conn.display.viewWidth = viewWidth;
        conn.display.viewHeight = viewHeight;
        if (conn.state() != ConnectionState.PLAYING) {
            return false;
        }
        conn.display.send(conn.channel());
        return true;
    }

    /** BEHAVIOR: stores the banks in the connection, which sends them before READY on every join. */
    @Override
    public void setModifierBanks(String[] banks) {
        System.arraycopy(banks, 0, conn.modifierBanks, 0, Msg.ModifierBank.BANKS);
    }

    static void copy(Msg.Settings from, Msg.Settings to) {
        to.power = from.power;
        to.turnSpeed = from.turnSpeed;
        to.turnResistance = from.turnResistance;
        to.altPower = from.altPower;
        to.altTurnSpeed = from.altTurnSpeed;
        to.altTurnResistance = from.altTurnResistance;
        to.flags = from.flags;
    }

    @Override
    public void disconnect(long nowNanos) { conn.disconnect(nowNanos); }

    @Override
    public ConnectionState state() { return conn.state(); }

    @Override
    public CharSequence status() { return conn.status(); }

    @Override
    public long packetsReceived() { return conn.packetsReceived(); }

    @Override
    public long packetsSent() { return conn.packetsSent(); }

    @Override
    public long malformed() { return conn.malformed(); }

    @Override
    public long snapshotsReceived() { return conn.snapshotsReceived(); }

    @Override
    public com.fulcrologic.jxpilot.client.net.PacketStats packetStats() { return conn.packetStats(); }

    @Override
    public void close() {
        if (transport != null) {
            transport.close();
        }
    }
}
