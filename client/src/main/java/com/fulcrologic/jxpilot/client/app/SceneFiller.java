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
import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.client.game.ParticlePool;
import com.fulcrologic.jxpilot.client.game.Players;
import com.fulcrologic.jxpilot.client.paint.Particles;
import com.fulcrologic.jxpilot.client.paint.RadarPainter;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.client.paint.ShipPainter;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;

/**
 * The seam between game state and the painters: maps a {@link ClientGame} (its {@link ClientWorld}
 * after {@code update}, {@link Players}, rules and options) into the {@link SceneData} the scene painter
 * draws and the {@link RadarBlips} of the radar. Pure: no GL, no clock, no I/O; allocation free.
 * <p>
 * Drawn: the own ship and other ships (shape from {@link Players}, ship-painter flags, team-relative
 * colour, lock, name), paused players as paused markers, shots (C fastshot/teamshot colouring),
 * sparks and debris (temperature colour), radar blips and the own radar ship. Not drawn because
 * {@link ClientWorld} does not model them yet: items, mines, missiles, lasers, balls, wreckage,
 * asteroids, ECM, links, ECM damage and round delay (left empty / 0).
 */
public final class SceneFiller {
    /** {@link Records.ShipFlags} bits the ship painter uses (same values as {@link ShipPainter}'s). */
    public static final int SHIP_PAINT_FLAGS = ShipPainter.SHIELD | ShipPainter.CLOAKED | ShipPainter.PHASING
        | ShipPainter.EMERGENCY_SHIELD | ShipPainter.DEFLECTOR;
    /** C {@code Frame_radar}: players are 3x3. */
    public static final int RADAR_PLAYER = 3;
    /** C {@code Frame_radar}: balls are 2x2. */
    public static final int RADAR_BALL = 2;
    /** The wire's radar record has no asteroid size: drawn as a size-1 asteroid. */
    public static final int RADAR_ASTEROID_SIZE = 1;
    /** Radar size byte meaning "not shown this frame" (a blink phase). */
    public static final int HIDDEN = -1;

    /**
     * Fill {@code out} and {@code radar} for the frame the world was last updated to.
     * <p>
     * BEHAVIOR: clears both; sets the frame scalars: loops ({@link #loops}), fps (rounded game fps),
     * selfId (the own ship's id, else the player id, -1 when none), damaged 0 and roundDelay 0.
     * BEHAVIOR: adds the own ship first (unless absent or dead), then every interpolated ship that is not
     * dead; paused ones become paused markers (countdown 0) instead (see {@link #addShip}).
     * BEHAVIOR: adds every shot ({@link #shotColor}, {@link #teamShot}), every visible particle
     * ({@link #particleColor}), every radar blip ({@link #radarSize}, hidden blink phases skipped) and the
     * own radar ship while visible.
     */
    public void fill(SceneData out, RadarBlips radar, ClientGame g) {
        ClientWorld w = g.world();
        Players p = g.players();
        out.clear();
        radar.clear();
        long loops = loops(w);
        out.loops = loops;
        out.fps = Math.max(1, Math.round(g.gameFps()));
        int selfId = w.hasSelf() ? w.selfId() : p.selfId();
        out.selfId = selfId == Protocol.NO_ID ? -1 : selfId;
        out.damaged = 0;
        out.roundDelay = 0;
        boolean teamPlay = g.teamPlay();
        int lock = w.hasSelf() ? w.selfLockId() : Protocol.NO_ID;
        boolean selfAlive = w.hasSelf() && (w.selfFlags() & Records.ShipFlags.DEAD) == 0;
        if (selfAlive) {
            addShip(out, p, w.selfId(), w.selfFlags(), w.selfX(), w.selfY(), w.selfHeading(), true, teamPlay, false);
        }
        for (int i = 0; i < w.shipCount(); i++) {
            int flags = w.shipFlags(i);
            if ((flags & Records.ShipFlags.DEAD) == 0) {
                addShip(out, p, w.shipId(i), flags, w.shipX(i), w.shipY(i), w.shipHeading(i), false, teamPlay,
                    w.shipId(i) == lock);
            }
        }
        boolean selfImmunity = g.options() != null && g.options().getBool(Opt.SELF_IMMUNITY);
        for (int i = 0; i < w.shotCount(); i++) {
            int c = w.shotColor(i);
            out.addShot(w.shotX(i), w.shotY(i), shotColor(c, selfImmunity), teamShot(c, selfImmunity));
        }
        particles(out, w);
        for (int i = 0; i < w.radarCount(); i++) {
            int size = radarSize(w.radarFlags(i), loops);
            if (size != HIDDEN) {
                radar.add(w.radarX(i), w.radarY(i), size);
            }
        }
        if (selfAlive) {
            radar.selfVisible = true;
            radar.selfX = w.selfX();
            radar.selfY = w.selfY();
            radar.selfHeading = w.selfHeading();
        }
    }

    /**
     * One ship.
     * <p>
     * BEHAVIOR: a PAUSED ship becomes a paused marker at its position (countdown 0: steadily shown).
     * BEHAVIOR: otherwise a ship with the player's shape (the default ship for unknown players), the
     * {@link #SHIP_PAINT_FLAGS} of its flags, colour {@link ShipPainter#shipColor}(friendly, self, no round
     * delay) with friendly from {@link #friendly}, the lock flag, and the player's name (null when unknown).
     */
    void addShip(SceneData out, Players p, int id, int flags, float x, float y, float heading, boolean self,
                 boolean teamPlay, boolean locked) {
        if ((flags & Records.ShipFlags.PAUSED) != 0) {
            out.addPaused(x, y, 0);
            return;
        }
        int slot = p.slotOf(id);
        ShipShape shape = slot >= 0 ? p.shape(slot) : ShipShape.defaultShip();
        CharSequence name = slot >= 0 ? p.name(slot) : null;
        int color = ShipPainter.shipColor(friendly(flags, p, slot, teamPlay), self, false);
        out.addShip(x, y, heading, shape, id, flags & SHIP_PAINT_FLAGS, color, locked, name);
    }

    /**
     * C {@code Gui_calculate_ship_color}'s friend test.
     * <p>
     * BEHAVIOR: true when the server marked the ship FRIENDLY, or in team play when both the ship's player
     * and the own player are known and on the same team (not {@link Protocol#NO_TEAM}).
     */
    static boolean friendly(int flags, Players p, int slot, boolean teamPlay) {
        if ((flags & Records.ShipFlags.FRIENDLY) != 0) {
            return true;
        }
        if (!teamPlay || slot < 0) {
            return false;
        }
        int self = p.slotOf(p.selfId());
        return self >= 0 && p.team(slot) != Protocol.NO_TEAM && p.team(slot) == p.team(self);
    }

    /**
     * Shot colour (C server {@code Frame_shots}): team-immune shots blue, blinking nuclear red, else the
     * owner's white.
     * <p>
     * BEHAVIOR: TEAM and BLUE are blue; OWN is blue iff selfImmunity; RED is red; everything else white.
     */
    static int shotColor(int objColor, boolean selfImmunity) {
        return switch (objColor) {
            case Records.ObjColor.TEAM, Records.ObjColor.BLUE -> Palette.BLUE;
            case Records.ObjColor.OWN -> selfImmunity ? Palette.BLUE : Palette.WHITE;
            case Records.ObjColor.RED -> Palette.RED;
            default -> Palette.WHITE;
        };
    }

    /**
     * C {@code teamshot}: drawn as an X or + instead of a square.
     * <p>
     * BEHAVIOR: true for TEAM, BLUE and RED, and for OWN iff selfImmunity; false otherwise.
     */
    static boolean teamShot(int objColor, boolean selfImmunity) {
        return objColor == Records.ObjColor.TEAM || objColor == Records.ObjColor.BLUE
            || objColor == Records.ObjColor.RED || objColor == Records.ObjColor.OWN && selfImmunity;
    }

    private static void particles(SceneData out, ClientWorld w) {
        ParticlePool pp = w.particles();
        float ff = w.frameFraction();
        double rt = w.renderTick();
        double now = w.serverTickNow();
        for (int i = 0; i < pp.count(); i++) {
            if (pp.visible(i)) {
                double tick = pp.own(i) ? now : rt;
                out.addParticle(pp.x(i), pp.y(i), particleColor(pp.life(i), pp.birth(i), tick, ff, pp.color(i)));
            }
        }
    }

    /**
     * Palette colour of a spark or debris particle (C {@code Frame_shots} temperature levels, then
     * {@code sparkColors}).
     * <p>
     * BEHAVIOR: the life left is {@code (int)(life - (tick - birth) * frameFraction)} original frames; an
     * afterburner (BLUE) particle counts as blue-owned; the colour is
     * {@link Particles#colorIndex(int, boolean)} of the two.
     */
    static int particleColor(float life, double birth, double tick, float frameFraction, int objColor) {
        int left = (int) (life - (tick - birth) * frameFraction);
        return Particles.colorIndex(left, objColor == Records.ObjColor.BLUE);
    }

    /**
     * C's radar size byte for a radar record, with the blinks the C server applies per frame
     * ({@code Frame_radar}).
     * <p>
     * BEHAVIOR: SELF records are {@link #HIDDEN} (the own ship is drawn from its own position).
     * BEHAVIOR: missiles are 0 but hidden on odd loops; mines 0 but hidden when {@code loops % 8 >= 6};
     * nuclear ones are 3 when {@code loops & 2}, else 0 (the hiding still applies).
     * BEHAVIOR: balls 2; asteroids {@link RadarPainter#asteroidSizeByte} of {@link #RADAR_ASTEROID_SIZE}.
     * BEHAVIOR: players 3, with 0x80 when FRIENDLY; a LOCKED player is hidden when {@code loops % 5 >= 3}
     * (C's compass blink).
     */
    static int radarSize(int flags, long loops) {
        if ((flags & Records.Radar.FLAG_SELF) != 0) {
            return HIDDEN;
        }
        boolean nuke = (flags & Records.Radar.FLAG_NUKE) != 0;
        int nukeSize = nuke && (loops & 2) != 0 ? RADAR_PLAYER : 0;
        if ((flags & Records.Radar.FLAG_MINE) != 0) {
            return loops % 8 >= 6 ? HIDDEN : nukeSize;
        }
        if ((flags & Records.Radar.FLAG_MISSILE) != 0) {
            return (loops & 1) != 0 ? HIDDEN : nukeSize;
        }
        if ((flags & Records.Radar.FLAG_BALL) != 0) {
            return RADAR_BALL;
        }
        if ((flags & Records.Radar.FLAG_ASTEROID) != 0) {
            return RadarPainter.asteroidSizeByte(RADAR_ASTEROID_SIZE);
        }
        if ((flags & Records.Radar.FLAG_LOCKED) != 0 && loops % 5 >= 3) {
            return HIDDEN;
        }
        return (flags & Records.Radar.FLAG_FRIENDLY) != 0 ? RADAR_PLAYER | 0x80 : RADAR_PLAYER;
    }

    /**
     * C {@code loops} for the frame: the server frame of the newer snapshot around the render tick
     * ({@code SceneData.loops}).
     * <p>
     * BEHAVIOR: 0 before any snapshot; else {@code floor(ceil(renderTick) * frameFraction)}, i.e. the
     * render tick rounded up to a whole tick, in original frames; never negative (render ticks before
     * tick 0 count as 0).
     */
    static long loops(ClientWorld w) {
        if (!w.hasSnapshot()) {
            return 0;
        }
        return Math.max(0, (long) Math.floor(Math.ceil(w.renderTick()) * w.frameFraction()));
    }
}
