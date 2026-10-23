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

import com.fulcrologic.jxpilot.common.Item;

/**
 * The 16x16 item symbols of the XPilot 4.5.5 client ({@code src/client/items/item*.xbm}, in the
 * {@code itemBitmapData} order of {@code xinit.c}, which is {@link Item} order), as row masks.
 * <p>
 * Row {@code r} (0 = top) of item {@code i} is {@code ROWS[i][r]}; bit {@code c} (0 = leftmost
 * pixel, XBM order) is set where the symbol is drawn. C stipples them in the current colour: red on
 * the playfield, the HUD colour on the HUD.
 */
public final class ItemBitmaps {
    private ItemBitmaps() {}

    /** Symbol side in pixels (C {@code ITEM_SIZE}). */
    public static final int SIZE = 16;

    static final int[][] ROWS = {
        // FUEL (itemEnergyPack.xbm)
        {0x3f00, 0x2180, 0x60c0, 0x7ff0, 0x7ffc, 0x7ffe, 0x701e, 0x701e, 0x7f1e, 0x7f1e, 0x7c1e, 0x7f1e, 0x7f1e, 0x7f1e, 0x7ffe, 0x3ffc},
        // WIDEANGLE (itemWideangleShot.xbm)
        {0x0180, 0x318c, 0x300c, 0x0000, 0x0180, 0x0db0, 0x0c30, 0x0000, 0x0000, 0x0180, 0x03c0, 0x0240, 0x0660, 0x0420, 0x0c30, 0x0810},
        // REARSHOT (itemRearShot.xbm)
        {0x1818, 0x1818, 0x300c, 0x300c, 0x3ffc, 0x0180, 0x0000, 0x0000, 0x0000, 0x0180, 0x0180, 0x0000, 0x0000, 0x0000, 0x0180, 0x0180},
        // AFTERBURNER (itemAfterburner.xbm)
        {0x1818, 0x1818, 0x300c, 0x300c, 0x3ffc, 0x0140, 0x0280, 0x00a0, 0x0620, 0x0550, 0x0850, 0x1588, 0x1028, 0x2200, 0x14a4, 0x428a},
        // CLOAK (itemCloakingDevice.xbm)
        {0x0100, 0x0100, 0x0300, 0x0200, 0x0200, 0x0040, 0x0040, 0x0c60, 0x0800, 0x0800, 0x0010, 0x0010, 0x3018, 0x2000, 0x6000, 0x4f3c},
        // SENSOR (itemSensorPack.xbm)
        {0x0078, 0x01fe, 0x0186, 0x0303, 0x0303, 0x0303, 0x0303, 0x0186, 0x03fe, 0x0778, 0x0e00, 0x1c00, 0x3800, 0x7000, 0xe000, 0x4000},
        // TRANSPORTER (itemTransporter.xbm)
        {0xf000, 0xe010, 0xc010, 0x9010, 0x1838, 0x0828, 0x0228, 0x036c, 0x0144, 0x0044, 0x00e6, 0x00b2, 0x0092, 0x0183, 0x0101, 0x01ff},
        // TANK (itemTank.xbm)
        {0x0820, 0x0820, 0x1c70, 0x1450, 0x1450, 0x36d8, 0x2288, 0x2208, 0x630c, 0x4104, 0x4104, 0xc186, 0xff82, 0x0002, 0x0603, 0x07ff},
        // MINE (itemMinePack.xbm)
        {0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x07e0, 0x7ffe, 0xffff, 0xffff, 0x7ffe, 0x07e0, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000},
        // MISSILE (itemRocketPack.xbm)
        {0x0180, 0x0180, 0x03c0, 0x03c0, 0x07e0, 0x03c0, 0x03c0, 0x03c0, 0x03c0, 0x03c0, 0x03c0, 0x07e0, 0x0ff0, 0x3ffc, 0x03c0, 0x0180},
        // ECM (itemEcm.xbm)
        {0x07e0, 0x0810, 0x03c0, 0x0420, 0x4002, 0x9009, 0xa005, 0xa185, 0xa185, 0xa005, 0x9009, 0x4002, 0x0420, 0x03c0, 0x0810, 0x07e0},
        // LASER (itemLaser.xbm)
        {0x0180, 0x0180, 0x0180, 0x0180, 0x0180, 0x0180, 0x0180, 0x0180, 0x0180, 0x0180, 0x0000, 0x0180, 0x03c0, 0x0240, 0x0660, 0x0420},
        // EMERGENCY_THRUST (itemEmergencyThrust.xbm)
        {0xc000, 0xb000, 0x4c00, 0x4300, 0x2224, 0x2500, 0x1b50, 0x1584, 0x0349, 0x08c0, 0x0222, 0x0088, 0x0122, 0x0808, 0x0242, 0x0010},
        // TRACTOR_BEAM (itemTractorBeam.xbm)
        {0xfe00, 0x8200, 0x5400, 0x4400, 0x2900, 0x2a40, 0x1410, 0x1088, 0x0208, 0x003c, 0x011c, 0x003e, 0x00be, 0x007f, 0x007f, 0x0000},
        // AUTOPILOT (itemAutopilot.xbm)
        {0x07c0, 0x0820, 0x17d0, 0x2fe8, 0x5ff4, 0xbbba, 0xbbba, 0xb83a, 0xbbba, 0xbbba, 0xbbba, 0x5ff4, 0x2fe8, 0x17d0, 0x0820, 0x07c0},
        // EMERGENCY_SHIELD (itemEmergencyShield.xbm)
        {0x0000, 0x0aa0, 0x1010, 0x2108, 0x4104, 0x0280, 0x4284, 0x0440, 0x4444, 0x0820, 0x4fe4, 0x2008, 0x1010, 0x0aa0, 0x0000, 0x0000},
        // DEFLECTOR (itemDeflector.xbm)
        {0x0080, 0x0080, 0x1004, 0x0808, 0x0080, 0x0080, 0x01c0, 0x61c3, 0x03e0, 0x03e0, 0x07f0, 0x0000, 0x1004, 0x2082, 0x0080, 0x0000},
        // HYPERJUMP (itemHyperJump.xbm)
        {0x0100, 0x638c, 0x610c, 0x1110, 0x0920, 0x0540, 0x4004, 0xfc7e, 0x4004, 0x0540, 0x0920, 0x1110, 0x610c, 0x638c, 0x0100, 0x0000},
        // PHASING (itemPhasingDevice.xbm)
        {0x0100, 0x0000, 0x0200, 0x0100, 0x0480, 0x0240, 0x0100, 0x0480, 0x0240, 0x0920, 0x0490, 0x1240, 0x0920, 0x2490, 0x1248, 0x4924},
        // MIRROR (itemMirror.xbm)
        {0x0000, 0x0000, 0x07e0, 0x0810, 0x1108, 0x1108, 0x17c8, 0x1108, 0x1108, 0x1008, 0x1008, 0x1008, 0x0810, 0x07e0, 0x0000, 0x0000},
        // ARMOR (itemArmor.xbm)
        {0x0000, 0x0000, 0x0000, 0x0380, 0x0100, 0x06c0, 0x0280, 0x0c60, 0x0440, 0x1830, 0x0fe0, 0x0aa0, 0x0000, 0x0000, 0x0000, 0x0000}
    };

    /**
     * True where item {@code item} draws pixel ({@code x}, {@code y}), y from the top.
     * <p>
     * BEHAVIOR: coordinates outside 0..15 are never set.
     */
    public static boolean pixel(int item, int x, int y) {
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE) {
            return false;
        }
        return (ROWS[item][y] >> x & 1) != 0;
    }

    /** Left edge of the item block in the UI atlas (right of the radar picture). */
    public static final int ATLAS_X = 768;
    /** Symbols per atlas row. */
    static final int PER_ROW = 16;

    /** Atlas texel x of item {@code item}'s left edge. */
    public static int atlasX(int item) {
        return ATLAS_X + (item % PER_ROW) * SIZE;
    }

    /** Atlas texel y of item {@code item}'s top edge (rows top-down, as stored). */
    public static int atlasY(int item) {
        return (item / PER_ROW) * SIZE;
    }

    /**
     * Write every symbol into an RGBA atlas ({@code atlasW} texels wide, packed like
     * {@link com.fulcrologic.jxpilot.client.render.Palette}): opaque white where set, transparent
     * elsewhere, so a quad's vertex colour tints it.
     */
    public static void bake(int[] rgba, int atlasW) {
        for (int i = 0; i < ROWS.length; i++) {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    rgba[(atlasY(i) + y) * atlasW + atlasX(i) + x] = pixel(i, x, y) ? -1 : 0;
                }
            }
        }
    }

    /** Number of item symbols ({@link Item#COUNT}). */
    public static int count() {
        return ROWS.length;
    }
}
