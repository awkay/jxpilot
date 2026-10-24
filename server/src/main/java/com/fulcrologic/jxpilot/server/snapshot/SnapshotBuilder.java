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
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import com.fulcrologic.jxpilot.common.sim.Click;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Grid;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.nio.ByteBuffer;

/**
 * Fills SNAPSHOT bodies for one viewer from the {@link World} with common's {@link SnapshotWriter}.
 * Allocation free; one instance per server thread.
 * <p>
 * Sections in order: SELF, SOUND (the viewer's queued sounds; the caller clears the queue once sent), SHIP (other ships in view), CANNON, FUEL,
 * TARGET, TREASURE, TEMP_WORMHOLE, RADAR (when requested), the item sections ({@link ItemSections}: TIMERS, SELF_ITEMS, ITEM, MINE,
 * MISSILE, LASER, ECM, beams, TRANSPORTER), BURST (bursts made since the viewer's cursor, in view), WRECKAGE (in
 * view), the game-mode objects (BALL, ASTEROID, ball CONNECTOR) and last SHOT, which fills the remaining room
 * nearest first ({@link ShotHash}, grouped by kind/colour relative to the viewer). Objects farther than the
 * view half-size from the viewer (or not encodable, {@link SnapshotWriter#inView}) are culled. When a
 * datagram fills up, the snapshot continues in the next buffer as a new self-contained part (same tick,
 * part numbers 0..n-1, the last flagged {@link SnapshotWriter#FLAG_LAST_PART}); a record that does not fit
 * in the given buffers is dropped, and smaller records of later sections are still tried.
 * <p>
 * The header tick is the number of the sim tick whose result this is ({@link World#tick} after
 * {@code Simulation.tick}); burst ages are measured from it.
 */
public final class SnapshotBuilder {
    /**
     * Pixels added on each side of the client's view (C culls at the view edge, {@code Frame_parameters}): the
     * client draws ships interpolated and shots extrapolated (up to two snapshot intervals) between snapshots,
     * so an object culled exactly at the edge would pop in or vanish inside the visible area; 128 px covers a
     * fast shot's travel over that time.
     */
    public static final int VIEW_MARGIN = 128;
    /** Default view half-width/height in pixels: the largest C view (MAX_VIEW_SIZE 1024) / 2 plus the margin. */
    public static final double DEFAULT_VIEW_HALF = Msg.Display.MAX_VIEW_SIZE / 2 + VIEW_MARGIN;
    /** Extra margin for bursts, whose particles spread from the origin. */
    public static final double BURST_MARGIN = 400;

    /** Assumed section headers per part when estimating the room left for shots. */
    static final int SHOT_SECTIONS_PER_PART = 8;

    private final World w;
    /** Wreckage handles of this round ({@link #beginRound}), {@code wrecks[0..wreckCount)}. */
    private final int[] wrecks;
    private int wreckCount;
    private final SnapshotWriter wr = new SnapshotWriter();
    private final ShotHash hash;
    /** {@link World#frames} when {@link #hash} was last rebuilt (-1: never). */
    private long hashFrames = -1;
    private final int[] shotList;
    /** C {@code wormholeVisible}: temporary wormholes are sent. */
    private final boolean wormholeVisible;
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
    /** Smallest record size that failed to fit in the last buffer (records this big are skipped). */
    private int failedSize;
    private int curType = -1;
    private int curVariant;
    private int tick;
    private int inputTick;
    private double cx;
    private double cy;
    /** The item sections (server-items). */
    private final ItemSections itemSections;
    /** C {@code maxVisibleObject} (frame.c:648, 739): a viewer is sent at most this many objects, the nearest. */
    private int maxVisible;
    /** C's cells as of this round, for the {@link #cVisible} pass (our own: snapshots may run off the sim thread). */
    private final Grid cells;
    /** Objects among the viewer's nearest {@link #maxVisible} have {@code visStamp[h] == visRound}. */
    private final int[] visStamp;
    private int visRound;

    public SnapshotBuilder(World w) {
        this.w = w;
        wr.setWorld(w.pixelWidth, w.pixelHeight, w.wrap);
        shotList = new int[w.objects.capacity];
        wormholeVisible = w.options.getBool(com.fulcrologic.jxpilot.common.config.Opt.WORMHOLE_VISIBLE);
        shotVariant = new int[w.objects.capacity];
        wrecks = new int[w.objects.capacity];
        visStamp = new int[w.objects.capacity];
        cells = new Grid(w.width, w.height, w.wrap, w.objects.capacity);
        hash = new ShotHash(w);
        itemSections = new ItemSections(w, this);
        initRecordClasses();
    }

    /** The section writer (for {@link ItemSections}). */
    SnapshotWriter writer() {
        return wr;
    }

    /**
     * Load and initialize every {@link Records} record class now, so the first snapshot does not allocate
     * while the JVM loads them lazily.
     */
    public static void initRecordClasses() {
        for (Class<?> c : Records.class.getDeclaredClasses()) {
            try {
                Class.forName(c.getName(), true, c.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Start a snapshot round: rebuild the shot hash from the current objects. {@link #build} does this by
     * itself once per tick ({@link World#frames}); call it after changing objects within a tick.
     */
    public void beginRound() {
        hash.rebuild();
        cells.rebuild(w.objects);
        maxVisible = w.options.getInt(com.fulcrologic.jxpilot.common.config.Opt.MAX_VISIBLE_OBJECT);
        hashFrames = w.frames;
        Objects o = w.objects;
        wreckCount = 0;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if (o.type[h] == Obj.WRECKAGE && o.life[h] > 0) {
                wrecks[wreckCount++] = h;
            }
        }
    }

    private boolean predict;
    /** This build writes OWN_SHOTS (set with the PREDICT sections). */
    private boolean ownShots;
    private long predictKeys;
    private int predictVersion;
    private boolean predictRare;

    /**
     * Include the PREDICT sections for the next {@link #build} (the receiving player's own ship, protocol v2): only
     * when the viewer is the receiving player (not while watching another).
     *
     * @param appliedKeys the input the server applied to the viewer this tick (the key flags compare with it)
     * @param rareVersion the version of the viewer's rare block
     * @param sendRare    also send the rare block
     */
    public void setPredict(boolean on, long appliedKeys, int rareVersion, boolean sendRare) {
        predict = on;
        predictKeys = appliedKeys;
        predictVersion = rareVersion;
        predictRare = sendRare;
    }

    /** Set the view half-size in pixels (C view size / 2 plus a margin). */
    public void setView(double halfWidth, double halfHeight) {
        viewHalfX = halfWidth;
        viewHalfY = halfHeight;
    }

    /**
     * Widen the view by {@code px} pixels on each axis: the owner's input lead (protocol v2) means its predicted
     * ship runs ahead of this snapshot by its speed times the lead, and what it will fly into must already be here.
     * <p>
     * BEHAVIOR: a positive margin is added to both half-sizes; zero or less changes nothing. Lasts until the next
     * {@link #setView}/{@link #setViewSize}.
     */
    public void extendView(double px) {
        if (px > 0) {
            viewHalfX += px;
            viewHalfY += px;
        }
    }

    /**
     * Cull the next {@link #build}s for a client view of {@code width x height} world pixels (C
     * {@code Frame_parameters}: the receiving connection's {@code view_width/height}).
     * <p>
     * BEHAVIOR: the view half-size becomes {@code size / 2 + VIEW_MARGIN} on each axis.
     */
    public void setViewSize(int width, int height) {
        setView(width / 2.0 + VIEW_MARGIN, height / 2.0 + VIEW_MARGIN);
    }

    /**
     * Build the snapshot of {@code viewer}.
     * <p>
     * BEHAVIOR: writes at each buffer's position (after the packet header) up to its limit; returns the number
     * of parts written (at least 1). Every part has the same tick and the final part count.
     * BEHAVIOR: bursts with sequence numbers in {@code [burstFrom, events.burstSeq())} that are still in the
     * ring are included.
     * BEHAVIOR: the viewer's {@link Player#audio} queue is written as the SOUND section, in queue order; the
     * queue is left as it is (the network layer clears it after sending, C {@code sound_play_queued}).
     *
     * @param viewer   the receiving player (view centre = its position)
     * @param parts    preallocated datagram buffers (at least one)
     * @param burstFrom first burst sequence number the viewer has not seen
     * @param radar    include the RADAR section
     */
    public int build(Player viewer, int inputTick, long burstFrom, boolean radar, ByteBuffer[] parts) {
        this.parts = parts;
        this.maxParts = Math.min(parts.length, MAX_PARTS);
        this.tick = w.tick;
        this.inputTick = inputTick;
        this.cx = viewer.cx / (double) Click.CLICK;
        this.cy = viewer.cy / (double) Click.CLICK;
        if (hashFrames != w.frames) {
            beginRound();
        }
        part = -1;
        failedSize = Integer.MAX_VALUE;
        curType = -1;
        markVisible(viewer);
        startPart();
        writeSelf(viewer);
        writePredict(viewer);
        writeSounds(viewer);
        writeShips(viewer);
        writeRefuel();
        writeCannons();
        writeFuel();
        writeTargets();
        writeTreasures();
        writeTempWormholes();
        if (radar) {
            writeRadar(viewer);
            writeModeRadar(viewer);
        }
        itemSections.write(viewer);
        writeBursts(burstFrom);
        writeWreckage();
        writeModeObjects();
        writeOwnShots(viewer);
        writeShots(viewer);
        closeSection();
        int count = part + 1;
        for (int i = 0; i < count; i++) {
            ByteBuffer b = parts[i];
            b.put(partStart[i] + SnapshotWriter.PART_COUNT_OFFSET, (byte) count);
            b.put(partStart[i] + SnapshotWriter.FLAGS_OFFSET, (byte) (i == count - 1 ? SnapshotWriter.FLAG_LAST_PART : 0));
        }
        return count;
    }

    private void startPart() {
        part++;
        ByteBuffer b = parts[part];
        partStart[part] = b.position();
        wr.begin(b, tick, inputTick, part, 1, 0, cx, cy);
    }

    private void closeSection() {
        if (curType >= 0) {
            wr.endSection();
            curType = -1;
        }
    }

    /**
     * Make sure a section of (type, variant) is open with room for one record, starting a new part if
     * needed.
     * <p>
     * BEHAVIOR: returns false when the record fits in none of the buffers: only that record is dropped;
     * records of the same or a larger size are skipped from then on, smaller ones are still tried.
     */
    boolean need(int type, int variant) {
        if (curType == type && curVariant == variant && wr.hasRoom()) {
            return true;
        }
        int size = Section.recordSize(type);
        if (size >= failedSize) {
            return false;
        }
        closeSection();
        if (!wr.beginSection(type, variant)) {
            if (part + 1 >= maxParts) {
                failedSize = size;
                return false;
            }
            startPart();
            if (!wr.beginSection(type, variant)) {
                failedSize = size;
                return false;
            }
        }
        curType = type;
        curVariant = variant;
        return true;
    }

    /**
     * C's {@code Cell_get_objects(viewer block, ..., MIN(NumObjs, maxVisibleObject))} for frame.c's object loop:
     * mark the viewer's nearest objects of every kind (sparks and debris count), nearest blocks first.
     * <p>
     * BEHAVIOR: the blocks searched cover our view (with its margin), so only the count limits; objects not
     * marked are not sent ({@link #cVisible}) even when there is room. Bursts are unaffected (one record per
     * explosion, not per particle).
     */
    private void markVisible(Player v) {
        visRound++;
        int n = Math.min(w.objects.count(), maxVisible);
        int range = (int) Math.ceil(Math.max(viewHalfX, viewHalfY) / Const.BLOCK_SZ) + 1;
        cells.query(v.bx, v.by, range, n);
        for (int i = 0; i < cells.resultCount; i++) {
            visStamp[cells.result[i]] = visRound;
        }
    }

    /** BEHAVIOR: whether object {@code h} is among the viewer's nearest {@code maxVisibleObject} ({@link #markVisible}). */
    boolean cVisible(int h) {
        return visStamp[h] == visRound;
    }

    boolean visible(double x, double y, double margin) {
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
        if ((pl.used & Has.REPAIR) != 0) {
            f |= Records.ShipFlags.REPAIRING;
        }
        if ((pl.have & Has.BALL) != 0) {
            f |= Records.ShipFlags.HAS_BALL;
        }
        if ((pl.used & Has.CLOAKING_DEVICE) != 0) {
            f |= Records.ShipFlags.CLOAKED;
        }
        if ((pl.used & Has.PHASING_DEVICE) != 0) {
            f |= Records.ShipFlags.PHASING;
        }
        if ((pl.used & Has.EMERGENCY_SHIELD) != 0) {
            f |= Records.ShipFlags.EMERGENCY_SHIELD;
        }
        if ((pl.used & Has.DEFLECTOR) != 0) {
            f |= Records.ShipFlags.DEFLECTOR;
        }
        if ((pl.used & Has.AUTOPILOT) != 0 || (pl.status & Obj.HOVERPAUSE) != 0) {
            f |= Records.ShipFlags.AUTOPILOT;
        }
        if ((pl.used & Has.EMERGENCY_THRUST) != 0) {
            f |= Records.ShipFlags.EMERGENCY_THRUST;
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
        int flags = (shipFlags(v, v) & ~Records.ShipFlags.AUTOPILOT)
            | (itemSections.autopilotLight(v) ? Records.ShipFlags.AUTOPILOT : 0);
        Records.Self.put(wr, v.id, flags, (float) cx, (float) cy, (float) v.vx, (float) v.vy,
            (float) heading(v), (float) (v.fuel / 256), (float) (v.fuelMax / 256),
            (v.lockTagged & Player.LOCK_PLAYER) != 0 && (v.used & Has.COMPASS) != 0 ? v.lockPlId : Protocol.NO_ID,
            v.numTanks, v.currentTank,
            (v.status & Obj.PLAYING) == 0 && v.count > 0 ? v.count : 0, v.power, v.turnSpeed, v.turnResistance,
            itemSections.lockDist(v), itemSections.lockDir(v), v.check, ItemSections.selfMods(v.mods));
    }

    /**
     * BEHAVIOR: with {@link #setPredict} on: PREDICT (frames, roundDelay, the core block), PREDICT_RARE when asked
     * for, PREDICT_KEYS when a key mask differs from the applied input; nothing otherwise. Each call of
     * {@link #build} needs its own {@link #setPredict}: it is switched off after use.
     */
    private void writePredict(Player v) {
        ownShots = predict;
        if (!predict) {
            return;
        }
        predict = false;
        if (need(Section.PREDICT, 0)) {
            Records.Predict.put(wr, v, w.frames, w.roundDelay, predictKeys, predictVersion);
        }
        if (predictRare && need(Section.PREDICT_RARE, 0)) {
            Records.PredictRare.put(wr, v, predictVersion);
        }
        if (v.keysDiffer(predictKeys) && need(Section.PREDICT_KEYS, 0)) {
            Records.PredictKeys.put(wr, v);
        }
    }

    private void writeSounds(Player v) {
        for (int i = 0; i < v.audio.count(); i++) {
            if (need(Section.SOUND, 0)) {
                Records.Sound.put(wr, v.audio.event(i), v.audio.volume(i));
            }
        }
    }

    /**
     * BEHAVIOR: other ships are sent when playing or paused and not GAME_OVER (C frame.c: dead ships being
     * carried home are not shown; a paused ship is shown, with the PAUSED flag).
     */
    static boolean shown(Player pl) {
        return (pl.status & (Obj.PLAYING | Obj.PAUSE)) != 0 && (pl.status & Obj.GAME_OVER) == 0;
    }

    private void writeShips(Player v) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if (pl == v || !shown(pl)) {
                continue;
            }
            if ((pl.status & Obj.PAUSE) == 0 && !itemSections.shipShown(pl, v)) {
                continue;
            }
            double x = pl.cx / (double) Click.CLICK;
            double y = pl.cy / (double) Click.CLICK;
            if (!visible(x, y, 0) || !need(Section.SHIP, 0)) {
                continue;
            }
            boolean paused = (pl.status & Obj.PAUSE) != 0;
            // a paused ship is still, so its vx carries C's pause count (Send_paused(.., pl->count)), as on the XP path
            Records.Ship.put(wr, pl.id, shipFlags(pl, v), x, y, heading(pl), paused ? Math.max(0, pl.count) : pl.vx,
                paused ? 0 : pl.vy);
        }
    }

    /**
     * BEHAVIOR: the SHOT section variant of a shot relative to the viewer: kind (NUCLEAR for any shot with
     * nuclear modifiers, C frame.c:830, the client blinks it), colour, and
     * {@code FLAG_HARMLESS} when C frame.c would draw it as harmless to the viewer (team immune owner, paused
     * owner, an unowned shot of the viewer's team in team play, or the viewer's own shot with selfImmunity).
     */
    int shotVariant(int h, Player v) {
        Objects o = w.objects;
        int kind;
        if (com.fulcrologic.jxpilot.common.sim.Mods.isNuclear(o.mods[h])) {
            kind = Records.ShotKind.NUCLEAR;
        } else if (o.type[h] == Obj.CANNON_SHOT) {
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
        return Records.ShotKind.variant(kind, color, harmless(owner, o.team[h], v));
    }

    /** C frame.c:818-828 for a shot of {@code owner}/{@code team} seen by {@code v}. */
    boolean harmless(int owner, int team, Player v) {
        if (w.teamImmune(owner, v.id)) {
            return true;
        }
        if (owner != Obj.NO_ID) {
            Player op = w.byId(owner);
            if (op != null && (op.status & Obj.PAUSE) != 0) {
                return true;
            }
        } else if (w.params.teamPlay && team == v.team) {
            return true;
        }
        return owner == v.id && w.params.selfImmunity;
    }

    /**
     * BEHAVIOR: how many more SHOT records fit in the open part and the unused buffers, keeping
     * {@link #SHOT_SECTIONS_PER_PART} section headers of room per part.
     */
    int shotRoom() {
        int bytes = parts[part].remaining() - SHOT_SECTIONS_PER_PART * Section.HEADER_SIZE;
        for (int p = part + 1; p < maxParts; p++) {
            bytes += parts[p].remaining() - SnapshotWriter.HEADER_SIZE - SHOT_SECTIONS_PER_PART * Section.HEADER_SIZE;
        }
        return Math.max(0, bytes / Records.Shot.SIZE);
    }

    /** Shots in view, nearest cells first, up to the room left; then grouped by variant. */
    /**
     * BEHAVIOR: with the PREDICT sections, the viewer's own bullets in view (those tagged with a spawn tick and
     * ordinal, {@code Shots.playerShot}) as OWN_SHOTS: position as SHOT, age = this tick minus the spawn tick, the
     * ordinal; in object order. They stay in SHOT as well (the 1b client draws them from there).
     */
    private void writeOwnShots(Player v) {
        if (!ownShots) {
            return;
        }
        Objects o = w.objects;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if (o.type[h] != Obj.SHOT || o.owner[h] != v.id || o.shotOrdinal[h] < 0) {
                continue;
            }
            double x = o.cx[h] / (double) Click.CLICK;
            double y = o.cy[h] / (double) Click.CLICK;
            if (visible(x, y, 0) && need(Section.OWN_SHOTS, 0)) {
                Records.OwnShot.put(wr, x, y, tick - o.spawnTick[h], o.shotOrdinal[h]);
            }
        }
    }

    private void writeShots(Player v) {
        int cap = shotRoom();
        ShotHash hs = hash;
        int n = 0;
        int vcx = (int) Math.floor(cx / ShotHash.CELL);
        int vcy = (int) Math.floor(cy / ShotHash.CELL);
        int rx = Math.min(ShotHash.RADIUS, (int) Math.ceil(viewHalfX / ShotHash.CELL) + 1);
        int ry = Math.min(ShotHash.RADIUS, (int) Math.ceil(viewHalfY / ShotHash.CELL) + 1);
        hs.beginWalk();
        for (int k = 0; k < hs.ringX.length && n < cap; k++) {
            int c = hs.cellAt(k, vcx, vcy, rx, ry);
            if (c < 0) {
                continue;
            }
            for (int i = hs.start[c], e = hs.start[c + 1]; i < e && n < cap; i++) {
                if (!visible(hs.x[i], hs.y[i], 0) || !cVisible(hs.handle[i])) {
                    continue;
                }
                shotList[n] = i;
                shotVariant[n] = shotVariant(hs.handle[i], v);
                n++;
            }
        }
        Objects o = w.objects;
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
                int e = shotList[j];
                int h = hs.handle[e];
                if (need(Section.SHOT, var)) {
                    Records.Shot.put(wr, hs.x[e], hs.y[e], o.vx[h], o.vy[h]);
                }
            }
        }
    }

    /**
     * Wreckage in view (C frame.c:802-807 Send_wreckage): position, shape (C info, 7 bits), size and rotation.
     */
    private void writeWreckage() {
        Objects o = w.objects;
        for (int i = 0; i < wreckCount; i++) {
            int h = wrecks[i];
            double x = o.cx[h] / (double) Click.CLICK;
            double y = o.cy[h] / (double) Click.CLICK;
            if (!visible(x, y, 0) || !cVisible(h) || !need(Section.WRECKAGE, 0)) {
                continue;
            }
            Records.Wreckage.put(wr, x, y, o.info[h] & 0x7F, o.size[h], o.rotation[h] * (2 * Math.PI / Const.RES));
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

    /**
     * C {@code Frame_ships} Send_refuel: a refuel beam (CONNECTOR, KIND_REFUEL) from the fuel station to every
     * refuelling ship.
     * <p>
     * BEHAVIOR: for every shown, unpaused ship in view whose used has REFUEL and whose station is in view
     * (C inview of both); stations are drawn from their pixel centre.
     */
    private void writeRefuel() {
        java.util.List<FuelStation> fuels = w.map.fuels();
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if (!shown(pl) || (pl.status & Obj.PAUSE) != 0 || (pl.used & Has.REFUEL) == 0 || pl.fs < 0
                || pl.fs >= fuels.size()) {
                continue;
            }
            double x = pl.cx / (double) Click.CLICK;
            double y = pl.cy / (double) Click.CLICK;
            FuelStation f = fuels.get(pl.fs);
            if (!visible(x, y, 0) || !visible(f.x(), f.y(), 0) || !need(Section.CONNECTOR, 0)) {
                continue;
            }
            Records.Connector.put(wr, f.x(), f.y(), x, y, Records.Connector.KIND_REFUEL);
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

    // ================================================================ game modes (server-modes)

    /** BEHAVIOR: targets in view: index, DEAD while dead, and the strength left (C {@code Send_target}). */
    private void writeTargets() {
        java.util.List<com.fulcrologic.jxpilot.common.map.Target> targets = w.map.targets();
        ModeState m = w.modes;
        for (int i = 0; i < targets.size(); i++) {
            com.fulcrologic.jxpilot.common.map.Target t = targets.get(i);
            if (!visible(t.x(), t.y(), 0) || !need(Section.TARGET, 0)) {
                continue;
            }
            boolean dead = w.targetDeadTime[i] > 0;
            Records.TargetState.put(wr, i, dead ? Records.TargetState.FLAG_DEAD : 0,
                (double) m.targetDamage[i] / ModeState.TARGET_DAMAGE);
        }
    }

    /** BEHAVIOR: treasures in view: index and HAS_BALL while their ball rests in them. */
    private void writeTreasures() {
        java.util.List<com.fulcrologic.jxpilot.common.map.Treasure> ts = w.map.treasures();
        for (int i = 0; i < ts.size(); i++) {
            com.fulcrologic.jxpilot.common.map.Treasure t = ts.get(i);
            if (!visible(t.x(), t.y(), 0) || !need(Section.TREASURE, 0)) {
                continue;
            }
            Records.TreasureState.put(wr, i, w.modes.treasureHave[i] ? Records.TreasureState.FLAG_HAS_BALL : 0);
        }
    }

    /**
     * C {@code Frame_map} wormholes (frame.c:611-636): with wormholeVisible, the temporary IN ends in view
     * (the OUT ends are never sent), as their block and type (Wormhole.Type ordinal).
     */
    private void writeTempWormholes() {
        if (!wormholeVisible) {
            return;
        }
        com.fulcrologic.jxpilot.server.world.WormTable worms = w.worms;
        for (int i = worms.mapCount; i < worms.count(); i++) {
            int type = worms.type(i);
            if (type == com.fulcrologic.jxpilot.server.world.WormTable.OUT
                || !visible((worms.bx(i) + 0.5) * Const.BLOCK_SZ, (worms.by(i) + 0.5) * Const.BLOCK_SZ, 0)) {
                continue;
            }
            if (!need(Section.TEMP_WORMHOLE, 0)) {
                return;
            }
            Records.TempWormhole.put(wr, worms.bx(i), worms.by(i), type);
        }
    }

    /**
     * Balls, connectors and asteroids in view (C {@code Frame_shots}, {@code Frame_ships}).
     * <p>
     * BEHAVIOR: a ball: its handle, team (its treasure's), and the ship it is connected to (or NO_ID); a playing
     * ship (not paused, not game over) reaching for a ball (C {@code pl->ball}) shows a connector from the ball
     * to the ship when both are in view; asteroids with size and rotation.
     */
    private void writeModeObjects() {
        Objects o = w.objects;
        ModeState m = w.modes;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            int type = o.type[h];
            if ((type & (Obj.BALL | Obj.ASTEROID)) == 0 || o.life[h] <= 0) {
                continue;
            }
            double x = o.cx[h] / (double) Click.CLICK;
            double y = o.cy[h] / (double) Click.CLICK;
            if (!visible(x, y, 0) || !cVisible(h)) {
                continue;
            }
            if (type == Obj.BALL) {
                if (need(Section.BALL, 0)) {
                    int team = m.treasureTeam[m.ballTreasure[h]];
                    Records.Ball.put(wr, h, x, y, team < 0 ? Protocol.NO_TEAM : team,
                        o.owner[h] == Obj.NO_ID ? Protocol.NO_ID : o.owner[h]);
                }
            } else if (need(Section.ASTEROID, 0)) {
                // C Send_asteroid's type_size: the outline type (info, frame.c:812) above the size (netserver.c:1871)
                Records.Asteroid.put(wr, x, y, (o.info[h] & 0x0F) << 4 | (o.size[h] & 0x0F),
                    o.rotation[h] * (2 * Math.PI / Const.RES));
            }
        }
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            int h = pl.ball;
            // C Frame_ships (frame.c:1009-1024, 1078-1085): playing ships in view, not paused, not game over
            if (h < 0 || !o.isLive(h) || o.type[h] != Obj.BALL || (pl.status & Obj.PLAYING) == 0
                || (pl.status & (Obj.PAUSE | Obj.GAME_OVER)) != 0) {
                continue;
            }
            double bx = o.cx[h] / (double) Click.CLICK;
            double by = o.cy[h] / (double) Click.CLICK;
            double sx = pl.cx / (double) Click.CLICK;
            double sy = pl.cy / (double) Click.CLICK;
            if (!visible(bx, by, 0) || !visible(sx, sy, 0) || !need(Section.CONNECTOR, 0)) {
                continue;
            }
            Records.Connector.put(wr, bx, by, sx, sy, Records.Connector.KIND_BALL);
        }
    }

    /**
     * BEHAVIOR: radar blips for balls (with treasuresOnRadar, FLAG_BALL) and asteroids (with asteroidsOnRadar,
     * FLAG_ASTEROID) within the viewer's sensor range (C Frame_radar). Asteroids go in one RADAR section per size,
     * the size as the section variant (C draws size + 1 pixels); balls in variant 0.
     */
    private void writeModeRadar(Player v) {
        boolean balls = w.modeParams.treasuresOnRadar;
        boolean asteroids = w.modeParams.asteroidsOnRadar;
        if (!balls && !asteroids) {
            return;
        }
        if (balls && !writeModeBlips(v, Obj.BALL, -1)) {
            return;
        }
        // one RADAR section per asteroid size, its variant the size (C size + 1 pixels); a single pass when they
        // all have one size
        int sizes = 0;
        Objects o = w.objects;
        for (int k = 0; asteroids && k < o.count(); k++) {
            int h = o.active[k];
            if (o.type[h] == Obj.ASTEROID) {
                sizes |= 1 << Math.max(1, Math.min(15, o.size[h]));
            }
        }
        for (int size = 1; size <= 15; size++) {
            if ((sizes & 1 << size) != 0 && !writeModeBlips(v, Obj.ASTEROID, size)) {
                return;
            }
        }
    }

    /** Ball (size -1) or asteroid blips of one size; false once the snapshot is full. */
    private boolean writeModeBlips(Player v, int type, int size) {
        Objects o = w.objects;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if (o.type[h] != type || size >= 0 && Math.max(1, Math.min(15, o.size[h])) != size) {
                continue;
            }
            if (w.wrapLength(v.px - o.px[h], v.py - o.py[h]) > v.sensorRange) {
                continue;
            }
            if (!need(Section.RADAR, Math.max(0, size))) {
                return false;
            }
            Records.Radar.put(wr, o.px[h], o.py[h], type == Obj.BALL ? Records.Radar.FLAG_BALL
                : Records.Radar.FLAG_ASTEROID);
        }
        return true;
    }

    private void writeRadar(Player v) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if (!pl.isAlive() || !itemSections.onRadar(pl, v) || !need(Section.RADAR, 0)) {
                continue;
            }
            int flags = 0;
            if (pl == v) {
                flags |= Records.Radar.FLAG_SELF;
            }
            if (pl != v && itemSections.radarFriendly(pl, v)) {
                flags |= Records.Radar.FLAG_FRIENDLY;
            }
            if (itemSections.radarLocked(pl, v)) {
                flags |= Records.Radar.FLAG_LOCKED;
            }
            Records.Radar.put(wr, pl.cx / (double) Click.CLICK, pl.cy / (double) Click.CLICK, flags);
        }
        itemSections.writeRadarObjects(v);
    }
}
