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

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;

import java.nio.ByteBuffer;

/**
 * A {@link ClientGame} driven through its listener API exactly as a connection drives it: a 20 x 10
 * block world (700 x 350 px, wrapping), 14 fps with one snapshot per tick, the own player {@link #SELF}
 * on team 1, {@link #MATE} (team 1), {@link #FOE} (team 2). Snapshots are built with the real
 * {@link SnapshotWriter}. Times: tick {@link #T0_TICK} arrives at {@link #T0}; {@link #update()} runs the
 * world 0.1 s later (estimated server tick 1.4 ahead, render tick 0.12 after the newest snapshot).
 */
final class AppFixture {
    static final long T0 = 5_000_000_000L;
    static final int T0_TICK = 100;
    static final int SELF = 7;
    static final int MATE = 9;
    static final int FOE = 11;
    static final String SHAPE = "(SH: 15,0 -9,8 -9,-8)(GU: 15,0)(EN: -9,0)";

    final Options options = new Options();
    final WorldMap map;
    final Msg.Welcome welcome = new Msg.Welcome();
    final ClientGame game = new ClientGame(16, 64, 4096, 64);
    final ByteBuffer buf = ByteBuffer.allocate(Protocol.MAX_PACKET);
    final ByteBuffer rel = ByteBuffer.allocate(512);
    final SnapshotWriter w = new SnapshotWriter();

    AppFixture(boolean teamPlay, String extraOptions) {
        String cfg = "mapName: Fixture\nmapWidth: 20\nmapHeight: 10\nedgeWrap: yes\nframesPerSecond: 14\n"
            + "limitedLives: yes\n" + extraOptions
            + "mapData: \\multiline: E\n"
            + "                    \n"
            + "  _    d     #   !  \n"
            + "         *          \n"
            + "                    \n".repeat(7)
            + "E\n";
        ConfigParser.parse(cfg, "fixture", OptionOrigin.MAP, options);
        map = WorldMap.fromOptions(options);
        welcome.playerId = SELF;
        welcome.simHz = 14;
        welcome.snapshotHz = 14;
        welcome.gameFps = 14;
        welcome.flags = Msg.Welcome.FLAG_EDGE_WRAP | (teamPlay ? Msg.Welcome.FLAG_TEAM_PLAY : 0);
        game.onAccepted(SELF);
        game.onWelcome(welcome);
        game.onConfig(welcome, options, map);
        w.setWorld(map.pixelWidth(), map.pixelHeight(), true);
    }

    AppFixture(boolean teamPlay) {
        this(teamPlay, "");
    }

    /** The three players: self (team 1, custom shape), mate (team 1), foe (team 2, default ship). */
    AppFixture players() {
        join(SELF, 1, Msg.PlayerJoin.FLAG_SELF, "Me", SHAPE);
        join(MATE, 1, 0, "Mate", SHAPE);
        join(FOE, 2, Msg.PlayerJoin.FLAG_ROBOT, "Foe", "");
        return this;
    }

    AppFixture join(int id, int team, int flags, String name, String shape) {
        Msg.PlayerJoin j = new Msg.PlayerJoin();
        j.id = id;
        j.team = team;
        j.flags = flags;
        j.name.append(name);
        j.shape.append(shape);
        j.power = 45;
        rel.clear();
        j.write(rel);
        rel.flip();
        game.onReliable(ReliableType.PLAYER_JOIN, rel);
        return this;
    }

    AppFixture score(int id, float score, int lives) {
        Msg.Score s = new Msg.Score();
        s.id = id;
        s.score = score;
        s.lives = lives;
        rel.clear();
        s.write(rel);
        rel.flip();
        game.onReliable(ReliableType.SCORE, rel);
        return this;
    }

    AppFixture scoreObject(float points, int bx, int by, String label) {
        Msg.ScoreObject o = new Msg.ScoreObject();
        o.score = points;
        o.bx = bx;
        o.by = by;
        o.text.append(label);
        rel.clear();
        o.write(rel);
        rel.flip();
        game.onReliable(ReliableType.SCORE_OBJECT, rel);
        return this;
    }

    AppFixture event(int kind, int a, int b) {
        Msg.GameEvent e = new Msg.GameEvent();
        e.kind = kind;
        e.a = a;
        e.b = b;
        rel.clear();
        e.write(rel);
        rel.flip();
        game.onReliable(ReliableType.GAME_EVENT, rel);
        return this;
    }

    AppFixture begin(int tick) {
        buf.clear();
        w.begin(buf, tick, 0, 0, 1, SnapshotWriter.FLAG_LAST_PART, 350, 175);
        return this;
    }

    AppFixture self(int flags, float x, float y, float heading, int lockId) {
        return self(flags, x, y, 0, 0, heading, lockId);
    }

    AppFixture self(int flags, float x, float y, float vx, float vy, float heading, int lockId) {
        w.beginSection(Section.SELF);
        Records.Self.put(w, SELF, flags, x, y, vx, vy, heading, 700, 1000, lockId, 0, 0, 0);
        w.endSection();
        return this;
    }

    /** A SELF at (350, 175) with the appended fields: power, turn speed, lock distance/direction and modifiers. */
    AppFixture selfControls(int power, int turnSpeed, int lockId, int lockDist, int lockDir, int mods) {
        w.beginSection(Section.SELF);
        Records.Self.put(w, SELF, 0, 350, 175, 0, 0, 0, 700, 1000, lockId, 0, 0, 0, power, turnSpeed, 0.12,
            lockDist, lockDir, 0, mods);
        w.endSection();
        return this;
    }

    /** A TIMERS record with every meter. */
    AppFixture timers(int destruct, int thrustLeft, int thrustMax, int shieldLeft, int shieldMax, int phasingLeft,
                      int phasingMax, int shutdownCount, int shutdownDelay) {
        w.beginSection(Section.TIMERS);
        Records.Timers.put(w, 0, 0, -1, destruct, thrustLeft, thrustMax, shieldLeft, shieldMax, phasingLeft, phasingMax,
            shutdownCount, shutdownDelay);
        w.endSection();
        return this;
    }

    AppFixture connector(double x0, double y0, double x1, double y1, int kind) {
        w.beginSection(Section.CONNECTOR);
        Records.Connector.put(w, x0, y0, x1, y1, kind);
        w.endSection();
        return this;
    }

    AppFixture ship(int id, int flags, double x, double y, double heading) {
        w.beginSection(Section.SHIP);
        Records.Ship.put(w, id, flags, x, y, heading, 0, 0);
        w.endSection();
        return this;
    }

    AppFixture shot(int kind, int color, double x, double y) {
        return shot(kind, color, false, x, y);
    }

    AppFixture shot(int kind, int color, boolean harmless, double x, double y) {
        w.beginSection(Section.SHOT, Records.ShotKind.variant(kind, color, harmless));
        Records.Shot.put(w, x, y, 0, 0);
        w.endSection();
        return this;
    }

    AppFixture radar(double x, double y, int flags) {
        w.beginSection(Section.RADAR);
        Records.Radar.put(w, x, y, flags);
        w.endSection();
        return this;
    }

    AppFixture items(int... counts) {
        w.beginSection(Section.SELF_ITEMS);
        Records.SelfItems.put(w, counts);
        w.endSection();
        return this;
    }

    AppFixture cannon(int index, int flags) {
        w.beginSection(Section.CANNON);
        Records.CannonState.put(w, index, flags);
        w.endSection();
        return this;
    }

    AppFixture fuel(int index, double frac) {
        w.beginSection(Section.FUEL);
        Records.FuelState.put(w, index, frac);
        w.endSection();
        return this;
    }

    AppFixture target(int index, int flags, double left) {
        w.beginSection(Section.TARGET);
        Records.TargetState.put(w, index, flags, left);
        w.endSection();
        return this;
    }

    AppFixture burst(int color) {
        DebrisBurst d = new DebrisBurst();
        d.x = 300;
        d.y = 100;
        d.seed = 777;
        d.count = 12;
        d.minDir = 10;
        d.dirSpan = 40;
        d.minSpeed = 1;
        d.maxSpeed = 5;
        d.minLife = 8;
        d.maxLife = 20;
        Records.Burst.quantize(d);
        w.beginSection(Section.BURST);
        Records.Burst.put(w, d, Records.BurstKind.DEBRIS, color, 0);
        w.endSection();
        return this;
    }

    /** Deliver the snapshot built since {@link #begin} at the local time of its tick. */
    AppFixture send(int tick) {
        buf.flip();
        game.onSnapshot(buf, T0 + Math.round((tick - T0_TICK) / 14.0 * 1e9));
        return this;
    }

    /** Run the game frame 0.1 s after tick {@link #T0_TICK} + 1 arrived. */
    AppFixture update() {
        game.frame(T0 + Math.round(1e9 / 14) + 100_000_000L, 0);
        return this;
    }

    /** Two identical snapshots (ticks 100 and 101) built by {@code body}, then {@link #update()}. */
    AppFixture twice(java.util.function.Consumer<AppFixture> body) {
        for (int t = T0_TICK; t <= T0_TICK + 1; t++) {
            begin(t);
            body.accept(this);
            send(t);
        }
        return update();
    }
}
