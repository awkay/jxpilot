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

import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CharBuf, TickClock, Players, Scoreboard, MessageLog, GameEvents. */
class ModelsTest {
    @Nested
    @DisplayName("CharBuf")
    class CharBufTest {
        String s(java.util.function.Consumer<CharBuf> f) {
            CharBuf b = new CharBuf(32);
            f.accept(b);
            return b.toString();
        }

        @Test
        @DisplayName("formats integers like C %d, %5d and %04d")
        void ints() {
            assertAll(
                () -> assertEquals("0", s(b -> b.appendInt(0)), "zero"),
                () -> assertEquals("-120", s(b -> b.appendInt(-120)), "negative"),
                () -> assertEquals("   42", s(b -> b.appendInt(42, 5, ' ')), "space padded"),
                () -> assertEquals("0042", s(b -> b.appendInt(42, 4, '0')), "zero padded"),
                () -> assertEquals("-042", s(b -> b.appendInt(-42, 4, '0')), "zero padding after the sign"),
                () -> assertEquals("  -42", s(b -> b.appendInt(-42, 5, ' ')), "space padding before the sign"),
                () -> assertEquals("12345", s(b -> b.appendInt(12345, 3, ' ')), "wider than the width"),
                () -> assertEquals("-2147483648", s(b -> b.appendInt(Integer.MIN_VALUE)), "MIN_VALUE"));
        }

        @Test
        @DisplayName("formats fixed point like C %*.*f")
        void fixed() {
            assertAll(
                () -> assertEquals("   12.3", s(b -> b.appendFixed(12.34, 1, 7)), "%7.1f"),
                () -> assertEquals("   -3.5", s(b -> b.appendFixed(-3.5, 1, 7)), "negative"),
                () -> assertEquals("0.0", s(b -> b.appendFixed(-0.04, 1, 0)), "rounds to zero without a sign"),
                () -> assertEquals("  2", s(b -> b.appendFixed(2.5, 0, 3)), "no decimals: no point; a tie goes to even"),
                () -> assertEquals("4", s(b -> b.appendFixed(3.5, 0, 0)), "3.5 ties to 4"),
                () -> assertEquals("2.2", s(b -> b.appendFixed(2.25, 1, 0)), "exact tie 2.25 to even, like printf"),
                () -> assertEquals("-2.2", s(b -> b.appendFixed(-2.25, 1, 0)), "negative tie to even"),
                () -> assertEquals("2.4", s(b -> b.appendFixed(2.35, 1, 0)), "2.35 is really above the tie"),
                () -> assertEquals("1.00", s(b -> b.appendFixed(1.005, 2, 0)), "1.005 is really below the tie"),
                () -> assertEquals("0.38", s(b -> b.appendFixed(0.375, 2, 0)), "exact 0.375 ties up to even 8"),
                () -> assertEquals("0.12", s(b -> b.appendFixed(0.125, 2, 0)), "exact 0.125 ties down to even 2"),
                () -> assertEquals("0.1", s(b -> b.appendFixed(0.05f, 1, 0)), "float 0.05 is above the tie"),
                () -> assertEquals("0.5", s(b -> b.appendFixed(0.45, 1, 0)), "0.45 is above the tie although 4.5 is"),
                () -> assertEquals("0.7", s(b -> b.appendFixed(0.65, 1, 0)), "0.65 is above the tie: not to even 6"),
                () -> assertEquals("0.1", s(b -> b.appendFixed(0.15, 1, 0)), "0.15 is below the tie: not to even 2"),
                () -> assertEquals("2.67", s(b -> b.appendFixed(2.675, 2, 0)), "2.675 is below the tie"),
                () -> assertEquals("1.05", s(b -> b.appendFixed(1.049, 2, 0)), "leading fraction zeros kept"),
                () -> assertEquals("0.000001", s(b -> b.appendFixed(0.000001, 9, 0)), "decimals clamped to 6"));
        }

        @Test
        @DisplayName("is bounded and editable")
        void bounded() {
            CharBuf b = new CharBuf(4);
            b.append("abcdef");
            String full = b.toString();
            b.truncate(2);
            String cut = b.toString();
            b.truncate(9);
            b.setLength(3);
            b.array()[2] = 'z';
            assertAll(
                () -> assertEquals("abcd", full, "chars past capacity are dropped"),
                () -> assertEquals("ab", cut, "truncate shortens"),
                () -> assertEquals("abz", b.toString(), "setLength exposes array contents"),
                () -> assertTrue(b.contentEquals("abz"), "contentEquals true for equal text"),
                () -> assertFalse(b.contentEquals("abc"), "contentEquals false for other text"),
                () -> assertFalse(b.contentEquals("ab"), "contentEquals false for other length"),
                () -> assertEquals('z', b.charAt(2), "charAt"),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> b.charAt(3), "charAt past length"),
                () -> assertEquals("bz", b.subSequence(1, 3).toString(), "subSequence"),
                () -> assertEquals("  ", new CharBuf(8).repeat(' ', 2).toString(), "repeat"),
                () -> assertEquals("xy", new CharBuf(8).append(new char[] {'w', 'x', 'y'}, 1, 2).toString(),
                    "array slice"),
                () -> assertEquals(4, b.capacity(), "capacity"));
        }
    }

    @Nested
    @DisplayName("TickClock")
    class Clock {
        @Test
        @DisplayName("first sample sets the estimate; later samples smooth; far samples resync")
        void samples() {
            TickClock c = new TickClock();
            c.reset(60);
            double before = c.serverTick(0);
            c.sample(600, 1_000_000_000L);
            double first = c.serverTick(2_000_000_000L);
            c.sample(663, 2_000_000_000L);
            double smoothed = c.serverTick(2_000_000_000L);
            c.sample(5000, 2_000_000_000L);
            assertAll(
                () -> assertEquals(0.0, before, "0 before any sample"),
                () -> assertEquals(660.0, first, 1e-9, "600 at 1 s, 60 ticks per second later"),
                () -> assertEquals(660.3, smoothed, 1e-9, "a sample 3 ticks ahead moves the estimate by 10%"),
                () -> assertEquals(5000.0, c.serverTick(2_000_000_000L), 1e-9, "a sample 30+ ticks away resyncs"),
                () -> assertTrue(c.synced(), "synced"));
        }
    }

    static Msg.PlayerJoin join(int id, int team, int flags, String name, String shape) {
        Msg.PlayerJoin j = new Msg.PlayerJoin();
        j.id = id;
        j.team = team;
        j.flags = flags;
        j.name.append(name);
        j.shape.append(shape);
        return j;
    }

    @Nested
    @DisplayName("Players")
    class PlayersTest {
        @Test
        @DisplayName("joins, rejoins, leaves and updates by wire id")
        void lifecycle() {
            Players p = new Players();
            int a = p.join(join(3, 1, 0, "Ann", ""));
            p.setScore(3, 5, 2, 0);
            int b = p.join(join(70000 & 0xFFFF, 2, Msg.PlayerJoin.FLAG_SELF, "B".repeat(40), "(SH: 15,0 -9,8 -9,-8)(EN: -7,1)"));
            int c = p.join(join(5, 0, 0, "Cy", ""));
            int again = p.join(join(3, 4, 0, "Ann2", ""));
            p.leave(4464);
            p.leave(999);
            p.setTeam(5, 3);
            p.setThrust(5, 33, 7);
            p.setTeam(999, 1);
            p.setThrust(999, 1, 1);
            float unknownDelta = p.setScore(999, 1, 1, 1);
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < p.count(); i++) {
                order.add(p.id(p.slotAt(i)));
            }
            assertAll(
                () -> assertEquals(a, again, "a rejoin reuses the slot"),
                () -> assertEquals(0f, p.score(a), "a rejoin resets the score"),
                () -> assertEquals(-1, p.lives(a), "and lives to unlimited"),
                () -> assertEquals(4, p.team(a), "and takes the new team"),
                () -> assertEquals("Ann2", p.name(a).toString(), "and the new name"),
                () -> assertEquals(4464, p.selfId(), "FLAG_SELF sets the self id"),
                () -> assertNotEquals(b, c, "different slots"),
                () -> assertEquals(List.of(3, 5), order, "join order kept after a leave"),
                () -> assertEquals(-1, p.slotOf(4464), "the left player is gone"),
                () -> assertEquals(3, p.team(c), "team change"),
                () -> assertEquals(33f, p.power(c), "thrust power"),
                () -> assertEquals(7, p.afterburners(c), "afterburners"),
                () -> assertEquals(0f, unknownDelta, "unknown ids are ignored"),
                () -> assertEquals(ShipShape.defaultShip().engineX, p.engineX(c), "default ship engine"));
        }

        @Test
        @DisplayName("keeps the parsed shape's engine and caps names at 32 chars")
        void shapeAndName() {
            Players p = new Players();
            int s = p.join(join(1, 0, 0, "N".repeat(40), "(SH: 15,0 -9,8 -9,-8)(EN: -7,1)"));
            float first = p.setScore(1, 1, 3, 0);
            float d = p.setScore(1, 3.5f, 3, 0);
            float d2 = p.setScore(1, 2.5f, 3, 0);
            Players q = new Players();
            q.join(join(1, 0, 0, "a", ""));
            q.setScore(1, 4, 3, 0);
            q.join(join(1, 0, 0, "again", ""));
            float rejoined = q.setScore(1, 9, 3, 0);
            assertAll(
                () -> assertEquals(-7, p.engineX(s), "engine x from the shape"),
                () -> assertEquals(1, p.engineY(s), "engine y from the shape"),
                () -> assertEquals(32, p.name(s).length(), "name capped"),
                () -> assertEquals(0f, first, "the first SCORE after joining is the baseline, not a change"),
                () -> assertEquals(0f, rejoined, "also after a rejoin"),
                () -> assertEquals(2.5f, d, "score delta"),
                () -> assertEquals(-1f, d2, "negative delta"));
        }

        @Test
        @DisplayName("status char priority D, P, W, T, R, blank")
        void statusChar() {
            Players p = new Players();
            int r = p.join(join(1, 0, Msg.PlayerJoin.FLAG_ROBOT | Msg.PlayerJoin.FLAG_TANK, "x", ""));
            char tank = p.statusChar(r);
            int r2 = p.join(join(2, 0, Msg.PlayerJoin.FLAG_ROBOT, "x", ""));
            int h = p.join(join(3, 0, 0, "x", ""));
            int pausedFlag = p.join(join(4, 0, Msg.PlayerJoin.FLAG_PAUSED, "x", ""));
            char blank = p.statusChar(h);
            p.setScore(3, 0, 0, Msg.Score.STATUS_WAITING);
            char waiting = p.statusChar(h);
            p.setScore(3, 0, 0, Msg.Score.STATUS_WAITING | Msg.Score.STATUS_PAUSED);
            char paused = p.statusChar(h);
            p.setScore(3, 0, 0, Msg.Score.STATUS_OUT_OF_LIVES | Msg.Score.STATUS_PAUSED);
            assertAll(
                () -> assertEquals('T', tank, "tank before robot"),
                () -> assertEquals('R', p.statusChar(r2), "robot"),
                () -> assertEquals(' ', blank, "plain player"),
                () -> assertEquals('W', waiting, "waiting"),
                () -> assertEquals('P', paused, "paused before waiting"),
                () -> assertEquals('P', p.statusChar(pausedFlag), "paused join flag"),
                () -> assertEquals('D', p.statusChar(h), "out of lives first"));
        }

        @Test
        @DisplayName("a full pool rejects joins; clear forgets everyone")
        void fullAndClear() {
            Players p = new Players();
            for (int i = 0; i < Players.CAPACITY; i++) {
                p.join(join(i, 0, 0, "p", ""));
            }
            int extra = p.join(join(5000, 0, 0, "p", ""));
            int v = p.version();
            p.clear();
            assertAll(
                () -> assertEquals(-1, extra, "no slot for player 257"),
                () -> assertEquals(0, p.count(), "cleared"),
                () -> assertEquals(-1, p.slotOf(3), "ids forgotten"),
                () -> assertEquals(Protocol.NO_ID, p.selfId(), "self forgotten"),
                () -> assertTrue(p.version() > v, "version bumped"),
                () -> assertEquals(0, p.join(join(9, 0, 0, "p", "")), "slots are free again"));
        }
    }

    @Nested
    @DisplayName("Scoreboard")
    class ScoreboardTest {
        List<String> labels(Scoreboard sb) {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < sb.rowCount(); i++) {
                l.add(sb.label(i).toString());
            }
            return l;
        }

        @Test
        @DisplayName("free for all: stable descending order, C line format, best by score/(life+1)")
        void freeForAll() {
            Players p = new Players();
            p.join(join(1, 0, 0, "Ann", ""));
            p.join(join(2, 0, 0, "Bob", ""));
            p.join(join(3, 0, Msg.PlayerJoin.FLAG_ROBOT, "Cy", ""));
            p.join(join(4, 0, 0, "Dee", ""));
            p.setScore(1, 12.34f, -1, 0);
            p.setScore(2, 30, -1, 0);
            p.setScore(3, 12.34f, -1, 0);
            p.setScore(4, -3.5f, -1, Msg.Score.STATUS_PAUSED);
            Scoreboard sb = new Scoreboard();
            sb.configure(false, false, false, 1);
            sb.update(p);
            assertAll(
                () -> assertEquals(" AL   SCORE   NAME", sb.heading().toString(), "heading"),
                () -> assertEquals(List.of("      30.0  Bob", "      12.3  Ann", "R     12.3  Cy", "P     -3.5  Dee"),
                    labels(sb), "rows by score, equal scores in join order"),
                () -> assertEquals(0, sb.bestRow(), "Bob is best"),
                () -> assertTrue(sb.dimmed(3), "paused player dimmed"),
                () -> assertFalse(sb.dimmed(2), "robot not dimmed"),
                () -> assertEquals(3, sb.rowLine(3), "line positions follow rows"),
                () -> assertEquals(p.slotOf(2), sb.rowSlot(0), "row slot"));
        }

        @Test
        @DisplayName("team play with limited lives: team rows after a blank line, ties by lives")
        void teams() {
            Players p = new Players();
            p.join(join(1, 0, 0, "Ann", ""));
            p.join(join(2, 1, 0, "Bob", ""));
            p.join(join(3, 0, 0, "Cy", ""));
            p.setScore(1, 5, 2, 0);
            p.setScore(2, 5, 3, 0);
            p.setScore(3, 1, 0, Msg.Score.STATUS_OUT_OF_LIVES);
            Scoreboard sb = new Scoreboard();
            sb.configure(true, false, true, 0);
            sb.setTeamScore(0, 6);
            sb.setTeamScore(1, 6);
            sb.setTeamScore(12, 6);
            sb.update(p);
            assertAll(
                () -> assertEquals(" TM   SCORE  LIFE NAME", sb.heading().toString(), "heading with LIFE"),
                () -> assertEquals(List.of("  0     5   2  Ann", "  1     5   3  Bob", "D 0     1   0  Cy",
                    "  1     6   4  Team 1", "  0     6   3  Team 0"), labels(sb),
                    "players, then teams; the tie goes to more lives"),
                () -> assertEquals(0, sb.bestRow(), "with limited lives the best is the highest score, first wins"),
                () -> assertEquals(4, sb.rowLine(3), "team rows start after one blank line"),
                () -> assertEquals(-1, sb.rowSlot(3), "team rows have no player"),
                () -> assertEquals(6f, sb.teamScore(1), "team score stored"));
        }

        @Test
        @DisplayName("unlimited lives: equal team scores go to fewer lives; timing marks the race column")
        void tieFewerLivesAndTiming() {
            Players p = new Players();
            p.join(join(1, 0, 0, "Ann", ""));
            p.join(join(2, 1, 0, "Bob", ""));
            p.setScore(1, 1, 5, 0);
            p.setScore(2, 1, 1, 0);
            Scoreboard sb = new Scoreboard();
            sb.configure(true, false, false, 0);
            sb.update(p);
            String firstTeam = sb.label(2).toString();
            Scoreboard race = new Scoreboard();
            race.configure(false, true, false, 0);
            p.setScore(2, 0, 1, 0);
            race.update(p);
            assertAll(
                () -> assertEquals("  1     0  Team 1", firstTeam, "team 1 (1 life) before team 0 (5)"),
                () -> assertEquals("          1  Ann", race.label(0).toString(), "timing adds the 3-char race column"),
                () -> assertEquals(0, race.bestRow(), "timing: the first row is best"),
                () -> assertEquals(2, race.rowCount(), "no team rows in timing mode"));
        }

        @Test
        @DisplayName("update rebuilds only on changes")
        void rebuildOnChange() {
            Players p = new Players();
            p.join(join(1, 0, 0, "Ann", ""));
            Scoreboard sb = new Scoreboard();
            sb.configure(false, false, false, 1);
            sb.update(p);
            sb.label(0).clear().append("stale");
            sb.update(p);
            String unchanged = sb.label(0).toString();
            sb.setTeamScore(0, 1);
            sb.update(p);
            assertAll(
                () -> assertEquals("stale", unchanged, "no rebuild without changes"),
                () -> assertEquals("       0.0  Ann", sb.label(0).toString(), "a team score change rebuilds"));
        }
    }

    @Nested
    @DisplayName("MessageLog")
    class Messages {
        @Test
        @DisplayName("talk vs game classification follows kind, then C's text rule")
        void classify() {
            assertAll(
                () -> assertTrue(MessageLog.isTalk(Msg.Text.KIND_CHAT, "hi"), "chat"),
                () -> assertTrue(MessageLog.isTalk(Msg.Text.KIND_TEAM_CHAT, "hi"), "team chat"),
                () -> assertTrue(MessageLog.isTalk(Msg.Text.KIND_PRIVATE, "hi"), "private"),
                () -> assertTrue(MessageLog.isTalk(Msg.Text.KIND_SERVER, "hello [Ann]"), "ends with ]"),
                () -> assertTrue(MessageLog.isTalk(Msg.Text.KIND_SERVER, " <Ann> hi"), "starts with ' <'"),
                () -> assertFalse(MessageLog.isTalk(Msg.Text.KIND_GAME, "Ann was killed"), "game message"),
                () -> assertFalse(MessageLog.isTalk(Msg.Text.KIND_GAME, "<"), "a lone '<' is not talk"));
        }

        @Test
        @DisplayName("newest first, oldest dropped at maxMessages, removed when life runs out")
        void ordering() {
            MessageLog log = new MessageLog(3);
            for (int i = 1; i <= 4; i++) {
                log.add(Msg.Text.KIND_GAME, "g" + i);
            }
            log.add(Msg.Text.KIND_CHAT, "t1");
            log.add(Msg.Text.KIND_CHAT, "");
            List<String> game = new ArrayList<>();
            for (int i = 0; i < log.gameCount(); i++) {
                game.add(log.game(i).text().toString());
            }
            int talk = log.talkCount();
            log.tick(500);
            log.add(Msg.Text.KIND_GAME, "g5");
            log.tick(524);
            int afterFirstExpiry = log.gameCount();
            log.tick(0.5);
            assertAll(
                () -> assertEquals(List.of("g4", "g3", "g2"), game, "newest first, g1 dropped"),
                () -> assertEquals(1, talk, "chat in the talk list; empty text ignored"),
                () -> assertEquals(3, afterFirstExpiry, "life exactly 0 is kept (g5, g4, g3)"),
                () -> assertEquals(1, log.gameCount(), "older messages expire, the new one stays"),
                () -> assertEquals("g5", log.game(0).text().toString(), "the survivor"),
                () -> assertEquals(0, log.talkCount(), "the talk message expired too"),
                () -> assertEquals(1, new MessageLog(0).maxMessages(), "maxMessages clamps to 1"),
                () -> assertEquals(15, new MessageLog(99).maxMessages(), "and to MAX_MSGS"));
        }

        @Test
        @DisplayName("messages type out at charsPerSecond and flash for the first 132 frames")
        void typing() {
            MessageLog log = new MessageLog();
            log.add(Msg.Text.KIND_GAME, "x".repeat(100));
            MessageLog.Entry e = log.game(0);
            int atStart = log.visibleChars(e, 14);
            boolean flashStart = e.flashing();
            log.tick(14);
            int after1s = log.visibleChars(e, 14);
            log.tick(118);
            boolean flashAt892 = e.flashing();
            log.setCharsPerSecond(1000);
            assertAll(
                () -> assertEquals(0, atStart, "nothing typed at first"),
                () -> assertTrue(flashStart, "flashing while new"),
                () -> assertEquals(50, after1s, "50 chars per second at 14 fps"),
                () -> assertFalse(flashAt892, "life 892 is no longer flashing"),
                () -> assertEquals(100, log.visibleChars(e, 14), "never more than the text"),
                () -> assertEquals(Msg.Text.KIND_GAME, e.kind(), "kind kept"),
                () -> assertEquals(892.0, e.life(), 1e-9, "life counts down in frames"));
        }
    }

    @Test
    @DisplayName("GameEvents keeps the newest events in order and overwrites the oldest")
    void events() {
        GameEvents ev = new GameEvents(3);
        Msg.GameEvent e = new Msg.GameEvent();
        for (int i = 1; i <= 5; i++) {
            e.kind = i;
            e.a = 10 + i;
            e.b = 20 + i;
            e.value = i / 2f;
            ev.add(e);
        }
        List<Integer> kinds = new ArrayList<>();
        for (int i = 0; i < ev.size(); i++) {
            kinds.add(ev.kind(i));
        }
        int sizeBefore = ev.size();
        ev.clear();
        assertAll(
            () -> assertEquals(List.of(3, 4, 5), kinds, "the last three, oldest first"),
            () -> assertEquals(3, sizeBefore, "capacity bounds the size"),
            () -> assertEquals(5, ev.total(), "total counts every event"),
            () -> assertEquals(0, ev.size(), "clear empties"));
        ev.add(e);
        assertAll(
            () -> assertEquals(1, ev.size(), "one after clear"),
            () -> assertEquals(15, ev.a(0), "a"),
            () -> assertEquals(25, ev.b(0), "b"),
            () -> assertEquals(2.5f, ev.value(0), "value"));
    }
}
