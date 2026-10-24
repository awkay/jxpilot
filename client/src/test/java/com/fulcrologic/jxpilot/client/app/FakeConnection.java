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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.xp.ConnectTarget;
import com.fulcrologic.jxpilot.client.xp.GameConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/** A {@link GameConnection} that records calls; {@link #onUpdate} lets a test feed the game during update. */
final class FakeConnection implements GameConnection {
    final List<String> calls = new ArrayList<>();
    ConnectionState state = ConnectionState.PLAYING;
    String status = "Playing on fake";
    LongConsumer onUpdate = t -> { };
    long lastKeys = -1;
    boolean chatAccepted = true;
    boolean record = true;
    /** Every sendSettings call as "power turnSpeed turnResistance altPower altTurnSpeed altTurnResistance". */
    final List<String> settings = new ArrayList<>();
    boolean settingsSent = true;
    /** The packet meters' stats this fake reports. */
    final com.fulcrologic.jxpilot.client.net.PacketStats stats = new com.fulcrologic.jxpilot.client.net.PacketStats();

    @Override public com.fulcrologic.jxpilot.client.net.PacketStats packetStats() { return stats; }

    @Override public ConnectTarget.Protocol protocol() { return ConnectTarget.Protocol.JXPILOT; }

    @Override public void start(long nowNanos) { log("start"); }

    @Override
    public void update(long nowNanos) {
        log("update");
        onUpdate.accept(nowNanos);
    }

    @Override
    public int sendInput(long keys, int aimTurn, long nowNanos) {
        lastKeys = keys;
        log("input");
        return 1;
    }

    @Override public void flush(long nowNanos) { log("flush"); }

    @Override
    public boolean sendChat(CharSequence text, long nowNanos) {
        log("chat:" + text);
        return chatAccepted;
    }

    @Override
    public boolean sendSettings(com.fulcrologic.jxpilot.common.net.Msg.Settings s, long nowNanos) {
        settings.add(s.power + " " + s.turnSpeed + " " + s.turnResistance + " " + s.altPower + " " + s.altTurnSpeed
            + " " + s.altTurnResistance);
        return settingsSent;
    }

    /** Every setModifierBanks call, the four texts joined with '|' (not in {@link #calls}). */
    final List<String> banks = new ArrayList<>();

    @Override
    public void setModifierBanks(String[] b) {
        banks.add(String.join("|", b));
    }

    /** Every sendDisplay call as "width x height" (not in {@link #calls}, so the step order stays as it was). */
    final List<String> displays = new ArrayList<>();

    @Override
    public boolean sendDisplay(int viewWidth, int viewHeight, long nowNanos) {
        displays.add(viewWidth + "x" + viewHeight);
        return true;
    }

    @Override
    public void disconnect(long nowNanos) {
        log("disconnect");
        state = ConnectionState.CLOSED;
    }

    @Override public ConnectionState state() { return state; }

    @Override public CharSequence status() { return status; }

    @Override public long packetsReceived() { return 0; }

    @Override public long packetsSent() { return 0; }

    @Override public long malformed() { return 0; }

    @Override public long snapshotsReceived() { return 0; }

    @Override public void close() { log("close"); }

    private void log(String s) {
        if (record) {
            calls.add(s);
        }
    }
}
