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
package com.fulcrologic.jxpilot.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** C common/checknames.c Fix_real_name and Fix_host_name. */
class NamesTest {
    private static String real(String s) {
        StringBuilder b = new StringBuilder(s);
        Names.fixRealName(b);
        return b.toString();
    }

    private static String host(String s) {
        StringBuilder b = new StringBuilder(s);
        Names.fixHostName(b);
        return b.toString();
    }

    @Test
    @DisplayName("real names: cut to 15 characters, empty is X, non-graphic characters become x")
    void realNames() {
        assertAll(
            () -> assertEquals("tony", real("tony"), "a login name is kept"),
            () -> assertEquals("X", real(""), "empty"),
            () -> assertEquals("abcdefghijklmno", real("abcdefghijklmnopqrs"), "15 characters"),
            () -> assertEquals("jxa", real("j a"), "a space"),
            () -> assertEquals("xx~!", real("é\t~!"), "Latin-1 and control characters; '~' and '!' are graphic"));
    }

    @Test
    @DisplayName("host names: cut to 63, must start alphanumeric, separators only between alphanumerics")
    void hostNames() {
        assertAll(
            () -> assertEquals("my-host.example.com", host("my-host.example.com"), "a normal name"),
            () -> assertEquals("xxx.xxx", host(""), "empty"),
            () -> assertEquals("xxx.xxx", host(".local"), "a leading dot"),
            () -> assertEquals("ax.b", host("a..b"), "a dot followed by a dot becomes x; the next one stays"),
            () -> assertEquals("ax", host("a."), "a trailing dot"),
            () -> assertEquals("axb", host("a_b"), "other characters"),
            () -> assertEquals(63, host("a".repeat(70)).length(), "63 characters"));
    }

    @Test
    @DisplayName("host names: a first character must be an ASCII letter or digit (each range's ends and neighbours)")
    void alphanumericBounds() {
        String[] kept = {"0", "9", "A", "Z", "a", "z"};
        String[] bad = {"/", ":", "@", "[", "`", "{"};
        for (String k : kept) {
            assertEquals(k + "b", host(k + "b"), "kept: " + k);
        }
        for (String x : bad) {
            assertEquals("xxx.xxx", host(x + "b"), "rejected: " + x);
        }
    }

    @Test
    @DisplayName("copy: at most MAX_CHARS - 1 characters, as C strlcpy(dst, src, MAX_CHARS)")
    void copy() {
        StringBuilder b = new StringBuilder("old");
        Names.copy(b, "r".repeat(100));
        StringBuilder c = new StringBuilder("old");
        Names.copy(c, "robot");
        assertAll(
            () -> assertEquals(Const.MAX_CHARS - 1, b.length(), "cut"),
            () -> assertEquals("robot", c.toString(), "replaced"));
    }
}
