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

import com.fulcrologic.jxpilot.client.net.ConnectionListener;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.ReliableType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Listener spy: records every call, decoding reliable messages to readable lines with the native Msg
 * codecs (so tests assert what the client model will see).
 */
final class Recorder implements ConnectionListener, XpReliableDecoder.Control {
    final List<String> events = new ArrayList<>();
    final List<byte[]> snapshots = new ArrayList<>();
    final List<Long> snapshotTimes = new ArrayList<>();
    final List<String> states = new ArrayList<>();
    Msg.Welcome welcome;
    WorldMap map;
    Options options;

    @Override
    public void onAccepted(int playerId) {
        events.add("accepted " + playerId);
    }

    @Override
    public void onWelcome(Msg.Welcome w) {
        events.add("welcome simHz=" + w.simHz + " snapHz=" + w.snapshotHz + " fps=" + (int) w.gameFps + " flags="
            + w.flags + " server=" + w.serverName);
        welcome = w;
    }

    @Override
    public void onConfig(Msg.Welcome w, Options o, WorldMap m) {
        events.add("config " + m.name() + " " + m.width() + "x" + m.height());
        map = m;
        options = o;
    }

    @Override
    public void onReliable(int type, ByteBuffer p) {
        if (type == ReliableType.PLAYER_JOIN) {
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.read(p.duplicate());
            identities.add(j.id + " " + j.realName + "@" + j.hostName);
        }
        events.add(describe(type, p));
    }

    /** Every PLAYER_JOIN's real name and host as "id real@host" (kept apart from {@link #events}). */
    final List<String> identities = new ArrayList<>();

    static String describe(int type, ByteBuffer p) {
        return switch (type) {
            case ReliableType.PLAYER_JOIN -> {
                Msg.PlayerJoin j = new Msg.PlayerJoin();
                j.read(p);
                yield "join id=" + j.id + " team=" + j.team + " flags=" + j.flags + " name=" + j.name + " shape="
                    + j.shape;
            }
            case ReliableType.PLAYER_LEAVE -> {
                Msg.PlayerLeave l = new Msg.PlayerLeave();
                l.read(p);
                yield "leave id=" + l.id;
            }
            case ReliableType.SCORE -> {
                Msg.Score s = new Msg.Score();
                s.read(p);
                yield "score id=" + s.id + " score=" + s.score + " lives=" + s.lives + " status=" + s.status;
            }
            case ReliableType.PLAYER_ALLIANCE -> {
                Msg.PlayerAlliance a = new Msg.PlayerAlliance();
                a.read(p);
                yield "alliance id=" + a.id + " mark=" + a.mark;
            }
            case ReliableType.TEAM_SCORE -> {
                Msg.TeamScore s = new Msg.TeamScore();
                s.read(p);
                yield "teamscore team=" + s.team + " score=" + s.score;
            }
            case ReliableType.MESSAGE -> {
                Msg.Text t = new Msg.Text();
                t.read(p);
                yield "message kind=" + t.kind + " from=" + t.from + " " + t.text;
            }
            case ReliableType.MOTD -> {
                int off = p.getInt();
                int total = p.getInt();
                byte[] t = new byte[p.remaining()];
                p.get(t);
                yield "motd " + off + "/" + total + " " + new String(t, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            case ReliableType.TIMING -> {
                Msg.Timing t = new Msg.Timing();
                t.read(p);
                yield "timing id=" + t.id + " check=" + t.check + " round=" + t.round;
            }
            case ReliableType.SCORE_OBJECT -> {
                Msg.ScoreObject o = new Msg.ScoreObject();
                o.read(p);
                yield "scoreobject score=" + o.score + " at=" + o.bx + "," + o.by + " label=" + o.text;
            }
            case ReliableType.BASE_OWNER -> {
                Msg.BaseOwner b = new Msg.BaseOwner();
                b.read(p);
                yield "base index=" + b.baseIndex + " player=" + b.playerId;
            }
            case ReliableType.WAR -> {
                Msg.War w = new Msg.War();
                w.read(p);
                yield "war robot=" + w.robotId + " killer=" + w.killerId;
            }
            case ReliableType.SEEK -> {
                Msg.Seek k = new Msg.Seek();
                k.read(p);
                yield "seek programmer=" + k.programmerId + " robot=" + k.robotId + " sought=" + k.soughtId;
            }
            default -> "reliable " + type;
        };
    }

    @Override
    public void onSnapshot(ByteBuffer body, long nowNanos) {
        byte[] a = new byte[body.remaining()];
        body.get(body.position(), a);
        snapshots.add(a);
        snapshotTimes.add(nowNanos);
    }

    @Override
    public void onStateChange(ConnectionState state, CharSequence status) {
        states.add(state + ": " + status);
    }

    // ---------------------------------------------------------------- Control spy
    @Override
    public void reply(int replyTo, int result) {
        events.add("reply " + replyTo + " " + result);
    }

    @Override
    public void magic(int magic) {
        events.add("magic " + Integer.toHexString(magic));
    }

    @Override
    public void quit(CharSequence reason) {
        events.add("quit " + reason);
    }

    @Override
    public void talkAck(int seq) {
        events.add("talkack " + seq);
    }
}
