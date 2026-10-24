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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;

import java.nio.ByteBuffer;

/**
 * Test world: 20 x 10 blocks (700 x 350 px), framesPerSecond 15 and simHz 60, so one sim tick is
 * exactly 0.25 original frames; snapshots at 30 Hz (interval 2 ticks). Tick {@link #T0_TICK} arrives
 * at {@link #T0}; {@link #at(double)} converts a server tick to the matching local time.
 */
final class WorldFixture {
    static final long T0 = 1_000_000_000L;
    static final int T0_TICK = 100;
    static final double W = 700;
    static final double H = 350;

    final Options options = new Options();
    final WorldMap map;
    final Msg.Welcome welcome = new Msg.Welcome();
    final Players players = new Players();
    final ClientWorld world;
    final ByteBuffer buf = ByteBuffer.allocate(Protocol.MAX_PACKET);
    final SnapshotWriter w = new SnapshotWriter();

    WorldFixture(boolean wrap) {
        String cfg = "mapWidth: 20\nmapHeight: 10\nedgeWrap: " + (wrap ? "yes" : "no")
            + "\nframesPerSecond: 15\nshotLife: 60\nmapData: \\multiline: E\n"
            + "                    \n"
            + "  _    d     #   !  \n"
            + "         *          \n"
            + "                    \n".repeat(7)
            + "E\n";
        ConfigParser.parse(cfg, "fixture", OptionOrigin.MAP, options);
        map = WorldMap.fromOptions(options);
        welcome.simHz = 60;
        welcome.snapshotHz = 30;
        welcome.gameFps = 15;
        world = new ClientWorld(players, 16, 16, 4096, 64);
        world.configure(map, options, welcome);
        w.setWorld(W, H, wrap);
    }

    /** Local time at which the estimated server tick equals {@code tick} (after the first sample). */
    static long at(double tick) {
        return T0 + Math.round((tick - T0_TICK) / 60.0 * 1e9);
    }

    WorldFixture begin(int tick) {
        return begin(tick, 0, 1);
    }

    WorldFixture begin(int tick, int part, int parts) {
        return beginView(tick, part, parts, 350, 175);
    }

    WorldFixture beginView(int tick, int part, int parts, double viewX, double viewY) {
        buf.clear();
        w.begin(buf, tick, 0, part, parts, 0, viewX, viewY);
        return this;
    }

    WorldFixture target(int index, int flags, double left) {
        w.beginSection(Section.TARGET);
        Records.TargetState.put(w, index, flags, left);
        w.endSection();
        return this;
    }

    WorldFixture treasure(int index, int flags) {
        w.beginSection(Section.TREASURE);
        Records.TreasureState.put(w, index, flags);
        w.endSection();
        return this;
    }

    WorldFixture self(int id, int flags, float x, float y, float vx, float vy, float heading) {
        w.beginSection(Section.SELF);
        Records.Self.put(w, id, flags, x, y, vx, vy, heading, 700, 1000, Protocol.NO_ID, 2, 1, 0);
        w.endSection();
        return this;
    }

    WorldFixture ship(int id, int flags, double x, double y, double heading, double vx, double vy) {
        w.beginSection(Section.SHIP);
        Records.Ship.put(w, id, flags, x, y, heading, vx, vy);
        w.endSection();
        return this;
    }

    WorldFixture shot(double x, double y, double vx, double vy) {
        w.beginSection(Section.SHOT, Records.ShotKind.variant(Records.ShotKind.CANNON_SHOT, Records.ObjColor.TEAM));
        Records.Shot.put(w, x, y, vx, vy);
        w.endSection();
        return this;
    }

    WorldFixture shot(int variant, double x, double y) {
        w.beginSection(Section.SHOT, variant);
        Records.Shot.put(w, x, y, 0, 0);
        w.endSection();
        return this;
    }

    WorldFixture cannon(int index, int flags) {
        w.beginSection(Section.CANNON);
        Records.CannonState.put(w, index, flags);
        w.endSection();
        return this;
    }

    WorldFixture fuel(int index, double frac) {
        w.beginSection(Section.FUEL);
        Records.FuelState.put(w, index, frac);
        w.endSection();
        return this;
    }

    WorldFixture radar(double x, double y, int flags) {
        w.beginSection(Section.RADAR);
        Records.Radar.put(w, x, y, flags);
        w.endSection();
        return this;
    }

    WorldFixture burst(DebrisBurst d, int kind, int color, int age) {
        w.beginSection(Section.BURST);
        Records.Burst.put(w, d, kind, color, age);
        w.endSection();
        return this;
    }

    WorldFixture items(int... counts) {
        w.beginSection(Section.SELF_ITEMS);
        Records.SelfItems.put(w, counts);
        w.endSection();
        return this;
    }

    WorldFixture sound(int event, int volume) {
        w.beginSection(Section.SOUND);
        Records.Sound.put(w, event, volume);
        w.endSection();
        return this;
    }

    /** Ingest the built snapshot at local time {@code now}. */
    int ingest(long now) {
        buf.flip();
        return world.ingest(buf, now);
    }

    /** Ingest at the time matching the snapshot's own tick. */
    int ingestAtTick(int tick) {
        return ingest(at(tick));
    }

    static DebrisBurst burst() {
        DebrisBurst d = new DebrisBurst();
        d.x = 300;
        d.y = 100;
        d.vx = 1;
        d.vy = -2;
        d.seed = 777;
        d.count = 12;
        d.minDir = 10;
        d.dirSpan = 40;
        d.minSpeed = 1;
        d.maxSpeed = 5;
        d.minLife = 8;
        d.maxLife = 20;
        Records.Burst.quantize(d);
        return d;
    }
}
