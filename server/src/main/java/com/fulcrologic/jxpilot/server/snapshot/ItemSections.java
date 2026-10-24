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
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.server.world.CannonItems;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Ecms;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ItemParams;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Pulses;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * The item sections of a snapshot (C {@code Frame_shots}, {@code Frame_ships} and {@code Frame_status} in
 * {@code src/server/frame.c}): ITEM, MINE, MISSILE, LASER, ECM, CONNECTOR (tractor beams), TRANSPORTER,
 * SELF_ITEMS and TIMERS, written through the {@link SnapshotBuilder}'s part and culling logic.
 * The random parts of C's frame code (random items, confused mines) use a snapshot-local RNG seeded from the
 * tick, never the simulation's.
 */
final class ItemSections {
    /** C {@code MINE_SENSE_BASE_RANGE}, {@code MINE_SENSE_RANGE_FACTOR}. */
    static final double MINE_SENSE_BASE_RANGE = Const.VISIBILITY_DISTANCE * 0.1 * 1.3;
    static final double MINE_SENSE_RANGE_FACTOR = Const.VISIBILITY_DISTANCE * 0.1 * 0.3;
    /** C {@code SMART_SHOT_LEN}, {@code HEAT_SHOT_LEN}, {@code TORPEDO_LEN}, {@code MISSILE_LEN}. */
    static final int SMART_SHOT_LEN = 12;
    static final int HEAT_SHOT_LEN = 15;
    static final int TORPEDO_LEN = 18;
    static final int MISSILE_LEN = 15;
    private final World w;
    private final SnapshotBuilder b;
    private final Rng rng = new Rng(1);

    ItemSections(World w, SnapshotBuilder b) {
        this.w = w;
        this.b = b;
    }

    /** All item sections for {@code v}. */
    void write(Player v) {
        rng.seed(w.tick * 0x9E3779B9 + v.id);
        writeSelfItems(v);
        writeTimers(v);
        writeObjects(v);
        writeLasers(v);
        writeEcms();
        writeBeams(v);
    }

    private double cx(int h) {
        return w.objects.cx[h] / (double) Click.CLICK;
    }

    private double cy(int h) {
        return w.objects.cy[h] / (double) Click.CLICK;
    }

    /**
     * BEHAVIOR: one SELF_ITEMS record with the viewer's item counts (tanks as its tank count) and its lose item
     * (C {@code Frame_lose_item_state} sends {@code pl->lose_item} as PKT_LOSEITEM while selecting; here always).
     */
    void writeSelfItems(Player v) {
        if (b.need(Section.SELF_ITEMS, 0)) {
            Records.SelfItems.put(b.writer(), v.items, v.loseItem);
        }
    }

    /**
     * BEHAVIOR: the lock distance (px) for SELF when the lock is shown on the HUD (C Frame_status LOCK_VISIBLE), else
     * 0 (the lock is not shown).
     */
    int lockDist(Player v) {
        Player t = w.byId(v.lockPlId);
        return (v.lockTagged & Player.LOCK_VISIBLE) != 0 && t != null ? (int) v.lockDistance : 0;
    }

    /**
     * C {@code showautopilot} (frame.c:424-429), sent as SELF's AUTOPILOT flag like the XPilot path's
     * {@code autopilotLight}.
     * <p>
     * BEHAVIOR: in a hover pause, on when its count has run out or in the first 4 of every 8 frames; with the
     * autopilot, on in the first 4 of every 8 frames; else off.
     */
    boolean autopilotLight(Player v) {
        if ((v.status & Obj.HOVERPAUSE) != 0) {
            return v.count <= 0 || (w.frames % 8) < 4;
        }
        return (v.used & Has.AUTOPILOT) != 0 && (w.frames % 8) < 4;
    }

    /** BEHAVIOR: the direction (RES) from {@code v} to the shown lock, else 0. */
    int lockDir(Player v) {
        Player t = w.byId(v.lockPlId);
        if ((v.lockTagged & Player.LOCK_VISIBLE) == 0 || t == null) {
            return 0;
        }
        return (int) com.fulcrologic.jxpilot.common.math.XMath.findDir(w.wrapDx(t.px - v.px), w.wrapDy(t.py - v.py))
            & (Const.RES - 1);
    }

    /**
     * BEHAVIOR: the viewer's modifiers in SELF's layout ({@link Records.Self#MOD_NUCLEAR} etc.): the server's
     * {@code Mods} packing with the warhead (bits 0-1) and nuclear (bits 2-3) fields swapped; the other fields line up.
     */
    static int selfMods(int m) {
        return ((m >> 2) & 3) | ((m & 3) << 2) | (m & ~0xF);
    }

    /**
     * BEHAVIOR: one TIMERS record: frames of blindness ({@code damaged}); the round delay while C sends it
     * ({@code round_delay_send}, which runs one second past the delay so clients see 0), else 0; the time left in
     * seconds: the gameDuration timer's wall-clock seconds while it runs, else with maxRoundTime the round time
     * rounded up (frame.c:1309-1313), else -1; self-destruct frames left; the emergency thrust, emergency shield and
     * phasing meters (left and maximum while in use, C Send_thrusttime/shieldtime/phasingtime; 0 otherwise); and
     * the shutdown countdown after a gameDuration game over (C Send_shutdown: frames left, ShutdownDelay), -1 none.
     */
    void writeTimers(Player v) {
        if (b.need(Section.TIMERS, 0)) {
            int fps = w.params.fps;
            int left;
            if (w.gameDuration > 0 && w.oldTimeLeft > 0) {
                left = (int) w.oldTimeLeft;
            } else {
                left = w.params.maxRoundTime > 0 && w.roundTime >= 0 ? (w.roundTime + fps - 1) / fps : -1;
            }
            int delay = w.roundDelaySend > 0 ? w.roundDelay : 0;
            boolean et = (v.used & Has.EMERGENCY_THRUST) != 0;
            boolean es = (v.used & Has.EMERGENCY_SHIELD) != 0;
            boolean ph = (v.used & Has.PHASING_DEVICE) != 0;
            Records.Timers.put(b.writer(), Math.min(0xFFFF, v.damaged), Math.min(0xFFFF, delay), left,
                (v.status & Obj.SELF_DESTRUCT) != 0 ? Math.max(0, v.count) : 0,
                et ? v.emergencyThrustLeft : 0, et ? v.emergencyThrustMax : 0,
                es ? v.emergencyShieldLeft : 0, es ? v.emergencyShieldMax : 0,
                ph ? v.phasingLeft : 0, ph ? v.phasingMax : 0, w.shutdownServer, World.SHUTDOWN_DELAY);
        }
    }

    /**
     * Items, mines and missiles in view (C {@code Frame_shots}).
     * <p>
     * BEHAVIOR: a random item shows a kind drawn like C's Choose_random_item (from the snapshot RNG).
     * BEHAVIOR: a mine is FRIENDLY when its controller is paused, team-immune with the viewer, or it is the viewer's
     * own owner-immune mine; with identifyMines a mine within SHIP_SZ + MINE_SENSE_BASE_RANGE + sensors *
     * MINE_SENSE_RANGE_FACTOR carries its controller's id (a confused one shows nobody and a random friendliness);
     * NUCLEAR and OWN flags are set for nuclear and own mines.
     * BEHAVIOR: missiles carry their kind (smart/heat/torpedo, nuclear bit), direction and C's length
     * (distinguishMissiles: 12/15/18, else 15).
     */
    void writeObjects(Player v) {
        Objects o = w.objects;
        ItemParams ip = w.items;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            int type = o.type[h];
            if ((type & (Obj.ITEM | Obj.MINE | Obj.SMART_SHOT | Obj.HEAT_SHOT | Obj.TORPEDO)) == 0) {
                continue;
            }
            double x = cx(h);
            double y = cy(h);
            if (!b.visible(x, y, 0)) {
                continue;
            }
            if (type == Obj.ITEM) {
                int kind = o.info[h];
                if ((o.status[h] & Obj.RANDOM_ITEM) != 0) {
                    kind = randomItem();
                }
                if (b.need(Section.ITEM, 0)) {
                    Records.ItemObj.put(b.writer(), x, y, kind);
                }
            } else if (type == Obj.MINE) {
                if (b.need(Section.MINE, 0)) {
                    Records.Mine.put(b.writer(), x, y, mineFlags(h, v), mineId(h, v));
                }
            } else if (b.need(Section.MISSILE, 0)) {
                int kind;
                int len;
                if (type == Obj.TORPEDO) {
                    kind = Records.Missile.KIND_TORPEDO;
                    len = ip.distinguishMissiles ? TORPEDO_LEN : MISSILE_LEN;
                } else if (type == Obj.SMART_SHOT) {
                    kind = Records.Missile.KIND_SMART;
                    len = ip.distinguishMissiles ? SMART_SHOT_LEN : MISSILE_LEN;
                } else {
                    kind = Records.Missile.KIND_HEAT;
                    len = ip.distinguishMissiles ? HEAT_SHOT_LEN : MISSILE_LEN;
                }
                if (Mods.isNuclear(o.mods[h])) {
                    kind |= Records.Missile.KIND_NUCLEAR;
                }
                int id = o.owner[h] == Obj.NO_ID ? Protocol.NO_ID : o.owner[h];
                Records.Missile.put(b.writer(), id, x, y, o.missileDir[h] * (2 * Math.PI / Const.RES), kind, len);
            }
        }
    }

    /** C {@code Choose_random_item} drawn from the snapshot RNG. */
    int randomItem() {
        ItemParams ip = w.items;
        float total = 0;
        for (int i = 0; i < Item.COUNT; i++) {
            total += ip.prob[i];
        }
        if (total <= 0) {
            return Item.COUNT;
        }
        float sum = total * rng.nextFloat();
        for (int i = 0; i < Item.COUNT; i++) {
            sum -= ip.prob[i];
            if (sum <= 0) {
                return i;
            }
        }
        return Item.FUEL.ordinal();
    }

    /** Whether the viewer can tell who controls mine {@code h} (C frame.c:866-870). */
    private boolean identified(int h, Player v) {
        Objects o = w.objects;
        return w.items.identifyMines && w.wrapLength(v.px - o.px[h], v.py - o.py[h])
            < (Const.SHIP_SZ + MINE_SENSE_BASE_RANGE + v.items[Item.SENSOR.ordinal()] * MINE_SENSE_RANGE_FACTOR);
    }

    /** BEHAVIOR: the Mine record flags of mine {@code h} for viewer {@code v} (C frame.c:858-889). */
    int mineFlags(int h, Player v) {
        Objects o = w.objects;
        int f = 0;
        boolean confused = identified(h, v) && (o.status[h] & Obj.CONFUSED) != 0;
        Player owner = o.owner[h] != Obj.NO_ID ? w.byId(o.owner[h]) : null;
        boolean friendly;
        if (owner != null && (owner.status & Obj.PAUSE) != 0) {
            friendly = true;
        } else {
            friendly = w.teamImmune(o.owner[h], v.id)
                || ((o.status[h] & Obj.OWNERIMMUNE) != 0 && o.mineOwner[h] == v.id);
            if (confused) {
                friendly = rng.nextFloat() < 0.5f;
            }
        }
        if (friendly) {
            f |= Records.Mine.FLAG_FRIENDLY;
        }
        if ((o.status[h] & Obj.OWNERIMMUNE) == 0) {
            f |= Records.Mine.FLAG_ARMED;
        }
        if (Mods.isNuclear(o.mods[h])) {
            f |= Records.Mine.FLAG_NUCLEAR;
        }
        if (o.owner[h] == v.id) {
            f |= Records.Mine.FLAG_OWN;
        }
        return f;
    }

    /**
     * BEHAVIOR: the identified controller's id ({@link Protocol#NO_ID} when not identified or confused; an
     * identified mine nobody owns is {@code EXPIRED_MINE_ID}, C frame.c:873; a
     * confused mine of a paused controller still shows it, C frame.c:877-889 resets the id only outside the
     * paused branch).
     */
    int mineId(int h, Player v) {
        Objects o = w.objects;
        if (!identified(h, v)) {
            return Protocol.NO_ID;
        }
        if (o.owner[h] == Obj.NO_ID) {
            return Records.Mine.EXPIRED_MINE_ID;
        }
        Player owner = w.byId(o.owner[h]);
        if ((o.status[h] & Obj.CONFUSED) != 0 && (owner == null || (owner.status & Obj.PAUSE) == 0)) {
            return Protocol.NO_ID;
        }
        return o.owner[h];
    }

    /**
     * Laser pulses (C {@code Frame_ships}): drawn from their start when that is in view, else from their end
     * backwards; pulses of length 0 or out of view are skipped. Blue when harmless to the viewer (team immune, or
     * its own with selfImmunity), else red.
     */
    void writeLasers(Player v) {
        Pulses p = w.pulses;
        for (int j = 0; j < p.count(); j++) {
            if (p.len[j] <= 0) {
                continue;
            }
            double x = w.wrapXPixel(p.x[j]);
            double y = w.wrapYPixel(p.y[j]);
            int dir;
            if (b.visible(x, y, 0)) {
                dir = p.dir[j];
            } else {
                x = w.wrapXPixel(x + Trig.tcos(p.dir[j]) * p.len[j]);
                y = w.wrapYPixel(y + Trig.tsin(p.dir[j]) * p.len[j]);
                if (!b.visible(x, y, 0)) {
                    continue;
                }
                dir = Trig.mod(p.dir[j] + Const.RES / 2);
            }
            int color = w.teamImmune(p.owner[j], v.id) || (p.owner[j] == v.id && w.params.selfImmunity)
                ? Records.ObjColor.BLUE : Records.ObjColor.RED;
            if (b.need(Section.LASER, 0)) {
                Records.Laser.put(b.writer(), x, y, dir * (2 * Math.PI / Const.RES), p.len[j], color);
            }
        }
    }

    /** ECM blasts in view (C sends them all; the client culls), with their current radius. */
    void writeEcms() {
        Ecms e = w.ecms;
        for (int i = 0; i < e.count(); i++) {
            if (!b.visible(e.x[i], e.y[i], e.size[i]) || !b.need(Section.ECM, 0)) {
                continue;
            }
            Records.Ecm.put(b.writer(), e.x[i], e.y[i], e.size[i]);
        }
    }

    /**
     * Tractor/pressor beams of players and cannons (three connectors to the target's hull points) and transporter
     * beams (from the victim to the thief, or to where a cannon fired it), when the target is in view.
     * <p>
     * BEHAVIOR: a player's beam needs the beamer playing (not paused, not GAME_OVER), in view, using its tractor
     * beam on an existing lock target in view (C frame.c:1007-1015, 1064-1076).
     */
    void writeBeams(Player v) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if ((pl.status & (Obj.PLAYING | Obj.PAUSE)) == 0 || (pl.status & (Obj.GAME_OVER | Obj.PAUSE)) != 0
                || (pl.used & Has.TRACTOR_BEAM) == 0 || !b.visible(pl.px, pl.py, 0)) {
                // C frame.c:1015: only ships in view get their tractor beam drawn
                continue;
            }
            Player t = w.byId(pl.lockPlId);
            if (t != null) {
                beam(t, pl.px, pl.py, pl.tractorIsPressor);
            }
        }
        CannonItems ci = w.cannonItems;
        for (int c = 0; c < w.map.cannons().size(); c++) {
            if (ci.tractorCount[c] > 0) {
                Player t = w.byId(ci.tractorTarget[c]);
                if (t != null) {
                    beam(t, (int) w.map.cannons().get(c).x(), (int) w.map.cannons().get(c).y(),
                        ci.tractorIsPressor[c]);
                }
            }
        }
        Ecms e = w.ecms;
        for (int i = 0; i < e.transporters(); i++) {
            Player victim = w.byId(e.transTarget[i]);
            if (victim == null) {
                continue;
            }
            Player thief = e.transOwner[i] != Obj.NO_ID ? w.byId(e.transOwner[i]) : null;
            double x = thief != null ? thief.px : e.transX[i];
            double y = thief != null ? thief.py : e.transY[i];
            if ((b.visible(victim.px, victim.py, 0) || b.visible(x, y, 0)) && b.need(Section.TRANSPORTER, 0)) {
                Records.Transporter.put(b.writer(), victim.px, victim.py, x, y);
            }
        }
    }

    private void beam(Player t, int x, int y, boolean pressor) {
        if (!b.visible(t.px, t.py, 0)) {
            return;
        }
        int kind = pressor ? Records.Connector.KIND_PRESSOR : Records.Connector.KIND_TRACTOR;
        for (int j = 0; j < 3 && j < t.ship.numPoints; j++) {
            if (b.need(Section.CONNECTOR, 0)) {
                Records.Connector.put(b.writer(), (int) (t.px + t.ship.ptX[j][t.dir]), (int) (t.py + t.ship.ptY[j][t.dir]),
                    x, y, kind);
            }
        }
    }

    /**
     * Radar blips of missiles and mines (C {@code Frame_radar} objects): with nukesOnRadar every missile and mine
     * (nuclear ones flagged), else missiles with missilesOnRadar and mines with minesOnRadar; only within the
     * viewer's sensor range. (C also blinks them by frame number; our radar goes out every few snapshots, so they
     * are always shown.)
     */
    void writeRadarObjects(Player v) {
        ItemParams ip = w.items;
        int mask;
        if (ip.nukesOnRadar) {
            mask = Obj.SMART_SHOT | Obj.TORPEDO | Obj.HEAT_SHOT | Obj.MINE;
        } else {
            mask = (ip.missilesOnRadar ? Obj.SMART_SHOT | Obj.TORPEDO | Obj.HEAT_SHOT : 0)
                | (ip.minesOnRadar ? Obj.MINE : 0);
        }
        if (mask == 0) {
            return;
        }
        Objects o = w.objects;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            int type = o.type[h];
            if ((type & mask) == 0) {
                continue;
            }
            boolean nuke = ip.nukesOnRadar && Mods.nuclear(o.mods[h]) != 0;
            if (type == Obj.MINE) {
                if (!ip.minesOnRadar && !nuke) {
                    continue;
                }
            } else if (!ip.missilesOnRadar && !nuke) {
                continue;
            }
            if (w.wrapLength(v.px - o.px[h], v.py - o.py[h]) > v.sensorRange || !b.need(Section.RADAR, 0)) {
                continue;
            }
            int flags = type == Obj.MINE ? Records.Radar.FLAG_MINE : Records.Radar.FLAG_MISSILE;
            if (nuke) {
                flags |= Records.Radar.FLAG_NUKE;
            }
            Records.Radar.put(b.writer(), o.px[h], o.py[h], flags);
        }
    }

    /**
     * Whether ship {@code pl} is drawn for {@code v} (C {@code Frame_ships}): seen (not cloaked away), the viewer
     * itself, a team mate or the viewer's own tank.
     */
    boolean shipShown(Player pl, Player v) {
        return pl == v || v.canSee[pl.ind] || w.sameTeam(pl, v) || (pl.tank && pl.lockPlId == v.id);
    }

    /**
     * Whether ship {@code pl} is on {@code v}'s radar (C {@code Frame_radar} players): playing, and a team mate
     * or own tank, or (with playersOnRadar) seen; within sensor range with limitedVisibility. (The locked player's
     * blink is the client's, see {@link #radarLocked}.)
     */
    boolean onRadar(Player pl, Player v) {
        if (pl == v) {
            return true;
        }
        if ((pl.status & (Obj.PLAYING | Obj.PAUSE | Obj.GAME_OVER)) != Obj.PLAYING) {
            return false;
        }
        boolean friend = w.sameTeam(pl, v) || (pl.tank && pl.lockPlId == v.id);
        if (!friend && (!w.items.playersOnRadar || !v.canSee[pl.ind])) {
            return false;
        }
        if (w.items.limitedVisibility && w.wrapLength(v.px - pl.px, v.py - pl.py) > v.sensorRange) {
            return false;
        }
        return true;
    }

    /**
     * BEHAVIOR: whether {@code pl} is {@code v}'s compass lock (C frame.c:1180-1185): the radar record carries
     * FLAG_LOCKED and the client hides it when {@code loops % 5 >= 3}, as C's server does per frame.
     */
    boolean radarLocked(Player pl, Player v) {
        return (v.used & Has.COMPASS) != 0 && (v.lockTagged & Player.LOCK_PLAYER) != 0 && v.lockPlId == pl.id;
    }

    /** BEHAVIOR: C's radar 0x80 (friendly) for {@code pl} seen by {@code v}: a team mate or {@code v}'s own tank. */
    boolean radarFriendly(Player pl, Player v) {
        return w.sameTeam(pl, v) || (pl.tank && pl.lockPlId == v.id);
    }
}
