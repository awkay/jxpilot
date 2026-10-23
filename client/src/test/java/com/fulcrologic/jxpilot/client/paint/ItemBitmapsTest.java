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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemBitmapsTest {
    @Test
    void oneSixteenBySixteenSymbolPerItemInItemOrder() {
        assertAll(
            () -> assertEquals(Item.COUNT, ItemBitmaps.count(), "21 symbols"),
            () -> assertEquals(16, ItemBitmaps.SIZE, "16 pixels square"),
            () -> {
                for (int[] rows : ItemBitmaps.ROWS) {
                    assertEquals(16, rows.length, "16 rows each");
                }
            });
    }

    @Test
    void decodesXbmRowsLeastSignificantBitLeftmost() {
        // itemTank.xbm row 0 bytes 0x20, 0x08: pixels 5 and 11 set
        int tank = Item.TANK.ordinal();
        // itemEnergyPack.xbm last row bytes 0xfc, 0x3f: pixels 2..13
        int fuel = Item.FUEL.ordinal();
        assertAll(
            () -> assertTrue(ItemBitmaps.pixel(tank, 5, 0), "tank row 0: bit 5 of 0x20"),
            () -> assertTrue(ItemBitmaps.pixel(tank, 11, 0), "tank row 0: bit 3 of the second byte 0x08 is pixel 11"),
            () -> assertFalse(ItemBitmaps.pixel(tank, 4, 0), "pixel 4 clear"),
            () -> assertFalse(ItemBitmaps.pixel(tank, 6, 0), "pixel 6 clear"),
            () -> assertTrue(ItemBitmaps.pixel(fuel, 2, 15), "fuel bottom row from pixel 2"),
            () -> assertTrue(ItemBitmaps.pixel(fuel, 13, 15), "to pixel 13"),
            () -> assertFalse(ItemBitmaps.pixel(fuel, 1, 15), "not pixel 1"),
            () -> assertFalse(ItemBitmaps.pixel(fuel, 14, 15), "nor 14"),
            () -> assertEquals(0x3ffc, ItemBitmaps.ROWS[fuel][15], "the row mask itself"));
    }

    @Test
    void outsideTheSymbolNothingIsSet() {
        int armor = Item.ARMOR.ordinal();
        assertAll(
            () -> assertFalse(ItemBitmaps.pixel(armor, -1, 0), "left of the symbol"),
            () -> assertFalse(ItemBitmaps.pixel(armor, 16, 0), "right of it"),
            () -> assertFalse(ItemBitmaps.pixel(armor, 0, -1), "above"),
            () -> assertFalse(ItemBitmaps.pixel(armor, 0, 16), "below"),
            () -> assertFalse(ItemBitmaps.pixel(Item.TANK.ordinal(), 16, 0), "bit 16 of a row is not pixel 16"));
    }

    @Test
    void atlasSlotsAreSixteenPerRowRightOfTheRadar() {
        assertAll(
            () -> assertEquals(768, ItemBitmaps.atlasX(0), "item 0 at the block's left edge"),
            () -> assertEquals(0, ItemBitmaps.atlasY(0), "and top"),
            () -> assertEquals(768 + 15 * 16, ItemBitmaps.atlasX(15), "item 15 last in the first row"),
            () -> assertEquals(768, ItemBitmaps.atlasX(16), "item 16 starts the second row"),
            () -> assertEquals(16, ItemBitmaps.atlasY(16), "one symbol down"),
            () -> assertEquals(768 + 4 * 16, ItemBitmaps.atlasX(20), "item 20 is fifth in the second row"));
    }

    @Test
    void bakeWritesOpaqueWhereSetAndTransparentElsewhere() {
        int w = 1024;
        int[] rgba = new int[w * 32];
        java.util.Arrays.fill(rgba, 0x12345678);
        ItemBitmaps.bake(rgba, w);
        int tank = Item.TANK.ordinal();
        int x0 = ItemBitmaps.atlasX(tank);
        assertAll(
            () -> assertEquals(-1, rgba[x0 + 5], "tank pixel (5,0) opaque white"),
            () -> assertEquals(0, rgba[x0 + 4], "tank pixel (4,0) transparent"),
            () -> assertEquals(-1, rgba[15 * w + ItemBitmaps.atlasX(Item.FUEL.ordinal()) + 13], "fuel pixel (13,15)"),
            () -> {
                int defl = Item.DEFLECTOR.ordinal(); // item 16: second atlas row
                int row = 0;
                while (ItemBitmaps.ROWS[defl][row] == 0) {
                    row++;
                }
                int col = Integer.numberOfTrailingZeros(ItemBitmaps.ROWS[defl][row]);
                assertEquals(-1, rgba[(16 + row) * w + 768 + col], "a set deflector pixel lands 16 rows down");
            },
            () -> assertEquals(0x12345678, rgba[767], "texels left of the block untouched"),
            () -> assertEquals(0x12345678, rgba[768 + 5 * 16 + 16 * w], "nor slots past item 20"));
    }

    @Test
    void columnZeroIsInsideTheSymbol() {
        assertAll(
            () -> assertTrue(ItemBitmaps.pixel(Item.TANK.ordinal(), 0, 14), "tank row 14 sets its leftmost pixel"),
            () -> assertFalse(ItemBitmaps.pixel(Item.TANK.ordinal(), 0, 0), "row 0 does not"));
    }
}
