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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The client's picture of the game, built from snapshots. Pure (no GL, no I/O, time passed in) and
 * allocation free in steady state.
 * <p>
 * <b>Ingest</b> ({@link #ingest}): snapshot parts are decoded into a ring of {@value #RING} preallocated
 * {@link SnapshotFrame}s (triple buffer: the two the renderer interpolates between plus the newest).
 * Bursts are expanded into the {@link ParticlePool}; thrust sparks are generated for every sim tick
 * of a thrusting ship ({@link ThrustSparkEmitter}); map object state (cannons, fuel, targets,
 * treasures), radar, own items and sounds ({@link #sounds()}) are applied from the newest tick only.
 * <p>
 * <b>Render</b> ({@link #update}): the tick clock takes one sample per receive batch (the newest tick
 * ingested since the last update). The render tick is the estimated server tick minus
 * {@link #interpolationDelayTicks()} (one snapshot interval plus a {@link #JITTER_MARGIN_SECONDS} margin).
 * Ships are interpolated between the snapshots around it, wrap-aware via {@link XMath#wrapDelta}; shots
 * are linked between the snapshots around it ({@link Tracker}); the own ship (and the camera) is predicted
 * from the newest SELF to the present with its prediction error blended out over a few frames (low latency
 * without a sawtooth, F1); particles are advanced to the render tick, own exhaust to the own ship's tick.
 * Nothing assumes a tick rate: rates come from WELCOME (the server ticks at the map's framesPerSecond).
 * Read results with the accessors (valid until the next update).
 * <p>
 * Single threaded: call ingest and update from the same thread.
 */
public final class ClientWorld {
    /** Snapshot frames kept. */
    public static final int RING = 3;
    /** Thrust spark ticks generated per ship per snapshot at most (after a gap only the latest). */
    public static final int MAX_SPARK_TICKS = 8;
    /** Time constant (s) with which the own ship's prediction error fades: about 4 render frames at 60 Hz. */
    public static final double ERROR_DECAY_SECONDS = 0.06;
    /** Largest own-ship acceleration estimate per axis (px per original frame squared). */
    public static final double MAX_SELF_ACCEL = 4;
    /** A ship moving farther than this between two snapshots is snapped, not interpolated (respawn). */
    public static final double TELEPORT_PX = 10 * 35.0;
    /** Render delay beyond one snapshot interval, for network jitter (a time, so any tick rate works). */
    public static final double JITTER_MARGIN_SECONDS = 0.02;
    /**
     * Farthest a shot or point particle is followed between frames, per original frame: shots fly at
     * {@code shotSpeed} (default 21) plus the firing ship's speed.
     */
    public static final double TRACK_PX_PER_FRAME = 64;
    /** Point colour codes from this value on are C debris temperature levels (code - base). */
    public static final int POINT_LEVEL_BASE = 16;
    /** A new shot is seeded from a ship at most this far from it (px; the gun plus a frame of flight). */
    public static final double SEED_RADIUS_PX = 60;
    /** Unambiguity ratio for shots without a known velocity (R2). */
    public static final double SHOT_AMBIGUITY_RATIO = 2;
    /** Slowest a shot of unknown velocity is taken to move (px per original frame). */
    public static final double MIN_SHOT_SPEED = 2;
    /** Snapshot parts are numbered 0..63 (64 x 1200 bytes is far beyond any tick); higher parts are malformed. */
    public static final int MAX_PARTS = 64;

    /** {@link #ingest} result: a new newest tick. */
    public static final int NEW_TICK = 1;
    /** {@link #ingest} result: another part of a tick already in the ring. */
    public static final int PART_ADDED = 2;
    /** {@link #ingest} result: an older tick inserted into the ring (reordering). */
    public static final int LATE_TICK = 3;
    /** {@link #ingest} result: duplicate part, ignored. */
    public static final int DUPLICATE = 4;
    /** {@link #ingest} result: older than everything in the ring, ignored. */
    public static final int STALE = 5;
    /** {@link #ingest} result: truncated or malformed (sections up to the error were used). */
    public static final int MALFORMED = 6;
    /** {@link #ingest} result: no map configured yet, ignored. */
    public static final int NO_MAP = 7;

    private final Players players;
    private final SnapshotFrame[] ring = new SnapshotFrame[RING];
    private final SnapshotReader reader = new SnapshotReader();
    private final Records.Self selfRec = new Records.Self();
    private final Records.Ship shipRec = new Records.Ship();
    private final Records.Shot shotRec = new Records.Shot();
    private final Records.Burst burstRec = new Records.Burst();
    private final Records.CannonState cannonRec = new Records.CannonState();
    private final Records.FuelState fuelRec = new Records.FuelState();
    private final Records.TargetState targetRec = new Records.TargetState();
    private final Records.TreasureState treasureRec = new Records.TreasureState();
    private final Records.TempWormhole tempWormRec = new Records.TempWormhole();
    /** Temporary wormholes of the newest tick (C {@code wormhole_ptr}, refilled every frame). */
    private final int[] tempWormBx = new int[TEMP_WORMHOLES];
    private final int[] tempWormBy = new int[TEMP_WORMHOLES];
    private int tempWorms;
    /** Most temporary wormholes kept per tick. */
    public static final int TEMP_WORMHOLES = 64;
    private final Records.Radar radarRec = new Records.Radar();
    private final Records.SelfItems itemsRec = new Records.SelfItems();
    private final Records.ItemObj itemRec = new Records.ItemObj();
    private final Records.Mine mineRec = new Records.Mine();
    private final Records.Missile missileRec = new Records.Missile();
    private final Records.Ball ballRec = new Records.Ball();
    private final Records.Laser laserRec = new Records.Laser();
    private final Records.Asteroid asteroidRec = new Records.Asteroid();
    private final Records.Wreckage wreckRec = new Records.Wreckage();
    private final Records.Ecm ecmRec = new Records.Ecm();
    private final Records.Connector connRec = new Records.Connector();
    private final Records.Transporter transRec = new Records.Transporter();
    private final ObjectLists outObjs = new ObjectLists(SnapshotFrame.OBJECT_CAP);
    private final Records.Timers timersRec = new Records.Timers();
    private final Records.Sound soundRec = new Records.Sound();
    /** Sounds kept for the player: several snapshots' worth (C sends at most one of each event per frame). */
    public static final int SOUND_CAP = 256;
    private final SoundQueue sounds = new SoundQueue(SOUND_CAP);
    private int damaged;
    private int roundDelay;
    private int destruct;
    private int thrustLeft;
    private int thrustMax;
    private int shieldLeft;
    private int shieldMax;
    private int phasingLeft;
    private int phasingMax;
    private int shutdownCount = -1;
    private int shutdownDelay;
    private boolean haveTimeLeft;
    private int timeLeftValue;
    private int timeLeftTick;
    private final DebrisBurst burst = new DebrisBurst();
    private final Rng rng = new Rng(0);
    private final ThrustSparkEmitter sparks = new ThrustSparkEmitter();
    private final ParticlePool particles;
    private final TickClock clock = new TickClock();
    private final ShipIndex shipIndex;
    private final boolean[] usedA;

    private boolean configured;
    private double worldW = 65536;
    private double worldH = 65536;
    private boolean wrap;
    // until configure: one sim tick per C frame at the default framesPerSecond, snapshot divisor 1
    private float frameFraction = 1f;
    private double snapshotInterval = Protocol.DEFAULT_SNAPSHOT_DIVISOR;
    /** Tick steps between the last {@value #STEP_WINDOW} new newest ticks (see {@link #arrivalIntervalSeconds}). */
    static final int STEP_WINDOW = 8;
    private final int[] tickSteps = new int[STEP_WINDOW];
    private int stepCount;
    private int stepNext;
    private double delayTicks = Protocol.DEFAULT_SNAPSHOT_DIVISOR + JITTER_MARGIN_SECONDS * ClientGame.DEFAULT_FPS;
    private double maxExtrapolateTicks = 2.0 * Protocol.DEFAULT_SNAPSHOT_DIVISOR;
    private int defaultEngineX = ShipShape.defaultShip().engineX;
    private int defaultEngineY = ShipShape.defaultShip().engineY;

    private boolean haveNewest;
    private boolean haveSelfState;
    private int selfStateTick;
    private boolean selfPlaying;
    private int newestTick;
    private boolean samplePending;
    private int sampleTick;
    private long sampleNanos;
    private long ingested;
    private long malformedCount;

    // map object state (indices of the WorldMap lists)
    private byte[] cannonFlags = new byte[0];
    private float[] fuelLevel = new float[0];
    private byte[] targetFlags = new byte[0];
    private float[] targetLeft = new float[0];
    private byte[] treasureFlags = new byte[0];
    private int[] baseOwner = new int[0];
    private final int[] selfItems = new int[Item.COUNT];
    /** SELF_ITEMS loseItem (C {@code lose_item}, items-2). */
    private int selfLoseItem;

    // radar (whole world, latest RADAR section)
    private final int radarCap;
    private final float[] radarX;
    private final float[] radarY;
    private final int[] radarFlags;
    private final int[] radarAsteroidSize;
    private int radarCount;
    private int radarTick;
    private boolean haveRadar;

    // render outputs
    private double renderTick;
    // own-ship prediction: the kernel predictor (protocol v2), else F1
    private com.fulcrologic.jxpilot.client.game.predict.OwnShipPredictor predictor;
    private boolean predicted;
    /** The snapshot's own bullets are left out (the predictor draws them). */
    private boolean hideOwnShots;
    /** An own bullet no provisional shot stands for is drawn anyway (set while one is put). */
    private boolean allowOwn;
    /** The own normal bullet's SHOT variant without the harmless bit. */
    private static final int OWN_SHOT = Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.OWN);
    /** Provisional own shots the last update appended to the shot list (drawn after the snapshot shots). */
    private int provisionalShots;
    private boolean havePrediction;
    private int predTick;
    private double predX;
    private double predY;
    private double predVx;
    private double predVy;
    private double predAx;
    private double predAy;
    private double errX;
    private double errY;
    private long lastPredictNanos;
    private boolean teleportPending;
    private int teleportTick;
    private double drawnTick;
    /** Tick of the SELF the last {@link #update} drew the own ship from. */
    private int drawnSelfTick;
    private double serverTickNow;
    private boolean outSelf;
    private int outSelfId = Protocol.NO_ID;
    private int outSelfFlags;
    private float outSelfX;
    private float outSelfY;
    private float outSelfVx;
    private float outSelfVy;
    private float outSelfHeading;
    private float outSelfFuel;
    private float outSelfFuelMax;
    private int outSelfLock = Protocol.NO_ID;
    private int outSelfTanks;
    private int outSelfCurrentTank;
    private int outSelfRespawn;
    private int outSelfPower;
    private int outSelfTurnSpeed;
    private int outSelfTurnRes255;
    private int outSelfLockDist;
    private int outSelfLockDir;
    private int outSelfCheck;
    private int outSelfMods;
    private final int shipCap;
    private int outShips;
    private final int[] outShipId;
    private final int[] outShipFlags;
    private final float[] outShipX;
    private final float[] outShipY;
    private final float[] outShipHeading;
    private final float[] outShipVx;
    private final float[] outShipVy;
    private final int shotCap;
    private int outShots;
    private final float[] outShotX;
    private final float[] outShotY;
    private final int[] outShotVariant;
    /** Each drawn shot's source record: the snapshot tick and its position there (none: Integer.MIN_VALUE tick). */
    private final int[] outShotSrcTick;
    private final float[] outShotSrcX;
    private final float[] outShotSrcY;
    /** Tracers of new bullets (prediction on): from the source to the drawn bullet, with the alpha left. */
    private int outTracers;
    private final float[] outTracerX1;
    private final float[] outTracerY1;
    private final float[] outTracerX2;
    private final float[] outTracerY2;
    private final float[] outTracerAlpha;
    private final int[] outTracerVariant;
    private long tracersDrawn;
    /** Map cannon positions (pixels), the sources of cannons' bullets. */
    private float[] cannonPx = new float[0];
    private float[] cannonPy = new float[0];
    /** The local time of the running {@link #update}. */
    private long updateNanos;
    private int outPoints;
    private final float[] outPointX;
    private final float[] outPointY;
    private final int[] outPointColor;
    // association scratch (one entry per shot of the later frame / earlier frame)
    private final Tracker tracker;
    private final ObjectLists.Scratch objScratch = new ObjectLists.Scratch(SnapshotFrame.OBJECT_CAP);
    private final int[] keyA;
    private final int[] keyB;
    private double shotSpeed = 21;

    /**
     * @param shipCap     ships per snapshot
     * @param shotCap     shots per snapshot
     * @param particleCap sparks and debris particles alive at once
     * @param radarCap    radar blips
     */
    public ClientWorld(Players players, int shipCap, int shotCap, int particleCap, int radarCap) {
        this.players = players;
        this.shipCap = shipCap;
        this.shotCap = shotCap;
        for (int i = 0; i < RING; i++) {
            ring[i] = new SnapshotFrame(shipCap, shotCap);
        }
        particles = new ParticlePool(particleCap);
        this.radarCap = radarCap;
        radarX = new float[radarCap];
        radarY = new float[radarCap];
        radarFlags = new int[radarCap];
        radarAsteroidSize = new int[radarCap];
        outShipId = new int[shipCap];
        outShipFlags = new int[shipCap];
        outShipX = new float[shipCap];
        outShipY = new float[shipCap];
        outShipHeading = new float[shipCap];
        outShipVx = new float[shipCap];
        outShipVy = new float[shipCap];
        outShotX = new float[shotCap];
        outShotY = new float[shotCap];
        outShotVariant = new int[shotCap];
        outShotSrcTick = new int[shotCap];
        drDraw = new boolean[shotCap];
        drX = new int[shotCap];
        drY = new int[shotCap];
        drVx = new float[shotCap];
        drVy = new float[shotCap];
        drLife = new long[shotCap];
        drCannon = new boolean[shotCap];
        drTeams = new int[shotCap];
        outShotSrcX = new float[shotCap];
        outShotSrcY = new float[shotCap];
        outTracerX1 = new float[shotCap];
        outTracerY1 = new float[shotCap];
        outTracerX2 = new float[shotCap];
        outTracerY2 = new float[shotCap];
        outTracerAlpha = new float[shotCap];
        outTracerVariant = new int[shotCap];
        outPointX = new float[shotCap];
        outPointY = new float[shotCap];
        outPointColor = new int[shotCap];
        tracker = new Tracker(shotCap);
        keyA = new int[shotCap];
        keyB = new int[shotCap];
        shipIndex = new ShipIndex(shipCap);
        usedA = new boolean[shipCap];
    }

    /** Default capacities: 128 ships, 2048 shots, 16384 particles, 1024 radar blips. */
    public ClientWorld(Players players) {
        this(players, 128, 2048, 16384, 1024);
    }

    // ================================================================ setup

    /**
     * Configure for a new map (after the config transfer). Allocates map-state arrays.
     * <p>
     * BEHAVIOR: world size and wrap from the map; frameFraction = gameFps / simHz; snapshot interval =
     * simHz / snapshotHz ticks; delay = interval + {@link #JITTER_MARGIN_SECONDS} * simHz ticks;
     * extrapolation cap = 2 intervals; the clock runs at simHz.
     * BEHAVIOR: a missing gameFps is the map's framesPerSecond; a missing simHz is the rounded gameFps (the
     * server ticks once per C frame, so frameFraction is then 1); a missing snapshotHz is
     * simHz / {@link Protocol#DEFAULT_SNAPSHOT_DIVISOR}.
     * BEHAVIOR: cannons alive, fuel stations full, targets intact, no base owners; clears all state.
     */
    public void configure(WorldMap map, Options opts, Msg.Welcome w) {
        worldW = map.pixelWidth();
        worldH = map.pixelHeight();
        wrap = map.edgeWrap();
        reader.setWorld(worldW, worldH, wrap);
        float gameFps = w.gameFps > 0 ? w.gameFps : opts.framesPerSecond();
        int simHz = w.simHz > 0 ? w.simHz : Math.max(1, Math.round(gameFps));
        int snapHz = w.snapshotHz > 0 ? w.snapshotHz : simHz / Protocol.DEFAULT_SNAPSHOT_DIVISOR;
        frameFraction = gameFps / simHz;
        snapshotInterval = (double) simHz / snapHz;
        delayTicks = snapshotInterval + JITTER_MARGIN_SECONDS * simHz;
        maxExtrapolateTicks = 2 * snapshotInterval;
        sparks.configure(opts.getInt(Opt.SHOT_LIFE), opts.framesPerSecond(), worldW, worldH, wrap);
        shotSpeed = opts.getDouble(Opt.SHOT_SPEED);
        clock.reset(simHz);
        cannonFlags = new byte[map.cannons().size()];
        cannonPx = new float[map.cannons().size()];
        cannonPy = new float[map.cannons().size()];
        for (int i = 0; i < cannonPx.length; i++) {
            cannonPx[i] = (float) map.cannons().get(i).x();
            cannonPy[i] = (float) map.cannons().get(i).y();
        }
        fuelLevel = new float[map.fuels().size()];
        targetFlags = new byte[map.targets().size()];
        targetLeft = new float[map.targets().size()];
        treasureFlags = new byte[map.treasures().size()];
        baseOwner = new int[map.bases().size()];
        configured = true;
        reset();
    }

    /** BEHAVIOR: forgets snapshots, particles, radar, items and map object state (keeps the map). */
    public void reset() {
        for (SnapshotFrame f : ring) {
            f.valid = false;
        }
        haveNewest = false;
        particles.clear();
        radarCount = 0;
        haveRadar = false;
        Arrays.fill(cannonFlags, (byte) 0);
        Arrays.fill(fuelLevel, 1f);
        Arrays.fill(targetFlags, (byte) 0);
        Arrays.fill(targetLeft, 1f);
        Arrays.fill(treasureFlags, (byte) 0);
        Arrays.fill(baseOwner, Protocol.NO_ID);
        Arrays.fill(selfItems, 0);
        selfLoseItem = 0;
        damaged = 0;
        roundDelay = 0;
        destruct = 0;
        thrustMax = 0;
        shieldMax = 0;
        phasingMax = 0;
        shutdownCount = -1;
        haveTimeLeft = false;
        clock.reset(clock.simHz());
        stepCount = 0;
        stepNext = 0;
        outShips = 0;
        outShots = 0;
        outPoints = 0;
        outObjs.clear();
        outSelf = false;
        havePrediction = false;
        haveSelfState = false;
        selfPlaying = false;
        teleportPending = false;
    }

    /**
     * R15: the own ship teleported (wormhole, hyperjump, respawn) at or before {@code tick}: the first SELF
     * from that tick on is snapped to, never blended in.
     */
    public void markSelfTeleport(int tick) {
        teleportPending = true;
        teleportTick = tick;
    }

    /** True while a teleport mark waits for its SELF ({@link #markSelfTeleport}). */
    public boolean selfTeleportPending() { return teleportPending; }

    /** Forget every frame's association (tests: time a fresh association in {@link #update}). */
    void invalidateAssociations() {
        for (SnapshotFrame f : ring) {
            f.assocValid = false;
        }
    }

    /** Override the interpolation delay (ticks behind the estimated server tick). */
    public void setInterpolationDelayTicks(double d) {
        delayTicks = d;
    }

    // ================================================================ ingest

    /**
     * Decode one SNAPSHOT body (position at the snapshot header, limit at its end).
     * <p>
     * BEHAVIOR: a tick newer than all others recycles the oldest frame ({@link #NEW_TICK}) and becomes the
     * pending clock sample (taken at the next {@link #update}); another part of a tick in the ring is merged ({@link #PART_ADDED}); a repeated part is
     * ignored ({@link #DUPLICATE}); an older tick is inserted if newer than the oldest frame or a slot
     * is free ({@link #LATE_TICK}), else ignored ({@link #STALE}).
     * BEHAVIOR: thrust sparks are generated only for ticks after the previous newest tick (each tick
     * once), at most {@link #MAX_SPARK_TICKS} per ship; late ticks generate none.
     * BEHAVIOR: map object state, radar, own items and HUD timers are applied only from the newest tick.
     * BEHAVIOR: the frame is associated ({@link #associate}) with the frames of the ticks just before and
     * after it when they are in the ring, so shot velocities carry from frame to frame even when the renderer
     * skips a pair.
     * BEHAVIOR: SOUND records are appended to {@link #sounds()} only from the newest tick (a late or repeated
     * part plays nothing).
     * BEHAVIOR: malformed data stops decoding at the error and returns {@link #MALFORMED}; so does a part
     * number of {@link #MAX_PARTS} or more (nothing is merged, so repeats cannot double a frame).
     */
    public int ingest(ByteBuffer body, long nowNanos) {
        if (!configured) {
            return NO_MAP;
        }
        if (!reader.begin(body)) {
            malformedCount++;
            return MALFORMED;
        }
        int tick = reader.tick();
        int part = reader.part();
        if (part >= MAX_PARTS) {
            malformedCount++;
            return MALFORMED;
        }
        long partBit = 1L << part;
        int result;
        SnapshotFrame f = frameFor(tick);
        if (f != null) {
            if ((f.parts & partBit) != 0) {
                return DUPLICATE;
            }
            result = PART_ADDED;
        } else if (!haveNewest || tick - newestTick > 0) {
            f = oldestOrFree();
            f.begin(tick, nowNanos, haveNewest ? Math.max(newestTick + 1, tick - MAX_SPARK_TICKS + 1)
                : tick - snapshotIntervalInt() + 1);
            if (haveNewest) {
                tickSteps[stepNext] = tick - newestTick;
                stepNext = (stepNext + 1) % STEP_WINDOW;
                stepCount = Math.min(stepCount + 1, STEP_WINDOW);
            }
            newestTick = tick;
            haveNewest = true;
            tempWorms = 0;
            samplePending = true;
            sampleTick = tick;
            sampleNanos = nowNanos;
            result = NEW_TICK;
        } else {
            SnapshotFrame o = oldestOrFree();
            if (o.valid && tick - o.tick <= 0) {
                return STALE;
            }
            f = o;
            f.begin(tick, nowNanos, tick + 1);
            result = LATE_TICK;
        }
        f.parts |= partBit;
        f.version++;
        ingested++;
        boolean newest = tick == newestTick;
        int firstShip = f.ships;
        boolean selfBefore = f.hasSelf;
        while (reader.nextSection()) {
            if (!reader.known()) {
                continue;
            }
            decodeSection(f, newest);
        }
        generateSparks(f, firstShip, !selfBefore && f.hasSelf);
        int partCount = reader.partCount();
        long all = partCount >= 64 ? -1L : (1L << partCount) - 1;
        f.complete = partCount > 0 && (f.parts & all) == all;
        if (predictor != null && !f.shotsConfirmed && f.complete) {
            f.shotsConfirmed = true;
            predictor.shots().confirm(f.tick, Math.min(f.shots, shotCap), f.shotX, f.shotY, f.shotVariant,
                f.shotOwnMatched);
        }
        if (f.hasSelf && (!haveSelfState || f.tick - selfStateTick >= 0)) {
            haveSelfState = true;
            selfStateTick = f.tick;
            selfPlaying = (f.selfFlags & Records.ShipFlags.DEAD) == 0;
        }
        SnapshotFrame prev = neighbour(tick, -1);
        if (prev != null) {
            associate(prev, f);
        }
        SnapshotFrame after = neighbour(tick, 1);
        if (after != null) {
            associate(f, after);
        }
        if (reader.malformed()) {
            malformedCount++;
            return MALFORMED;
        }
        return result;
    }

    private int snapshotIntervalInt() {
        return Math.max(1, (int) Math.round(snapshotInterval));
    }

    /**
     * BEHAVIOR: the nearest valid frame before ({@code dir} -1) or after (+1) {@code tick}, at most two snapshot
     * intervals away (a lost snapshot between them is fine), else null.
     */
    private SnapshotFrame neighbour(int tick, int dir) {
        SnapshotFrame best = null;
        int reach = (int) Math.ceil(2 * snapshotInterval);
        for (SnapshotFrame f : ring) {
            int d = (f.tick - tick) * dir;
            if (f.valid && d > 0 && d <= reach && (best == null || (f.tick - best.tick) * dir < 0)) {
                best = f;
            }
        }
        return best;
    }

    /** The frame of {@code tick} held in the ring, else null (for tests). */
    SnapshotFrame frameAt(int tick) {
        return frameFor(tick);
    }

    private SnapshotFrame frameFor(int tick) {
        for (SnapshotFrame f : ring) {
            if (f.valid && f.tick == tick) {
                return f;
            }
        }
        return null;
    }

    private SnapshotFrame oldestOrFree() {
        SnapshotFrame best = null;
        for (SnapshotFrame f : ring) {
            if (!f.valid) {
                return f;
            }
            if (best == null || f.tick - best.tick < 0) {
                best = f;
            }
        }
        return best;
    }

    private void decodeSection(SnapshotFrame f, boolean newest) {
        int n = reader.count();
        switch (reader.type()) {
            case Section.SELF -> {
                if (n > 0) {
                    readSelf(f);
                }
            }
            case Section.SHIP -> {
                for (int i = 0; i < n && f.ships < f.shipCap; i++) {
                    shipRec.wrap(reader, i);
                    int k = f.ships++;
                    f.shipId[k] = shipRec.id();
                    f.shipFlags[k] = shipRec.flags();
                    f.shipX[k] = shipRec.x();
                    f.shipY[k] = shipRec.y();
                    f.shipHeading[k] = shipRec.heading();
                    f.shipVx[k] = shipRec.vx();
                    f.shipVy[k] = shipRec.vy();
                }
            }
            case Section.SHOT -> {
                byte variant = (byte) reader.variant();
                for (int i = 0; i < n && f.shots < f.shotCap; i++) {
                    shotRec.wrap(reader, i);
                    int k = f.shots++;
                    f.shotX[k] = shotRec.x();
                    f.shotY[k] = shotRec.y();
                    f.shotVx[k] = (byte) shotRec.vx();
                    f.shotVy[k] = (byte) shotRec.vy();
                    f.shotWireVx[k] = shotRec.vxf();
                    f.shotWireVy[k] = shotRec.vyf();
                    f.shotVariant[k] = variant;
                    f.shotEstVx[k] = f.shotVx[k];
                    f.shotEstVy[k] = f.shotVy[k];
                    f.shotState[k] = f.shotVx[k] != 0 || f.shotVy[k] != 0 ? Tracker.KNOWN : Tracker.UNKNOWN;
                    f.shotNext[k] = -1;
                    f.shotAge[k] = 0;
                    f.shotSeenNanos[k] = Long.MIN_VALUE;
                }
            }
            case Section.BURST -> {
                for (int i = 0; i < n; i++) {
                    burstRec.wrap(reader, i);
                    burstRec.read(burst);
                    if (isPoint(burst)) {
                        if (f.points < f.pointCap) {
                            int k = f.points++;
                            f.pointX[k] = burst.x;
                            f.pointY[k] = burst.y;
                            f.pointColor[k] = burstRec.color();
                            f.pointEstVx[k] = 0;
                            f.pointEstVy[k] = 0;
                            f.pointState[k] = Tracker.UNKNOWN;
                            f.pointNext[k] = -1;
                        }
                        continue;
                    }
                    burst.hypotenuse = sparks.hypotenuse();
                    particles.spawn(burst, rng, f.tick - burstRec.age(), burstRec.kind(), burstRec.color());
                }
            }
            case Section.CANNON -> {
                if (newest) {
                    for (int i = 0; i < n; i++) {
                        cannonRec.wrap(reader, i);
                        int idx = cannonRec.index();
                        if (idx < cannonFlags.length) {
                            cannonFlags[idx] = (byte) cannonRec.flags();
                        }
                    }
                }
            }
            case Section.FUEL -> {
                if (newest) {
                    for (int i = 0; i < n; i++) {
                        fuelRec.wrap(reader, i);
                        int idx = fuelRec.index();
                        if (idx < fuelLevel.length) {
                            fuelLevel[idx] = fuelRec.fraction();
                        }
                    }
                }
            }
            case Section.TARGET -> {
                if (newest) {
                    for (int i = 0; i < n; i++) {
                        targetRec.wrap(reader, i);
                        int idx = targetRec.index();
                        if (idx < targetFlags.length) {
                            targetFlags[idx] = (byte) targetRec.flags();
                            targetLeft[idx] = targetRec.damageLeft();
                        }
                    }
                }
            }
            case Section.TREASURE -> {
                if (newest) {
                    for (int i = 0; i < n; i++) {
                        treasureRec.wrap(reader, i);
                        int idx = treasureRec.index();
                        if (idx < treasureFlags.length) {
                            treasureFlags[idx] = (byte) treasureRec.flags();
                        }
                    }
                }
            }
            case Section.RADAR -> {
                if (newest) {
                    readRadar(f.tick, n);
                }
            }
            case Section.SELF_ITEMS -> {
                if (newest && n > 0) {
                    itemsRec.wrap(reader, 0);
                    for (int i = 0; i < Item.COUNT; i++) {
                        selfItems[i] = itemsRec.count(i);
                    }
                    selfLoseItem = itemsRec.loseItem();
                }
            }
            case Section.ITEM -> {
                for (int i = 0; i < n; i++) {
                    itemRec.wrap(reader, i);
                    f.objs.add(ObjectLists.ITEM, itemRec.x(), itemRec.y(), itemRec.itemType(), 0, 0, 0);
                }
            }
            case Section.MINE -> {
                for (int i = 0; i < n; i++) {
                    mineRec.wrap(reader, i);
                    f.objs.add(ObjectLists.MINE, mineRec.x(), mineRec.y(), mineRec.flags(), mineRec.ownerId(), 0, 0);
                }
            }
            case Section.MISSILE -> {
                for (int i = 0; i < n; i++) {
                    missileRec.wrap(reader, i);
                    f.objs.add(ObjectLists.MISSILE, missileRec.x(), missileRec.y(), missileRec.heading(),
                        missileRec.kind(), missileRec.length(), missileRec.id());
                }
            }
            case Section.BALL -> {
                for (int i = 0; i < n; i++) {
                    ballRec.wrap(reader, i);
                    f.objs.add(ObjectLists.BALL, ballRec.x(), ballRec.y(), ballRec.team(), ballRec.connectedPlayer(),
                        ballRec.id(), 0);
                }
            }
            case Section.LASER -> {
                for (int i = 0; i < n; i++) {
                    laserRec.wrap(reader, i);
                    f.objs.add(ObjectLists.LASER, laserRec.x(), laserRec.y(), laserRec.heading(), laserRec.length(),
                        laserRec.color(), 0);
                }
            }
            case Section.ASTEROID -> {
                for (int i = 0; i < n; i++) {
                    asteroidRec.wrap(reader, i);
                    f.objs.add(ObjectLists.ASTEROID, asteroidRec.x(), asteroidRec.y(), asteroidRec.size(),
                        asteroidRec.rotation(), 0, 0);
                }
            }
            case Section.WRECKAGE -> {
                for (int i = 0; i < n; i++) {
                    wreckRec.wrap(reader, i);
                    f.objs.add(ObjectLists.WRECK, wreckRec.x(), wreckRec.y(), wreckRec.shape(), wreckRec.size(),
                        wreckRec.rotation(), 0);
                }
            }
            case Section.ECM -> {
                for (int i = 0; i < n; i++) {
                    ecmRec.wrap(reader, i);
                    f.objs.add(ObjectLists.ECM, ecmRec.x(), ecmRec.y(), ecmRec.radius(), 0, 0, 0);
                }
            }
            case Section.CONNECTOR -> {
                for (int i = 0; i < n; i++) {
                    connRec.wrap(reader, i);
                    f.objs.add(ObjectLists.CONNECTOR, connRec.x0(), connRec.y0(), connRec.x1(), connRec.y1(),
                        connRec.kind(), 0);
                }
            }
            case Section.TRANSPORTER -> {
                for (int i = 0; i < n; i++) {
                    transRec.wrap(reader, i);
                    f.objs.add(ObjectLists.TRANSPORTER, transRec.x0(), transRec.y0(), transRec.x1(), transRec.y1(), 0, 0);
                }
            }
            case Section.TIMERS -> {
                if (newest && n > 0) {
                    timersRec.wrap(reader, 0);
                    damaged = timersRec.damaged();
                    roundDelay = timersRec.roundDelay();
                    destruct = timersRec.destruct();
                    thrustLeft = timersRec.thrustLeft();
                    thrustMax = timersRec.thrustMax();
                    shieldLeft = timersRec.shieldLeft();
                    shieldMax = timersRec.shieldMax();
                    phasingLeft = timersRec.phasingLeft();
                    phasingMax = timersRec.phasingMax();
                    shutdownCount = timersRec.shutdownCount();
                    shutdownDelay = timersRec.shutdownDelay();
                    if (timersRec.timeLeft() >= 0) {
                        haveTimeLeft = true;
                        timeLeftValue = timersRec.timeLeft();
                        timeLeftTick = f.tick;
                    }
                }
            }
            case Section.SOUND -> {
                if (newest) {
                    for (int i = 0; i < n; i++) {
                        soundRec.wrap(reader, i);
                        sounds.add(soundRec.event(), soundRec.volume());
                        if (soundRec.event() == com.fulcrologic.jxpilot.common.SoundEvent.WORM_HOLE.ordinal()
                            || soundRec.event() == com.fulcrologic.jxpilot.common.SoundEvent.HYPERJUMP.ordinal()) {
                            markSelfTeleport(f.tick); // R15: C plays these where the own ship warped
                        }
                    }
                }
            }
            case Section.TEMP_WORMHOLE -> {
                if (newest) {
                    for (int i = 0; i < n && tempWorms < TEMP_WORMHOLES; i++) {
                        tempWormRec.wrap(reader, i);
                        tempWormBx[tempWorms] = tempWormRec.bx();
                        tempWormBy[tempWorms] = tempWormRec.by();
                        tempWorms++;
                    }
                }
            }
            default -> { } // newer sections are not modelled
        }
    }

    private void readSelf(SnapshotFrame f) {
        selfRec.wrap(reader, 0);
        f.hasSelf = true;
        f.selfId = selfRec.id();
        f.selfFlags = selfRec.flags();
        f.selfX = selfRec.x();
        f.selfY = selfRec.y();
        f.selfVx = selfRec.vx();
        f.selfVy = selfRec.vy();
        f.selfHeading = selfRec.heading();
        f.selfFuel = selfRec.fuel();
        f.selfFuelMax = selfRec.fuelMax();
        f.selfLockId = selfRec.lockId();
        f.selfNumTanks = selfRec.numTanks();
        f.selfCurrentTank = selfRec.currentTank();
        f.selfRespawnFrames = selfRec.respawnFrames();
        f.selfPower = selfRec.power();
        f.selfTurnSpeed = selfRec.turnSpeed();
        f.selfTurnRes255 = selfRec.turnResistance255();
        f.selfLockDist = selfRec.lockDist();
        f.selfLockDir = selfRec.lockDir();
        f.selfCheck = selfRec.check();
        f.selfMods = selfRec.mods();
    }

    private void readRadar(int tick, int n) {
        if (!haveRadar || radarTick != tick) {
            radarCount = 0;
            radarTick = tick;
            haveRadar = true;
        }
        for (int i = 0; i < n && radarCount < radarCap; i++) {
            radarRec.wrap(reader, i);
            radarX[radarCount] = radarRec.x();
            radarY[radarCount] = radarRec.y();
            radarFlags[radarCount] = radarRec.flags();
            radarAsteroidSize[radarCount] = (radarRec.flags() & Records.Radar.FLAG_ASTEROID) != 0 ? reader.variant() : 0;
            radarCount++;
        }
    }

    /**
     * BEHAVIOR: the own ship sparks from its SELF record quantized like the wire (what the server seeds
     * from: half-pixel position, 1/16 px velocity, RES heading) and is marked as own exhaust; other ships
     * spark from their SHIP records (already wire-quantized).
     */
    private void generateSparks(SnapshotFrame f, int firstShip, boolean selfInPart) {
        if (f.sparkFrom - f.tick > 0) {
            return;
        }
        int selfId = f.hasSelf ? f.selfId : players.selfId();
        if (selfInPart && thrusting(f.selfFlags) && !ownSparksPredicted()) {
            emitTicks(f, f.selfId, ThrustSparkEmitter.quantizePos(f.selfX), ThrustSparkEmitter.quantizePos(f.selfY),
                f.selfHeading, ThrustSparkEmitter.quantizeVel(f.selfVx), ThrustSparkEmitter.quantizeVel(f.selfVy),
                true);
        }
        for (int i = firstShip; i < f.ships; i++) {
            if (f.shipId[i] != selfId && thrusting(f.shipFlags[i])) {
                emitTicks(f, f.shipId[i], f.shipX[i], f.shipY[i], f.shipHeading[i], f.shipVx[i], f.shipVy[i], false);
            }
        }
    }

    /** The predictor draws the own ship (and makes its exhaust from the predicted ticks). */
    private boolean ownSparksPredicted() {
        return predictor != null && predictor.active();
    }

    private static boolean thrusting(int flags) {
        return (flags & Records.ShipFlags.THRUSTING) != 0 && (flags & Records.ShipFlags.DEAD) == 0;
    }

    private void emitTicks(SnapshotFrame f, int id, double x, double y, float heading, float vx, float vy,
                           boolean own) {
        int slot = players.slotOf(id);
        float power = slot >= 0 ? players.power(slot) : 45f;
        int ab = slot >= 0 ? players.afterburners(slot) : 0;
        int ex = slot >= 0 ? players.engineX(slot) : defaultEngineX;
        int ey = slot >= 0 ? players.engineY(slot) : defaultEngineY;
        for (int t = f.sparkFrom; t - f.tick <= 0; t++) {
            double back = (double) (f.tick - t) * frameFraction;
            sparks.emit(t, id, x - vx * back, y - vy * back, heading, vx, vy, power, ab, ex, ey, own, particles);
        }
    }

    // ================================================================ render

    /**
     * Compute everything the renderer draws for local time {@code nowNanos}.
     * <p>
     * BEHAVIOR: first feeds the pending clock sample (the newest tick of the batch ingested since the
     * last update) to the clock; renderTick = estimated server tick - delay.
     * BEHAVIOR: ships come from the earliest frame after the render tick (B), interpolated from the latest
     * frame at or before it (A) by t = (renderTick - A)/(B - A) with wrap-aware deltas; ships only in B
     * are drawn at B; ships only in A (B's part lost, or the ship left) are extrapolated from A like the
     * no-B case, so they do not flicker; ships that moved more than {@link #TELEPORT_PX} are snapped to B;
     * flags come from the nearer frame. With no B, ships are extrapolated from A by their velocity, at most
     * 2 snapshot intervals; with no A, B is drawn as is. The own ship is not in the ship list. Ships without
     * an id (C paused ships) are never interpolated or extrapolated: drawn as A has them (F6).
     * BEHAVIOR: M2 objects (items, mines, missiles, balls, lasers, asteroids, wreckage, ECM, connectors,
     * transporters) come from A (else B), balls and missiles interpolated ({@link #drawObjects}).
     * BEHAVIOR: shots and point particles are linked from A to their continuations in B
     * ({@link #associate}) and interpolated; unlinked ones move by their velocity (wire, else estimated)
     * from A; with only B they are drawn at B (see {@link #drawShots}).
     * BEHAVIOR: the own ship is predicted to the server tick now ({@link #predictSelf}); the line ends that are on it
     * (refuel, ball pick-up, tractor/pressor and transporter beams) follow it ({@link #anchorOwnLines}).
     * BEHAVIOR: with the own-ship predictor active, shots are instead dead-reckoned to the predicted tick
     * ({@link #drawShotsPredicted}).
     * BEHAVIOR: particles are advanced to the render tick, the own exhaust to the own ship's drawn tick.
     */
    public void update(long nowNanos) {
        updateNanos = nowNanos;
        outShips = 0;
        outShots = 0;
        outTracers = 0;
        outPoints = 0;
        outObjs.clear();
        outSelf = false;
        if (!haveNewest) {
            return;
        }
        if (samplePending) {
            clock.sample(sampleTick, sampleNanos);
            samplePending = false;
        }
        serverTickNow = clock.serverTick(nowNanos);
        renderTick = serverTickNow - delayTicks;
        SnapshotFrame a = null;
        SnapshotFrame b = null;
        for (SnapshotFrame f : ring) {
            if (!f.valid) {
                continue;
            }
            double d = f.tick - renderTick;
            if (d <= 0) {
                if (a == null || f.tick - a.tick > 0) {
                    a = f;
                }
            } else if (b == null || f.tick - b.tick < 0) {
                b = f;
            }
        }
        interpolateShips(a, b);
        if (a != null && b != null) {
            associate(a, b);
        }
        drawShots(a, b);
        drawPoints(a, b);
        drawObjects(a, b);
        predictSelf(nowNanos);
        anchorOwnLines(a, b);
        particles.step(renderTick, outSelf ? drawnTick : renderTick, frameFraction, worldW, worldH, wrap);
    }

    /**
     * The ship id the SELF record of {@code f} stands for (drawn from SELF, so not from the ship list), else the
     * player id. While watching another player's ship that is the watched player, so our own ship (usually
     * paused) stays in the ship list and is drawn, as in C.
     */
    private int selfShipId(SnapshotFrame f) {
        return f.hasSelf ? f.selfId : players.selfId();
    }

    private void interpolateShips(SnapshotFrame a, SnapshotFrame b) {
        if (b == null) {
            extrapolateShips(a, selfShipId(a), false);
            return;
        }
        int selfId = selfShipId(b);
        if (a != null) {
            shipIndex.clear();
            for (int j = 0; j < a.ships; j++) {
                if (a.shipId[j] != Protocol.NO_ID) {
                    shipIndex.put(a.shipId[j], j);
                }
                usedA[j] = false;
            }
        }
        double t = a == null ? 1 : (renderTick - a.tick) / (double) (b.tick - a.tick);
        for (int i = 0; i < b.ships; i++) {
            int id = b.shipId[i];
            if (id == selfId || id == Protocol.NO_ID && a != null) {
                continue; // F6: ships without an id (C paused ships) are drawn from the earlier frame as they are
            }
            int j = a == null ? -1 : shipIndex.get(id);
            if (j >= 0) {
                usedA[j] = true;
            }
            if (j < 0) {
                putShip(b, i, b.shipX[i], b.shipY[i], b.shipHeading[i], b.shipFlags[i]);
                continue;
            }
            double dx = XMath.wrapDelta(b.shipX[i] - a.shipX[j], worldW, wrap);
            double dy = XMath.wrapDelta(b.shipY[i] - a.shipY[j], worldH, wrap);
            if (dx * dx + dy * dy > TELEPORT_PX * TELEPORT_PX) {
                putShip(b, i, b.shipX[i], b.shipY[i], b.shipHeading[i], b.shipFlags[i]);
                continue;
            }
            double h = a.shipHeading[j] + XMath.angleDiff(a.shipHeading[j], b.shipHeading[i]) * t;
            putShip(b, i, wrapX(a.shipX[j] + dx * t), wrapY(a.shipY[j] + dy * t),
                (float) XMath.normalizeAngle(h), t < 0.5 ? a.shipFlags[j] : b.shipFlags[i]);
        }
        if (a != null) {
            extrapolateShips(a, selfShipId(a), true);
        }
    }

    /** Ships of {@code a} (all, or only those B did not have) moved by velocity to the render tick. */
    private void extrapolateShips(SnapshotFrame a, int selfId, boolean onlyUnused) {
        double dt = clampExtrapolation(renderTick - a.tick) * frameFraction;
        for (int i = 0; i < a.ships; i++) {
            if (a.shipId[i] == selfId || onlyUnused && usedA[i]) {
                continue;
            }
            if (a.shipId[i] == Protocol.NO_ID || (a.shipFlags[i] & Records.ShipFlags.PAUSED) != 0) {
                // still: a paused ship's vx is C's pause count, not a velocity
                putShip(a, i, a.shipX[i], a.shipY[i], a.shipHeading[i], a.shipFlags[i]);
                continue;
            }
            putShip(a, i, wrapX(a.shipX[i] + a.shipVx[i] * dt), wrapY(a.shipY[i] + a.shipVy[i] * dt),
                a.shipHeading[i], a.shipFlags[i]);
        }
    }

    private double clampExtrapolation(double ticks) {
        return ticks < 0 ? 0 : Math.min(ticks, maxExtrapolateTicks);
    }

    private float wrapX(double x) {
        return (float) (wrap ? XMath.wrap(x, worldW) : x);
    }

    private float wrapY(double y) {
        return (float) (wrap ? XMath.wrap(y, worldH) : y);
    }

    private void putShip(SnapshotFrame src, int i, float x, float y, float heading, int flags) {
        if (outShips >= shipCap) {
            return;
        }
        int k = outShips++;
        outShipId[k] = src.shipId[i];
        outShipFlags[k] = flags;
        outShipX[k] = x;
        outShipY[k] = y;
        outShipHeading[k] = heading;
        outShipVx[k] = src.shipVx[i];
        outShipVy[k] = src.shipVy[i];
    }

    /**
     * BEHAVIOR: a point burst (one particle, no speed, no life) is a position-only particle the server
     * resends every frame (C debris on the XPilot path); it is tracked across frames like a shot instead
     * of living one frame in the particle pool.
     */
    static boolean isPoint(DebrisBurst d) {
        return d.count == 1 && d.maxSpeed == 0 && d.maxLife == 0;
    }

    /**
     * Link the shots and point particles of {@code a} to their continuations in the later frame {@code b}
     * (once per pair of frames and parts) with the {@link Tracker}, and estimate the velocity of {@code b}'s
     * from the links, for objects the wire sends without velocity (C fast shots and debris).
     * <p>
     * BEHAVIOR: shots of {@code a} without a velocity are first seeded ({@link #seedShots}); shots link with the
     * same variant, KNOWN tightly, the rest only when unambiguous ({@link #SHOT_AMBIGUITY_RATIO}); points link
     * with the same colour to the nearest within {@link #TRACK_PX_PER_FRAME} per frame.
     * BEHAVIOR: a later shot with a wire velocity keeps it; other linked ones get the displacement over the
     * frames (KNOWN); unlinked ones none (UNKNOWN).
     * BEHAVIOR: the moving M2 objects are associated too ({@link ObjectLists#associate}).
     * BEHAVIOR: a linked shot's age (ticks since first seen) is its link's plus the gap; it inherits its link's first
     * sight and tracer origin, and a shot new in {@code b} gets them ({@link #tracerOrigin}); except when {@code a}
     * arrived after {@code b} (late, reordered): then {@code b}'s first sights and origins are kept (no second tracer).
     */
    private void associate(SnapshotFrame a, SnapshotFrame b) {
        if (a.assocValid && a.assocTick == b.tick && a.assocParts == b.parts && a.assocOwnParts == a.parts) {
            return;
        }
        a.assocValid = true;
        b.version++; // its links, velocity estimates and ages change
        a.assocTick = b.tick;
        a.assocParts = b.parts;
        a.assocOwnParts = a.parts;
        double frames = (b.tick - a.tick) * (double) frameFraction;
        int sn = Math.min(a.shots, shotCap);
        int sm = Math.min(b.shots, shotCap);
        seedShots(a, sn);
        for (int i = 0; i < sn; i++) {
            keyA[i] = a.shotVariant[i];
        }
        for (int j = 0; j < sm; j++) {
            keyB[j] = b.shotVariant[j];
        }
        tracker.link(sn, a.shotX, a.shotY, a.shotEstVx, a.shotEstVy, a.shotState, keyA, sm, b.shotX, b.shotY, keyB,
            frames, TRACK_PX_PER_FRAME, SHOT_AMBIGUITY_RATIO, MIN_SHOT_SPEED, worldW, worldH, wrap, a.shotNext);
        int ticks = b.tick - a.tick;
        // a arrived after b (a late, reordered frame): b's bullets were drawn first, so their first sight (and
        // tracers) stay as they are; only their ages learn from a
        boolean tracers = a.arrivalNanos <= b.arrivalNanos;
        if (tracers) {
            for (int j = 0; j < sm; j++) {
                b.shotSeenNanos[j] = Long.MIN_VALUE;
                b.shotAge[j] = 0; // from the links below, or the source (a redo of the pair gives the same)
            }
        }
        for (int i = 0; i < sn; i++) {
            int j = a.shotNext[i];
            if (j >= 0 && j < sm && ticks > 0) {
                b.shotAge[j] = a.shotAge[i] + ticks;
                if (tracers) {
                    b.shotSeenNanos[j] = a.shotSeenNanos[i];
                    b.shotOriginX[j] = a.shotOriginX[i];
                    b.shotOriginY[j] = a.shotOriginY[i];
                }
            }
        }
        for (int j = 0; j < sm && tracers; j++) {
            if (b.shotAge[j] == 0 && ticks > 0 && ticks <= 2 * snapshotInterval) {
                tracerOrigin(b, j, ticks); // a new bullet: none of a's continues as it
            }
        }
        for (int j = 0; j < sm; j++) {
            if (b.shotVx[j] != 0 || b.shotVy[j] != 0) {
                continue;
            }
            int i = tracker.claimOf(j);
            if (i >= 0 && frames > 0) {
                b.shotEstVx[j] = (float) (XMath.wrapDelta(b.shotX[j] - a.shotX[i], worldW, wrap) / frames);
                b.shotEstVy[j] = (float) (XMath.wrapDelta(b.shotY[j] - a.shotY[i], worldH, wrap) / frames);
                b.shotState[j] = Tracker.KNOWN;
            } else {
                b.shotEstVx[j] = 0;
                b.shotEstVy[j] = 0;
                b.shotState[j] = Tracker.UNKNOWN;
            }
        }
        int pn = Math.min(a.points, shotCap);
        int pm = Math.min(b.points, shotCap);
        tracker.link(pn, a.pointX, a.pointY, a.pointEstVx, a.pointEstVy, a.pointState, a.pointColor, pm, b.pointX,
            b.pointY, b.pointColor, frames, TRACK_PX_PER_FRAME, 1, 0, worldW, worldH, wrap, a.pointNext);
        tracker.estimate(pm, a.pointX, a.pointY, b.pointX, b.pointY, b.pointEstVx, b.pointEstVy, b.pointState, frames);
        a.objs.associate(b.objs, tracker, objScratch, frames, worldW, worldH, wrap);
    }

    /**
     * R2: a shot without a velocity (just fired, on the C path) gets a guess from the ship nearest to it within
     * {@link #SEED_RADIUS_PX}: C {@code Fire_normal_shots}' velocity, the ship's velocity plus shotSpeed along
     * its heading.
     * <p>
     * BEHAVIOR: only UNKNOWN shots are seeded (GUESSED); the own ship (SELF) and every SHIP record are
     * candidates; with none in range the shot stays UNKNOWN.
     */
    void seedShots(SnapshotFrame a, int n) {
        for (int i = 0; i < n; i++) {
            if (a.shotState[i] != Tracker.UNKNOWN) {
                continue;
            }
            double bestD = SEED_RADIUS_PX * SEED_RADIUS_PX;
            double h = 0;
            double vx = 0;
            double vy = 0;
            boolean found = false;
            if (a.hasSelf) {
                double d = dist2(a.selfX, a.selfY, a.shotX[i], a.shotY[i]);
                if (d <= bestD) {
                    bestD = d;
                    h = a.selfHeading;
                    vx = a.selfVx;
                    vy = a.selfVy;
                    found = true;
                }
            }
            for (int s = 0; s < a.ships; s++) {
                double d = dist2(a.shipX[s], a.shipY[s], a.shotX[i], a.shotY[i]);
                if (d < bestD) {
                    bestD = d;
                    h = a.shipHeading[s];
                    boolean still = (a.shipFlags[s] & Records.ShipFlags.PAUSED) != 0; // its vx is the pause count
                    vx = still ? 0 : a.shipVx[s];
                    vy = still ? 0 : a.shipVy[s];
                    found = true;
                }
            }
            if (found) {
                a.shotEstVx[i] = (float) (vx + shotSpeed * Math.cos(h));
                a.shotEstVy[i] = (float) (vy + shotSpeed * Math.sin(h));
                a.shotState[i] = Tracker.GUESSED;
            }
        }
    }

    private double dist2(double ax, double ay, double bx, double by) {
        double dx = XMath.wrapDelta(bx - ax, worldW, wrap);
        double dy = XMath.wrapDelta(by - ay, worldH, wrap);
        return dx * dx + dy * dy;
    }

    /**
     * BEHAVIOR: between two frames a linked shot is interpolated to its continuation (wrap-aware); an
     * unlinked one with a KNOWN velocity has died (it would have been found) and stays where the earlier frame
     * has it (no overshoot); other unlinked ones move by their velocity (the wire's, else the estimate) from
     * the earlier frame; with only
     * an earlier frame every shot is extrapolated that way (at most 2 snapshot intervals); with only a later
     * frame shots are drawn where it has them.
     */
    private void drawShots(SnapshotFrame a, SnapshotFrame b) {
        outShots = 0;
        hideOwnShots = ownSparksPredicted();
        if (hideOwnShots && predictor.shots().motion() != null && drawShotsPredicted()) {
            return;
        }
        if (a == null) {
            for (int i = 0; i < b.shots && i < shotCap; i++) {
                putShot(b.shotX[i], b.shotY[i], b.shotVariant[i], b, i);
            }
            return;
        }
        double t = b == null ? 0 : (renderTick - a.tick) / (double) (b.tick - a.tick);
        double dt = clampExtrapolation(renderTick - a.tick) * frameFraction;
        for (int i = 0; i < a.shots && i < shotCap; i++) {
            int j = b == null ? -1 : a.shotNext[i];
            if (j >= 0) {
                putShot(wrapX(a.shotX[i] + XMath.wrapDelta(b.shotX[j] - a.shotX[i], worldW, wrap) * t),
                    wrapY(a.shotY[i] + XMath.wrapDelta(b.shotY[j] - a.shotY[i], worldH, wrap) * t), a.shotVariant[i],
                    a, i);
                continue;
            }
            if (b != null && a.shotState[i] == Tracker.KNOWN) {
                // F14: a tracked shot with no continuation died between A and B (wall, ship): drawn where A had
                // it, as C does, instead of flying on past the impact
                putShot(a.shotX[i], a.shotY[i], a.shotVariant[i], a, i);
                continue;
            }
            double vx = a.shotEstVx[i];
            double vy = a.shotEstVy[i];
            putShot(wrapX(a.shotX[i] + vx * dt), wrapY(a.shotY[i] + vy * dt), a.shotVariant[i], a, i);
        }
    }

    /**
     * BEHAVIOR: the M2 objects of the frame at or before the render tick (else the later one); tracked objects
     * (items, mines, missiles, balls, asteroids, wreckage) move toward their continuation in the later frame,
     * or by their estimated velocity when they have none ({@link ObjectLists#moveTracked}, extrapolation capped
     * like shots); connectors and beams move with their endpoints ({@link ObjectLists#interpolateLines}).
     */
    private void drawObjects(SnapshotFrame a, SnapshotFrame b) {
        SnapshotFrame src = a != null ? a : b;
        outObjs.copyFrom(src.objs);
        if (a == null) {
            return;
        }
        double t = b == null ? 0 : (renderTick - a.tick) / (double) (b.tick - a.tick);
        double dt = clampExtrapolation(renderTick - a.tick) * frameFraction;
        outObjs.moveTracked(a.objs, b == null ? null : b.objs, t, dt, worldW, worldH, wrap);
        if (b != null) {
            outObjs.interpolateLines(b.objs, t, TRACK_PX_PER_FRAME * (b.tick - a.tick) * frameFraction, worldW,
                worldH, wrap);
        }
    }

    /**
     * BEHAVIOR: the main-gun point of each ship of {@code f}, then of SELF: its position plus its shape's main gun
     * (else the default shape's) rotated by its heading; computed once per set of parts (the first new bullet).
     */
    private void guns(SnapshotFrame f) {
        int n = Math.min(f.ships, f.gunX.length - 1);
        for (int k = 0; k <= n; k++) {
            boolean self = k == n;
            if (self && !f.hasSelf) {
                break;
            }
            int slot = players.slotOf(self ? f.selfId : f.shipId[k]);
            com.fulcrologic.jxpilot.common.math.ShipShape shape = slot < 0 ? null : players.shape(slot);
            if (shape == null) {
                shape = DEFAULT_SHAPE;
            }
            double h = self ? f.selfHeading : f.shipHeading[k];
            double c = Math.cos(h);
            double sn = Math.sin(h);
            f.gunX[k] = (float) ((self ? f.selfX : f.shipX[k])
                + com.fulcrologic.jxpilot.common.math.ShipShape.rotX(shape.mainGunX, shape.mainGunY, c, sn));
            f.gunY[k] = (float) ((self ? f.selfY : f.shipY[k])
                + com.fulcrologic.jxpilot.common.math.ShipShape.rotY(shape.mainGunX, shape.mainGunY, c, sn));
        }
        f.gunParts = f.parts;
        gunComputations++;
    }

    private long gunComputations;

    /** Times a frame's gun points were computed (for tests: once per frame and set of parts). */
    long gunComputations() { return gunComputations; }

    private static final com.fulcrologic.jxpilot.common.math.ShipShape DEFAULT_SHAPE =
        com.fulcrologic.jxpilot.common.math.ShipShape.defaultShip();

    /** How long a new bullet's tracer is drawn (it fades out over this time). */
    public static final long TRACER_NANOS = 150_000_000L;
    /** How far from a bullet's backward path a cannon or ship may be and still be taken as its source. */
    static final double TRACER_SOURCE_PX = Const.BLOCK_SZ;

    /**
     * Where the tracer of bullet j of {@code f}, first seen in it, starts: its source. With prediction on, bullets are
     * drawn dead-reckoned to the predicted tick, so a new one would pop into view RTT plus the lead of flight away
     * from where it was fired; a short tracer from its source to where it is drawn shows where it came from.
     * <p>
     * BEHAVIOR: back along its velocity (the SHOT record's) for at most {@code ticks} (since the previous frame, when
     * it was not there yet): a cannon's bullet starts at the nearest map cannon within {@link #TRACER_SOURCE_PX} of
     * that backward path, any other at the main gun of the nearest ship of the frame (the own ship included; its shape,
     * else the default) within it. With a source
     * the bullet is marked seen at the frame's arrival and its age is the ticks back to the source (its life in dead
     * reckoning counts from there). Without one (a bullet the tracker lost mid-flight, or one flying into the view)
     * it gets no tracer and keeps age 0. A bullet without a wire velocity gets none.
     */
    private void tracerOrigin(SnapshotFrame f, int j, int ticks) {
        double vx = f.shotWireVx[j] * frameFraction;
        double vy = f.shotWireVy[j] * frameFraction;
        double v2 = vx * vx + vy * vy;
        if (v2 == 0) {
            return;
        }
        double px = f.shotX[j];
        double py = f.shotY[j];
        double best = TRACER_SOURCE_PX * TRACER_SOURCE_PX;
        double ox = 0;
        double oy = 0;
        double age = 0;
        boolean found = false;
        boolean cannon = Records.ShotKind.kindOf(f.shotVariant[j]) == Records.ShotKind.CANNON_SHOT;
        int n = cannon ? cannonPx.length : f.ships + (f.hasSelf ? 1 : 0);
        for (int k = 0; k < n; k++) {
            double sx;
            double sy;
            if (cannon) {
                sx = cannonPx[k];
                sy = cannonPy[k];
            } else { // a ship's bullet starts at its main gun (C Fire_main_shot: pos + ship->gun[dir])
                if (f.gunParts != f.parts) {
                    guns(f);
                }
                sx = f.gunX[k];
                sy = f.gunY[k];
            }
            double dx = XMath.wrapDelta(sx - px, worldW, wrap);
            double dy = XMath.wrapDelta(sy - py, worldH, wrap);
            double back = -(dx * vx + dy * vy) / v2; // ticks back along the path to the source's foot
            if (back < -1 || back > ticks + 1) {
                continue;
            }
            double ex = dx + vx * back;
            double ey = dy + vy * back;
            double d2 = ex * ex + ey * ey;
            if (d2 <= best) {
                best = d2;
                ox = px + dx;
                oy = py + dy;
                age = back;
                found = true;
            }
        }
        if (!found) {
            return; // no source: not a new bullet whose origin we can show
        }
        tracerSources++;
        f.shotAge[j] = (int) Math.max(0, Math.round(age));
        f.shotSeenNanos[j] = f.arrivalNanos;
        f.shotOriginX[j] = (float) ox;
        f.shotOriginY[j] = (float) oy;
    }

    /** How far from the own ship a line end may be and still be on it (its hull, plus the wire's rounding). */
    static final double OWN_LINE_PX = Const.SHIP_SZ + 2;

    /**
     * The lines drawn from the render tick's frames end on the own ship where the server had it then, an interpolation
     * delay (and, predicted, the input lead) behind where it is drawn. Their ends on the own ship move with it.
     * <p>
     * BEHAVIOR: with the own ship drawn from its own SELF (not while watching another player): each ship end of a
     * CONNECTOR (refuel and ball: the second end; tractor and pressor: both, the target's hull points and the beamer)
     * or of a TRANSPORTER (both ends) within {@link #OWN_LINE_PX} of the own ship at the render tick (A's SELF moved
     * towards B's as the lines are, else A's) is moved by the drawn position minus that one: an end at the ship lands
     * on the drawn ship, a hull point keeps its offset. Other ends (the fuel station, the ball) are not moved.
     * BEHAVIOR: the records carry no owner, so nearness decides, except a refuel line: only while A's SELF is
     * REFUELING (another ship refuelling next to ours is not ours). Nothing moves while A's SELF is dead or when it
     * jumped more than {@link #TELEPORT_PX} from A to B (a respawn: the render tick's position is meaningless).
     */
    private void anchorOwnLines(SnapshotFrame a, SnapshotFrame b) {
        if (!outSelf || a == null || !a.hasSelf || a.selfId != outSelfId || players.selfId() != Protocol.NO_ID
            && outSelfId != players.selfId()) {
            return;
        }
        if ((a.selfFlags & Records.ShipFlags.DEAD) != 0) {
            return;
        }
        boolean withB = b != null && b.hasSelf && b.selfId == a.selfId;
        if (withB) {
            double jx = XMath.wrapDelta(b.selfX - a.selfX, worldW, wrap);
            double jy = XMath.wrapDelta(b.selfY - a.selfY, worldH, wrap);
            if (jx * jx + jy * jy > TELEPORT_PX * TELEPORT_PX) {
                return;
            }
        }
        boolean refueling = (a.selfFlags & Records.ShipFlags.REFUELING) != 0;
        double t = !withB ? 0 : (renderTick - a.tick) / (double) (b.tick - a.tick);
        double rx = a.selfX;
        double ry = a.selfY;
        if (t > 0) {
            rx = wrapX(rx + XMath.wrapDelta(b.selfX - a.selfX, worldW, wrap) * t);
            ry = wrapY(ry + XMath.wrapDelta(b.selfY - a.selfY, worldH, wrap) * t);
        }
        double dx = XMath.wrapDelta(outSelfX - rx, worldW, wrap);
        double dy = XMath.wrapDelta(outSelfY - ry, worldH, wrap);
        ObjectLists o = outObjs;
        for (int i = 0; i < o.count(ObjectLists.CONNECTOR); i++) {
            int kind = (int) o.get(ObjectLists.CONNECTOR, i, 4);
            boolean tractor = kind == Records.Connector.KIND_TRACTOR || kind == Records.Connector.KIND_PRESSOR;
            if (tractor) {
                anchorEnd(o, ObjectLists.CONNECTOR, i, 0, rx, ry, a.selfX, a.selfY, dx, dy);
            }
            if (tractor || kind == Records.Connector.KIND_REFUEL && refueling || kind == Records.Connector.KIND_BALL) {
                anchorEnd(o, ObjectLists.CONNECTOR, i, 2, rx, ry, a.selfX, a.selfY, dx, dy);
            }
        }
        for (int i = 0; i < o.count(ObjectLists.TRANSPORTER); i++) {
            anchorEnd(o, ObjectLists.TRANSPORTER, i, 0, rx, ry, a.selfX, a.selfY, dx, dy);
            anchorEnd(o, ObjectLists.TRANSPORTER, i, 2, rx, ry, a.selfX, a.selfY, dx, dy);
        }
    }

    /**
     * Move end (field, field + 1) of line i by (dx, dy) when it is within {@link #OWN_LINE_PX} of the own ship at the
     * render tick (rx, ry), or, for a line seen only in A (not interpolated), of A's own ship (ax, ay).
     */
    private void anchorEnd(ObjectLists o, int kind, int i, int field, double rx, double ry, double ax, double ay,
                           double dx, double dy) {
        float x = o.get(kind, i, field);
        float y = o.get(kind, i, field + 1);
        double ex = XMath.wrapDelta(x - rx, worldW, wrap);
        double ey = XMath.wrapDelta(y - ry, worldH, wrap);
        double fx = XMath.wrapDelta(x - ax, worldW, wrap);
        double fy = XMath.wrapDelta(y - ay, worldH, wrap);
        double ox;
        double oy;
        if (ex * ex + ey * ey <= OWN_LINE_PX * OWN_LINE_PX) {
            ox = dx;
            oy = dy;
        } else if (fx * fx + fy * fy <= OWN_LINE_PX * OWN_LINE_PX) {
            ox = XMath.wrapDelta(outSelfX - ax, worldW, wrap);
            oy = XMath.wrapDelta(outSelfY - ay, worldH, wrap);
        } else {
            return;
        }
        o.set(kind, i, field, (float) wrapX(x + ox));
        o.set(kind, i, field + 1, (float) wrapY(y + oy));
    }

    private void putShot(float x, float y, byte variant, SnapshotFrame src, int i) {
        int k = outShots;
        putShot(x, y, variant);
        if (outShots > k) {
            outShotSrcTick[k] = src.tick;
            outShotSrcX[k] = src.shotX[i];
            outShotSrcY[k] = src.shotY[i];
        }
    }

    private void putShot(float x, float y, byte variant) {
        if (outShots >= outShotX.length || hideOwnShots && !allowOwn && (variant & 0x7F) == OWN_SHOT) {
            return; // the predictor draws the own bullets (provisional shots) on the own ship's timeline
        }
        int k = outShots++;
        outShotSrcTick[k] = Integer.MIN_VALUE;
        outShotX[k] = x;
        outShotY[k] = y;
        outShotVariant[k] = variant & 0xFF;
    }

    /**
     * The shots dead-reckoned to the own ship's predicted tick (prediction on, latency-fixes.md §4.6-B for shots): so
     * a shield pressed now is judged against the shots where they will be when the press lands, not where they were
     * an interpolation delay ago. Each shot of the newest complete frame (else the newest) moves on from it as the
     * server moves bullets ({@link com.fulcrologic.jxpilot.client.game.predict.ShotMotion}: gravity, walls) for the
     * gap to the predicted tick, the last fraction blended; its velocity is the SHOT record's (exact to 1/16 px per
     * frame; the tracker's estimate for a record without one). Its team: the own team for a bullet the server
     * colours TEAM, else none (an enemy's team is not on the wire: its own cannons and targets crash it here).
     * <p>
     * BEHAVIOR: the gap is capped at the own ship's lead over the server's tick now plus 2 snapshot intervals; a shot
     * that ends on the way (a wall, or its life: shotLife for a player's bullet from the tick it was first seen, at
     * most {@link #CANNON_SHOT_LIFE_MAX} for a cannon's) is not drawn. The own bullets are the provisional shots',
     * except own records no provisional shot was matched to in the frame's reconciliation (bullets the client did not
     * predict), which are reckoned like the others. Returns false without a frame (the interpolated drawing then
     * runs).
     */
    private boolean drawShotsPredicted() {
        SnapshotFrame n = null;
        SnapshotFrame any = null;
        for (SnapshotFrame f : ring) {
            if (!f.valid) {
                continue;
            }
            if (any == null || f.tick - any.tick > 0) {
                any = f;
            }
            if (f.complete && (n == null || f.tick - n.tick > 0)) {
                n = f;
            }
        }
        if (n == null) {
            n = any;
        }
        if (n == null) {
            return false;
        }
        com.fulcrologic.jxpilot.client.game.predict.ShotMotion m = predictor.shots().motion();
        double target = predictor.drawnTick();
        double cap = Math.max(0, target - serverTickNow) + 2 * snapshotInterval;
        double gap = Math.max(0, Math.min(target - n.tick, cap));
        int whole = (int) gap;
        double frac = gap - whole;
        int team0 = predictor.shots().team();
        boolean cached = drFrame == n && drVersion == n.version && drWhole == whole && drMotion == m && drTeam == team0;
        if (!cached) {
            drFrame = n;
            drVersion = n.version;
            drWhole = whole;
            drMotion = m;
            drTeam = team0;
            stepWhole(n, m, whole, team0);
            wholeSteps++;
        }
        for (int j = 0; j < n.shots && j < shotCap; j++) {
            if (!drDraw[j]) {
                continue;
            }
            int x = drX[j];
            int y = drY[j];
            double px = x / (double) com.fulcrologic.jxpilot.common.sim.Click.CLICK;
            double py = y / (double) com.fulcrologic.jxpilot.common.sim.Click.CLICK;
            if (frac > 0 && m.tick(x, y, drVx[j], drVy[j], drLife[j], drCannon[j], drTeams[j], shotStep)) {
                px += frac * XMath.wrapDelta(shotStep.x / (double) com.fulcrologic.jxpilot.common.sim.Click.CLICK - px,
                    worldW, wrap);
                py += frac * XMath.wrapDelta(shotStep.y / (double) com.fulcrologic.jxpilot.common.sim.Click.CLICK - py,
                    worldH, wrap);
            }
            allowOwn = (n.shotVariant[j] & 0x7F) == OWN_SHOT;
            putShot(wrapX(px), wrapY(py), n.shotVariant[j], n, j);
            allowOwn = false;
            reckoned++;
            putTracer(n, j, px, py);
        }
        return true;
    }

    /**
     * BEHAVIOR: a bullet of {@code n} first seen less than {@link #TRACER_NANOS} ago gets a tracer from its origin
     * ({@link #tracerOrigin}, the copy nearest the bullet) to where it is drawn, with alpha 1 at first sight falling
     * linearly to 0.
     */
    private void putTracer(SnapshotFrame n, int j, double px, double py) {
        long seen = n.shotSeenNanos[j];
        if (seen == Long.MIN_VALUE || outTracers >= outTracerX1.length) {
            return;
        }
        long age = updateNanos - seen;
        if (age < 0 || age >= TRACER_NANOS) {
            return;
        }
        int k = outTracers++;
        outTracerX2[k] = wrapX(px);
        outTracerY2[k] = wrapY(py);
        outTracerX1[k] = (float) (outTracerX2[k] - XMath.wrapDelta(outTracerX2[k] - n.shotOriginX[j], worldW, wrap));
        outTracerY1[k] = (float) (outTracerY2[k] - XMath.wrapDelta(outTracerY2[k] - n.shotOriginY[j], worldH, wrap));
        outTracerAlpha[k] = 1f - (float) age / TRACER_NANOS;
        outTracerVariant[k] = n.shotVariant[j] & 0xFF;
        tracersDrawn++;
    }

    /** Tracers drawn by the last {@link #update} (new bullets, prediction on). */
    public int tracerCount() { return outTracers; }

    /** Tracer i's start: its bullet's source (unwrapped: the copy nearest {@link #tracerX2}). */
    public float tracerX1(int i) { return outTracerX1[i]; }

    public float tracerY1(int i) { return outTracerY1[i]; }

    /** Tracer i's end: where its bullet is drawn. */
    public float tracerX2(int i) { return outTracerX2[i]; }

    public float tracerY2(int i) { return outTracerY2[i]; }

    /** Tracer i's alpha, 1 at the bullet's first sight down to 0 after {@link #TRACER_NANOS}. */
    public float tracerAlpha(int i) { return outTracerAlpha[i]; }

    /** Tracer i's bullet's SHOT variant (kind and colour). */
    public int tracerVariant(int i) { return outTracerVariant[i]; }

    private long tracerSources;

    /** New bullets whose source was found ({@link #tracerOrigin}) since the start. */
    public long tracerSources() { return tracerSources; }

    /** Tracers drawn since the start (all updates). */
    public long tracersDrawn() { return tracersDrawn; }

    /** C CANNON_SHOT_LIFE_MAX bound: a cannon's bullet lives 8 + rand(24) ticks, at most 31. */
    static final int CANNON_SHOT_LIFE_MAX = 31;


    /**
     * Step each drawable bullet of {@code n} by the gap's whole ticks into the dead reckoning's cache (redone only when
     * the frame, its contents, the whole ticks, the motion or the own team changed: the per-frame work is then one
     * fractional step per bullet).
     */
    private void stepWhole(SnapshotFrame n, com.fulcrologic.jxpilot.client.game.predict.ShotMotion m, int whole,
                           int ownTeam) {
        for (int j = 0; j < n.shots && j < shotCap; j++) {
            drDraw[j] = false;
            boolean own = (n.shotVariant[j] & 0x7F) == OWN_SHOT;
            if (own && !(n.shotsConfirmed && !n.shotOwnMatched[j])) {
                continue; // a provisional shot stands for it (or the frame was not reconciled: not known)
            }
            // the wire's velocity is exact to 1/16 px per frame; a record without one has the tracker's estimate
            boolean wire = n.shotWireVx[j] != 0 || n.shotWireVy[j] != 0;
            float vx = (wire ? n.shotWireVx[j] : n.shotEstVx[j]) * frameFraction;
            float vy = (wire ? n.shotWireVy[j] : n.shotEstVy[j]) * frameFraction;
            int x = com.fulcrologic.jxpilot.common.sim.Click.fromPixels(n.shotX[j]);
            int y = com.fulcrologic.jxpilot.common.sim.Click.fromPixels(n.shotY[j]);
            boolean cannon = Records.ShotKind.kindOf(n.shotVariant[j]) == Records.ShotKind.CANNON_SHOT;
            // the server marks a team mate's (or own team cannon's) bullet TEAM: the own team; any other is not ours
            int team = own || Records.ShotKind.colorOf(n.shotVariant[j]) == Records.ObjColor.TEAM
                ? ownTeam : com.fulcrologic.jxpilot.common.Const.TEAM_NOT_SET;
            // its life left after the frame's tick, as far as it is knowable: a player's bullet lives shotLife ticks
            // from the tick it was first seen (or earlier), a cannon's at most CANNON_SHOT_LIFE_MAX
            long life = (cannon ? CANNON_SHOT_LIFE_MAX : predictor.shots().shotLife()) - n.shotAge[j] - 1;
            boolean alive = life > 0;
            for (int k = 0; k < whole && alive; k++) {
                alive = m.tick(x, y, vx, vy, life, cannon, team, shotStep);
                if (alive) {
                    x = shotStep.x;
                    y = shotStep.y;
                    vx = shotStep.vx;
                    vy = shotStep.vy;
                    life = shotStep.life;
                }
            }
            if (!alive) {
                continue; // it ends on the way (a wall, its life): not drawn stuck at the wall
            }
            drDraw[j] = true;
            drX[j] = x;
            drY[j] = y;
            drVx[j] = vx;
            drVy[j] = vy;
            drLife[j] = life;
            drCannon[j] = cannon;
            drTeams[j] = team;
        }
    }

    private long wholeSteps;
    private long reckoned;

    /** Times the dead reckoning stepped its bullets' whole ticks (the cache missed). */
    public long reckoningSteps() { return wholeSteps; }

    /** Bullet-frames drawn dead-reckoned. */
    public long reckonedShots() { return reckoned; }

    // the dead reckoning's cache of the bullets' states after the gap's whole ticks
    private SnapshotFrame drFrame;
    private long drVersion;
    private int drWhole;
    private com.fulcrologic.jxpilot.client.game.predict.ShotMotion drMotion;
    private int drTeam;
    private final boolean[] drDraw;
    private final int[] drX;
    private final int[] drY;
    private final float[] drVx;
    private final float[] drVy;
    private final long[] drLife;
    private final boolean[] drCannon;
    private final int[] drTeams;

    private final com.fulcrologic.jxpilot.client.game.predict.ShotStep shotStep =
        new com.fulcrologic.jxpilot.client.game.predict.ShotStep();

    /** As {@link #drawShots}, for position-only point particles (no wire velocity). */
    private void drawPoints(SnapshotFrame a, SnapshotFrame b) {
        outPoints = 0;
        SnapshotFrame src = a != null ? a : b;
        if (src == null) {
            return;
        }
        double t = a == null || b == null ? 0 : (renderTick - a.tick) / (double) (b.tick - a.tick);
        double dt = a == null ? 0 : clampExtrapolation(renderTick - a.tick) * frameFraction;
        for (int i = 0; i < src.points && outPoints < outPointX.length; i++) {
            int j = a != null && b != null ? a.pointNext[i] : -1;
            float x;
            float y;
            if (j >= 0) {
                x = wrapX(a.pointX[i] + XMath.wrapDelta(b.pointX[j] - a.pointX[i], worldW, wrap) * t);
                y = wrapY(a.pointY[i] + XMath.wrapDelta(b.pointY[j] - a.pointY[i], worldH, wrap) * t);
            } else {
                x = wrapX(src.pointX[i] + src.pointEstVx[i] * dt);
                y = wrapY(src.pointY[i] + src.pointEstVy[i] * dt);
            }
            int k = outPoints++;
            outPointX[k] = x;
            outPointY[k] = y;
            outPointColor[k] = src.pointColor[i];
        }
    }

    /**
     * F1, the own ship (and the camera following it) with low latency and no sawtooth: predicted from the newest
     * SELF to the estimated server tick now ({@link #serverTickNow}), with its velocity and an acceleration
     * estimated from the previous SELF; when a new SELF changes the prediction, the difference to what was
     * drawn is kept as an error offset that decays over a few render frames ({@link #ERROR_DECAY_SECONDS})
     * instead of snapping.
     * <p>
     * BEHAVIOR: drawn at {@link #selfDrawnTick()} = serverTickNow, the prediction horizon clamped to
     * [-1, 2 snapshot intervals] ticks from the newest SELF; the newest heading is used as is; flags, fuel and
     * the rest come from the newest SELF.
     * BEHAVIOR: acceleration = (newest velocity - previous velocity) / frames between them, when the previous
     * SELF is at most 3 ticks older, alive and within {@link #TELEPORT_PX}; else 0; clamped to
     * {@link #MAX_SELF_ACCEL} per axis.
     * BEHAVIOR: while dead, after a teleport (prediction error beyond {@link #TELEPORT_PX}, or the server's
     * signal: {@link #markSelfTeleport}, R15) or on the first SELF the ship is drawn at the newest SELF's
     * position without error offset.
     */
    private void predictSelf(long nowNanos) {
        SnapshotFrame n = null;
        SnapshotFrame p = null;
        for (SnapshotFrame f : ring) {
            if (!f.valid || !f.hasSelf) {
                continue;
            }
            if (n == null || f.tick - n.tick > 0) {
                p = n;
                n = f;
            } else if (p == null || f.tick - p.tick > 0) {
                p = f;
            }
        }
        if (n == null) {
            havePrediction = false;
            predicted = false;
            return;
        }
        outSelf = true;
        drawnSelfTick = n.tick;
        if (predictor != null && predictor.active() && n.selfId == players.selfId()) {
            drawPredicted(n);
            havePrediction = false;
            return;
        }
        drainExhaust(n, false);
        predicted = false;
        provisionalShots = 0;
        double decay = havePrediction ? Math.exp(-Math.max(0, nowNanos - lastPredictNanos) / 1e9 / ERROR_DECAY_SECONDS)
            : 0;
        lastPredictNanos = nowNanos;
        errX *= decay;
        errY *= decay;
        double ticks = serverTickNow - n.tick;
        ticks = Math.max(-1, Math.min(ticks, maxExtrapolateTicks));
        drawnTick = n.tick + ticks;
        boolean dead = (n.selfFlags & Records.ShipFlags.DEAD) != 0;
        if (dead) {
            putSelf(n, n.selfX, n.selfY, n.selfHeading);
            havePrediction = false;
            errX = 0;
            errY = 0;
            return;
        }
        double ax = 0;
        double ay = 0;
        if (p != null && n.tick - p.tick <= 3 && (p.selfFlags & Records.ShipFlags.DEAD) == 0) {
            double dx = XMath.wrapDelta(n.selfX - p.selfX, worldW, wrap);
            double dy = XMath.wrapDelta(n.selfY - p.selfY, worldH, wrap);
            if (dx * dx + dy * dy <= TELEPORT_PX * TELEPORT_PX) {
                double frames = (n.tick - p.tick) * (double) frameFraction;
                ax = clampAccel((n.selfVx - p.selfVx) / frames);
                ay = clampAccel((n.selfVy - p.selfVy) / frames);
            }
        }
        double dt = ticks * frameFraction;
        double px = n.selfX + n.selfVx * dt + 0.5 * ax * dt * dt;
        double py = n.selfY + n.selfVy * dt + 0.5 * ay * dt * dt;
        if (havePrediction && n.tick != predTick) {
            // the old trajectory evaluated now, plus its decayed error, is what the eye expects: keep it
            double odt = (drawnTick - predTick) * frameFraction;
            double ox = predX + predVx * odt + 0.5 * predAx * odt * odt + errX;
            double oy = predY + predVy * odt + 0.5 * predAy * odt * odt + errY;
            errX = XMath.wrapDelta(ox - px, worldW, wrap);
            errY = XMath.wrapDelta(oy - py, worldH, wrap);
            boolean warped = teleportPending && n.tick - teleportTick >= 0;
            if (warped) {
                teleportPending = false;
            }
            if (warped || errX * errX + errY * errY > TELEPORT_PX * TELEPORT_PX) {
                errX = 0;
                errY = 0;
            }
        } else if (!havePrediction) {
            errX = 0;
            errY = 0;
        }
        havePrediction = true;
        predTick = n.tick;
        predX = n.selfX;
        predY = n.selfY;
        predVx = n.selfVx;
        predVy = n.selfVy;
        predAx = ax;
        predAy = ay;
        putSelf(n, wrapX(px + errX), wrapY(py + errY), n.selfHeading);
        outSelfVx = (float) (n.selfVx + ax * dt);
        outSelfVy = (float) (n.selfVy + ay * dt);
    }

    /**
     * The own ship as the kernel predictor draws it ({@link com.fulcrologic.jxpilot.client.game.predict}): its
     * position, heading, velocity, fuel and the bits the own keys decide, at its drawn (predicted) tick; the rest
     * from the newest SELF. Its exhaust comes from the newly final predicted ticks.
     * <p>
     * BEHAVIOR: drawn at {@link #selfDrawnTick()} = the predictor's drawn tick; flags as
     * {@link com.fulcrologic.jxpilot.client.game.predict.OwnShipPredictor#flags}; fuel in units (C / 256).
     * BEHAVIOR: own exhaust: one emission per predicted thrusting tick, at that tick's predicted state (quantized
     * as the server seeds); none from SELF records meanwhile.
     * BEHAVIOR: the provisional own shots are appended to the drawn shots (own colour), as many as fit; the
     * snapshots' own bullets (SHOT records of colour OWN) are left out meanwhile, and each complete snapshot
     * confirms the provisional ones ({@link com.fulcrologic.jxpilot.client.game.predict.ProvisionalShots#confirm}).
     */
    private void drawPredicted(SnapshotFrame n) {
        com.fulcrologic.jxpilot.client.game.predict.OwnShipPredictor p = predictor;
        putSelf(n, (float) p.x(), (float) p.y(), (float) p.heading());
        outSelfFlags = p.flags(n.selfFlags);
        outSelfVx = p.vx();
        outSelfVy = p.vy();
        outSelfFuel = (float) (p.fuel() / 256);
        outSelfFuelMax = (float) (p.fuelMax() / 256);
        drawnTick = p.drawnTick();
        predicted = true;
        drainExhaust(n, true);
        com.fulcrologic.jxpilot.client.game.predict.ProvisionalShots ps = p.shots();
        hideOwnShots = false;
        for (int i = 0; i < ps.count() && outShots < outShotX.length; i++) {
            putShot(ps.x(i), ps.y(i), (byte) OWN_SHOT);
        }
        provisionalShots = ps.count();
    }

    private void drainExhaust(SnapshotFrame n, boolean emit) {
        if (predictor == null) {
            return;
        }
        int id = n.selfId;
        int slot = players.slotOf(id);
        float power = slot >= 0 ? players.power(slot) : 45f;
        int ab = slot >= 0 ? players.afterburners(slot) : 0;
        int ex = slot >= 0 ? players.engineX(slot) : defaultEngineX;
        int ey = slot >= 0 ? players.engineY(slot) : defaultEngineY;
        for (int i = predictor.takeExhaust(); i >= 0; i = predictor.takeExhaust()) {
            if (emit) {
                sparks.emit(predictor.exhaustTick(i), id, ThrustSparkEmitter.quantizePos(predictor.exhaustX(i)),
                    ThrustSparkEmitter.quantizePos(predictor.exhaustY(i)), predictor.exhaustHeading(i),
                    ThrustSparkEmitter.quantizeVel(predictor.exhaustVx(i)),
                    ThrustSparkEmitter.quantizeVel(predictor.exhaustVy(i)), power, ab, ex, ey, true, particles);
            }
        }
    }

    /**
     * Provisional own shots in the drawn shot list: its last this many entries (0 unless {@link #selfPredicted}).
     */
    public int provisionalShotCount() { return provisionalShots; }

    /** Whether the last {@link #update} drew the own ship from the kernel predictor. */
    public boolean selfPredicted() { return predicted; }

    /** The own-ship predictor the own ship is drawn from when it is active (null: F1 only). */
    public void setPredictor(com.fulcrologic.jxpilot.client.game.predict.OwnShipPredictor p) {
        predictor = p;
    }

    private static double clampAccel(double a) {
        return Math.max(-MAX_SELF_ACCEL, Math.min(MAX_SELF_ACCEL, a));
    }

    /** The tick the own ship was drawn at by the last {@link #update} (F1: about the server tick now). */
    public double selfDrawnTick() { return drawnTick; }

    /**
     * The tick of the SELF record the last {@link #update} drew the own ship from (its newest SELF at that moment);
     * meaningful while {@link #hasSelf()}. A SELF that arrives after the update is not counted until the next one.
     */
    public int selfDrawnFromTick() { return drawnSelfTick; }

    /** Newest tick with a SELF record in the ring (0 before any). */
    public int newestSelfTick() {
        int t = Integer.MIN_VALUE;
        boolean any = false;
        for (SnapshotFrame f : ring) {
            if (f.valid && f.hasSelf && (!any || f.tick - t > 0)) {
                t = f.tick;
                any = true;
            }
        }
        return any ? t : 0;
    }

    private void putSelf(SnapshotFrame s, float x, float y, float heading) {
        outSelfId = s.selfId;
        outSelfFlags = s.selfFlags;
        outSelfX = x;
        outSelfY = y;
        outSelfVx = s.selfVx;
        outSelfVy = s.selfVy;
        outSelfHeading = heading;
        outSelfFuel = s.selfFuel;
        outSelfFuelMax = s.selfFuelMax;
        outSelfLock = s.selfLockId;
        outSelfTanks = s.selfNumTanks;
        outSelfCurrentTank = s.selfCurrentTank;
        outSelfRespawn = s.selfRespawnFrames;
        outSelfPower = s.selfPower;
        outSelfTurnSpeed = s.selfTurnSpeed;
        outSelfTurnRes255 = s.selfTurnRes255;
        outSelfLockDist = s.selfLockDist;
        outSelfLockDir = s.selfLockDir;
        outSelfCheck = s.selfCheck;
        outSelfMods = s.selfMods;
    }

    // ================================================================ reliable-event state

    /**
     * BASE_OWNER, as C {@code Handle_base} (client.c): a player owns one base at a time.
     * <p>
     * BEHAVIOR: every other base owned by {@code playerId} is released, then the base gets the owner;
     * {@link Protocol#NO_ID} releases the base. Out-of-range base indices are ignored (nothing changes).
     */
    public void setBaseOwner(int baseIndex, int playerId) {
        if (baseIndex < 0 || baseIndex >= baseOwner.length) {
            return;
        }
        for (int i = 0; i < baseOwner.length; i++) {
            if (baseOwner[i] == playerId) {
                baseOwner[i] = Protocol.NO_ID;
            }
        }
        baseOwner[baseIndex] = playerId;
    }

    // ================================================================ accessors

    public boolean configured() { return configured; }

    public double worldWidth() { return worldW; }

    public double worldHeight() { return worldH; }

    public boolean wraps() { return wrap; }

    /** Original frames per sim tick (gameFps / simHz). */
    public float frameFraction() { return frameFraction; }

    public double snapshotIntervalTicks() { return snapshotInterval; }

    /**
     * Seconds between snapshots as they really arrive, for sounds that the server repeats every frame (thrust).
     * <p>
     * BEHAVIOR: the larger of the WELCOME snapshot interval and the smallest tick step between the last
     * {@value #STEP_WINDOW} new newest ticks (a C server sends paused players every 4th frame and players out of
     * the game every 2nd, frame.c), divided by simHz; the smallest step ignores single lost snapshots. Without
     * steps yet (and after a reset), the WELCOME interval.
     */
    public double arrivalIntervalSeconds() {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < stepCount; i++) {
            min = Math.min(min, tickSteps[i]);
        }
        double ticks = stepCount == 0 ? snapshotInterval : Math.max(snapshotInterval, min);
        return ticks / clock.simHz();
    }

    public double interpolationDelayTicks() { return delayTicks; }

    public TickClock clock() { return clock; }

    /** Newest snapshot tick received. */
    public int newestTick() { return newestTick; }

    /** Temporary wormholes in the newest tick (TEMP_WORMHOLE: C draws the IN ends the server sends). */
    public int tempWormholes() { return tempWorms; }

    /** Block x of temporary wormhole {@code i}. */
    public int tempWormholeBx(int i) { return tempWormBx[i]; }

    /** Block y of temporary wormhole {@code i}. */
    public int tempWormholeBy(int i) { return tempWormBy[i]; }

    public boolean hasSnapshot() { return haveNewest; }

    /** Snapshot parts accepted so far. */
    public long ingested() { return ingested; }

    /** Sounds received, in arrival order (see {@link SoundQueue}). */
    public SoundQueue sounds() { return sounds; }

    public long malformedSnapshots() { return malformedCount; }

    /** Tick the last {@link #update} rendered (fractional). */
    public double renderTick() { return renderTick; }

    /** Estimated server tick at the last {@link #update}. */
    public double serverTickNow() { return serverTickNow; }

    /**
     * F3: the own player is in play according to the newest SELF ever received (not DEAD: C's status is
     * PLAYING); frames without SELF (C sends an ECM-damaged player none) keep the last state.
     */
    public boolean selfPlaying() { return selfPlaying; }

    /** True when a SELF record exists (own ship accessors valid). */
    public boolean hasSelf() { return outSelf; }

    public int selfId() { return outSelfId; }

    /** {@link Records.ShipFlags}. */
    public int selfFlags() { return outSelfFlags; }

    public float selfX() { return outSelfX; }

    public float selfY() { return outSelfY; }

    public float selfVx() { return outSelfVx; }

    public float selfVy() { return outSelfVy; }

    /** Radians. */
    public float selfHeading() { return outSelfHeading; }

    public float selfFuel() { return outSelfFuel; }

    public float selfFuelMax() { return outSelfFuelMax; }

    public int selfLockId() { return outSelfLock; }

    public int selfNumTanks() { return outSelfTanks; }

    public int selfCurrentTank() { return outSelfCurrentTank; }

    public int selfRespawnFrames() { return outSelfRespawn; }

    /** Engine power the server uses (SELF; C {@code displayedPower}). */
    public int selfPower() { return outSelfPower; }

    /** Turn speed the server uses (SELF; C {@code displayedTurnspeed}). */
    public int selfTurnSpeed() { return outSelfTurnSpeed; }

    /** Turn resistance * 255 (SELF). */
    public int selfTurnResistance255() { return outSelfTurnRes255; }

    /** Distance to the locked player in px; 0 when the server does not show the lock (C lock_dist). */
    public int selfLockDist() { return outSelfLockDist; }

    /** Direction to the locked player in RES units (C lock_dir). */
    public int selfLockDir() { return outSelfLockDir; }

    /** Next race checkpoint (C {@code nextCheckPoint}). */
    public int selfCheck() { return outSelfCheck; }

    /** Weapon modifiers, {@link Records.Self} MOD_* bits. */
    public int selfMods() { return outSelfMods; }

    /** Count of a carried {@link Item} (by ordinal). */
    public int selfItem(int item) { return selfItems[item]; }

    /** The {@link Item} (ordinal) KEY_LOSE_ITEM drops (C {@code lose_item}), from SELF_ITEMS; 0 before any. */
    public int selfLoseItem() { return selfLoseItem; }

    public int shipCount() { return outShips; }

    public int shipId(int i) { return outShipId[i]; }

    public int shipFlags(int i) { return outShipFlags[i]; }

    public float shipX(int i) { return outShipX[i]; }

    public float shipY(int i) { return outShipY[i]; }

    /** Radians. */
    public float shipHeading(int i) { return outShipHeading[i]; }

    public float shipVx(int i) { return outShipVx[i]; }

    public float shipVy(int i) { return outShipVy[i]; }

    public int shotCount() { return outShots; }

    public float shotX(int i) { return outShotX[i]; }

    public float shotY(int i) { return outShotY[i]; }

    /**
     * The snapshot tick of drawn shot {@code i}'s source record (the record it was interpolated or reckoned from),
     * Integer.MIN_VALUE for a provisional own shot.
     */
    public int shotSourceTick(int i) { return outShotSrcTick[i]; }

    /** That record's position (the server's at that tick, to the wire's half pixel). */
    public float shotSourceX(int i) { return outShotSrcX[i]; }

    public float shotSourceY(int i) { return outShotSrcY[i]; }

    /** {@link Records.ShotKind} (the variant's 3 kind bits). */
    public int shotKind(int i) { return Records.ShotKind.kindOf(outShotVariant[i]); }

    /** {@link Records.ObjColor} (the variant's colour nibble). */
    public int shotColor(int i) { return Records.ShotKind.colorOf(outShotVariant[i]); }

    /**
     * True when the shot cannot hurt the own ship ({@link Records.ShotKind#FLAG_HARMLESS}; C frame.c's
     * teamshot case, drawn as C {@code Gui_paint_teamshot}).
     */
    public boolean shotHarmless(int i) { return Records.ShotKind.harmless(outShotVariant[i]); }

    public ParticlePool particles() { return particles; }

    /** Frames of ECM damage left on the own ship (newest TIMERS; C {@code damaged}). */
    public int damaged() { return damaged; }

    /** Frames until the round starts, 0 when none (newest TIMERS). */
    public int roundDelay() { return roundDelay; }

    /** Self-destruct frames left, 0 when not self-destructing (newest TIMERS; C {@code destruct}). */
    public int destruct() { return destruct; }

    /** Emergency thrust left (frames) of the meter; see {@link #thrustMax()}. */
    public int thrustLeft() { return thrustLeft; }

    /** Emergency thrust meter maximum, 0 when no meter (C thrusttimemax). */
    public int thrustMax() { return thrustMax; }

    public int shieldLeft() { return shieldLeft; }

    /** Emergency shield meter maximum, 0 when no meter. */
    public int shieldMax() { return shieldMax; }

    public int phasingLeft() { return phasingLeft; }

    /** Phasing meter maximum, 0 when no meter. */
    public int phasingMax() { return phasingMax; }

    /** Seconds until the server shuts down, -1 when none (C shutdown_count). */
    public int shutdownCount() { return shutdownCount; }

    public int shutdownDelay() { return shutdownDelay; }

    /**
     * Seconds of game time left (C {@code Paint_HUD} time left), counted down from the last report by the
     * render ticks since; -1 when never reported.
     * <p>
     * BEHAVIOR: {@code max(0, reported - floor((renderTick - reportTick) / simHz))}.
     */
    public int timeLeft() {
        if (!haveTimeLeft) {
            return -1;
        }
        double secs = (renderTick - timeLeftTick) / clock.simHz();
        return (int) Math.max(0, timeLeftValue - Math.floor(Math.max(0, secs)));
    }

    /** The M2 objects drawn this frame ({@link ObjectLists}; valid until the next update). */
    public ObjectLists objects() { return outObjs; }

    /** Position-only point particles (C debris on the XPilot path), tracked and interpolated. */
    public int pointCount() { return outPoints; }

    public float pointX(int i) { return outPointX[i]; }

    public float pointY(int i) { return outPointY[i]; }

    /** The burst colour the point came with ({@link Records.ObjColor}, or a temperature level code). */
    public int pointColor(int i) { return outPointColor[i]; }

    public int radarCount() { return radarCount; }

    public float radarX(int i) { return radarX[i]; }

    public float radarY(int i) { return radarY[i]; }

    /** {@link Records.Radar} FLAG_* bits. */
    public int radarFlags(int i) { return radarFlags[i]; }

    /**
     * An asteroid blip's size (its RADAR section's variant: C's size, drawn size + 1 pixels), 0 when the server did not
     * send one.
     */
    public int radarAsteroidSize(int i) { return radarAsteroidSize[i]; }

    /** {@link Records.CannonState} flags of cannon {@code index} (WorldMap.cannons()). */
    public int cannonFlags(int index) { return cannonFlags[index]; }

    /** Fuel station level 0..1 of {@code Const.MAX_STATION_FUEL}. */
    public float fuelLevel(int index) { return fuelLevel[index]; }

    public int targetFlags(int index) { return targetFlags[index]; }

    /** Target strength left 0..1. */
    public float targetDamageLeft(int index) { return targetLeft[index]; }

    public int treasureFlags(int index) { return treasureFlags[index]; }

    /** Owner of base {@code index} or {@link Protocol#NO_ID}. */
    public int baseOwner(int index) { return baseOwner[index]; }
}
