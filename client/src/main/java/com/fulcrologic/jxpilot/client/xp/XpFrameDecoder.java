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

import java.nio.ByteBuffer;

/**
 * Decodes the unreliable frame packets of a C server datagram into an {@link XpFrame}: a port of the
 * {@code Receive_*} handlers registered in {@code receive_tbl} ({@code client/netclient.c},
 * {@code Receive_init}), with the formats of the matching {@code Send_*} functions in
 * {@code server/netserver.c}. {@code PKT_RELIABLE} and {@code PKT_QUIT} are left to the caller
 * ({@link XpConnection}). Map object updates are acknowledged like C (PKT_ACK_FUEL / _CANNON /
 * _TARGET appended to the outgoing buffer). Allocation free.
 */
public final class XpFrameDecoder {
    /** {@link #decode} result: one packet decoded. */
    public static final int OK = 1;
    /** Truncated packet (C handler returned 0): drop the rest of the datagram. */
    public static final int INCOMPLETE = 0;
    /** No handler for the type (C "Received unknown packet type"): drop the rest of the datagram. */
    public static final int UNKNOWN = -1;
    /** PKT_START not newer than the last frame (C "ignoring frame"): drop the rest of the datagram. */
    public static final int STALE = -2;
    /** PKT_START acking a keyboard change not sent yet (C "Premature keyboard ack"): drop the rest. */
    public static final int PREMATURE_ACK = -3;
    /** C ignores map-object acks once the outgoing buffer holds this much ({@code MAX_MAP_ACK_LEN}). */
    public static final int MAX_MAP_ACK_LEN = 500;

    private final XpKeyboard keyboard;
    private ByteBuffer out;
    private int lastLoops;
    private double worldW = 1;
    private double worldH = 1;
    private int radarHeight = 256;

    public XpFrameDecoder(XpKeyboard keyboard) {
        this.keyboard = keyboard;
    }

    /** Where map-object acks go (the next client datagram). */
    public void setOut(ByteBuffer out) {
        this.out = out;
    }

    /**
     * Map size in blocks, for fast radar coordinates.
     * <p>
     * BEHAVIOR: the fast radar height is C's {@code (256 * mapHeight) / mapWidth}.
     */
    public void setMap(int blocksW, int blocksH) {
        worldW = (double) blocksW * XpProto.BLOCK_SZ;
        worldH = (double) blocksH * XpProto.BLOCK_SZ;
        radarHeight = Math.max(1, 256 * blocksH / blocksW);
    }

    /** BEHAVIOR: forgets the last frame number (new connection). */
    public void reset() {
        lastLoops = 0;
    }

    /** Newest frame number started (C {@code last_loops}). */
    public int lastLoops() { return lastLoops; }

    /**
     * Decode the packet at the reader's position into {@code f}.
     * <p>
     * BEHAVIOR: on OK the reader is past the packet; on any other result it is unspecified and the
     * caller drops the rest of the datagram.
     */
    public int decode(XpReader r, XpFrame f) {
        int type = r.peekU8();
        if (type >= XpProto.PKT_DEBRIS && type < XpProto.PKT_DEBRIS + XpProto.DEBRIS_TYPES) {
            return debris(r, f);
        }
        return switch (type) {
            case XpProto.PKT_START -> start(r, f);
            case XpProto.PKT_END -> end(r, f);
            case XpProto.PKT_SELF -> self(r, f);
            case XpProto.PKT_SELF_ITEMS -> selfItems(r, f);
            case XpProto.PKT_MODIFIERS -> modifiers(r, f);
            case XpProto.PKT_DAMAGED -> damaged(r, f);
            case XpProto.PKT_DESTRUCT -> destruct(r, f);
            case XpProto.PKT_SHUTDOWN, XpProto.PKT_THRUSTTIME, XpProto.PKT_SHIELDTIME,
                 XpProto.PKT_PHASINGTIME, XpProto.PKT_ROUNDDELAY -> countMax(r, f, type);
            case XpProto.PKT_TIME_LEFT -> timeLeft(r, f);
            case XpProto.PKT_EYES -> eyes(r, f);
            case XpProto.PKT_AUDIO -> audio(r, f);
            case XpProto.PKT_LOSEITEM -> loseItem(r, f);
            case XpProto.PKT_REFUEL -> refuel(r, f);
            case XpProto.PKT_CONNECTOR -> connector(r, f);
            case XpProto.PKT_LASER -> laser(r, f);
            case XpProto.PKT_MISSILE -> missile(r, f);
            case XpProto.PKT_BALL -> ball(r, f);
            case XpProto.PKT_SHIP -> ship(r, f);
            case XpProto.PKT_MINE -> mine(r, f);
            case XpProto.PKT_ITEM -> item(r, f);
            case XpProto.PKT_ECM -> ecm(r, f);
            case XpProto.PKT_TRANS -> trans(r, f);
            case XpProto.PKT_PAUSED -> paused(r, f);
            case XpProto.PKT_RADAR -> radar(r, f);
            case XpProto.PKT_FASTRADAR -> fastRadar(r, f);
            case XpProto.PKT_WRECKAGE -> wreckage(r, f);
            case XpProto.PKT_ASTEROID -> asteroid(r, f);
            case XpProto.PKT_WORMHOLE -> wormhole(r, f);
            case XpProto.PKT_FASTSHOT -> fastshot(r, f);
            case XpProto.PKT_FUEL -> fuel(r, f);
            case XpProto.PKT_CANNON -> cannon(r, f);
            case XpProto.PKT_TARGET -> target(r, f);
            default -> UNKNOWN;
        };
    }

    // ---------------------------------------------------------------- frame bracket

    /**
     * C {@code Receive_start}: {@code %c%ld%ld} loops, keyboard ack.
     * <p>
     * BEHAVIOR: a frame not newer than the last is STALE; a keyboard ack beyond the last change is
     * PREMATURE_ACK (frame number not taken); otherwise clears the frame, records loops and ack.
     */
    private int start(XpReader r, XpFrame f) {
        if (!r.has(9)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int loops = r.s32();
        int keyAck = r.s32();
        if (loops - lastLoops <= 0) {
            return STALE;
        }
        lastLoops = loops;
        if (!keyboard.onAck(keyAck)) {
            return PREMATURE_ACK;
        }
        f.clear();
        f.started = true;
        f.loops = loops;
        f.keyAck = keyAck;
        return OK;
    }

    /** C {@code Receive_end}: {@code %c%ld}. BEHAVIOR: marks the frame complete. */
    private int end(XpReader r, XpFrame f) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(5);
        f.ended = true;
        return OK;
    }

    // ---------------------------------------------------------------- own ship / HUD

    /**
     * C {@code Receive_self} (version &gt;= 0x4203): {@code %c %hd%hd%hd%hd%c %c%c%c %hd%hd%c%c
     * %c%hd%hd %hd%hd%c %c%c}.
     * <p>
     * BEHAVIOR: stores everything; item counts reset to 0 (PKT_SELF_ITEMS follows when any are owned).
     */
    private int self(XpReader r, XpFrame f) {
        if (!r.has(31)) {
            return INCOMPLETE;
        }
        r.skip(1);
        f.selfX = r.s16();
        f.selfY = r.s16();
        f.selfVx = r.s16();
        f.selfVy = r.s16();
        f.selfDir = r.u8();
        f.selfPower = r.u8();
        f.selfTurnspeed = r.u8();
        f.selfTurnresistance = r.u8();
        f.selfLockId = r.s16();
        f.selfLockDist = r.s16();
        f.selfLockDir = r.u8();
        f.selfCheck = r.u8();
        f.selfCurrentTank = r.u8();
        f.selfFuelSum = r.s16();
        f.selfFuelMax = r.s16();
        f.viewWidth = r.s16();
        f.viewHeight = r.s16();
        f.debrisColors = r.u8();
        f.selfStat = r.u8();
        f.autopilotLight = r.u8();
        java.util.Arrays.fill(f.items, 0);
        f.hasSelf = true;
        return OK;
    }

    /**
     * C {@code Receive_self_items}: {@code %c%u} item mask, then one count byte per set bit.
     * <p>
     * BEHAVIOR: counts for bits beyond the known items are skipped; missing count bytes at the end of
     * the datagram are treated as 0 (C reads only what is there).
     */
    private int selfItems(XpReader r, XpFrame f) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int mask = r.s32();
        for (int i = 0; mask != 0; i++) {
            int bit = 1 << i;
            if ((mask & bit) != 0) {
                mask ^= bit;
                if (r.has(1)) {
                    int v = r.u8();
                    if (i < XpProto.NUM_ITEMS) {
                        f.items[i] = v;
                    }
                }
            }
        }
        return OK;
    }

    /** C {@code Receive_modifiers}: {@code %c%s}. */
    private int modifiers(XpReader r, XpFrame f) {
        int start = r.pos();
        r.skip(1);
        int n = r.string(f.modifiers, XpProto.MAX_CHARS);
        if (n <= 0) {
            r.pos(start);
            return INCOMPLETE;
        }
        return OK;
    }

    /** C {@code Receive_damaged}: {@code %c%c}. */
    private int damaged(XpReader r, XpFrame f) {
        if (!r.has(2)) {
            return INCOMPLETE;
        }
        r.skip(1);
        f.damaged = r.u8();
        return OK;
    }

    /** C {@code Receive_destruct}: {@code %c%hd}. */
    private int destruct(XpReader r, XpFrame f) {
        if (!r.has(3)) {
            return INCOMPLETE;
        }
        r.skip(1);
        f.destruct = r.s16();
        return OK;
    }

    /** C {@code Receive_shutdown/thrusttime/shieldtime/phasingtime/rounddelay}: {@code %c%hd%hd}. */
    private int countMax(XpReader r, XpFrame f, int type) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int count = r.s16();
        int max = r.s16();
        switch (type) {
            case XpProto.PKT_SHUTDOWN -> {
                f.shutdownCount = count;
                f.shutdownDelay = max;
            }
            case XpProto.PKT_THRUSTTIME -> {
                f.thrustTime = count;
                f.thrustMax = max;
            }
            case XpProto.PKT_SHIELDTIME -> {
                f.shieldTime = count;
                f.shieldMax = max;
            }
            case XpProto.PKT_PHASINGTIME -> {
                f.phasingTime = count;
                f.phasingMax = max;
            }
            default -> {
                f.roundDelay = count;
                f.roundDelayMax = max;
            }
        }
        return OK;
    }

    /** C {@code Receive_time_left}: {@code %c%ld}. */
    private int timeLeft(XpReader r, XpFrame f) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(1);
        f.timeLeft = r.s32();
        return OK;
    }

    /** C {@code Receive_eyes}: {@code %c%hd}. */
    private int eyes(XpReader r, XpFrame f) {
        if (!r.has(3)) {
            return INCOMPLETE;
        }
        r.skip(1);
        f.eyes = r.s16();
        return OK;
    }

    /** C {@code Receive_audio}: {@code %c%c%c} (no sound in JXPilot: counted only). */
    private int audio(XpReader r, XpFrame f) {
        if (!r.has(3)) {
            return INCOMPLETE;
        }
        r.skip(3);
        f.audioCount++;
        return OK;
    }

    /** C {@code Receive_loseitem}: {@code %c%c}. */
    private int loseItem(XpReader r, XpFrame f) {
        if (!r.has(2)) {
            return INCOMPLETE;
        }
        r.skip(1);
        f.loseItem = r.u8();
        return OK;
    }

    // ---------------------------------------------------------------- objects

    /** C {@code Receive_refuel}: {@code %c%hd%hd%hd%hd}. */
    private int refuel(XpReader r, XpFrame f) {
        if (!r.has(9)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x0 = r.s16();
        int y0 = r.s16();
        int x1 = r.s16();
        int y1 = r.s16();
        if (f.refuels >= XpFrame.SHIP_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.refuels++;
        f.refuelX0[k] = x0;
        f.refuelY0[k] = y0;
        f.refuelX1[k] = x1;
        f.refuelY1[k] = y1;
        return OK;
    }

    /** C {@code Receive_connector}: {@code %c%hd%hd%hd%hd%c}. */
    private int connector(XpReader r, XpFrame f) {
        if (!r.has(10)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x0 = r.s16();
        int y0 = r.s16();
        int x1 = r.s16();
        int y1 = r.s16();
        int tractor = r.u8();
        if (f.connectors >= XpFrame.OBJ_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.connectors++;
        f.connX0[k] = x0;
        f.connY0[k] = y0;
        f.connX1[k] = x1;
        f.connY1[k] = y1;
        f.connTractor[k] = tractor;
        return OK;
    }

    /** C {@code Receive_laser}: {@code %c%c%hd%hd%hd%c} colour, x, y, len, dir. */
    private int laser(XpReader r, XpFrame f) {
        if (!r.has(9)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int color = r.u8();
        int x = r.s16();
        int y = r.s16();
        int len = r.s16();
        int dir = r.u8();
        if (f.lasers >= XpFrame.OBJ_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.lasers++;
        f.laserColor[k] = color;
        f.laserX[k] = x;
        f.laserY[k] = y;
        f.laserLen[k] = len;
        f.laserDir[k] = dir;
        return OK;
    }

    /** C {@code Receive_missile}: {@code %c%hd%hd%c%c} x, y, len, dir. */
    private int missile(XpReader r, XpFrame f) {
        if (!r.has(7)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x = r.s16();
        int y = r.s16();
        int len = r.u8();
        int dir = r.u8();
        if (f.missiles >= XpFrame.OBJ_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.missiles++;
        f.missileX[k] = x;
        f.missileY[k] = y;
        f.missileLen[k] = len;
        f.missileDir[k] = dir;
        return OK;
    }

    /** C {@code Receive_ball}: {@code %c%hd%hd%hd} x, y, id. */
    private int ball(XpReader r, XpFrame f) {
        if (!r.has(7)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x = r.s16();
        int y = r.s16();
        int id = r.s16();
        if (f.balls >= XpFrame.OBJ_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.balls++;
        f.ballX[k] = x;
        f.ballY[k] = y;
        f.ballId[k] = id;
        return OK;
    }

    /** C {@code Receive_ship}: {@code %c%hd%hd%hd%c%c} x, y, id, dir, flags. */
    private int ship(XpReader r, XpFrame f) {
        if (!r.has(9)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x = r.s16();
        int y = r.s16();
        int id = r.s16();
        int dir = r.u8();
        int flags = r.u8();
        if (f.ships >= XpFrame.SHIP_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.ships++;
        f.shipX[k] = x;
        f.shipY[k] = y;
        f.shipId[k] = id;
        f.shipDir[k] = dir;
        f.shipFlags[k] = flags;
        return OK;
    }

    /** C {@code Receive_mine}: {@code %c%hd%hd%c%hd} x, y, team mine, id. */
    private int mine(XpReader r, XpFrame f) {
        if (!r.has(8)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x = r.s16();
        int y = r.s16();
        int team = r.u8();
        int id = r.s16();
        if (f.mines >= XpFrame.OBJ_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.mines++;
        f.mineX[k] = x;
        f.mineY[k] = y;
        f.mineTeam[k] = team;
        f.mineId[k] = id;
        return OK;
    }

    /** C {@code Receive_item}: {@code %c%hd%hd%c}. BEHAVIOR: types &gt;= NUM_ITEMS are ignored (as C). */
    private int item(XpReader r, XpFrame f) {
        if (!r.has(6)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x = r.s16();
        int y = r.s16();
        int type = r.u8();
        if (type >= XpProto.NUM_ITEMS) {
            return OK;
        }
        if (f.itemObjs >= XpFrame.OBJ_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.itemObjs++;
        f.itemX[k] = x;
        f.itemY[k] = y;
        f.itemType[k] = type;
        return OK;
    }

    /** C {@code Receive_ecm}: {@code %c%hd%hd%hd} x, y, size. */
    private int ecm(XpReader r, XpFrame f) {
        if (!r.has(7)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x = r.s16();
        int y = r.s16();
        int size = r.s16();
        if (f.ecms >= XpFrame.OBJ_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.ecms++;
        f.ecmX[k] = x;
        f.ecmY[k] = y;
        f.ecmSize[k] = size;
        return OK;
    }

    /** C {@code Receive_trans}: {@code %c%hd%hd%hd%hd}. */
    private int trans(XpReader r, XpFrame f) {
        if (!r.has(9)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x1 = r.s16();
        int y1 = r.s16();
        int x2 = r.s16();
        int y2 = r.s16();
        if (f.trans >= XpFrame.OBJ_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.trans++;
        f.transX1[k] = x1;
        f.transY1[k] = y1;
        f.transX2[k] = x2;
        f.transY2[k] = y2;
        return OK;
    }

    /** C {@code Receive_paused}: {@code %c%hd%hd%hd} x, y, count. */
    private int paused(XpReader r, XpFrame f) {
        if (!r.has(7)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x = r.s16();
        int y = r.s16();
        int count = r.s16();
        if (f.paused >= XpFrame.SHIP_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.paused++;
        f.pausedX[k] = x;
        f.pausedY[k] = y;
        f.pausedCount[k] = count;
        return OK;
    }

    /** C {@code Receive_radar} (servers before 4.4.1): {@code %c%hd%hd%c}, x/y in world pixels. */
    private int radar(XpReader r, XpFrame f) {
        if (!r.has(6)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x = r.s16();
        int y = r.s16();
        int size = r.u8();
        addRadar(f, x, y, size);
        return OK;
    }

    /**
     * C {@code Receive_fastradar}: {@code %c} count, then 3 bytes per blip: x (of 256), y low byte,
     * flags (y bits 8-9 in 0xC0, 0x20 friendly, size in 0x07).
     * <p>
     * BEHAVIOR: blips decode to the centre of their radar cell in world pixels; the 0x20 bit becomes
     * the C 0x80 size flag.
     */
    private int fastRadar(XpReader r, XpFrame f) {
        if (!r.has(2)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int n = r.u8();
        if (!r.has(n * 3)) {
            return INCOMPLETE;
        }
        for (int i = 0; i < n; i++) {
            int x = r.u8();
            int y = r.u8();
            int b = r.u8();
            y |= (b & 0xC0) << 2;
            int size = b & 0x07;
            if ((b & 0x20) != 0) {
                size |= 0x80;
            }
            addRadar(f, (x + 0.5) * worldW / 256, (y + 0.5) * worldH / radarHeight, size);
        }
        return OK;
    }

    private static void addRadar(XpFrame f, double x, double y, int size) {
        if (f.radar >= XpFrame.OBJ_CAP) {
            f.overflow++;
            return;
        }
        int k = f.radar++;
        f.radarX[k] = (float) x;
        f.radarY[k] = (float) y;
        f.radarSize[k] = size;
    }

    /** C {@code Receive_wreckage}: {@code %c%hd%hd%c%c%c} x, y, type (0x80 deadly), size, rotation. */
    private int wreckage(XpReader r, XpFrame f) {
        if (!r.has(8)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x = r.s16();
        int y = r.s16();
        int type = r.u8();
        int size = r.u8();
        int rot = r.u8();
        if (f.wrecks >= XpFrame.OBJ_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.wrecks++;
        f.wreckX[k] = x;
        f.wreckY[k] = y;
        f.wreckType[k] = type;
        f.wreckSize[k] = size;
        f.wreckRot[k] = rot;
        return OK;
    }

    /** C {@code Receive_asteroid}: {@code %c%hd%hd%c%c} x, y, type &lt;&lt; 4 | size, rotation. */
    private int asteroid(XpReader r, XpFrame f) {
        if (!r.has(7)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x = r.s16();
        int y = r.s16();
        int ts = r.u8();
        int rot = r.u8();
        if (f.asteroids >= XpFrame.OBJ_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.asteroids++;
        f.astX[k] = x;
        f.astY[k] = y;
        f.astType[k] = (ts >> 4) & 0x0F;
        f.astSize[k] = ts & 0x0F;
        f.astRot[k] = rot;
        return OK;
    }

    /** C {@code Receive_wormhole}: {@code %c%hd%hd} block centre in pixels. */
    private int wormhole(XpReader r, XpFrame f) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int x = r.s16();
        int y = r.s16();
        if (f.wormholes >= XpFrame.OBJ_CAP) {
            f.overflow++;
            return OK;
        }
        int k = f.wormholes++;
        f.wormX[k] = x;
        f.wormY[k] = y;
        return OK;
    }

    // ---------------------------------------------------------------- particle lists

    /**
     * C {@code Receive_debris}: type byte {@code PKT_DEBRIS + colour * areas + area}, count, then
     * count (x, y) byte pairs relative to the view area.
     * <p>
     * BEHAVIOR: like C, a packet needs more than its two header bytes and all its pairs.
     */
    private int debris(XpReader r, XpFrame f) {
        if (!r.has(3)) {
            return INCOMPLETE;
        }
        int type = r.u8() - XpProto.PKT_DEBRIS;
        int n = r.u8();
        if (!r.has(n * 2)) {
            return INCOMPLETE;
        }
        for (int i = 0; i < n; i++) {
            int x = r.u8();
            int y = r.u8();
            if (f.debris >= XpFrame.PARTICLE_CAP) {
                f.overflow++;
                continue;
            }
            int k = f.debris++;
            f.debrisType[k] = (short) type;
            f.debrisX[k] = (byte) x;
            f.debrisY[k] = (byte) y;
        }
        return OK;
    }

    /**
     * C {@code Receive_fastshot}: {@code PKT_FASTSHOT}, type ({@code [teamshot DEBRIS_TYPES +]
     * colour * areas + area}), count, then count (x, y) byte pairs.
     */
    private int fastshot(XpReader r, XpFrame f) {
        if (!r.has(4)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int type = r.u8();
        int n = r.u8();
        if (!r.has(n * 2)) {
            return INCOMPLETE;
        }
        for (int i = 0; i < n; i++) {
            int x = r.u8();
            int y = r.u8();
            if (f.fastshots >= XpFrame.PARTICLE_CAP) {
                f.overflow++;
                continue;
            }
            int k = f.fastshots++;
            f.fastshotType[k] = (short) type;
            f.fastshotX[k] = (byte) x;
            f.fastshotY[k] = (byte) y;
        }
        return OK;
    }

    // ---------------------------------------------------------------- map objects (acked)

    /** C {@code Receive_fuel}: {@code %c%hu%hu} number, fuel units; acked with PKT_ACK_FUEL. */
    private int fuel(XpReader r, XpFrame f) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int num = r.u16();
        int units = r.u16();
        if (f.fuelUpdates < XpFrame.MAP_CAP) {
            int k = f.fuelUpdates++;
            f.fuelNum[k] = num;
            f.fuelUnits[k] = units;
        } else {
            f.overflow++;
        }
        mapAck(XpProto.PKT_ACK_FUEL, num);
        return OK;
    }

    /** C {@code Receive_cannon}: {@code %c%hu%hu} number, dead time; acked with PKT_ACK_CANNON. */
    private int cannon(XpReader r, XpFrame f) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int num = r.u16();
        int dead = r.u16();
        if (f.cannonUpdates < XpFrame.MAP_CAP) {
            int k = f.cannonUpdates++;
            f.cannonNum[k] = num;
            f.cannonDeadTime[k] = dead;
        } else {
            f.overflow++;
        }
        mapAck(XpProto.PKT_ACK_CANNON, num);
        return OK;
    }

    /** C {@code Receive_target}: {@code %c%hu%hu%hu} number, dead time, damage; acked with PKT_ACK_TARGET. */
    private int target(XpReader r, XpFrame f) {
        if (!r.has(7)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int num = r.u16();
        int dead = r.u16();
        int damage = r.u16();
        if (f.targetUpdates < XpFrame.MAP_CAP) {
            int k = f.targetUpdates++;
            f.targetNum[k] = num;
            f.targetDeadTime[k] = dead;
            f.targetDamage[k] = damage;
        } else {
            f.overflow++;
        }
        mapAck(XpProto.PKT_ACK_TARGET, num);
        return OK;
    }

    /** BEHAVIOR: {@code %c%ld%hu} type, last loops, number; skipped once the buffer holds MAX_MAP_ACK_LEN. */
    private void mapAck(int type, int num) {
        if (out != null && out.position() < MAX_MAP_ACK_LEN) {
            XpWriter.u8(out, type);
            XpWriter.s32(out, lastLoops);
            XpWriter.s16(out, num);
        }
    }
}
