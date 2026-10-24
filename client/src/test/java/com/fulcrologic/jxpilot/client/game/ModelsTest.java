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
        @DisplayName("first sample sets the estimate; later samples smooth and are slewed in; far samples resync")
        void samples() {
            TickClock c = new TickClock();
            c.reset(60);
            double before = c.serverTick(0);
            c.sample(600, 1_000_000_000L);
            double first = c.serverTick(2_000_000_000L);
            c.sample(663, 2_000_000_000L);
            double offset = c.offset();
            double same = c.serverTick(2_000_000_000L);
            double fiveTicks = c.serverTick(2_000_000_000L + 5_000_000_000L / 60);
            double later = c.serverTick(3_000_000_000L);
            c.sample(5000, 3_000_000_000L);
            assertAll(
                () -> assertEquals(0.0, before, "0 before any sample"),
                () -> assertEquals(660.0, first, 1e-9, "600 at 1 s, 60 ticks per second later"),
                () -> assertEquals(600.3, offset, 1e-9,
                    "a sample 3 ticks ahead moves the smoothed offset (from the first sample's time) by 10% (0.3)"),
                () -> assertEquals(660.0, same, 1e-9, "no local time elapsed: the shown estimate has not moved yet"),
                () -> assertEquals(665.15, fiveTicks, 1e-6, "5 ticks later (nanosecond rounding): slewed by 5 * 3% = 0.15"),
                () -> assertEquals(720.3, later, 1e-9, "1 s later: the whole 0.3 is in"),
                () -> assertEquals(5000.0, c.serverTick(3_000_000_000L), 1e-9, "a sample 30+ ticks away resyncs at once"),
                () -> assertTrue(c.synced(), "synced"));
        }

        /** Feed a server running at {@code speed} x nominal (14 Hz) for {@code secs}; returns the worst error after 6 s. */
        double drift(double speed, double secs, java.util.SplittableRandom rnd, TickClock c) {
            c.reset(14);
            double worst = 0;
            int tick = 0;
            for (double t = 0; t < secs; t += 1 / (14 * speed)) {
                long now = Math.round((t + rnd.nextDouble(-0.008, 0.008)) * 1e9);
                c.sample(tick, now);
                for (int k = 0; k < 4; k++) {
                    double rt = t + k * 0.25 / (14 * speed);
                    double est = c.serverTick(Math.round(rt * 1e9));
                    double truth = rt * 14 * speed;
                    if (t > 6) {
                        worst = Math.max(worst, Math.abs(est - truth));
                    }
                }
                tick++;
            }
            return worst;
        }

        @Test
        @DisplayName("F7/R4: servers at 0.95x and 1.10x the nominal rate are followed (rate estimated, error < 0.3 tick)")
        void rate() {
            TickClock slow = new TickClock();
            TickClock fast = new TickClock();
            double eSlow = drift(0.95, 20, new java.util.SplittableRandom(1), slow);
            double eFast = drift(1.10, 20, new java.util.SplittableRandom(2), fast);
            TickClock nominal = new TickClock();
            double eNom = drift(1.0, 20, new java.util.SplittableRandom(3), nominal);
            assertAll(
                () -> assertEquals(0.95, slow.rate(), 0.01, "slow server rate estimated"),
                () -> assertEquals(1.10, fast.rate(), 0.01, "fast server rate estimated"),
                () -> assertEquals(1.0, nominal.rate(), 0.01, "nominal rate"),
                () -> assertTrue(eSlow < 0.3, "slow: the estimate stays within 0.3 tick (" + eSlow + ")"),
                () -> assertTrue(eFast < 0.3, "fast: within 0.3 tick (" + eFast + ")"),
                () -> assertTrue(eNom < 0.3, "nominal: within 0.3 tick (" + eNom + ")"));
        }

        @Test
        @DisplayName("the rate waits for enough samples over a second, is clamped, and resets; a far shown offset snaps")
        void rateRulesAndSnap() {
            TickClock c = new TickClock();
            c.reset(10);
            for (int i = 0; i < TickClock.RATE_MIN_SAMPLES - 1; i++) {
                c.sample((int) Math.round(i * 1.4), i * 100_000_000L);
            }
            double early = c.rate();
            for (int i = TickClock.RATE_MIN_SAMPLES - 1; i < 40; i++) {
                c.sample((int) Math.round(i * 1.4), i * 100_000_000L);
            }
            double clamped = c.rate();
            c.reset(10);
            double afterReset = c.rate();
            TickClock d = new TickClock();
            d.reset(10);
            d.sample(100, 0);
            d.serverTick(0);
            for (int i = 1; i <= 8; i++) {
                d.sample(100 + i * 10 + 4, i * 1_000_000_000L);
            }
            double snapped = d.serverTick(8_000_000_000L);
            assertAll(
                () -> assertEquals(1.0, early, "15 samples: no estimate yet"),
                () -> assertEquals(TickClock.RATE_LIMIT, clamped, 1e-9, "a server 1.4x as fast is clamped to 1.25"),
                () -> assertEquals(1.0, afterReset, "reset forgets the rate"),
                () -> assertEquals(d.offset() + d.rate() * 80, snapped, 1e-6,
                    "the smoothed offset moved more than 0.5 tick from the shown one: snapped, not slewed"));
        }

        @Test
        @DisplayName("slewing works downwards too and never moves when time goes backwards")
        void slewDown() {
            TickClock c = new TickClock();
            c.reset(10);
            c.sample(100, 0);
            c.serverTick(0);
            c.sample(98, 0);
            double back = c.serverTick(-1_000_000_000L);
            double fwd = c.serverTick(1_000_000_000L);
            assertAll(
                () -> assertEquals(90.0, back, 1e-9, "time went back: local -10 ticks, offset unchanged"),
                () -> assertEquals(109.8, fwd, 1e-9, "20 local ticks since the last call allow 0.6: the whole -0.2 is in"));
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
        @DisplayName("other players' shapes are read without limits (C Handle_player's Convert_shape_str)")
        void shapeWithoutLimits() {
            Players p = new Players();
            int tiny = p.join(join(1, 0, 0, "t", "(SH: 1,1 2,2 3,3)"));
            int notch = p.join(join(2, 0, 0, "n", "(SH: 15,0 -9,8 0,0 -9,-8)"));
            int bad = p.join(join(3, 0, 0, "b", "(SH: 1,1 2,2)"));
            assertAll(
                () -> assertArrayEquals(new int[] {1, 2, 3}, p.shape(tiny).ptX, "a ship below the size limits is kept"),
                () -> assertEquals(-9, p.engineX(notch), "an engine outside the hull is kept"),
                () -> assertSame(ShipShape.defaultShip(), p.shape(bad), "fewer than 3 points give the default ship"));
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
        @DisplayName("ID mode: rows are C's \"NAME=REAL@HOST\"; toggling rebuilds; a rejoin forgets the identity")
        void idMode() {
            Players p = new Players();
            p.join(join(1, 0, 0, "Ann", ""));
            p.join(join(3, 0, Msg.PlayerJoin.FLAG_ROBOT, "Cy", ""));
            p.setScore(1, 30, -1, 0);
            p.setScore(3, 20, -1, 0);
            p.setWar(3, 1);
            p.setIdentity(1, "ann", "box.example");
            p.setIdentity(99, "x", "y");
            p.setIdentity(3, "r".repeat(100), "robots");
            Scoreboard sb = new Scoreboard();
            sb.configure(false, false, false, 1);
            sb.update(p);
            List<String> normal = labels(sb);
            sb.toggleIdMode();
            sb.update(p);
            List<String> ids = labels(sb);
            boolean on = sb.idMode();
            p.join(join(1, 0, 0, "Ann", ""));
            sb.update(p);
            String rejoined = sb.label(1).toString();
            sb.toggleIdMode();
            sb.update(p);
            assertAll(
                () -> assertEquals(List.of("      30.0  Ann", "R     20.0  Cy (Ann)"), normal, "normal rows"),
                () -> assertEquals(List.of("Ann=ann@box.example", "Cy=" + "r".repeat(Players.MAX_IDENTITY) + "@robots"), ids,
                    "C paint.c showRealName; no war suffix; real name cut to 79 chars"),
                () -> assertTrue(on, "on"),
                () -> assertEquals("Ann=@", rejoined, "a rejoin clears real and host until PKT_PLAYER says them again"),
                () -> assertEquals("R     20.0  Cy (Ann)", sb.label(0).toString(), "off again: the score rows"));
        }

        @Test
        @DisplayName("a robot's war target follows its name as \" (NAME)\" (C paint.c:553); a gone target shows nothing")
        void warTarget() {
            Players p = new Players();
            p.join(join(1, 0, 0, "Ann", ""));
            p.join(join(3, 0, Msg.PlayerJoin.FLAG_ROBOT, "Cy", ""));
            p.join(join(4, 0, Msg.PlayerJoin.FLAG_ROBOT, "Dee", ""));
            p.setScore(1, 30, -1, 0);
            p.setScore(3, 20, -1, 0);
            p.setScore(4, 10, -1, 0);
            p.setWar(3, 1);
            p.setWar(4, 77);
            p.setWar(99, 1);
            Scoreboard sb = new Scoreboard();
            sb.configure(false, false, false, 1);
            sb.update(p);
            List<String> withWar = labels(sb);
            p.setWar(3, com.fulcrologic.jxpilot.common.net.Protocol.NO_ID);
            sb.update(p);
            assertAll(
                () -> assertEquals(List.of("      30.0  Ann", "R     20.0  Cy (Ann)", "R     10.0  Dee"), withWar,
                    "Cy is at war with Ann; Dee's target is no player"),
                () -> assertEquals("R     20.0  Cy", sb.label(1).toString(), "a war that ended (version bumped: re-laid out)"));
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
        @DisplayName("F4 timing mode: race order (progress, then who got there first; P/T/W last), race column")
        void race() {
            Players p = new Players();
            p.join(join(1, 0, 0, "Ann", ""));
            p.join(join(2, 0, Msg.PlayerJoin.FLAG_ROBOT, "Bob", ""));
            p.join(join(3, 0, 0, "Cy", ""));
            p.join(join(4, 0, 0, "Dee", ""));
            p.join(join(5, 0, 0, "Eve", ""));
            p.setScore(1, 50, -1, 0);
            p.setScore(4, 0, -1, Msg.Score.STATUS_PAUSED);
            p.setTiming(1, 3, 1, 100);
            p.setTiming(2, 2, 2, 120);
            p.setTiming(3, 2, 2, 110);
            p.setTiming(4, 5, 9, 90);
            p.setTiming(5, 0, 120, 95);
            p.setTiming(99, 1, 1, 1);
            int v = p.version();
            p.setTiming(1, 3, 1, 200);
            Scoreboard sb = new Scoreboard();
            sb.configure(false, true, false, 0);
            sb.update(p);
            assertAll(
                () -> assertEquals(List.of("  120     0  Eve", "  2.c     0  Cy", "R 2.c     0  Bob",
                    "  1.d    50  Ann", "P         0  Dee"), labels(sb),
                    "most progress first; equal progress: earlier tick first; paused last with a blank race column"),
                () -> assertEquals(v, p.version(), "unchanged progress changes nothing (no new tick)"),
                () -> assertEquals(100, p.timingTick(p.slotOf(1)), "the first tick it got there is kept"),
                () -> assertEquals(1 * 26 + 3, p.timing(p.slotOf(1)), "timing = round * 26 + check"),
                () -> assertEquals(0, sb.bestRow(), "the leader is best"));
        }

        @Test
        @DisplayName("free for all: the team column shows the alliance char (C teamStr = other->alliance)")
        void allianceColumn() {
            Players p = new Players();
            p.join(join(1, 0, 0, "Ann", ""));
            p.setScore(1, 30, -1, 0);
            p.setAlliance(1, 'a');
            p.setAlliance(2, 'b');
            Scoreboard sb = new Scoreboard();
            sb.configure(false, false, false, 1);
            sb.update(p);
            assertEquals("  a   30.0  Ann", labels(sb).get(0), "alliance in the team column");
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
        String printed(java.util.function.Consumer<java.io.PrintStream> f) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.PrintStream out = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8);
            f.accept(out);
            return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("messagesToStdout: 0 none, 1 messages ending in ']', 2 all, as they arrive (C Add_message)")
        void toStdout() {
            String[] outs = new String[3];
            for (int mode = 0; mode < 3; mode++) {
                int md = mode;
                outs[mode] = printed(out -> {
                    MessageLog log = new MessageLog(4);
                    log.setStdout(md, out);
                    log.add(Msg.Text.KIND_GAME, "Ann was killed by Bob.");
                    log.add(Msg.Text.KIND_CHAT, "hi [Ann]");
                    log.add(Msg.Text.KIND_CHAT, "");
                });
            }
            assertAll(
                () -> assertEquals("", outs[0], "0: nothing"),
                () -> assertEquals("hi [Ann]\n", outs[1], "1: player messages only"),
                () -> assertEquals("Ann was killed by Bob.\nhi [Ann]\n", outs[2], "2: everything; empty text never"));
        }

        @Test
        @DisplayName("print: talk newest first, server messages oldest first, both reversed with reverseScroll")
        void print() {
            MessageLog log = new MessageLog(3);
            log.add(Msg.Text.KIND_CHAT, "t1 [A]");
            log.add(Msg.Text.KIND_CHAT, "t2 [A]");
            log.add(Msg.Text.KIND_GAME, "g1");
            log.add(Msg.Text.KIND_GAME, "g2");
            String normal = printed(out -> log.print(out, false));
            String reversed = printed(out -> log.print(out, true));
            MessageLog empty = new MessageLog(3);
            assertAll(
                () -> assertEquals("[talk messages]\n  t2 [A]\n  t1 [A]\n[server messages]\n  g1\n  g2\n\n", normal,
                    "C Print_messages_to_stdout"),
                () -> assertEquals("[talk messages]\n[server messages]\n  g2\n  g1\n\n".replace("[talk messages]\n",
                    "[talk messages]\n  t1 [A]\n  t2 [A]\n"), reversed, "reverseScroll flips both"),
                () -> assertEquals("[talk messages]\n[server messages]\n\n", printed(out -> empty.print(out, false)),
                    "no messages: the headers"));
        }

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
