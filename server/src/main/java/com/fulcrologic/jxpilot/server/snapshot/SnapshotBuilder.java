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
package com.fulcrologic.jxpilot.server.snapshot;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.map.FuelStation;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.nio.ByteBuffer;

/**
 * Fills SNAPSHOT bodies for one viewer from the {@link World} with common's {@link SnapshotWriter}.
 * Allocation free; one instance per server thread.
 * <p>
 * Sections in priority order: SELF, SHIP (other playing ships in view), SHOT (grouped by
 * kind/colour relative to the viewer), BURST (bursts made since the viewer's cursor, in view), CANNON,
 * FUEL, and RADAR (whole world, when requested). Objects farther than the view half-size from the viewer
 * (or not encodable, {@link SnapshotWriter#inView}) are culled. When a datagram fills up, the snapshot
 * continues in the next buffer as a new self-contained part (same tick, part numbers 0..n-1, the last
 * flagged {@link SnapshotWriter#FLAG_LAST_PART}); what does not fit in the given buffers is dropped.
 * <p>
 * The header tick is the tick whose simulation produced this state ({@code world.tick - 1}); burst ages
 * are measured from it.
 */
public final class SnapshotBuilder {
    /** Default view half-width/height in pixels (the C client's maximum view is 1024 px wide). */
    public static final double DEFAULT_VIEW_HALF = 640;
    /** Extra margin for bursts, whose particles spread from the origin. */
    public static final double BURST_MARGIN = 400;

    private final World w;
    private final SnapshotWriter wr = new SnapshotWriter();
    private final int[] shotList;
    private final int[] shotVariant;
    private double viewHalfX = DEFAULT_VIEW_HALF;
    private double viewHalfY = DEFAULT_VIEW_HALF;

    // ---- part state
    private ByteBuffer[] parts;
    /** Snapshot part numbers are a u8. */
    static final int MAX_PARTS = 255;
    private final int[] partStart = new int[MAX_PARTS];
    private int maxParts;
    private int part;
    private boolean full;
    private int curType = -1;
    private int curVariant;
    private int tick;
    private int lastInputSeq;
    private double cx;
    private double cy;

    public SnapshotBuilder(World w) {
        this.w = w;
        wr.setWorld(w.pixelWidth, w.pixelHeight, w.wrap);
        shotList = new int[w.objects.capacity];
        shotVariant = new int[w.objects.capacity];
    }

    /** Set the view half-size in pixels (C view size / 2 plus a margin). */
    public void setView(double halfWidth, double halfHeight) {
        viewHalfX = halfWidth;
        viewHalfY = halfHeight;
    }

    /**
     * Build the snapshot of {@code viewer}.
     * <p>
     * BEHAVIOR: writes at each buffer's position (after the packet header) up to its limit; returns the number
     * of parts written (at least 1). Every part has the same tick and the final part count.
     * BEHAVIOR: bursts with sequence numbers in {@code [burstFrom, events.burstSeq())} that are still in the
     * ring are included.
     *
     * @param viewer   the receiving player (view centre = its position)
     * @param parts    preallocated datagram buffers (at least one)
     * @param burstFrom first burst sequence number the viewer has not seen
     * @param radar    include the RADAR section
     */
    public int build(Player viewer, int lastInputSeq, long burstFrom, boolean radar, ByteBuffer[] parts) {
        this.parts = parts;
        this.maxParts = Math.min(parts.length, MAX_PARTS);
        this.tick = w.tick - 1;
        this.lastInputSeq = lastInputSeq;
        this.cx = viewer.cx / (double) Click.CLICK;
        this.cy = viewer.cy / (double) Click.CLICK;
        part = -1;
        full = false;
        curType = -1;
        startPart();
        writeSelf(viewer);
        writeShips(viewer);
        writeShots(viewer);
        writeBursts(burstFrom);
        writeCannons();
        writeFuel();
        if (radar) {
            writeRadar(viewer);
        }
        closeSection();
        int count = part + 1;
        for (int i = 0; i < count; i++) {
            ByteBuffer b = parts[i];
            b.put(partStart[i] + 9, (byte) count);
            b.put(partStart[i] + 10, (byte) (i == count - 1 ? SnapshotWriter.FLAG_LAST_PART : 0));
        }
        return count;
    }

    private void startPart() {
        part++;
        ByteBuffer b = parts[part];
        partStart[part] = b.position();
        wr.begin(b, tick, lastInputSeq, part, 1, 0, cx, cy);
    }

    private void closeSection() {
        if (curType >= 0) {
            wr.endSection();
            curType = -1;
        }
    }

    /**
     * Make sure a section of (type, variant) is open with room for one record, starting a new part if
     * needed. Returns false when all buffers are full (the record is dropped).
     */
    private boolean need(int type, int variant) {
        if (full) {
            return false;
        }
        if (curType == type && curVariant == variant && wr.hasRoom()) {
            return true;
        }
        closeSection();
        if (!wr.beginSection(type, variant)) {
            if (part + 1 >= maxParts) {
                full = true;
                return false;
            }
            startPart();
            if (!wr.beginSection(type, variant)) {
                full = true;
                return false;
            }
        }
        curType = type;
        curVariant = variant;
        return true;
    }

    private boolean visible(double x, double y, double margin) {
        double dx = w.wrapDx(x - cx);
        double dy = w.wrapDy(y - cy);
        return Math.abs(dx) <= viewHalfX + margin && Math.abs(dy) <= viewHalfY + margin && wr.inView(x, y);
    }

    /** BEHAVIOR: the {@code Records.ShipFlags} of a ship as seen by {@code viewer}. */
    int shipFlags(Player pl, Player viewer) {
        int f = 0;
        if ((pl.used & Has.SHIELD) != 0) {
            f |= Records.ShipFlags.SHIELD;
        }
        if ((pl.status & Obj.THRUSTING) != 0) {
            f |= Records.ShipFlags.THRUSTING;
        }
        if ((pl.status & Obj.PLAYING) == 0) {
            f |= Records.ShipFlags.DEAD;
        }
        if ((pl.status & Obj.PAUSE) != 0) {
            f |= Records.ShipFlags.PAUSED;
        }
        if ((pl.status & Obj.SELF_DESTRUCT) != 0) {
            f |= Records.ShipFlags.SELF_DESTRUCT;
        }
        if ((pl.used & Has.REFUEL) != 0) {
            f |= Records.ShipFlags.REFUELING;
        }
        if (pl == viewer || w.sameTeam(pl, viewer)) {
            f |= Records.ShipFlags.FRIENDLY;
        }
        return f;
    }

    private static double heading(Player pl) {
        return pl.dir * (2 * Math.PI / Const.RES);
    }

    private void writeSelf(Player v) {
        if (!need(Section.SELF, 0)) {
            return;
        }
        Records.Self.put(wr, v.id, shipFlags(v, v), (float) cx, (float) cy, (float) v.vx, (float) v.vy,
            (float) heading(v), (float) (v.fuel / 256), (float) (v.fuelMax / 256), Protocol.NO_ID, 0, 0,
            (v.status & Obj.PLAYING) == 0 && v.count > 0 ? (int) Math.ceil(v.count) : 0);
    }

    private void writeShips(Player v) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if (pl == v || !pl.isAlive()) {
                continue;
            }
            double x = pl.cx / (double) Click.CLICK;
            double y = pl.cy / (double) Click.CLICK;
            if (!visible(x, y, 0) || !need(Section.SHIP, 0)) {
                continue;
            }
            Records.Ship.put(wr, pl.id, shipFlags(pl, v), x, y, heading(pl), pl.vx, pl.vy);
        }
    }

    /** BEHAVIOR: the SHOT section variant of a shot relative to the viewer (kind and colour). */
    int shotVariant(int h, Player v) {
        Objects o = w.objects;
        int kind;
        if (o.type[h] == Obj.CANNON_SHOT) {
            kind = Records.ShotKind.CANNON_SHOT;
        } else if ((o.mods[h] & Obj.MOD_CLUSTER) != 0) {
            kind = Records.ShotKind.CLUSTER;
        } else {
            kind = Records.ShotKind.SHOT;
        }
        int color;
        int owner = o.owner[h];
        if (owner == v.id) {
            color = Records.ObjColor.OWN;
        } else if (w.params.teamPlay && o.team[h] == v.team && v.team != Const.TEAM_NOT_SET) {
            color = Records.ObjColor.TEAM;
        } else if (owner == Obj.NO_ID && o.type[h] != Obj.CANNON_SHOT) {
            color = Records.ObjColor.NEUTRAL;
        } else {
            color = Records.ObjColor.ENEMY;
        }
        return Records.ShotKind.variant(kind, color);
    }

    private void writeShots(Player v) {
        Objects o = w.objects;
        int n = 0;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if ((o.type[h] & (Obj.SHOT | Obj.CANNON_SHOT)) == 0 || o.life[h] <= 0) {
                continue;
            }
            if (!visible(o.cx[h] / (double) Click.CLICK, o.cy[h] / (double) Click.CLICK, 0)) {
                continue;
            }
            shotList[n] = h;
            shotVariant[n] = shotVariant(h, v);
            n++;
        }
        // group by variant: repeatedly emit the variant of the first not-yet-written shot
        for (int i = 0; i < n; i++) {
            int var = shotVariant[i];
            if (var < 0) {
                continue;
            }
            for (int j = i; j < n; j++) {
                if (shotVariant[j] != var) {
                    continue;
                }
                shotVariant[j] = -1;
                int h = shotList[j];
                if (need(Section.SHOT, var)) {
                    Records.Shot.put(wr, o.cx[h] / (double) Click.CLICK, o.cy[h] / (double) Click.CLICK, o.vx[h], o.vy[h]);
                }
            }
        }
    }

    private void writeBursts(long from) {
        Events e = w.events;
        long seq = Math.max(from, e.oldestBurstSeq());
        long end = e.burstSeq();
        for (; seq < end; seq++) {
            int i = Events.burstIndex(seq);
            DebrisBurst d = e.burst[i];
            if (!visible(d.x, d.y, BURST_MARGIN) || !need(Section.BURST, 0)) {
                continue;
            }
            Records.Burst.put(wr, d, e.burstKind[i], e.burstColor[i], tick - e.burstTick[i]);
        }
    }

    private void writeCannons() {
        java.util.List<Cannon> cannons = w.map.cannons();
        for (int i = 0; i < cannons.size(); i++) {
            Cannon c = cannons.get(i);
            if (!visible(c.x(), c.y(), 0) || !need(Section.CANNON, 0)) {
                continue;
            }
            Records.CannonState.put(wr, c.index(), w.cannonDeadTime[c.index()] > 0 ? Records.CannonState.FLAG_DEAD : 0);
        }
    }

    private void writeFuel() {
        java.util.List<FuelStation> fuels = w.map.fuels();
        for (int i = 0; i < fuels.size(); i++) {
            FuelStation f = fuels.get(i);
            if (!visible(f.x(), f.y(), 0) || !need(Section.FUEL, 0)) {
                continue;
            }
            Records.FuelState.put(wr, f.index(), w.fuelLevel[f.index()] / (Const.MAX_STATION_FUEL * 256));
        }
    }

    private void writeRadar(Player v) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if (!pl.isAlive() || !need(Section.RADAR, 0)) {
                continue;
            }
            int flags = 0;
            if (pl == v) {
                flags |= Records.Radar.FLAG_SELF;
            }
            if (pl != v && w.sameTeam(pl, v)) {
                flags |= Records.Radar.FLAG_FRIENDLY;
            }
            Records.Radar.put(wr, pl.cx / (double) Click.CLICK, pl.cy / (double) Click.CLICK, flags);
        }
    }
}
