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
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;

import java.nio.ByteBuffer;

/**
 * Maps C server state into the protocol-neutral client model: every decoded {@link XpFrame} becomes
 * one JXPilot SNAPSHOT body (docs/PROTOCOL.md; one tick per C frame, so the client interpolates at the
 * server's framesPerSecond) and every reliable C event becomes the JXPilot reliable message the
 * {@link ConnectionListener} (normally {@link com.fulcrologic.jxpilot.client.game.ClientGame}) already
 * understands. Painters, HUD and input therefore work unchanged on a C server.
 * <p>
 * C debris and spark particles (positions only, re-sent every frame) become one-frame, zero-speed
 * BURST records of one particle each; fast shots become SHOT records. Ship velocities, which C does
 * not send, are derived from consecutive frames for extrapolation. Allocation free after
 * {@link #configure}.
 */
public final class XpTranslator {
    /** Snapshot body capacity: far above the worst case of a 4 KB C datagram (27 B per particle). */
    public static final int SNAPSHOT_CAPACITY = 1 << 18;
    /** Players remembered for C's client-side leave/war/seek messages. */
    public static final int NAME_CAP = 256;
    /** A ship moving farther than this between frames gets no derived velocity (respawn, wrap jump). */
    public static final int TELEPORT_PX = 10 * XpProto.BLOCK_SZ;

    private static final double RAD_PER_DIR = 2 * Math.PI / XpProto.RES;

    private final ConnectionListener listener;
    private final ByteBuffer snap = ByteBuffer.allocate(SNAPSHOT_CAPACITY);
    private final SnapshotWriter w = new SnapshotWriter();
    private final ByteBuffer pay = ByteBuffer.allocate(2048);
    private final DebrisBurst burst = new DebrisBurst();
    private final int[] selfItems = new int[XpProto.NUM_ITEMS];

    private final Msg.PlayerJoin join = new Msg.PlayerJoin();
    private final Msg.PlayerLeave leave = new Msg.PlayerLeave();
    private final Msg.Score score = new Msg.Score();
    private final Msg.TeamScore teamScore = new Msg.TeamScore();
    private final Msg.Text text = new Msg.Text();
    private final Msg.BaseOwner baseOwner = new Msg.BaseOwner();

    // configuration
    private XpMap.Built map;
    private double worldW = 65536;
    private double worldH = 65536;
    private boolean wrap;
    private boolean limitedLives;
    private int version = XpProto.MY_VERSION;
    private int[] cBaseOwner = new int[0];

    // players (for C's client-generated messages)
    private final int[] nameId = new int[NAME_CAP];
    private final StringBuilder[] names = new StringBuilder[NAME_CAP];
    private final char[] nameChar = new char[NAME_CAP];
    private int nameCount;
    private int selfId = Protocol.NO_ID;
    private boolean expectSelf;

    // previous frame, for derived ship velocities
    private final int[] prevId = new int[XpFrame.SHIP_CAP];
    private final int[] prevX = new int[XpFrame.SHIP_CAP];
    private final int[] prevY = new int[XpFrame.SHIP_CAP];
    private int prevCount;
    private int prevLoops;
    private double viewX;
    private double viewY;
    private int viewW = XpProto.DEF_VIEW_SIZE;
    private int viewH = XpProto.DEF_VIEW_SIZE;
    private int originX;
    private int originY;

    // statistics
    private long snapshots;
    private long culled;
    private long badIndex;

    public XpTranslator(ConnectionListener listener) {
        this.listener = listener;
        for (int i = 0; i < NAME_CAP; i++) {
            names[i] = new StringBuilder(XpProto.MAX_CHARS);
        }
        // final sizes up front, so messages never grow them while playing
        text.text.ensureCapacity(2 * XpProto.MSG_LEN);
        join.name.ensureCapacity(XpProto.MAX_CHARS);
        join.shape.ensureCapacity(2 * XpProto.MSG_LEN);
    }

    /**
     * Use a freshly built map (after the setup).
     * <p>
     * BEHAVIOR: world size and wrap from the map, limited lives from the setup mode, no base owners,
     * no players, no previous frame.
     */
    public void configure(XpMap.Built built, int setupMode, int protocolVersion) {
        map = built;
        worldW = built.map().pixelWidth();
        worldH = built.map().pixelHeight();
        wrap = built.map().edgeWrap();
        w.setWorld(worldW, worldH, wrap);
        limitedLives = (setupMode & XpProto.MODE_LIMITED_LIVES) != 0;
        version = protocolVersion;
        cBaseOwner = new int[built.baseIndex().length];
        java.util.Arrays.fill(cBaseOwner, Protocol.NO_ID);
        nameCount = 0;
        selfId = Protocol.NO_ID;
        expectSelf = false;
        prevCount = 0;
        viewX = worldW / 2;
        viewY = worldH / 2;
    }

    /** The next PKT_PLAYER is the client itself (the C server sends it first after the play reply). */
    public void expectSelf() {
        expectSelf = true;
    }

    /** Own C player id, {@link Protocol#NO_ID} until known. */
    public int selfId() { return selfId; }

    public long snapshots() { return snapshots; }

    /** Objects outside the encodable range around the view centre (should stay 0). */
    public long culled() { return culled; }

    /** Map object updates with a C number outside the map's lists (should stay 0). */
    public long badIndex() { return badIndex; }

    /** C protocol version in use (affects score formats). */
    public int version() { return version; }

    // ================================================================ frames

    /**
     * Encode a complete frame as one SNAPSHOT body and pass it to the listener.
     * <p>
     * BEHAVIOR: tick = C frame number, one part; the view centre is the own ship (else the last one).
     * BEHAVIOR: sections: SELF (with PKT_SELF), SHIP (ships, paused ships), SHOT (fast shots by colour),
     * BURST (debris), CANNON/FUEL/TARGET (updates, C numbers mapped to WorldMap indices), RADAR,
     * SELF_ITEMS (with PKT_SELF) and the M2 object sections.
     * BEHAVIOR: does nothing before {@link #configure}.
     */
    public void frame(XpFrame f, long nowNanos) {
        if (map == null) {
            return;
        }
        if (f.hasSelf) {
            viewX = wrapX(f.selfX);
            viewY = wrapY(f.selfY);
            viewW = f.viewWidth;
            viewH = f.viewHeight;
            originX = f.selfX - viewW / 2;
            originY = f.selfY - viewH / 2;
        }
        snap.clear();
        w.begin(snap, f.loops, 0, 0, 1, SnapshotWriter.FLAG_LAST_PART, viewX, viewY);
        int selfShip = selfId == Protocol.NO_ID ? -1 : f.findShip(selfId);
        if (f.hasSelf) {
            writeSelf(f, selfShip);
        }
        writeShips(f);
        writeShots(f);
        writeDebris(f);
        writeMapState(f);
        writeRadar(f, selfShip);
        if (f.hasSelf && w.beginSection(Section.SELF_ITEMS)) {
            System.arraycopy(f.items, 0, selfItems, 0, XpProto.NUM_ITEMS);
            Records.SelfItems.put(w, selfItems);
            w.endSection();
        }
        writeObjects(f);
        rememberShips(f);
        snap.flip();
        snapshots++;
        listener.onSnapshot(snap, nowNanos);
    }

    private double wrapX(double x) {
        return wrap ? XMath.wrap(x, worldW) : x;
    }

    private double wrapY(double y) {
        return wrap ? XMath.wrap(y, worldH) : y;
    }

    private boolean visible(double x, double y) {
        if (w.inView(x, y)) {
            return true;
        }
        culled++;
        return false;
    }

    /**
     * BEHAVIOR: flags from the own PKT_SHIP (shield, cloak...), DEAD when the own ship is not in the
     * frame (C {@code selfVisible}), PAUSED / SELF_DESTRUCT from the status byte; fuel in C units.
     */
    private void writeSelf(XpFrame f, int selfShip) {
        if (!w.beginSection(Section.SELF)) {
            return;
        }
        int flags = selfShip >= 0 ? shipFlags(f.shipFlags[selfShip]) : Records.ShipFlags.DEAD;
        if ((f.selfStat & XpProto.STATUS_PAUSE) != 0) {
            flags |= Records.ShipFlags.PAUSED;
        }
        if ((f.selfStat & XpProto.STATUS_SELF_DESTRUCT) != 0 || f.destruct > 0) {
            flags |= Records.ShipFlags.SELF_DESTRUCT;
        }
        if (f.autopilotLight != 0) {
            flags |= Records.ShipFlags.AUTOPILOT;
        }
        Records.Self.put(w, selfId & 0xFFFF, flags, (float) viewX, (float) viewY, f.selfVx, f.selfVy,
            (float) (f.selfDir * RAD_PER_DIR), f.selfFuelSum, f.selfFuelMax,
            f.selfLockId < 0 ? Protocol.NO_ID : f.selfLockId, f.items[XpProto.ITEM_TANK], f.selfCurrentTank, 0);
        w.endSection();
    }

    /** C PKT_SHIP flag bits to {@link Records.ShipFlags}. */
    static int shipFlags(int c) {
        int fl = 0;
        if ((c & 1) != 0) {
            fl |= Records.ShipFlags.SHIELD;
        }
        if ((c & 2) != 0) {
            fl |= Records.ShipFlags.CLOAKED;
        }
        if ((c & 4) != 0) {
            fl |= Records.ShipFlags.EMERGENCY_SHIELD;
        }
        if ((c & 8) != 0) {
            fl |= Records.ShipFlags.PHASING;
        }
        if ((c & 16) != 0) {
            fl |= Records.ShipFlags.DEFLECTOR;
        }
        return fl;
    }

    /**
     * BEHAVIOR: one SHIP record per PKT_SHIP with the derived velocity; a PKT_REFUEL ending at a ship
     * marks it REFUELING, one starting at a ship marks it REPAIRING; paused ships (no id on the wire)
     * are SHIP records with id NO_ID and PAUSED.
     */
    private void writeShips(XpFrame f) {
        if (f.ships + f.paused == 0 || !w.beginSection(Section.SHIP)) {
            return;
        }
        for (int i = 0; i < f.ships; i++) {
            double x = wrapX(f.shipX[i]);
            double y = wrapY(f.shipY[i]);
            if (!visible(x, y)) {
                continue;
            }
            int flags = shipFlags(f.shipFlags[i]) | refuelFlags(f, f.shipX[i], f.shipY[i]);
            Records.Ship.put(w, f.shipId[i] & 0xFFFF, flags, x, y, f.shipDir[i] * RAD_PER_DIR,
                velocity(f, i, true), velocity(f, i, false));
        }
        for (int i = 0; i < f.paused; i++) {
            double x = wrapX(f.pausedX[i]);
            double y = wrapY(f.pausedY[i]);
            if (visible(x, y)) {
                Records.Ship.put(w, Protocol.NO_ID, Records.ShipFlags.PAUSED, x, y, 0, 0, 0);
            }
        }
        w.endSection();
    }

    private static int refuelFlags(XpFrame f, int x, int y) {
        int fl = 0;
        for (int k = 0; k < f.refuels; k++) {
            if (f.refuelX1[k] == x && f.refuelY1[k] == y) {
                fl |= Records.ShipFlags.REFUELING;
            } else if (f.refuelX0[k] == x && f.refuelY0[k] == y) {
                fl |= Records.ShipFlags.REPAIRING;
            }
        }
        return fl;
    }

    /**
     * BEHAVIOR: (position now - position in the previous frame) / frames between them, wrap-aware;
     * 0 for a ship not in the previous frame or one that moved more than {@link #TELEPORT_PX}.
     */
    private double velocity(XpFrame f, int i, boolean xAxis) {
        int frames = f.loops - prevLoops;
        if (frames <= 0) {
            return 0;
        }
        int id = f.shipId[i];
        for (int k = 0; k < prevCount; k++) {
            if (prevId[k] == id) {
                double dx = XMath.wrapDelta(f.shipX[i] - prevX[k], worldW, wrap);
                double dy = XMath.wrapDelta(f.shipY[i] - prevY[k], worldH, wrap);
                if (dx * dx + dy * dy > (double) TELEPORT_PX * TELEPORT_PX) {
                    return 0;
                }
                return (xAxis ? dx : dy) / frames;
            }
        }
        return 0;
    }

    private void rememberShips(XpFrame f) {
        prevCount = f.ships;
        for (int i = 0; i < f.ships; i++) {
            prevId[i] = f.shipId[i];
            prevX[i] = f.shipX[i];
            prevY[i] = f.shipY[i];
        }
        prevLoops = f.loops;
    }

    /** C colour to {@link Records.ObjColor}: BEHAVIOR: blue and red keep their colour, the rest is white. */
    static int objColor(int c) {
        return switch (c) {
            case XpProto.BLUE -> Records.ObjColor.BLUE;
            case XpProto.RED -> Records.ObjColor.RED;
            default -> Records.ObjColor.WHITE;
        };
    }

    private int xAreas() {
        return (viewW + 255) >> 8;
    }

    private int yAreas() {
        return (viewH + 255) >> 8;
    }

    /**
     * World x of a debris/fast shot byte (C {@code BASE_X} with the view origin of PKT_SELF).
     * <p>
     * BEHAVIOR: area column {@code i % xAreas} adds 256 px per column.
     */
    private double particleX(int i, int xa, int px) {
        return wrapX(originX + ((i % xa) << 8) + px);
    }

    /** BEHAVIOR: area row {@code (i / xAreas) % yAreas} adds 256 px per row (y up, as the server). */
    private double particleY(int i, int xa, int ya, int py) {
        return wrapY(originY + (((i / xa) % ya) << 8) + py);
    }

    /**
     * BEHAVIOR: fast shots (C {@code Paint_fastshots}/{@code Paint_teamshots}): normal shots that are
     * neither white nor blue are drawn white; team shots keep their colour. One SHOT section per
     * resulting colour, velocity 0 (C sends none).
     */
    private void writeShots(XpFrame f) {
        if (f.fastshots == 0) {
            return;
        }
        int xa = xAreas();
        int ya = yAreas();
        int areas = xa * ya;
        for (int col = Records.ObjColor.RED; col <= Records.ObjColor.WHITE; col++) {
            boolean open = false;
            for (int k = 0; k < f.fastshots; k++) {
                int t = f.fastshotType[k];
                boolean team = t >= XpProto.DEBRIS_TYPES;
                int i = team ? t - XpProto.DEBRIS_TYPES : t;
                int c = i / areas;
                if (!team && c != XpProto.WHITE && c != XpProto.BLUE) {
                    c = XpProto.WHITE;
                }
                if (objColor(c) != col) {
                    continue;
                }
                double x = particleX(i, xa, f.fastshotX[k] & 0xFF);
                double y = particleY(i, xa, ya, f.fastshotY[k] & 0xFF);
                if (!visible(x, y)) {
                    continue;
                }
                if (!open) {
                    if (!w.beginSection(Section.SHOT, Records.ShotKind.variant(Records.ShotKind.SHOT, col))) {
                        return;
                    }
                    open = true;
                }
                Records.Shot.put(w, x, y, 0, 0);
            }
            if (open) {
                w.endSection();
            }
        }
    }

    /**
     * BEHAVIOR: each debris/spark particle is a BURST of one particle with no speed and one frame of
     * life at its C position (C redraws all particles every frame). Colour: the object colour when the
     * client asked for fewer than 3 debris colours, else the temperature level as
     * {@code ClientWorld.POINT_LEVEL_BASE + level} (C's debris colour index).
     */
    private void writeDebris(XpFrame f) {
        if (f.debris == 0 || !w.beginSection(Section.BURST)) {
            return;
        }
        int xa = xAreas();
        int ya = yAreas();
        int areas = xa * ya;
        burst.vx = 0;
        burst.vy = 0;
        burst.count = 1;
        burst.minDir = 0;
        burst.dirSpan = 0;
        burst.minSpeed = 0;
        burst.maxSpeed = 0;
        burst.minLife = 0;
        burst.maxLife = 0;
        burst.seed = 0;
        for (int k = 0; k < f.debris; k++) {
            int i = f.debrisType[k];
            int c = i / areas;
            double x = particleX(i, xa, f.debrisX[k] & 0xFF);
            double y = particleY(i, xa, ya, f.debrisY[k] & 0xFF);
            if (!visible(x, y)) {
                continue;
            }
            burst.x = (float) x;
            burst.y = (float) y;
            int color = f.debrisColors >= 3 ? com.fulcrologic.jxpilot.client.game.ClientWorld.POINT_LEVEL_BASE + c
                : objColor(c);
            Records.Burst.put(w, burst, Records.BurstKind.SPARK, color, 0);
        }
        w.endSection();
    }

    /**
     * BEHAVIOR: cannon dead time &gt; 0 is FLAG_DEAD; fuel units / 500; target dead time &gt; 0 is
     * FLAG_DEAD with damage / TARGET_DAMAGE left. C numbers without a WorldMap object are counted in
     * {@link #badIndex()} and skipped.
     */
    private void writeMapState(XpFrame f) {
        if (f.cannonUpdates > 0 && w.beginSection(Section.CANNON)) {
            for (int k = 0; k < f.cannonUpdates; k++) {
                int idx = mapIndex(map.cannonIndex(), f.cannonNum[k]);
                if (idx >= 0) {
                    Records.CannonState.put(w, idx, f.cannonDeadTime[k] > 0 ? Records.CannonState.FLAG_DEAD : 0);
                }
            }
            w.endSection();
        }
        if (f.fuelUpdates > 0 && w.beginSection(Section.FUEL)) {
            for (int k = 0; k < f.fuelUpdates; k++) {
                int idx = mapIndex(map.fuelIndex(), f.fuelNum[k]);
                if (idx >= 0) {
                    double frac = Math.min(1.0, f.fuelUnits[k] / (double) XpProto.MAX_STATION_FUEL_UNITS);
                    Records.FuelState.put(w, idx, frac);
                }
            }
            w.endSection();
        }
        if (f.targetUpdates > 0 && w.beginSection(Section.TARGET)) {
            for (int k = 0; k < f.targetUpdates; k++) {
                int idx = mapIndex(map.targetIndex(), f.targetNum[k]);
                if (idx >= 0) {
                    double left = Math.min(1.0, f.targetDamage[k] / (double) XpProto.TARGET_DAMAGE);
                    Records.TargetState.put(w, idx, f.targetDeadTime[k] > 0 ? Records.TargetState.FLAG_DEAD : 0,
                        left);
                }
            }
            w.endSection();
        }
    }

    private int mapIndex(int[] table, int c) {
        int idx = XpMap.Built.lookup(table, c);
        if (idx < 0) {
            badIndex++;
        }
        return idx;
    }

    /**
     * BEHAVIOR: C size 3 is a ship (0x80 = friendly), 2 a ball, 0 a missile or mine; asteroids (0x80
     * with size 1..2, 4..7) are flagged ASTEROID. The own ship is added with FLAG_SELF when visible,
     * as the C client does in {@code Handle_ship}.
     */
    private void writeRadar(XpFrame f, int selfShip) {
        if (f.radar == 0 && selfShip < 0 || !w.beginSection(Section.RADAR)) {
            return;
        }
        for (int k = 0; k < f.radar; k++) {
            Records.Radar.put(w, f.radarX[k], f.radarY[k], radarFlags(f.radarSize[k]));
        }
        if (selfShip >= 0) {
            Records.Radar.put(w, wrapX(f.shipX[selfShip]), wrapY(f.shipY[selfShip]), Records.Radar.FLAG_SELF);
        }
        w.endSection();
    }

    static int radarFlags(int size) {
        int s = size & 0x07;
        boolean hi = (size & 0x80) != 0;
        if (s == 3) {
            return hi ? Records.Radar.FLAG_FRIENDLY : 0;
        }
        if (hi) {
            return Records.Radar.FLAG_ASTEROID;
        }
        if (s == 2) {
            return Records.Radar.FLAG_BALL;
        }
        return Records.Radar.FLAG_MISSILE;
    }

    /**
     * Missile kind from its drawn length (C {@code Frame_shots} with distinguishMissiles: smart 12, heat
     * 15, torpedo 18; without it every missile is 15 long).
     * <p>
     * BEHAVIOR: 18 is a torpedo, 15 a heat seeker, anything else a smart missile.
     */
    static int missileKind(int len) {
        return switch (len) {
            case 18 -> Records.Missile.KIND_TORPEDO;
            case 15 -> Records.Missile.KIND_HEAT;
            default -> Records.Missile.KIND_SMART;
        };
    }

    /** BEHAVIOR: the M2 object sections (items, mines, missiles, balls, lasers, ...); culled like ships. */
    private void writeObjects(XpFrame f) {
        if (f.itemObjs > 0 && w.beginSection(Section.ITEM)) {
            for (int k = 0; k < f.itemObjs; k++) {
                double x = wrapX(f.itemX[k]);
                double y = wrapY(f.itemY[k]);
                if (visible(x, y)) {
                    Records.ItemObj.put(w, x, y, f.itemType[k]);
                }
            }
            w.endSection();
        }
        if (f.mines > 0 && w.beginSection(Section.MINE)) {
            for (int k = 0; k < f.mines; k++) {
                double x = wrapX(f.mineX[k]);
                double y = wrapY(f.mineY[k]);
                if (visible(x, y)) {
                    int id = f.mineId[k];
                    int owner = id == 0 || id == XpProto.EXPIRED_MINE_ID ? Protocol.NO_ID : id;
                    Records.Mine.put(w, x, y, f.mineTeam[k] != 0 ? Records.Mine.FLAG_FRIENDLY : 0, owner);
                }
            }
            w.endSection();
        }
        if (f.missiles > 0 && w.beginSection(Section.MISSILE)) {
            for (int k = 0; k < f.missiles; k++) {
                double x = wrapX(f.missileX[k]);
                double y = wrapY(f.missileY[k]);
                if (visible(x, y)) {
                    Records.Missile.put(w, Protocol.NO_ID, x, y, f.missileDir[k] * RAD_PER_DIR,
                        missileKind(f.missileLen[k]), f.missileLen[k]);
                }
            }
            w.endSection();
        }
        if (f.balls > 0 && w.beginSection(Section.BALL)) {
            for (int k = 0; k < f.balls; k++) {
                double x = wrapX(f.ballX[k]);
                double y = wrapY(f.ballY[k]);
                if (visible(x, y)) {
                    Records.Ball.put(w, f.ballId[k] & 0xFFFF, x, y, Protocol.NO_TEAM, Protocol.NO_ID);
                }
            }
            w.endSection();
        }
        if (f.lasers > 0 && w.beginSection(Section.LASER)) {
            for (int k = 0; k < f.lasers; k++) {
                double x = wrapX(f.laserX[k]);
                double y = wrapY(f.laserY[k]);
                if (visible(x, y)) {
                    Records.Laser.put(w, x, y, f.laserDir[k] * RAD_PER_DIR, f.laserLen[k], objColor(f.laserColor[k]));
                }
            }
            w.endSection();
        }
        if (f.asteroids > 0 && w.beginSection(Section.ASTEROID)) {
            for (int k = 0; k < f.asteroids; k++) {
                double x = wrapX(f.astX[k]);
                double y = wrapY(f.astY[k]);
                if (visible(x, y)) {
                    Records.Asteroid.put(w, x, y, f.astSize[k], f.astRot[k] * RAD_PER_DIR);
                }
            }
            w.endSection();
        }
        if (f.wrecks > 0 && w.beginSection(Section.WRECKAGE)) {
            for (int k = 0; k < f.wrecks; k++) {
                double x = wrapX(f.wreckX[k]);
                double y = wrapY(f.wreckY[k]);
                if (visible(x, y)) {
                    Records.Wreckage.put(w, x, y, f.wreckType[k] & 0x7F, f.wreckSize[k], f.wreckRot[k] * RAD_PER_DIR);
                }
            }
            w.endSection();
        }
        if (f.ecms > 0 && w.beginSection(Section.ECM)) {
            for (int k = 0; k < f.ecms; k++) {
                double x = wrapX(f.ecmX[k]);
                double y = wrapY(f.ecmY[k]);
                if (visible(x, y)) {
                    Records.Ecm.put(w, x, y, Math.max(0, f.ecmSize[k]) / 2);
                }
            }
            w.endSection();
        }
        writeLines(f);
        if (f.wormholes > 0 && w.beginSection(Section.TEMP_WORMHOLE)) {
            for (int k = 0; k < f.wormholes; k++) {
                Records.TempWormhole.put(w, Math.floorDiv(f.wormX[k], XpProto.BLOCK_SZ),
                    Math.floorDiv(f.wormY[k], XpProto.BLOCK_SZ), 0);
            }
            w.endSection();
        }
    }

    /** BEHAVIOR: connectors (tractor byte 0 = ball, else tractor beam) and transporter beams. */
    private void writeLines(XpFrame f) {
        if (f.connectors > 0 && w.beginSection(Section.CONNECTOR)) {
            for (int k = 0; k < f.connectors; k++) {
                double x0 = wrapX(f.connX0[k]);
                double y0 = wrapY(f.connY0[k]);
                double x1 = wrapX(f.connX1[k]);
                double y1 = wrapY(f.connY1[k]);
                if (visible(x0, y0) && visible(x1, y1)) {
                    Records.Connector.put(w, x0, y0, x1, y1,
                        f.connTractor[k] != 0 ? Records.Connector.KIND_TRACTOR : Records.Connector.KIND_BALL);
                }
            }
            w.endSection();
        }
        if (f.trans > 0 && w.beginSection(Section.TRANSPORTER)) {
            for (int k = 0; k < f.trans; k++) {
                double x0 = wrapX(f.transX1[k]);
                double y0 = wrapY(f.transY1[k]);
                double x1 = wrapX(f.transX2[k]);
                double y1 = wrapY(f.transY2[k]);
                if (visible(x0, y0) && visible(x1, y1)) {
                    Records.Transporter.put(w, x0, y0, x1, y1);
                }
            }
            w.endSection();
        }
    }

    // ================================================================ reliable events

    private void deliver(Msg.Payload p, int type) {
        pay.clear();
        p.write(pay);
        pay.flip();
        listener.onReliable(type, pay);
    }

    private int nameSlot(int id) {
        for (int i = 0; i < nameCount; i++) {
            if (nameId[i] == id) {
                return i;
            }
        }
        return -1;
    }

    /**
     * C {@code Handle_player}: a player joined (or its data changed).
     * <p>
     * BEHAVIOR: the first player after {@link #expectSelf()} is the client itself: the listener gets
     * {@code onAccepted(id)} first and the join carries FLAG_SELF (also for later updates of that id).
     * BEHAVIOR: mychar 'R' is a robot, 'T' a tank, 'P' paused; team 255 is no team; shape = both
     * shape strings concatenated.
     */
    public void player(int id, int team, int mychar, CharSequence name, CharSequence shape) {
        if (expectSelf) {
            expectSelf = false;
            selfId = id;
            listener.onAccepted(id);
        }
        int s = nameSlot(id);
        if (s < 0 && nameCount < NAME_CAP) {
            s = nameCount++;
            nameId[s] = id;
        }
        if (s >= 0) {
            names[s].setLength(0);
            names[s].append(name);
            nameChar[s] = (char) mychar;
        }
        join.id = id & 0xFFFF;
        join.team = team & 0xFF;
        join.flags = joinFlags(mychar) | (id == selfId ? Msg.PlayerJoin.FLAG_SELF : 0);
        join.power = 45f;
        join.afterburners = 0;
        join.name.setLength(0);
        join.name.append(name);
        join.shape.setLength(0);
        join.shape.append(shape, 0, Math.min(shape.length(), Msg.MAX_SHAPE_LEN * 2));
        deliver(join, ReliableType.PLAYER_JOIN);
    }

    static int joinFlags(int mychar) {
        return switch (mychar) {
            case 'R' -> Msg.PlayerJoin.FLAG_ROBOT;
            case 'T' -> Msg.PlayerJoin.FLAG_TANK;
            case 'P' -> Msg.PlayerJoin.FLAG_PAUSED;
            default -> 0;
        };
    }

    /**
     * C {@code Handle_score}.
     * <p>
     * BEHAVIOR: lives are reported only with limitedLives (else -1, unlimited); mychar 'P' is paused,
     * 'W' waiting, 'D' out of lives.
     */
    public void score(int id, double value, int life, int mychar) {
        score.id = id & 0xFFFF;
        score.score = (float) value;
        score.lives = limitedLives ? life : -1;
        score.status = switch (mychar) {
            case 'P' -> Msg.Score.STATUS_PAUSED;
            case 'W' -> Msg.Score.STATUS_WAITING;
            case 'D' -> Msg.Score.STATUS_OUT_OF_LIVES;
            default -> 0;
        };
        int s = nameSlot(id);
        if (s >= 0) {
            nameChar[s] = (char) mychar;
        }
        deliver(score, ReliableType.SCORE);
    }

    /** C {@code Handle_team_score}. */
    public void teamScore(int team, double value) {
        teamScore.team = team & 0xFF;
        teamScore.score = (float) value;
        deliver(teamScore, ReliableType.TEAM_SCORE);
    }

    /** C {@code Handle_message}: BEHAVIOR: a server MESSAGE; talk vs game is decided by the text (MessageLog). */
    public void message(CharSequence msg) {
        text.kind = Msg.Text.KIND_GAME;
        text.from = Protocol.NO_ID;
        text.text.setLength(0);
        text.text.append(msg);
        deliver(text, ReliableType.MESSAGE);
    }

    private StringBuilder clientMessage() {
        text.kind = Msg.Text.KIND_GAME;
        text.from = Protocol.NO_ID;
        text.text.setLength(0);
        return text.text;
    }

    /**
     * C {@code Handle_leave}.
     * <p>
     * BEHAVIOR: PLAYER_LEAVE; for players other than robots and tanks also the C message
     * "NAME left this world."; unknown ids only produce the leave.
     */
    public void leave(int id) {
        int s = nameSlot(id);
        if (s >= 0) {
            if (nameChar[s] != 'R' && nameChar[s] != 'T') {
                clientMessage().append(names[s]).append(" left this world.");
                deliver(text, ReliableType.MESSAGE);
            }
            nameCount--;
            nameId[s] = nameId[nameCount];
            StringBuilder t = names[s];
            names[s] = names[nameCount];
            names[nameCount] = t;
            nameChar[s] = nameChar[nameCount];
        }
        if (id == selfId) {
            selfId = Protocol.NO_ID;
        }
        leave.id = id & 0xFFFF;
        deliver(leave, ReliableType.PLAYER_LEAVE);
    }

    /** C {@code Handle_war}: BEHAVIOR: "ROBOT declares war on KILLER." when both are known and killer != -1. */
    public void war(int robotId, int killerId) {
        int r = nameSlot(robotId);
        int k = nameSlot(killerId);
        if (r < 0 || killerId == -1 || k < 0) {
            return;
        }
        clientMessage().append(names[r]).append(" declares war on ").append(names[k]).append('.');
        deliver(text, ReliableType.MESSAGE);
    }

    /** C {@code Handle_seek}: BEHAVIOR: "P has programmed R to seek S." when all three are known. */
    public void seek(int programmerId, int robotId, int soughtId) {
        int p = nameSlot(programmerId);
        int r = nameSlot(robotId);
        int s = nameSlot(soughtId);
        if (p < 0 || r < 0 || s < 0) {
            return;
        }
        clientMessage().append(names[p]).append(" has programmed ").append(names[r]).append(" to seek ")
            .append(names[s]).append('.');
        deliver(text, ReliableType.MESSAGE);
    }

    /**
     * C {@code Handle_base}: player {@code id} owns C base {@code num}.
     * <p>
     * BEHAVIOR: bases the player owned before are released first (BASE_OWNER to NO_ID); out-of-range
     * base numbers are counted in {@link #badIndex()} and ignored.
     */
    public void base(int id, int num) {
        if (map == null || num < 0 || num >= cBaseOwner.length) {
            badIndex++;
            return;
        }
        for (int i = 0; i < cBaseOwner.length; i++) {
            if (cBaseOwner[i] == id && i != num) {
                cBaseOwner[i] = Protocol.NO_ID;
                sendBase(i, Protocol.NO_ID);
            }
        }
        cBaseOwner[num] = id;
        sendBase(num, id);
    }

    private void sendBase(int cNum, int id) {
        int idx = XpMap.Built.lookup(map.baseIndex(), cNum);
        if (idx < 0) {
            return;
        }
        baseOwner.baseIndex = idx;
        baseOwner.playerId = id & 0xFFFF;
        deliver(baseOwner, ReliableType.BASE_OWNER);
    }
}
