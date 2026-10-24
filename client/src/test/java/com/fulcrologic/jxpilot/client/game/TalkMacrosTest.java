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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** C talkmacros.c Talk_macro_parse_mesg, escape by escape. */
class TalkMacrosTest {
    static final class Fake implements TalkMacros.Context {
        final Players players = new Players();
        final List<String> sent = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final Map<String, String> files = new HashMap<>();
        int self = -1;
        int lock = -1;
        boolean teamPlay;
        boolean limitedLives;
        String home = "/home/ann";
        int random;

        @Override public Players players() { return players; }
        @Override public int selfSlot() { return self; }
        @Override public int lockSlot() { return lock; }
        @Override public boolean teamPlay() { return teamPlay; }
        @Override public boolean limitedLives() { return limitedLives; }
        @Override public String home() { return home; }
        @Override public String readFile(String name) { return files.get(name); }
        @Override public int random(int n) { return random % n; }
        @Override public void talk(CharSequence line) { sent.add(line.toString()); }
        @Override public void error(String text) { errors.add(text); }

        int join(int id, int team, String name, float score, int lives) {
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = id;
            j.team = team;
            j.name.append(name);
            players.join(j);
            players.setScore(id, score, lives, 0);
            return players.slotOf(id);
        }
    }

    final Fake f = new Fake();
    final TalkMacros m = new TalkMacros(f);

    List<String> send(String macro) {
        f.sent.clear();
        m.send(macro);
        return List.copyOf(f.sent);
    }

    @Nested
    @DisplayName("plain text and simple escapes")
    class Simple {
        @Test
        void textAndEscapes() {
            f.teamPlay = true;
            f.self = f.join(1, 3, "Ann", 12.5f, 2);
            assertAll(
                () -> assertEquals(List.of("hello world"), send("hello world"), "plain text is one line"),
                () -> assertEquals(List.of("ab"), send("a\nb"), "newlines are dropped"),
                () -> assertEquals(List.of("50# off"), send("50## off"), "## is a '#'"),
                () -> assertEquals(List.of("team 3"), send("team #t"), "#t: own team digit in team play"),
                () -> assertEquals(List.of("in /home/ann!"), send("in #h!"), "#h: $HOME"),
                () -> assertEquals(List.of("ab"), send("a#xb"), "an unknown escape is dropped"),
                () -> assertEquals(List.of("trailing"), send("trailing#"), "a '#' at the end ends the text"),
                () -> assertEquals(List.of(), send(""), "an empty macro sends nothing"),
                () -> assertEquals(List.of(), send(null), "an unset macro sends nothing"),
                () -> assertEquals(List.of("stop"), send("stop\0more"), "a NUL ends the text"));
        }

        @Test
        void teamOnlyInTeamPlayAndWithASelf() {
            f.self = f.join(1, 3, "Ann", 0, 0);
            List<String> noTeamPlay = send("t#t.");
            f.teamPlay = true;
            f.self = -1;
            assertAll(
                () -> assertEquals(List.of("t."), noTeamPlay, "no digit outside team play"),
                () -> assertEquals(List.of("t."), send("t#t."), "nor without an own player"));
        }
    }

    @Nested
    @DisplayName("#s and #l: player fields")
    class PlayerFields {
        @Test
        void selfAndLockFields() {
            f.teamPlay = true;
            f.limitedLives = true;
            f.self = f.join(1, 3, "Ann", 12.5f, 2);
            f.lock = f.join(2, 4, "Bob", -3f, 5);
            assertAll(
                () -> assertEquals(List.of("I am Ann (12.50), team 3, 2 lives"),
                    send("I am #sn (#ss), team #st, #sl lives"), "own name, score %.2f, team, lives"),
                () -> assertEquals(List.of("Bob: -3.00 4 5"), send("#ln: #ls #lt #ll"), "the locked player"),
                () -> assertEquals(List.of("x"), send("#sqx"), "an unknown field char is eaten"));
        }

        @Test
        void fieldsNeedTheirModes() {
            f.self = f.join(1, 3, "Ann", 1f, 2);
            assertEquals(List.of("[|]"), send("[#st|#sl]"), "team only in team play, lives only with limited lives");
        }

        @Test
        void noLockDropsTheWholeMessage() {
            f.self = f.join(1, 3, "Ann", 1f, 2);
            List<String> noLock = send("Hey #ln, die");
            f.self = -1;
            assertAll(
                () -> assertEquals(List.of(), noLock, "C: pos = 0 and done"),
                () -> assertEquals(List.of("xn"), send("x#sn"), "no own player: #s selects nobody, n is text"));
        }
    }

    @Nested
    @DisplayName("lines")
    class Lines {
        @Test
        void hashNSendsALineAndLongTextIsSplit() {
            String a78 = "a".repeat(78);
            assertAll(
                () -> assertEquals(List.of("one", "two"), send("one#ntwo"), "#n ends a line"),
                () -> assertEquals(List.of("one"), send("one#n"), "#n at the end sends the line; nothing is left to send"),
                () -> assertEquals(List.of(a78, "bc"), send(a78 + "bc"), "78 chars (MAX_CHARS - 2) fill a line"),
                () -> assertEquals(List.of(a78), send(a78), "exactly 78: one line"));
        }
    }

    @Nested
    @DisplayName("#r, #= and #f")
    class Fields {
        @Test
        void randomPicksAFieldAndExpandsIt() {
            f.self = f.join(1, 3, "Ann", 0, 0);
            f.random = 1;
            List<String> second = send("Go #r[left|#sn|right]!");
            f.random = 5;
            assertAll(
                () -> assertEquals(List.of("Go Ann!"), second, "field random % 3 + 1 = 2, expanded"),
                () -> assertEquals(List.of("Go right!"), send("Go #r[left|#sn|right]!"), "5 % 3 + 1 = 3"),
                () -> assertEquals(List.of("x[a"), send("x#r[a"), "an open group: not a group, the text stays (C)"),
                () -> assertEquals(List.of("][a]"), send("#r][a]"), "a stray ']' first: likewise"));
        }

        @Test
        void comparisons() {
            f.self = f.join(1, 3, "Ann", 0, 0);
            assertAll(
                () -> assertEquals(List.of("me!"), send("#=[#sn|Ann|me!|you]"), "equal: the third field"),
                () -> assertEquals(List.of("you"), send("#=[#sn|Bob|me!|you]"), "different: the fourth"),
                () -> assertEquals(List.of("<>"), send("<#=[a|b|same]>"), "different without a fourth: nothing"),
                () -> assertEquals(List.of("[a|b]"), send("#=[a|b]"), "two fields: not a comparison, the text stays"),
                () -> assertEquals(List.of("[a|b|c|d|e]"), send("#=[a|b|c|d|e]"), "five fields neither"),
                () -> assertEquals(List.of("eq"), send("#=[" + "x".repeat(30) + "|" + "x".repeat(18) + "y|eq|ne]"),
                    "each side is cut to 18 chars (FIELD_LEN - 2) before comparing"),
                () -> assertEquals(List.of("in[ner]"), send("#=[a|a|in[ner]]"), "brackets nest in a field"));
        }

        @Test
        void files() {
            f.home = "/h";
            f.files.put("/h/greet", "hi\nthere");
            f.files.put("loop", "x#f[loop]");
            assertAll(
                () -> assertEquals(List.of("<hithere>"), send("<#f[#h/greet]>"), "the file name is expanded; the file too"),
                () -> assertEquals(List.of("<>"), send("<#f[nope]>"), "an unreadable file adds nothing"),
                () -> assertEquals(List.of("Couldn't open file nope"), f.errors, "and says so"),
                () -> assertEquals(List.of("[a|b]"), send("#f[a|b]"), "two fields: not a file"),
                () -> assertEquals(List.of("x".repeat(TalkMacros.MAX_DEPTH)), send("#f[loop]"),
                    "a file including itself stops MAX_DEPTH files deep"));
        }
    }

    @Nested
    @DisplayName("limits (C's pos checks)")
    class Limits {
        @Test
        void namesScoresAndHomeStopAtTheLineLimit() {
            f.self = f.join(1, 3, "Abcdefghijklmnop", 123456.78f, 2);
            f.home = "/home/ann";
            assertAll(
                () -> assertEquals(List.of("x".repeat(70) + "Abcdefgh"), send("x".repeat(70) + "#sn"),
                    "a name stops at 78 chars"),
                () -> assertEquals(List.of("x".repeat(72) + "123456."), send("x".repeat(72) + "#ss"),
                    "a score starts below 73 and stops at 79 chars"),
                () -> assertEquals(List.of("x".repeat(73)), send("x".repeat(73) + "#ss"), "from 73 on no score"),
                () -> assertEquals(List.of("x".repeat(75) + "/ho"), send("x".repeat(75) + "#h"), "$HOME stops at 78"));
        }

        @Test
        void aLockedPlayerInSlotZeroCounts() {
            f.lock = f.join(1, 3, "Ann", 0, 0);
            assertAll(
                () -> assertEquals(0, f.lock, "slot 0"),
                () -> assertEquals(List.of("hi Ann"), send("hi #ln"), "slot 0 is a player"));
        }

        @Test
        void everyRecursionCountsTowardsTheDepth() {
            // a recursion that did not count would recurse until the stack overflows
            f.files.put("r", "y#r[#f[r]]");
            f.files.put("t", "z#=[a|a|#f[t]]");
            f.files.put("e", "w#=[a|b|x|#f[e]]");
            f.files.put("c", "#=[#f[c]|b|c|d]");
            assertAll(
                () -> assertFalse(send("#f[r]").isEmpty(), "#r in a file"),
                () -> assertFalse(send("#f[t]").isEmpty(), "#= then-branch"),
                () -> assertFalse(send("#f[e]").isEmpty(), "#= else-branch"),
                () -> assertDoesNotThrow(() -> send("#f[c]"), "a compared field"),
                () -> assertEquals(List.of("q".repeat(TalkMacros.MAX_DEPTH)), send(nested(20)),
                    "nested then-branches keep what they wrote up to the depth limit"));
        }

        String nested(int n) {
            return n == 0 ? "" : "#=[a|a|q" + nested(n - 1) + "]";
        }
    }

    @Nested
    @DisplayName("field helpers")
    class Helpers {
        @Test
        void fieldsInfoAndField() {
            int[] n = new int[1];
            int next = TalkMacros.fieldsInfo("x[a|[b|c]|d]y", 0, n);
            int count = n[0];
            assertAll(
                () -> assertEquals(12, next, "after the closing bracket"),
                () -> assertEquals(3, count, "nested '|' does not count"),
                () -> assertEquals("[b|c]", TalkMacros.field("x[a|[b|c]|d]y", 0, 2), "a nested field whole"),
                () -> assertEquals("d", TalkMacros.field("x[a|[b|c]|d]y", 0, 3), "the last field"),
                () -> assertEquals("", TalkMacros.field("[a|b]", 0, 3), "no such field"),
                () -> assertEquals(-1, TalkMacros.fieldsInfo("[a|b", 0, n), "unclosed"),
                () -> assertEquals("", TalkMacros.field("]a", 0, 1), "a ']' before any '['"),
                () -> assertEquals("", TalkMacros.field("[a", 0, 1), "an unclosed group: no field (no overrun)"),
                () -> assertEquals(-1, TalkMacros.fieldsInfo("]a", 0, n), "a ']' first: not a group"));
        }
    }
}
