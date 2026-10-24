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

import com.fulcrologic.jxpilot.client.game.CharBuf;
import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.Modifiers;
import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.client.game.Players;
import com.fulcrologic.jxpilot.client.game.ScoreObjects;
import com.fulcrologic.jxpilot.client.game.hud.Hud;
import com.fulcrologic.jxpilot.client.game.hud.LoseItemSelection;
import com.fulcrologic.jxpilot.client.paint.HudPainter;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;

/**
 * The HUD parts {@code HudPainter} draws directly (the rest is client-net's {@code Hud} draw list), as
 * plain values in C's unscaled window coordinates: the lock ({@code Paint_lock}), the item list, the
 * autopilot light, the weapon modifiers and the on-map score objects ({@code Paint_score_objects}). Pure;
 * allocation free. Not filled: the HUD radar (C default off).
 */
public final class HudExtras {
    /** True when the own ship's HUD is shown (it exists and the HUD is on). */
    public boolean active;
    /** C {@code hud_pos_x/y}, unscaled. */
    public int hx;
    public int hy;

    /** A locked player is known. */
    public boolean lock;
    public CharSequence lockName;
    public boolean limitedLives;
    public int lockLives;
    /** Pixels to the target; 0 when it is not in view (C then shows no disc). */
    public int lockDist;
    /** RES (0..127) direction to the target. */
    public int lockDir;
    public boolean lockFriend;
    /** C's alternating {@code warningCount} phase (from loops). */
    public boolean warningPhase;
    public int mapDiag;
    public final float[] lockShipX = new float[ShipShape.MAX_SHIP_PTS];
    public final float[] lockShipY = new float[ShipShape.MAX_SHIP_PTS];
    public int lockShipPoints;

    /** Per {@link Item} ordinal: the count shown, or -1 (C {@code showItems}: counts above 0). */
    public final int[] itemCounts = new int[Item.COUNT];
    public boolean autopilot;
    /** Seconds of game time left, -1 when the server never said ({@link ClientWorld#timeLeft()}). */
    public int timeLeft = -1;
    /** C {@code lose_item}: the {@link Item} (ordinal) KEY_LOSE_ITEM drops ({@link ClientWorld#selfLoseItem}). */
    public int loseItem;
    /** C {@code lose_item_active != 0} on this painted frame: outline {@link #loseItem} ({@link #loseItem(LoseItemSelection, double)}). */
    public boolean loseActive;
    /** C {@code mods}: the own weapon modifiers as the C HUD string ({@link Modifiers#format}). */
    public final CharBuf mods = new CharBuf(32);

    private final int[] scoreSlot = new int[ScoreObjects.MAX_SCORE_OBJECTS];
    private final int[] scoreX = new int[ScoreObjects.MAX_SCORE_OBJECTS];
    private final int[] scoreY = new int[ScoreObjects.MAX_SCORE_OBJECTS];
    private int scoreCount;

    /**
     * Compute this frame's values.
     * <p>
     * BEHAVIOR: inactive (nothing else filled except the score objects) without an own ship or with the
     * HUD off; hx/hy are {@link Hud#hudX}/{@link Hud#hudY} of the unscaled view
     * ({@link Hud#extent}, C {@code ext_view_width}; the Hud's zoom is set to {@code zoom}).
     * BEHAVIOR: lock: a known locked player (not NO_ID); its name, lives, team friendliness
     * ({@link HudPainter#isFriend}, alliances included), its shape's points at heading 0; distance and RES
     * direction as the server sends them in SELF (C frame.c lock_dist/lock_dir) when non-zero, else from the
     * own ship when its ship is in the interpolated list (wrap-aware), else 0.
     * BEHAVIOR: warningPhase is {@code loops} even; items: {@link #itemCount} per item, one C paint per new
     * {@code loops}; autopilot from the
     * own ship's AUTOPILOT flag; time left from the world; mods: the SELF modifier bits as C's string.
     * BEHAVIOR: score objects: see {@link #scoreObjects}.
     *
     * @param centreX world x at the view centre (the camera)
     */
    public void fill(ClientGame g, Hud hud, int viewW, int viewH, double zoom, double centreX, double centreY,
                     long loops) {
        ClientWorld w = g.world();
        Players p = g.players();
        hud.zoom = zoom;
        int uw = hud.extent(viewW);
        int uh = hud.extent(viewH);
        scoreObjects(g, uw, uh, centreX, centreY);
        active = w.hasSelf() && hud.showHud;
        lock = false;
        autopilot = false;
        loseActive = false;
        if (!active) {
            return;
        }
        loseItem = w.selfLoseItem();
        hx = hud.hudX(uw, w.selfVx());
        hy = hud.hudY(uh, w.selfVy());
        autopilot = (w.selfFlags() & Records.ShipFlags.AUTOPILOT) != 0;
        timeLeft = w.timeLeft();
        Modifiers.format(w.selfMods(), mods.clear());
        int paints = itemLoops < 0 ? 1 : (int) Math.max(0, Math.min(Integer.MAX_VALUE, loops - itemLoops));
        itemLoops = loops;
        int showFrames = (int) (showItemsTime * g.gameFps());
        for (int i = 0; i < Item.COUNT; i++) {
            itemCounts[i] = itemCount(i, w.selfItem(i), paints, showFrames);
        }
        warningPhase = (loops & 1) == 0;
        int id = w.selfLockId();
        int slot = id == Protocol.NO_ID ? -1 : p.slotOf(id);
        if (slot < 0) {
            return;
        }
        lock = true;
        lockName = p.name(slot);
        limitedLives = g.options() != null && g.options().getBool(Opt.LIMITED_LIVES);
        lockLives = p.lives(slot);
        int self = p.slotOf(p.selfId());
        lockFriend = HudPainter.isFriend(self >= 0, g.teamPlay(), self >= 0 ? p.team(self) : -1, p.team(slot),
            self >= 0 ? p.alliance(self) : ' ', p.alliance(slot));
        ShipShape s = p.shape(slot);
        lockShipPoints = s.numPoints();
        for (int i = 0; i < lockShipPoints; i++) {
            lockShipX[i] = s.ptX[i];
            lockShipY[i] = s.ptY[i];
        }
        mapDiag = g.map() == null ? 0 : HudPainter.mapDiag(g.map().width(), g.map().height());
        lockDist = w.selfLockDist();
        lockDir = w.selfLockDir();
        if (lockDist != 0) {
            return;
        }
        for (int i = 0; i < w.shipCount(); i++) {
            if (w.shipId(i) == id) {
                double dx = XMath.wrapDelta(w.shipX(i) - w.selfX(), w.worldWidth(), w.wraps());
                double dy = XMath.wrapDelta(w.shipY(i) - w.selfY(), w.worldHeight(), w.wraps());
                lockDist = (int) Math.sqrt(dx * dx + dy * dy);
                lockDir = direction(dx, dy);
                break;
            }
        }
    }

    /**
     * C {@code painthud.c}:558-566, after {@link #fill}: whether this painted frame outlines the lose item.
     * <p>
     * BEHAVIOR: loseActive iff the HUD is active, the lose item is listed (a count is shown; fuel never is) and
     * {@link LoseItemSelection#paint} draws; the selection is aged by {@code dt} only on such frames (C counts
     * the fade only while it paints the lose item).
     *
     * @param dt seconds since the last painted frame
     */
    public void loseItem(LoseItemSelection sel, double dt) {
        loseActive = active && loseItem >= 0 && loseItem < Item.COUNT && loseItem != Item.FUEL.ordinal()
            && itemCounts[loseItem] >= 0 && sel.paint(dt);
    }

    /** C {@code showItems} (default on): owned items stay on the HUD; off, they show for a while after a change. */
    public boolean showItems = true;
    /** C {@code showItemsTime} (default 2.0, 0..10): seconds an item shows after a change while showItems is off. */
    public double showItemsTime = 2.0;
    private final int[] lastItem = new int[Item.COUNT];
    private final int[] itemTime = new int[Item.COUNT];
    private long itemLoops = -1;

    /**
     * C {@code Paint_HUD}'s item counts for one item over {@code paints} paints (frames since the last call).
     * <p>
     * BEHAVIOR: with showItems: counts above 0, else -1 (fuel: likewise, C skips it).
     * BEHAVIOR: without: a count different from the last one restarts a timer of {@code showFrames}
     * ({@code showItemsTime * FPS}) paints; the count (0 included) shows while the timer, counted down once per
     * paint, was above 0 before that paint's decrement; then -1. With no new paint the timer only decides.
     */
    int itemCount(int i, int num, int paints, int showFrames) {
        if (showItems || i == Item.FUEL.ordinal()) {
            lastItem[i] = num;
            return num > 0 ? num : -1;
        }
        if (num != lastItem[i]) {
            itemTime[i] = showFrames;
            lastItem[i] = num;
        }
        if (paints == 0) {
            return itemTime[i] > 0 ? num : -1;
        }
        if (paints > 1) {
            itemTime[i] = Math.max(0, itemTime[i] - (paints - 1));
        }
        if (itemTime[i]-- <= 0) {
            itemTime[i] = 0;
            return -1;
        }
        return num;
    }

    /** BEHAVIOR: RES (128 per turn) direction of (dx, dy), rounded, in 0..127. */
    static int direction(double dx, double dy) {
        return (int) Math.round(Math.atan2(dy, dx) * 128 / (2 * Math.PI)) & 127;
    }

    /**
     * C {@code Paint_score_objects}: own score changes shown at the block where they happened.
     * <p>
     * BEHAVIOR: every live object with a position and {@link HudPainter#scoreObjectVisible} of its
     * (whole) count, at C's {@code X()/Y()} of the block centre: {@code uw/2 + dx}, {@code uh/2 - dy} with
     * wrap-aware deltas from the view centre, kept only inside {@code [0, uw] x [0, uh]}.
     */
    void scoreObjects(ClientGame g, int uw, int uh, double cx, double cy) {
        scoreCount = 0;
        ScoreObjects so = g.scoreObjects();
        ClientWorld w = g.world();
        for (int s = 0; s < ScoreObjects.MAX_SCORE_OBJECTS; s++) {
            if (!so.active(s) || !so.hasPosition(s) || !HudPainter.scoreObjectVisible((int) so.count(s))) {
                continue;
            }
            double x = so.blockX(s) * Const.BLOCK_SZ + Const.BLOCK_SZ / 2;
            double y = so.blockY(s) * Const.BLOCK_SZ + Const.BLOCK_SZ / 2;
            int sx = (int) (uw / 2 + XMath.wrapDelta(x - cx, w.worldWidth(), w.wraps()));
            int sy = (int) (uh / 2 - XMath.wrapDelta(y - cy, w.worldHeight(), w.wraps()));
            if (sx >= 0 && sx <= uw && sy >= 0 && sy <= uh) {
                scoreSlot[scoreCount] = s;
                scoreX[scoreCount] = sx;
                scoreY[scoreCount] = sy;
                scoreCount++;
            }
        }
    }

    public int scoreObjectCount() { return scoreCount; }

    /** ScoreObjects slot of visible score object i. */
    public int scoreObjectSlot(int i) { return scoreSlot[i]; }

    public int scoreObjectX(int i) { return scoreX[i]; }

    public int scoreObjectY(int i) { return scoreY[i]; }
}
