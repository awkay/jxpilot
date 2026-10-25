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
package com.fulcrologic.jxpilot.common.sim;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.KernelConfig;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.OptionType;
import com.fulcrologic.jxpilot.common.config.Options;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEHAVIOR (KernelConfig): {@link KernelConfig#OPTIONS} is every option the kernel world depends on (no other
 * option changes {@link KernelFingerprint} on several maps and base configurations); encode/decode carries those
 * options and the hidden map characters so the decoded options build the same kernel world; a malformed blob is
 * refused.
 */
@DisplayName("KernelConfig")
class KernelConfigTest {
    static final String[] MAPS = {"globe.xp", "pad.xp", "teamball.xp", "fuzz.xp", "doggy.xp", "war.xp"};
    /** Base configurations on top of the map: also with a point gravity source and a worm time (FPS matters). */
    static final String[] BASES = {"", "gravityPointSource: true\ngravityPoint: 10,12\nwormTime: 3\n"};

    static String mapText(String map) throws IOException {
        return Files.readString(Path.of("..", "maps", map), StandardCharsets.ISO_8859_1);
    }

    static Options options(String text, String extra) {
        Options o = new Options();
        ConfigParser.parse(text, "map", OptionOrigin.MAP, o);
        ConfigParser.parse(extra, "extra", OptionOrigin.COMMAND, o);
        return o;
    }

    static String[] variants(Opt o, Options base) {
        return switch (o.type()) {
            case BOOL -> new String[] {base.getBool(o) ? "false" : "true"};
            case INT, SEC -> new String[] {"3", "17", "0"};
            case IPOS -> new String[] {"3,7", "10,10"};
            case REAL, PER_SEC -> new String[] {"0.37", "2.5", "0", "-1.25"};
            case STRING, LIST -> new String[] {"x", ""};
            default -> new String[0];
        };
    }

    /** A client's view of the map: the blocks C Packet_map hides become space (gravity, wormholes, friction ...). */
    static String masked(String map) {
        char[] c = map.toCharArray();
        for (int i = 0; i < c.length; i++) {
            c[i] = switch (c[i]) {
                case '+', '-', '>', '<', 'i', 'm', 'k', 'j', '@', '(', ')', '%', '&' -> ' ';
                case 'z' -> 'b';
                default -> c[i];
            };
        }
        return new String(c);
    }

    @Test
    @DisplayName("no option outside OPTIONS changes the kernel world (6 maps x 2 bases x every option and value)")
    void optionsAreComplete() throws IOException {
        TreeSet<String> outside = new TreeSet<>();
        int perturbed = 0;
        for (String map : MAPS) {
            String text = mapText(map);
            for (String extra : BASES) {
                String fp = KernelFingerprint.of(KernelWorld.of(options(text, extra)));
                for (Opt o : Opt.values()) {
                    if (o == Opt.MAP_DATA || o.type() == OptionType.VOID || KernelConfig.affects(o)) {
                        continue;
                    }
                    for (String v : variants(o, options(text, extra))) {
                        Options x = options(text, extra);
                        x.force(o, v);
                        String f;
                        try {
                            f = KernelFingerprint.of(KernelWorld.of(x));
                        } catch (RuntimeException e) {
                            f = "error " + e;
                        }
                        perturbed++;
                        if (!f.equals(fp)) {
                            outside.add(o.name() + "=" + v + " on " + map);
                        }
                    }
                }
            }
        }
        int n = perturbed;
        assertAll(
            () -> assertEquals(new TreeSet<String>(), outside, "options outside OPTIONS that change the kernel world"),
            () -> assertTrue(n > 5000, "the scan perturbed every option (" + n + ")"));
    }

    @Test
    @DisplayName("each option in OPTIONS can change the kernel world on some map and base (the shot-motion options:"
        + " the predicted bullets' motion, checked by KernelConfigParityTest)")
    void optionsAreNeeded() throws IOException {
        List<String> unused = new ArrayList<>();
        for (Opt o : KernelConfig.OPTIONS) {
            if (KernelConfig.SHOT_MOTION.contains(o)) {
                continue;
            }
            boolean seen = false;
            for (String map : MAPS) {
                String text = mapText(map);
                for (String extra : BASES) {
                    String fp = KernelFingerprint.of(KernelWorld.of(options(text, extra)));
                    for (String v : variants(o, options(text, extra))) {
                        Options x = options(text, extra);
                        x.force(o, v);
                        try {
                            seen |= !KernelFingerprint.of(KernelWorld.of(x)).equals(fp);
                        } catch (RuntimeException e) {
                            seen = true;
                        }
                    }
                }
            }
            if (!seen) {
                unused.add(o.name());
            }
        }
        assertAll(
            () -> assertEquals(List.of(), unused, "every listed option matters somewhere"),
            () -> assertTrue(KernelConfig.OPTIONS.containsAll(KernelConfig.SHOT_MOTION),
                "the shot-motion options travel in the blob"));
    }

    @Test
    @DisplayName("decode(encode) against the client's masked map rebuilds the same kernel world; hidden blocks travel")
    void roundTrip() throws IOException {
        List<String> differ = new ArrayList<>();
        int hiddenTotal = 0;
        for (String map : MAPS) {
            Options server = options(mapText(map), "gravity: -0.31\nfriction: 0.02\n");
            String client = masked(server.mapData());
            byte[] raw = KernelConfig.encode(server, client);
            Options kernel = KernelConfig.decode(raw, client);
            if (!KernelFingerprint.of(KernelWorld.of(kernel)).equals(KernelFingerprint.of(KernelWorld.of(server)))) {
                differ.add(map);
            }
            if (!kernel.mapData().equals(server.mapData())) {
                differ.add(map + " mapData");
            }
            for (int i = 0; i < client.length(); i++) {
                hiddenTotal += client.charAt(i) != server.mapData().charAt(i) ? 1 : 0;
            }
        }
        Options none = options(mapText("globe.xp"), "");
        Options whole = KernelConfig.decode(KernelConfig.encode(none, null), null);
        Options shorter = KernelConfig.decode(KernelConfig.encode(none, "xx"), "xx");
        int hidden = hiddenTotal;
        assertAll(
            () -> assertEquals(List.of(), differ, "the decoded options build the server's kernel world"),
            () -> assertTrue(hidden > 0, "the maps do hide blocks (" + hidden + ")"),
            () -> assertEquals(none.mapData(), whole.mapData(), "without a client map the whole map travels"),
            () -> assertEquals(none.mapData(), shorter.mapData(), "and with a client map of another length"));
    }

    @Test
    @DisplayName("runs: one at index 0 and one ending the blob; long runs split at 65535; no map stays no map")
    void runs() throws IOException {
        Options o = options(mapText("pad.xp"), "");
        String client = o.mapData();
        byte[] text = new byte[0];
        java.nio.ByteBuffer raw = java.nio.ByteBuffer.allocate(4 + 4 + 2 * 6 + 4);
        raw.putInt(0).putInt(2).putInt(0).putShort((short) 2).put((byte) 'A').put((byte) 'B')
            .putInt(5).putShort((short) 2).put((byte) 'C').put((byte) 'D');
        Options patched = KernelConfig.decode(raw.array(), client);
        Options big = new Options();
        big.force(Opt.MAP_DATA, "x".repeat(70_000));
        Options bigBack = KernelConfig.decode(KernelConfig.encode(big, null), null);
        Options none = new Options();
        Options noneBack = KernelConfig.decode(KernelConfig.encode(none, null), null);
        assertAll(
            () -> assertEquals("AB" + client.substring(2, 5) + "CD" + client.substring(7), patched.mapData(),
                "both runs applied, the first at index 0, the last ending the blob"),
            () -> assertEquals(70_000, bigBack.mapData().length(), "a 70000-char map in runs of at most 65535"),
            () -> assertEquals(big.mapData(), bigBack.mapData(), "intact"),
            () -> assertEquals(none.mapData(), noneBack.mapData(), "no map stays no map"),
            () -> assertEquals(0, text.length, "no option text needed"));
    }

    @Test
    @DisplayName("a truncated blob or a run outside the map is refused")
    void malformed() throws IOException {
        Options o = options(mapText("pad.xp"), "");
        String client = masked(o.mapData());
        byte[] raw = KernelConfig.encode(o, client);
        byte[] cut = java.util.Arrays.copyOf(raw, raw.length - 1);
        byte[] badText = java.nio.ByteBuffer.allocate(4).putInt(-5).array();
        byte[] badRun = java.nio.ByteBuffer.allocate(14).putInt(0).putInt(1).putInt(-1).putShort((short) 0).array();
        assertAll(
            () -> assertThrows(IllegalStateException.class, () -> KernelConfig.decode(cut, client), "truncated"),
            () -> assertThrows(IllegalStateException.class, () -> KernelConfig.decode(badText, client), "a negative text length"),
            () -> assertThrows(IllegalStateException.class, () -> KernelConfig.decode(badRun, client), "a negative run start"));
    }
}
