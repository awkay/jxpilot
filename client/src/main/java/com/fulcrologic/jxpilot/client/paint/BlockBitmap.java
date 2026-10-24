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
package com.fulcrologic.jxpilot.client.paint;

/**
 * The C client's block bitmaps ({@code texturedObjects}; {@code blockbitmaps.c} {@code xp_pixmaps},
 * {@code blockbitmaps.h} {@code BM_*}, in C's order): the PPM file under {@code /textures/}, C's "rotations"
 * (positive: one picture rotated through a full turn in that many steps; negative: that many pictures side by
 * side in the file) and the size of one picture.
 */
public enum BlockBitmap {
    HOLDER_FRIEND("holder1.ppm", 1, 35, 35),
    HOLDER_ENEMY("holder2.ppm", 1, 35, 35),
    BALL("ball.ppm", 1, 21, 21),
    SHIP_SELF("ship_red.ppm", 128, 32, 32),
    SHIP_FRIEND("ship_blue.ppm", 128, 32, 32),
    SHIP_ENEMY("ship_red2.ppm", 128, 32, 32),
    BULLET("bullet.ppm", -8, 8, 8),
    BULLET_OWN("bullet_blue.ppm", -8, 8, 8),
    BASE_DOWN("base_down.ppm", 1, 35, 35),
    BASE_LEFT("base_left.ppm", 1, 35, 35),
    BASE_UP("base_up.ppm", 1, 35, 35),
    BASE_RIGHT("base_right.ppm", 1, 35, 35),
    FUELCELL("fuelcell.ppm", 1, 35, 35),
    FUEL("fuel2.ppm", -16, 29, 29),
    ALL_ITEMS("allitems.ppm", -30, 16, 16),
    CANNON_DOWN("cannon_down.ppm", 1, 35, 35),
    CANNON_LEFT("cannon_left.ppm", 1, 35, 35),
    CANNON_UP("cannon_up.ppm", 1, 35, 35),
    CANNON_RIGHT("cannon_right.ppm", 1, 35, 35),
    SPARKS("sparks.ppm", -8, 2, 2),
    PAUSED("paused.ppm", -2, 35, 35),
    WALL_TOP("wall_top.ppm", 1, 35, 35),
    WALL_LEFT("wall_left.ppm", 1, 35, 35),
    WALL_BOTTOM("wall_bottom.ppm", 1, 35, 35),
    WALL_RIGHT("wall_right.ppm", 1, 35, 35),
    WALL_LU("wall_ul.ppm", 1, 35, 35),
    WALL_RU("wall_ur.ppm", 1, 35, 35),
    WALL_LD("wall_dl.ppm", 1, 35, 35),
    WALL_RD("wall_dr.ppm", 1, 35, 35),
    WALL_FILLED("wall_fi.ppm", 1, 35, 35),
    WALL_UR("wall_url.ppm", 1, 35, 35),
    WALL_UL("wall_ull.ppm", 1, 35, 35),
    SCORE_BG("clouds.ppm", 1, 256, 442),
    LOGO("logo.ppm", 1, 256, 223),
    REFUEL("refuel.ppm", -4, 8, 8),
    WORMHOLE("wormhole.ppm", 8, 35, 35),
    MINE_TEAM("mine_team.ppm", 1, 21, 15),
    MINE_OTHER("mine_other.ppm", 1, 21, 15),
    CONCENTRATOR("concentrator.ppm", 32, 35, 35),
    PLUSGRAVITY("plus.ppm", 1, 35, 35),
    MINUSGRAVITY("minus.ppm", 1, 35, 35),
    CHECKPOINT("checkpoint.ppm", -2, 35, 35),
    METER("meter.ppm", -2, 200, 11),
    ASTEROIDCONC("asteroidconcentrator.ppm", 32, 35, 35);

    /** File name under {@code /textures/}. */
    public final String file;
    /** C's rotations value (see the class comment). */
    public final int rotations;
    public final int width;
    public final int height;

    BlockBitmap(String file, int rotations, int width, int height) {
        this.file = file;
        this.rotations = rotations;
        this.width = width;
        this.height = height;
    }

    /** BEHAVIOR: true for a picture C rotates (rotations above 1); JXPilot rotates its one picture when drawing. */
    public boolean rotates() {
        return rotations > 1;
    }

    /** BEHAVIOR: pictures in the file: 1 for a single or rotated picture, otherwise -rotations. */
    public int pictures() {
        return rotations < 0 ? -rotations : 1;
    }

    /** BEHAVIOR: C's image count (the rotation steps, or the pictures). */
    public int images() {
        return rotations < 0 ? -rotations : rotations;
    }

    /** BEHAVIOR: the score window's background and logo are drawn from their own texture, not the UI atlas. */
    public boolean inAtlas() {
        return this != SCORE_BG && this != LOGO;
    }
}
