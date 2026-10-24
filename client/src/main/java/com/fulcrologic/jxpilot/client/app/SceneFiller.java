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
import com.fulcrologic.jxpilot.client.game.ObjectLists;
import com.fulcrologic.jxpilot.client.game.ParticlePool;
import com.fulcrologic.jxpilot.client.game.Players;
import com.fulcrologic.jxpilot.client.paint.PaintStyle;
import com.fulcrologic.jxpilot.client.paint.Particles;
import com.fulcrologic.jxpilot.client.paint.RadarPainter;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.client.paint.ShipPainter;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;

/**
 * The seam between game state and the painters: maps a {@link ClientGame} (its {@link ClientWorld}
 * after {@code update}, {@link Players}, rules and options) into the {@link SceneData} the scene painter
 * draws and the {@link RadarBlips} of the radar. Pure: no GL, no clock, no I/O; allocation free.
 * <p>
 * Drawn: the own ship and other ships (shape from {@link Players}, ship-painter flags, team-relative
 * colour, lock, name), paused players as paused markers, shots (C fastshot/teamshot colouring),
 * sparks and debris (temperature colour) and tracked point particles (C debris), the M2 objects
 * ({@link #objects}, refuel/repair beams among them), ECM damage and round delay (red ships),
 * radar blips and the own radar ship.
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

    /** The live look options: {@code sparkColors} colour the particles. */
    private PaintStyle style = new PaintStyle();

    /** Use the shared live look options ({@code sparkColors}). */
    public void setStyle(PaintStyle style) {
        this.style = style;
    }

    /**
     * Fill {@code out} and {@code radar} for the frame the world was last updated to.
     * <p>
     * BEHAVIOR: clears both; sets the frame scalars: loops ({@link #loops}), fps (rounded game fps),
     * selfId (the own ship's id, else the player id, -1 when none), damaged and roundDelay from the world.
     * BEHAVIOR: adds the own ship first (unless absent or dead), then every interpolated ship that is not
     * dead; paused ones become paused markers (countdown 0) instead (see {@link #addShip}).
     * BEHAVIOR: adds every shot ({@link #shotColor}, {@link #teamShot}), every visible particle
     * ({@link #particleColor}), every radar blip ({@link #radarSize}, hidden blink phases skipped; when the
     * server already blinks them {@link #radarSizeUnblinked}) and the own radar ship while visible.
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
        out.damaged = w.damaged();
        out.roundDelay = w.roundDelay();
        boolean teamPlay = g.teamPlay();
        roundDelay = w.roundDelay() > 0;
        map = g.map();
        int lock = w.hasSelf() ? w.selfLockId() : Protocol.NO_ID;
        boolean selfAlive = w.hasSelf() && (w.selfFlags() & Records.ShipFlags.DEAD) == 0;
        if (selfAlive) {
            pauseCount = 0;
            addShip(out, p, w.selfId(), w.selfFlags(), w.selfX(), w.selfY(), w.selfHeading(), true, teamPlay, false);
        }
        for (int i = 0; i < w.shipCount(); i++) {
            int flags = w.shipFlags(i);
            if ((flags & Records.ShipFlags.DEAD) == 0) {
                pauseCount = w.shipVx(i);
                addShip(out, p, w.shipId(i), flags, w.shipX(i), w.shipY(i), w.shipHeading(i), false, teamPlay,
                    w.shipId(i) == lock);
            }
        }
        for (int i = 0; i < w.shotCount(); i++) {
            boolean harmless = w.shotHarmless(i);
            int c = nuclearBlink(w.shotKind(i), w.shotColor(i), harmless, w.renderTick());
            out.addShot(w.shotX(i), w.shotY(i), shotColor(c, harmless), teamShot(c, harmless));
        }
        objects(out, w);
        particles(out, w, style);
        for (int i = 0; i < w.pointCount(); i++) {
            out.addParticle(w.pointX(i), w.pointY(i), pointColor(w.pointColor(i), style));
        }
        boolean blinked = g.radarBlinked();
        for (int i = 0; i < w.radarCount(); i++) {
            int size = blinked ? radarSizeUnblinked(w.radarFlags(i)) : radarSize(w.radarFlags(i), loops);
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
     * BEHAVIOR: a PAUSED ship becomes a paused marker at its position; one without an id (C PKT_PAUSED) carries
     * C's pause count in its vx (the P blinks while it is above 0), others count 0 (steadily shown).
     * BEHAVIOR: otherwise a ship with the player's shape (the default ship for unknown players), the
     * {@link #SHIP_PAINT_FLAGS} of its flags, colour {@link ShipPainter#shipColor}(friendly, self, round delay
     * pending) with friendly from {@link #friendly}, the lock flag, and the player's name (null when
     * unknown).
     */
    void addShip(SceneData out, Players p, int id, int flags, float x, float y, float heading, boolean self,
                 boolean teamPlay, boolean locked) {
        if ((flags & Records.ShipFlags.PAUSED) != 0) {
            out.addPaused(x, y, id == Protocol.NO_ID ? Math.max(0, Math.round(pauseCount)) : 0);
            return;
        }
        int slot = p.slotOf(id);
        ShipShape shape = slot >= 0 ? p.shape(slot) : ShipShape.defaultShip();
        CharSequence name = slot >= 0 ? p.name(slot) : null;
        int color = ShipPainter.shipColor(friendly(flags, p, slot, teamPlay), self, roundDelay);
        out.addShip(x, y, heading, shape, id, flags & SHIP_PAINT_FLAGS, color, locked, name);
    }

    private boolean roundDelay;
    private WorldMap map;
    /** The vx of the ship being added: C's pause count for a paused ship without an id (see XpTranslator). */
    private float pauseCount;

    /**
     * The M2 objects of {@link ClientWorld#objects()} into the scene.
     * <p>
     * BEHAVIOR: items with their type; mines (team mine iff FRIENDLY, no name); missiles with their length
     * and RES direction; balls, each with its tether ({@link #ballTether}); lasers with RES direction and the object colour ({@link #pointColor});
     * asteroids (shape type from the size byte's high nibble, RES rotation, size from its low nibble);
     * wreckage (deadly iff its shape byte has bit 7 (C), the shape in the low 7 bits, RES rotation, size); ECM
     * flashes of diameter 2 * radius; connectors ({@link #linkKind}: ball pick-up lines, refuel/repair beams
     * from the server's endpoints, tractor/pressor beams) and transporter beams (LINK_TRANSPORTER), each the
     * short way ({@link #line}).
     */
    void objects(SceneData out, ClientWorld w) {
        ObjectLists o = w.objects();
        for (int i = 0; i < o.count(ObjectLists.ITEM); i++) {
            out.addItem(o.get(ObjectLists.ITEM, i, 0), o.get(ObjectLists.ITEM, i, 1), (int) o.get(ObjectLists.ITEM, i, 2));
        }
        for (int i = 0; i < o.count(ObjectLists.MINE); i++) {
            out.addMine(o.get(ObjectLists.MINE, i, 0), o.get(ObjectLists.MINE, i, 1),
                ((int) o.get(ObjectLists.MINE, i, 2) & Records.Mine.FLAG_FRIENDLY) != 0, null);
        }
        for (int i = 0; i < o.count(ObjectLists.MISSILE); i++) {
            out.addMissile(o.get(ObjectLists.MISSILE, i, 0), o.get(ObjectLists.MISSILE, i, 1),
                o.get(ObjectLists.MISSILE, i, 4), res(o.get(ObjectLists.MISSILE, i, 2)));
        }
        for (int i = 0; i < o.count(ObjectLists.BALL); i++) {
            float bx = o.get(ObjectLists.BALL, i, 0);
            float by = o.get(ObjectLists.BALL, i, 1);
            out.addBall(bx, by);
            ballTether(out, w, bx, by, (int) o.get(ObjectLists.BALL, i, 3));
        }
        for (int i = 0; i < o.count(ObjectLists.LASER); i++) {
            out.addLaser(o.get(ObjectLists.LASER, i, 0), o.get(ObjectLists.LASER, i, 1), o.get(ObjectLists.LASER, i, 3),
                res(o.get(ObjectLists.LASER, i, 2)), pointColor((int) o.get(ObjectLists.LASER, i, 4), style));
        }
        for (int i = 0; i < o.count(ObjectLists.ASTEROID); i++) {
            int sizeByte = (int) o.get(ObjectLists.ASTEROID, i, 2);
            out.addAsteroid(o.get(ObjectLists.ASTEROID, i, 0), o.get(ObjectLists.ASTEROID, i, 1), sizeByte >> 4,
                res(o.get(ObjectLists.ASTEROID, i, 3)), sizeByte & 0x0F);
        }
        for (int i = 0; i < o.count(ObjectLists.WRECK); i++) {
            int shape = (int) o.get(ObjectLists.WRECK, i, 2);
            out.addWreck(o.get(ObjectLists.WRECK, i, 0), o.get(ObjectLists.WRECK, i, 1), (shape & 0x80) != 0,
                shape & 0x7F, res(o.get(ObjectLists.WRECK, i, 4)), (int) o.get(ObjectLists.WRECK, i, 3));
        }
        for (int i = 0; i < o.count(ObjectLists.ECM); i++) {
            out.addEcm(o.get(ObjectLists.ECM, i, 0), o.get(ObjectLists.ECM, i, 1), 2 * o.get(ObjectLists.ECM, i, 2));
        }
        for (int i = 0; i < o.count(ObjectLists.CONNECTOR); i++) {
            line(out, w, o.get(ObjectLists.CONNECTOR, i, 0), o.get(ObjectLists.CONNECTOR, i, 1),
                o.get(ObjectLists.CONNECTOR, i, 2), o.get(ObjectLists.CONNECTOR, i, 3),
                linkKind((int) o.get(ObjectLists.CONNECTOR, i, 4)));
        }
        for (int i = 0; i < o.count(ObjectLists.TRANSPORTER); i++) {
            line(out, w, o.get(ObjectLists.TRANSPORTER, i, 0), o.get(ObjectLists.TRANSPORTER, i, 1),
                o.get(ObjectLists.TRANSPORTER, i, 2), o.get(ObjectLists.TRANSPORTER, i, 3), SceneData.LINK_TRANSPORTER);
        }
    }

    /**
     * C {@code Paint_balls}: the solid line from a connected ball to the ship holding it.
     * <p>
     * BEHAVIOR: for a holder id other than NO_ID, a LINK_BALL from the ball to the drawn own ship (when it is
     * the holder and alive) or to the drawn ship with that id; nothing when that ship is not drawn.
     */
    static void ballTether(SceneData out, ClientWorld w, float bx, float by, int holder) {
        if (holder == Protocol.NO_ID) {
            return;
        }
        if (w.hasSelf() && w.selfId() == holder) {
            if ((w.selfFlags() & Records.ShipFlags.DEAD) == 0) {
                tether(out, w, bx, by, w.selfX(), w.selfY());
            }
            return;
        }
        for (int s = 0; s < w.shipCount(); s++) {
            if (w.shipId(s) == holder && (w.shipFlags(s) & Records.ShipFlags.DEAD) == 0) {
                tether(out, w, bx, by, w.shipX(s), w.shipY(s));
                return;
            }
        }
    }

    /** The ship end is the copy nearest the ball (a tether never spans the world). */
    private static void tether(SceneData out, ClientWorld w, float bx, float by, float sx, float sy) {
        line(out, w, bx, by, sx, sy, SceneData.LINK_BALL);
    }

    /**
     * BEHAVIOR: a link whose second end is the copy of (x2, y2) nearest (x1, y1) in a wrapping world, so a
     * line between objects on both sides of the seam (or in a world smaller than the view) is the short one.
     */
    static void line(SceneData out, ClientWorld w, float x1, float y1, float x2, float y2, int kind) {
        out.addLink(x1, y1, (float) (x1 + XMath.wrapDelta(x2 - x1, w.worldWidth(), w.wraps())),
            (float) (y1 + XMath.wrapDelta(y2 - y1, w.worldHeight(), w.wraps())), kind);
    }

    /**
     * BEHAVIOR: connector kind to link kind: BALL is LINK_CONNECTOR, REFUEL and REPAIR are LINK_REFUEL (C
     * Paint_refuel), anything else (tractor, pressor) LINK_TRACTOR.
     */
    static int linkKind(int kind) {
        return switch (kind) {
            case Records.Connector.KIND_BALL -> SceneData.LINK_CONNECTOR;
            case Records.Connector.KIND_REFUEL, Records.Connector.KIND_REPAIR -> SceneData.LINK_REFUEL;
            default -> SceneData.LINK_TRACTOR;
        };
    }

    /** BEHAVIOR: radians to RES (128 per turn), rounded, in 0..127. */
    static int res(double radians) {
        return (int) Math.round(radians * 128 / (2 * Math.PI)) & 127;
    }

    /**
     * C {@code Gui_calculate_ship_color}'s friend test.
     * <p>
     * BEHAVIOR: true when the server marked the ship FRIENDLY; else, both players known and the ship not
     * the own one: true when both have the same non-blank alliance mark (C guiobjects.c:680-684), when it is the
     * viewer's own tank ({@link #myTank}, guiobjects.c:687), or in team play on the same team (not
     * {@link Protocol#NO_TEAM}).
     */
    static boolean friendly(int flags, Players p, int slot, boolean teamPlay) {
        if ((flags & Records.ShipFlags.FRIENDLY) != 0) {
            return true;
        }
        if (slot < 0) {
            return false;
        }
        int self = p.slotOf(p.selfId());
        if (self < 0 || self == slot) {
            return false;
        }
        if (p.alliance(self) != ' ' && p.alliance(self) == p.alliance(slot)) {
            return true; // C Gui_calculate_ship_color: same alliance
        }
        if (myTank(p, self, slot, teamPlay)) {
            return true; // C Gui_calculate_ship_color: Gui_is_my_tank (L6)
        }
        return teamPlay && p.team(slot) != Protocol.NO_TEAM && p.team(slot) == p.team(self);
    }

    /** C {@code "'s tank"}: the name the server gives a player's detached tank. */
    static final String TANK_SUFFIX = "'s tank";

    /**
     * C {@code Gui_is_my_tank}.
     * <p>
     * BEHAVIOR: true iff the player in {@code slot} is a tank ('T'), in team play on the viewer's team, and named
     * the viewer's name plus "'s tank", cut to {@code MAX_NAME_LEN - 1} characters as C's {@code strlcat} cuts it
     * (no suffix at all when the viewer's own name already fills the buffer).
     */
    static boolean myTank(Players p, int self, int slot, boolean teamPlay) {
        if (p.statusChar(slot) != 'T' || teamPlay && p.team(self) != p.team(slot)) {
            return false;
        }
        CharSequence own = p.name(self);
        CharSequence other = p.name(slot);
        int max = Const.MAX_NAME_LEN - 1;
        int ownLen = Math.min(own.length(), max);
        int len = own.length() < Const.MAX_NAME_LEN ? Math.min(max, ownLen + TANK_SUFFIX.length()) : ownLen;
        if (other.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char want = i < ownLen ? own.charAt(i) : TANK_SUFFIX.charAt(i - ownLen);
            if (other.charAt(i) != want) {
                return false;
            }
        }
        return true;
    }

    /**
     * Shot colour (C server {@code frame.c} shot case, client {@code Paint_fastshots}/{@code Paint_teamshots}):
     * a harmless shot is BLUE (frame.c sets {@code color = BLUE} for every teamshot that cannot hurt the
     * viewer), a blinking nuclear one RED (XPilot path only), a fast shot keeps BLUE, everything else is the
     * owner's WHITE (C {@code Paint_fastshots} draws other colours white).
     * <p>
     * BEHAVIOR: harmless → blue whatever the colour; else RED → red, BLUE → blue, any other colour (OWN,
     * TEAM, ENEMY, NEUTRAL, WHITE) → white.
     */
    static int shotColor(int objColor, boolean harmless) {
        if (harmless) {
            return Palette.BLUE;
        }
        return switch (objColor) {
            case Records.ObjColor.RED -> Palette.RED;
            case Records.ObjColor.BLUE -> Palette.BLUE;
            default -> Palette.WHITE;
        };
    }

    /**
     * C frame.c:830: a dangerous nuclear shot is sent as a RED teamshot when {@code frame_loops & 2}; on the native
     * path the server marks it {@link Records.ShotKind#NUCLEAR} and the client blinks it from the server tick.
     * <p>
     * BEHAVIOR: RED for a NUCLEAR shot that is not harmless at ticks with bit 1 set (2, 3, 6, 7, ...), else the
     * shot's own colour.
     */
    static int nuclearBlink(int kind, int objColor, boolean harmless, double tick) {
        if (kind == Records.ShotKind.NUCLEAR && !harmless && (((long) Math.floor(tick)) & 2) != 0) {
            return Records.ObjColor.RED;
        }
        return objColor;
    }

    /**
     * C {@code teamshot} ({@code Gui_paint_teamshot} → {@code Gui_paint_nastyshot}): drawn as an X or +
     * instead of a square.
     * <p>
     * BEHAVIOR: true iff the shot is harmless or RED (the blinking nuclear teamshot); a team mate's or the
     * own shot that can hurt is a plain square like any other.
     */
    static boolean teamShot(int objColor, boolean harmless) {
        return harmless || objColor == Records.ObjColor.RED;
    }

    private static void particles(SceneData out, ClientWorld w, PaintStyle style) {
        ParticlePool pp = w.particles();
        float ff = w.frameFraction();
        double rt = w.renderTick();
        double own = w.hasSelf() ? w.selfDrawnTick() : rt;
        for (int i = 0; i < pp.count(); i++) {
            if (pp.visible(i)) {
                double tick = pp.own(i) ? own : rt;
                out.addParticle(pp.x(i), pp.y(i), particleColor(pp.life(i), pp.birth(i), tick, ff, pp.color(i), style));
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
        return particleColor(life, birth, tick, frameFraction, objColor, DEFAULT_STYLE);
    }

    /**
     * {@link #particleColor(float, double, double, float, int)} with the live {@code sparkColors}: as many
     * temperature levels as spark colours ({@link Particles#colorIndex(int, boolean, PaintStyle)}).
     */
    static int particleColor(float life, double birth, double tick, float frameFraction, int objColor,
                             PaintStyle style) {
        int left = (int) (life - (tick - birth) * frameFraction);
        return Particles.colorIndex(left, objColor == Records.ObjColor.BLUE, style);
    }

    private static final PaintStyle DEFAULT_STYLE = new PaintStyle();

    /**
     * Palette colour of a position-only point particle.
     * <p>
     * BEHAVIOR: codes from {@link ClientWorld#POINT_LEVEL_BASE} are C temperature levels mapped through
     * {@code sparkColors} (level clamped to the colours); otherwise the object colour: RED red, BLUE blue,
     * anything else white.
     */
    static int pointColor(int code) {
        return pointColor(code, DEFAULT_STYLE);
    }

    /**
     * {@link #pointColor(int)} with the live {@code sparkColors}.
     * <p>
     * BEHAVIOR: a temperature level is clamped to the spark colours there are now (the server was told the count
     * at login; C clamps {@code debris_colors} to {@code num_spark_colors}) and mapped by
     * {@link PaintStyle#levelColor}.
     */
    static int pointColor(int code, PaintStyle style) {
        if (code >= ClientWorld.POINT_LEVEL_BASE) {
            return style.levelColor(Math.min(code - ClientWorld.POINT_LEVEL_BASE, style.sparkCount() - 1));
        }
        return switch (code) {
            case Records.ObjColor.RED -> Palette.RED;
            case Records.ObjColor.BLUE -> Palette.BLUE;
            default -> Palette.WHITE;
        };
    }

    /**
     * F11: the size byte of a blip the server has already blinked (C path: a blip in its dark phase is simply
     * not sent).
     * <p>
     * BEHAVIOR: SELF records {@link #HIDDEN}; missiles and mines 0; balls 2; asteroids as in
     * {@link #radarSize}; players 3, 0x80 when FRIENDLY; never hidden otherwise.
     */
    static int radarSizeUnblinked(int flags) {
        if ((flags & Records.Radar.FLAG_SELF) != 0) {
            return HIDDEN;
        }
        if ((flags & (Records.Radar.FLAG_MINE | Records.Radar.FLAG_MISSILE)) != 0) {
            return 0;
        }
        if ((flags & Records.Radar.FLAG_BALL) != 0) {
            return RADAR_BALL;
        }
        if ((flags & Records.Radar.FLAG_ASTEROID) != 0) {
            return RadarPainter.asteroidSizeByte(RADAR_ASTEROID_SIZE);
        }
        return (flags & Records.Radar.FLAG_FRIENDLY) != 0 ? RADAR_PLAYER | 0x80 : RADAR_PLAYER;
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
