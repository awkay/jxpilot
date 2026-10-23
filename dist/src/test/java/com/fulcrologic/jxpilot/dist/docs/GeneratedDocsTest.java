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
package com.fulcrologic.jxpilot.dist.docs;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionType;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code docs/guide/generated/} in step with the code. Run with
 * {@code -Djxpilot.regenerateDocs=true} (or {@code make -C docs/guide generate}) to rewrite the files.
 */
class GeneratedDocsTest {
    private static final Path GENERATED =
        Path.of(System.getProperty("jxpilot.repoRoot", ".."), "docs", "guide", "generated");
    private static final String HOW_TO_FIX = " is stale: the code changed. Regenerate it with "
        + "`make -C docs/guide generate` (or: mvn -pl dist -am test -Djxpilot.regenerateDocs=true) and commit it.";

    private static void check(String file, String expected) throws IOException {
        Path p = GENERATED.resolve(file);
        if (Boolean.getBoolean("jxpilot.regenerateDocs")) {
            Files.createDirectories(GENERATED);
            Files.writeString(p, expected, StandardCharsets.UTF_8);
            return;
        }
        String actual = Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        assertEquals(expected, actual, "docs/guide/generated/" + file + HOW_TO_FIX);
    }

    @Test
    @DisplayName("the committed option reference matches the Opt registry")
    void optionsUpToDate() throws IOException {
        check("options.adoc", GuideTables.optionsAdoc());
    }

    @Test
    @DisplayName("the committed item table matches the item bitmaps")
    void itemsUpToDate() throws IOException {
        check("items.adoc", ItemTable.itemsAdoc());
    }

    @Test
    @DisplayName("the committed item pictures have exactly the pixels the item bitmaps give")
    void itemImagesUpToDate() throws IOException {
        Path dir = GENERATED.resolveSibling("images");
        for (Map.Entry<String, BufferedImage> e : ItemTable.images().entrySet()) {
            Path p = dir.resolve(e.getKey());
            BufferedImage want = e.getValue();
            if (Boolean.getBoolean("jxpilot.regenerateDocs")) {
                Files.createDirectories(p.getParent());
                ImageIO.write(want, "png", p.toFile());
                continue;
            }
            String what = "docs/guide/images/" + e.getKey();
            assertTrue(Files.exists(p), what + HOW_TO_FIX);
            BufferedImage got = ImageIO.read(p.toFile());
            assertEquals(want.getWidth() + "x" + want.getHeight(), got.getWidth() + "x" + got.getHeight(),
                what + HOW_TO_FIX);
            for (int y = 0; y < want.getHeight(); y++) {
                for (int x = 0; x < want.getWidth(); x++) {
                    if ((want.getRGB(x, y) | 0xFF000000) != (got.getRGB(x, y) | 0xFF000000)) {
                        assertEquals(want.getRGB(x, y), got.getRGB(x, y), what + " pixel " + x + "," + y + HOW_TO_FIX);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the committed key table matches ControlKey")
    void keysUpToDate() throws IOException {
        check("keys.adoc", GuideTables.keysAdoc());
    }

    @Nested
    @DisplayName("options.adoc content")
    class OptionsContent {
        @Test
        void everyOptionOnceWithItsFacts() {
            String doc = GuideTables.optionsAdoc();
            int total = GuideTables.grouped().values().stream().mapToInt(List::size).sum();
            assertAll(
                () -> assertEquals(Opt.values().length, total, "places every option in exactly one category"),
                () -> {
                    for (Opt o : Opt.values()) {
                        String a = "[[opt-" + o.optionName() + "]]";
                        assertEquals(doc.indexOf(a), doc.lastIndexOf(a), "anchors " + o.optionName() + " once");
                        assertTrue(doc.contains(a), "anchors " + o.optionName());
                    }
                },
                () -> assertTrue(doc.contains("|[[opt-contactPort]]`+contactPort+`\n|`+port+`\n|integer\n|`+15345+`\n"
                        + "|command line, config file\n|The server contact port number.\n"),
                    "a row lists name, short name, type, default, sources and help"),
                () -> assertTrue(doc.contains("|[[opt-gravity]]`+gravity+`\n|\n|real\n|`+-0.14+`\n"
                        + "|command line, map, config file\n"),
                    "a short name equal to the name is left blank; ANY options list all three sources"),
                () -> assertTrue(doc.contains("|[[opt-robotFile]]`+robotFile+`\n|\n|text\n|_(none)_\n"),
                    "a null default reads (none)"),
                () -> assertTrue(doc.contains("|[[opt-denyHosts]]`+denyHosts+`\n|\n|text\n|_(empty)_\n"),
                    "an empty default reads (empty)"),
                () -> assertTrue(doc.contains("<<opt-allowAlliances,allowAlliances>> ·\n"),
                    "the alphabetical index links each option"));
        }

        @Test
        void categoriesFollowTheFirstMatchingRule() {
            Map<GuideTables.Category, List<Opt>> g = GuideTables.grouped();
            assertAll(
                () -> assertTrue(in(g, "opts-network").contains(Opt.CONTACT_PORT), "contactPort is a network option"),
                () -> assertTrue(in(g, "opts-scoring").contains(Opt.CANNON_POINTS),
                    "cannonPoints is scoring, not cannons (scoring comes first)"),
                () -> assertTrue(in(g, "opts-items").contains(Opt.MAX_TANKS), "maxTanks pairs with initialTanks: items"),
                () -> assertTrue(in(g, "opts-walls").contains(Opt.ASTEROIDS_WALL_BOUNCE),
                    "a ...WallBounce option is a wall option"),
                () -> assertTrue(in(g, "opts-radar").contains(Opt.MINES_ON_RADAR), "minesOnRadar is radar, not weapons"),
                () -> assertTrue(in(g, "opts-asteroids").contains(Opt.MAX_ASTEROID_DENSITY),
                    "maxAsteroidDensity is an asteroid option"));
        }

        private List<Opt> in(Map<GuideTables.Category, List<Opt>> g, String id) {
            return g.entrySet().stream().filter(e -> e.getKey().id().equals(id)).findFirst().orElseThrow().getValue();
        }
    }

    @Nested
    @DisplayName("item pictures and table")
    class ItemPictures {
        private final int red = ItemTable.argb(com.fulcrologic.jxpilot.client.render.Palette.RED);
        private final int blue = ItemTable.argb(com.fulcrologic.jxpilot.client.render.Palette.BLUE);
        private final int black = ItemTable.argb(com.fulcrologic.jxpilot.client.render.Palette.BLACK);

        /** Colour of the 1x pixel (x, y): the centre of its SCALE x SCALE block. */
        private int at(BufferedImage img, int x, int y) {
            return img.getRGB(x * ItemTable.SCALE + 1, y * ItemTable.SCALE + 1) | 0xFF000000;
        }

        @Test
        void symbolAndInPlayPictures() {
            // itemMinePack.xbm row 7 is 0xffff (a full row), row 0 is empty.
            BufferedImage sym = ItemTable.symbol(com.fulcrologic.jxpilot.common.Item.MINE);
            BufferedImage play = ItemTable.inPlay(com.fulcrologic.jxpilot.common.Item.MINE);
            int s = com.fulcrologic.jxpilot.client.paint.PaintConfig.ITEM_TRIANGLE_SIZE;
            int c = ItemTable.MARGIN + s;
            assertAll(
                () -> assertEquals(48, sym.getWidth(), "the symbol is 16 pixels scaled 3 times"),
                () -> assertEquals(red, at(sym, 0, 7), "a set bitmap pixel is red"),
                () -> assertEquals(black, at(sym, 0, 0), "a clear bitmap pixel is black"),
                () -> assertEquals((2 * c + 1) * 3, play.getWidth(), "the in-play canvas is 2*(margin+S)+1 pixels, scaled"),
                () -> assertEquals(blue, at(play, c, c + s), "the triangle's apex points down at (c, c+S)"),
                () -> assertEquals(blue, at(play, c - s, c - s), "the top-left corner is drawn"),
                () -> assertEquals(blue, at(play, c, c - s), "the top edge is drawn"),
                () -> assertEquals(red, at(play, c - 8, c - s + 2 + 7), "the symbol's top-left is (c-8, c-S+2)"),
                () -> assertEquals(black, at(play, 0, 0), "the margin is black"));
        }

        @Test
        void namesKeysAndHash() {
            assertAll(
                () -> assertEquals("emergency-thrust", ItemTable.stem(com.fulcrologic.jxpilot.common.Item.EMERGENCY_THRUST),
                    "file stems are lower case with dashes"),
                () -> assertEquals("Emergency thrust",
                    ItemTable.displayName(com.fulcrologic.jxpilot.common.Item.EMERGENCY_THRUST), "names read as words"),
                () -> assertEquals("ECM", ItemTable.displayName(com.fulcrologic.jxpilot.common.Item.ECM),
                    "ECM stays an acronym"),
                () -> assertEquals("-", ItemTable.keys(com.fulcrologic.jxpilot.common.Item.ARMOR),
                    "a passive item has no keys"),
                () -> assertEquals("ECM: `+bracketleft+` (pass:c[[])",
                    ItemTable.keys(com.fulcrologic.jxpilot.common.Item.ECM), "keys list function and default keys"),
                () -> assertEquals(ItemTable.pixelHash(ItemTable.images()), ItemTable.pixelHash(ItemTable.images()),
                    "the pixel hash is deterministic"),
                () -> assertEquals(42, ItemTable.images().size(), "two pictures per item"),
                () -> assertEquals(com.fulcrologic.jxpilot.common.Item.COUNT, ItemTable.C_TEXT.size(),
                    "every item has its C description"),
                () -> assertTrue(ItemTable.itemsAdoc().contains("|image:items/fuel.png[Fuel in play]\n"
                    + "|image:items/fuel-symbol.png[Fuel symbol]\n|[[item-fuel]]*Fuel*\n|Extra energy/fuel\n|-\n"
                    + "|Not yet (M2)\n"), "a row has both pictures, name, C text, keys and status"));
        }
    }

    @Nested
    @DisplayName("formatting helpers")
    class Helpers {
        @Test
        void helpers() {
            assertAll(
                () -> assertEquals("`+a b+`", GuideTables.literal("a b"), "plain text becomes literal monospace"),
                () -> assertEquals("`pass:c[a+b\\]]`", GuideTables.literal("a+b]"),
                    "text with + uses an escaped pass macro"),
                () -> assertEquals("x \\| y \\{z}", GuideTables.cell("x | y {z}"),
                    "cells escape the column separator and attribute references"),
                () -> assertEquals("switch (no value)", GuideTables.typeName(OptionType.VOID), "VOID is a switch"),
                () -> assertEquals("seconds", GuideTables.typeName(OptionType.SEC), "SEC is seconds"),
                () -> assertEquals("command line", GuideTables.origins(Opt.EXPAND), "expand is command line only"),
                () -> assertEquals("command line, config file, password file", GuideTables.origins(Opt.PASSWORD),
                    "password may also come from the password file"),
                () -> assertEquals("-", GuideTables.origins(Opt.HELP), "help has no settable source"),
                () -> assertEquals("-", GuideTables.defaultText(Opt.HELP), "a switch shows no default"),
                () -> assertEquals("Lock next close", GuideTables.functionName(ControlKey.LOCK_NEXT_CLOSE),
                    "enum names read as sentences"),
                () -> assertEquals("`+Shift_R+` (pass:c[Right Shift]), `+Shift_L+` (pass:c[Left Shift])",
                    GuideTables.keysText(ControlKey.THRUST.defaultKeys), "keysyms get friendly labels"),
                () -> assertEquals("`+bracketright+` (pass:c[\\]])", GuideTables.keysText("bracketright"),
                    "a ] in a label is escaped"),
                () -> assertEquals("`+a+`", GuideTables.keysText("a"), "letters need no label"),
                () -> assertEquals("-", GuideTables.keysText(" "), "no keys reads -"));
        }
    }
}
