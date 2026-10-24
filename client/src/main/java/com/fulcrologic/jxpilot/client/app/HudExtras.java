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
    /** Pixels to the target as SELF sends it (C {@code lock_dist}); 0 when the lock is not shown (no distance, no disc). */
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

    /** Per {@link Item} ordinal: the count shown, or -1 ({@link #items}). */
    public final int[] itemCounts = new int[Item.COUNT];
    /** C option {@code showItems} (default yes, KEY_TOGGLE_OWNED_ITEMS toggles it): list owned items permanently. */
    public boolean showItems = true;
    /** C option {@code showItemsTime} (default 2.0, limited to 0..10): seconds a changed count shows without showItems. */
    public double showItemsTime = 2.0;
    /** C {@code lastNumItems}: the count each item had on the previous HUD frame. */
    private final int[] lastNumItems = new int[Item.COUNT];
    /** C {@code numItemsTime}: original frames each changed count still shows (without showItems). */
    private final double[] numItemsTime = new double[Item.COUNT];
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
     * direction exactly as the server sends them in SELF (C frame.c lock_dist/lock_dir, painthud.c:335-376: a 0
     * distance means the lock is not shown, so no distance and no disc, even when the target ship is drawn).
     * BEHAVIOR: warningPhase is {@code loops} even; items: {@link #items}; autopilot from the
     * own ship's AUTOPILOT flag; time left from the world; mods: the SELF modifier bits as C's string.
     * BEHAVIOR: score objects: see {@link #scoreObjects}.
     *
     * @param centreX world x at the view centre (the camera)
     */
    public void fill(ClientGame g, Hud hud, int viewW, int viewH, double zoom, double centreX, double centreY,
                     long loops) {
        fill(g, hud, viewW, viewH, zoom, centreX, centreY, loops, 1);
    }

    /**
     * {@link #fill(ClientGame, Hud, int, int, double, double, double, long)} for a painted frame
     * {@code frames} original frames after the previous one (C paints once per frame: 1), which age the
     * item timers ({@link #items}).
     */
    public void fill(ClientGame g, Hud hud, int viewW, int viewH, double zoom, double centreX, double centreY,
                     long loops, double frames) {
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
        for (int i = 0; i < Item.COUNT; i++) {
            itemCounts[i] = items(i, w.selfItem(i), frames, g.gameFps());
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
    }

    /**
     * C {@code Paint_HUD}'s item count choice (painthud.c:527-546) for item {@code i} owning {@code num}, on a
     * HUD frame {@code frames} original frames after the previous one.
     * <p>
     * BEHAVIOR: fuel is never listed (-1). With {@link #showItems}: {@code num} when above 0, else -1; the count is
     * remembered.
     * BEHAVIOR: without it, a count different from the remembered one restarts the item's timer at
     * {@code (int)(showItemsTime * fps)} frames and is remembered; the count (also 0) is shown while the timer is
     * above 0, and the timer then runs down by {@code frames}; at 0 it is -1.
     */
    int items(int i, int num, double frames, double fps) {
        if (i == Item.FUEL.ordinal()) {
            return -1;
        }
        if (showItems) {
            lastNumItems[i] = num;
            return num > 0 ? num : -1;
        }
        if (num != lastNumItems[i]) {
            numItemsTime[i] = (int) (showItemsTime * (float) fps);
            lastNumItems[i] = num;
        }
        if (numItemsTime[i] <= 0) {
            numItemsTime[i] = 0;
            return -1;
        }
        numItemsTime[i] -= frames;
        return num;
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
