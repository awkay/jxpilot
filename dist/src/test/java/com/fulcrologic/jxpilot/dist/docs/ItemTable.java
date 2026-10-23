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

import com.fulcrologic.jxpilot.client.paint.ItemBitmaps;
import com.fulcrologic.jxpilot.client.paint.PaintConfig;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.net.ControlKey;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The guide's item table ({@code docs/guide/generated/items.adoc}) and item pictures
 * ({@code docs/guide/images/items/}), drawn from the client's {@link ItemBitmaps} (the C client's XBMs)
 * the way the C client paints an item ({@code Paint_item} in {@code paintobjects.c}): a blue triangle of
 * half-size {@code ITEM_TRIANGLE_SIZE} pointing down, with the red 16x16 symbol stippled inside it.
 * Plain {@link BufferedImage}s, no GL; every pixel is scaled up {@link #SCALE} times, nearest-neighbour.
 */
public final class ItemTable {
    private ItemTable() {}

    /** Pixel magnification of the pictures. */
    static final int SCALE = 3;
    /** Blank pixels around the triangle in the in-play picture, at 1x. */
    static final int MARGIN = 2;

    /**
     * Items whose game rules the JXPilot (Java) server implements. Empty until milestone M2; add an item here
     * when the Java server lets players pick it up and use it, and regenerate the guide. (On an original C
     * XPilot server every item already works: the C server runs the rules and the client draws the items and
     * sends the keys.)
     */
    static final Set<Item> JAVA_SERVER_IMPLEMENTED = EnumSet.noneOf(Item.class);

    /** The C client's description of each item ({@code itemBitmapData} in {@code src/client/xinit.c}). */
    static final Map<Item, String> C_TEXT = new EnumMap<>(Map.ofEntries(
        Map.entry(Item.FUEL, "Extra energy/fuel"),
        Map.entry(Item.WIDEANGLE, "Extra front cannons"),
        Map.entry(Item.REARSHOT, "Extra rear cannon"),
        Map.entry(Item.AFTERBURNER, "Afterburner; makes your engines more powerful"),
        Map.entry(Item.CLOAK, "Cloaking device; makes you almost invisible, both on radar and on screen"),
        Map.entry(Item.SENSOR, "Sensor; enables you to see cloaked opponents more easily"),
        Map.entry(Item.TRANSPORTER, "Transporter; enables you to steal equipment from other players"),
        Map.entry(Item.TANK, "Tank; makes refueling quicker, increases maximum fuel capacity and can be "
            + "jettisoned to confuse enemies"),
        Map.entry(Item.MINE, "Mine; can be dropped as a bomb or as a stationary mine"),
        Map.entry(Item.MISSILE, "Rocket; can be utilized as smart missile, heatseeking missile, nuclear missile "
            + "or just a plain unguided missile (torpedo)"),
        Map.entry(Item.ECM, "ECM (Electronic Counter Measures); can be used to disturb electronic equipment, "
            + "for instance can it be used to confuse smart missiles and reprogram robots to seek certain players"),
        Map.entry(Item.LASER, "Laser; limited range laser beam, costs a lot of fuel, having more laser items "
            + "increases the range of the laser, they can be irrepairably damaged by ECMs"),
        Map.entry(Item.EMERGENCY_THRUST, "Emergency Thrust; gives emergency thrust capabilities for a limited "
            + "period"),
        Map.entry(Item.TRACTOR_BEAM, "Tractor Beam; gives mutual attractive force to currently locked on ship, "
            + "this means the heavier your ship, the less likely you will move when being tractored or using a "
            + "tractor"),
        Map.entry(Item.AUTOPILOT, "Autopilot; when on, the ship will turn and thrust against the direction of "
            + "travel"),
        Map.entry(Item.EMERGENCY_SHIELD, "EmergencyShield; gives emergency shield capabilities for a limited "
            + "period"),
        Map.entry(Item.DEFLECTOR, "Deflector; pushes hostile objects away from your ship"),
        Map.entry(Item.HYPERJUMP, "Hyperjump; enables you to teleport to a random map location"),
        Map.entry(Item.PHASING, "Phasing Device; lets you fly through anything for a limited period"),
        Map.entry(Item.MIRROR, "Mirror; reflects laser beams"),
        Map.entry(Item.ARMOR, "Armor; absorbs shots in the absence of shields")));

    /** The game keys that use each item (the rest work by being carried). */
    static final Map<Item, List<ControlKey>> KEYS = new EnumMap<>(Map.ofEntries(
        Map.entry(Item.CLOAK, List.of(ControlKey.CLOAK)),
        Map.entry(Item.TRANSPORTER, List.of(ControlKey.TRANSPORTER)),
        Map.entry(Item.TANK, List.of(ControlKey.TANK_DETACH, ControlKey.TANK_NEXT, ControlKey.TANK_PREV)),
        Map.entry(Item.MINE, List.of(ControlKey.DROP_MINE, ControlKey.DETACH_MINE)),
        Map.entry(Item.MISSILE, List.of(ControlKey.FIRE_MISSILE, ControlKey.FIRE_HEAT, ControlKey.FIRE_TORPEDO)),
        Map.entry(Item.ECM, List.of(ControlKey.ECM)),
        Map.entry(Item.LASER, List.of(ControlKey.FIRE_LASER)),
        Map.entry(Item.EMERGENCY_THRUST, List.of(ControlKey.EMERGENCY_THRUST)),
        Map.entry(Item.TRACTOR_BEAM, List.of(ControlKey.TRACTOR_BEAM, ControlKey.PRESSOR_BEAM)),
        Map.entry(Item.AUTOPILOT, List.of(ControlKey.TOGGLE_AUTOPILOT)),
        Map.entry(Item.EMERGENCY_SHIELD, List.of(ControlKey.EMERGENCY_SHIELD)),
        Map.entry(Item.DEFLECTOR, List.of(ControlKey.DEFLECTOR)),
        Map.entry(Item.HYPERJUMP, List.of(ControlKey.HYPERJUMP)),
        Map.entry(Item.PHASING, List.of(ControlKey.PHASING))));

    /** BEHAVIOR: lower-case file name stem with dashes: EMERGENCY_THRUST is "emergency-thrust". */
    static String stem(Item i) {
        return i.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** BEHAVIOR: display name: EMERGENCY_THRUST is "Emergency thrust", ECM stays "ECM". */
    static String displayName(Item i) {
        if (i == Item.ECM) {
            return "ECM";
        }
        String s = i.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** {@code 0xAABBGGRR} palette colour as opaque {@code 0xAARRGGBB}. */
    static int argb(int paletteIndex) {
        int c = Palette.xpilotDefault().color(paletteIndex);
        return 0xFF000000 | (c & 0xFF) << 16 | (c >> 8 & 0xFF) << 8 | (c >> 16 & 0xFF);
    }

    /**
     * The bare symbol: 16x16, red where the bitmap is set, black elsewhere, scaled {@link #SCALE} times.
     * <p>
     * BEHAVIOR: pixel (x, y) at 1x is red iff {@link ItemBitmaps#pixel} is set.
     */
    static BufferedImage symbol(Item item) {
        int n = ItemBitmaps.SIZE;
        int[] px = new int[n * n];
        int red = argb(Palette.RED);
        int black = argb(Palette.BLACK);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                px[y * n + x] = ItemBitmaps.pixel(item.ordinal(), x, y) ? red : black;
            }
        }
        return scaled(px, n, n);
    }

    /**
     * The item as the C client paints it, centre (c, c) with c = MARGIN + S, S = ITEM_TRIANGLE_SIZE: blue
     * lines (c-S, c-S) to (c, c+S) to (c+S, c-S) and back (X11 zero-width lines, both end points drawn),
     * then the symbol stippled in red with its top-left at (c - 8, c - S + 2).
     * <p>
     * BEHAVIOR: the canvas is 2 * (MARGIN + S) + 1 pixels square at 1x, black where nothing is drawn.
     */
    static BufferedImage inPlay(Item item) {
        int s = PaintConfig.ITEM_TRIANGLE_SIZE;
        int c = MARGIN + s;
        int n = 2 * c + 1;
        int[] px = new int[n * n];
        java.util.Arrays.fill(px, argb(Palette.BLACK));
        int blue = argb(Palette.BLUE);
        line(px, n, c - s, c - s, c, c + s, blue);
        line(px, n, c, c + s, c + s, c - s, blue);
        line(px, n, c + s, c - s, c - s, c - s, blue);
        int red = argb(Palette.RED);
        int left = c - ItemBitmaps.SIZE / 2;
        int top = c - s + 2;
        for (int y = 0; y < ItemBitmaps.SIZE; y++) {
            for (int x = 0; x < ItemBitmaps.SIZE; x++) {
                if (ItemBitmaps.pixel(item.ordinal(), x, y)) {
                    px[(top + y) * n + left + x] = red;
                }
            }
        }
        return scaled(px, n, n);
    }

    /** Bresenham line including both end points. */
    static void line(int[] px, int w, int x0, int y0, int x1, int y1, int argb) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            px[y0 * w + x0] = argb;
            if (x0 == x1 && y0 == y1) {
                return;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    private static BufferedImage scaled(int[] px, int w, int h) {
        BufferedImage img = new BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h * SCALE; y++) {
            for (int x = 0; x < w * SCALE; x++) {
                img.setRGB(x, y, px[(y / SCALE) * w + x / SCALE]);
            }
        }
        return img;
    }

    /** Every picture, keyed by its path under {@code docs/guide/images/}, in item order. */
    static Map<String, BufferedImage> images() {
        Map<String, BufferedImage> out = new LinkedHashMap<>();
        for (Item i : Item.values()) {
            out.put("items/" + stem(i) + ".png", inPlay(i));
            out.put("items/" + stem(i) + "-symbol.png", symbol(i));
        }
        return out;
    }

    /** BEHAVIOR: SHA-256 (hex) of the pixels of every picture, in order; independent of PNG encoding. */
    static String pixelHash(Map<String, BufferedImage> images) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, BufferedImage> e : images.entrySet()) {
                md.update(e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                BufferedImage img = e.getValue();
                ByteBuffer b = ByteBuffer.allocate(8 + 4 * img.getWidth() * img.getHeight());
                b.putInt(img.getWidth()).putInt(img.getHeight());
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        b.putInt(img.getRGB(x, y) | 0xFF000000);
                    }
                }
                md.update(b.array());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The complete {@code items.adoc}. */
    public static String itemsAdoc() {
        Map<String, BufferedImage> images = images();
        StringBuilder sb = new StringBuilder();
        sb.append("// GENERATED from client/src/main/java/com/fulcrologic/jxpilot/client/paint/ItemBitmaps.java and\n")
            .append("// dist/src/test/java/com/fulcrologic/jxpilot/dist/docs/ItemTable.java. Do not edit:\n")
            .append("// run `make -C docs/guide generate` instead. The build fails when this file or the pictures\n")
            .append("// in docs/guide/images/items/ are stale.\n")
            .append("// pixel-sha256: ").append(pixelHash(images)).append("\n")
            .append("ifdef::env-github[]\n:imagesdir: ../images\nendif::[]\n\n")
            .append("[cols=\"2,1,3,7,3,2\",options=\"header\"]\n|===\n")
            .append("|In play |Symbol |Item |What it does (from the XPilot client) |Keys |Works on\n\n");
        for (Item i : Item.values()) {
            String name = displayName(i);
            sb.append("|image:items/").append(stem(i)).append(".png[").append(name).append(" in play]\n")
                .append("|image:items/").append(stem(i)).append("-symbol.png[").append(name).append(" symbol]\n")
                .append("|[[item-").append(stem(i)).append("]]*").append(name).append("*\n")
                .append('|').append(GuideTables.cell(C_TEXT.get(i))).append('\n')
                .append('|').append(keys(i)).append('\n')
                .append('|').append(status(i)).append("\n\n");
        }
        sb.append("|===\n");
        return sb.toString();
    }

    /** BEHAVIOR: C server always yes; Java server yes once implemented, else milestone M2; one per line. */
    static String status(Item i) {
        return "C server: yes +\nJava server: " + (JAVA_SERVER_IMPLEMENTED.contains(i) ? "yes" : "M2");
    }

    /** BEHAVIOR: "Function (`keysym`)" for each key, separated by line breaks; "-" for items used passively. */
    static String keys(Item i) {
        List<ControlKey> ks = KEYS.get(i);
        if (ks == null) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        for (ControlKey k : ks) {
            parts.add(GuideTables.functionName(k) + ": " + GuideTables.keysText(k.defaultKeys));
        }
        return String.join(" +\n", parts);
    }
}
