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

import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Boundary behaviors of the game models. */
class GameEdgeTest {
    @Nested
    @DisplayName("CharBuf boundaries")
    class Chars {
        @Test
        @DisplayName("digit counts at powers of ten, truncate to 0 and chaining")
        void bounds() {
            CharBuf b = new CharBuf(40);
            b.appendInt(10).append(' ').appendInt(9, 2, '0').append(' ').appendFixed(10.0, 1, 0).append(' ')
                .appendFixed(9.96, 1, 5).append(' ').appendInt(-5, 3, '0');
            String s = b.toString();
            b.truncate(s.length());
            int same = b.length();
            b.truncate(0);
            assertAll(
                () -> assertEquals("10 09 10.0  10.0 -05", s, "two digits for 10, rounding up to 10.0"),
                () -> assertEquals(s.length(), same, "truncate to the length is a no-op"),
                () -> assertEquals(0, b.length(), "truncate(0) empties"));
        }
    }

    @Nested
    @DisplayName("Players accessors and versions")
    class PlayersMore {
        @Test
        @DisplayName("every change bumps the version; flags, shape and status are kept")
        void versions() {
            Players p = new Players();
            List<Integer> v = new ArrayList<>();
            v.add(p.version());
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = 4;
            j.flags = Msg.PlayerJoin.FLAG_ROBOT;
            p.join(j);
            v.add(p.version());
            p.setTeam(4, 2);
            v.add(p.version());
            p.setScore(4, 1, 1, Msg.Score.STATUS_DEAD);
            v.add(p.version());
            int slot = p.slotOf(4);
            int flags = p.flags(slot);
            ShipShape shape = p.shape(slot);
            int status = p.status(slot);
            p.leave(4);
            v.add(p.version());
            assertAll(
                () -> assertEquals(List.of(0, 1, 2, 3, 4), v, "join, team, score and leave each add one"),
                () -> assertEquals(Msg.PlayerJoin.FLAG_ROBOT, flags, "join flags"),
                () -> assertSame(ShipShape.defaultShip(), shape, "default shape"),
                () -> assertEquals(Msg.Score.STATUS_DEAD, status, "status"));
        }
    }

    @Nested
    @DisplayName("Scoreboard details")
    class ScoreMore {
        Msg.PlayerJoin join(int id, int team) {
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = id;
            j.team = team;
            j.name.append("p").append(id);
            return j;
        }

        List<String> rows(Scoreboard sb) {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < sb.rowCount(); i++) {
                l.add(sb.label(i).toString() + "@" + sb.rowLine(i));
            }
            return l;
        }

        @Test
        @DisplayName("insertion keeps descending order; best uses score/(life+1); rebuilding is idempotent")
        void orderAndBest() {
            Players p = new Players();
            for (int i = 1; i <= 4; i++) {
                p.join(join(i, i % 2));
            }
            p.setScore(1, 1, 0, 0);
            p.setScore(2, 4, 0, 0);
            p.setScore(3, 9, 8, 0);
            p.setScore(4, 6, 1, 0);
            Scoreboard sb = new Scoreboard();
            sb.configure(true, false, false, 0);
            sb.setTeamScore(0, 3);
            sb.setTeamScore(1, 7);
            sb.setTeamScore(10, 99);
            sb.setTeamScore(-1, 99);
            sb.rebuild(p);
            List<String> first = rows(sb);
            sb.rebuild(p);
            assertAll(
                () -> assertEquals(List.of("  1     9  p3@0", "  0     6  p4@1", "  0     4  p2@2", "  1     1  p1@3",
                    "  1     7  Team 1@5", "  0     3  Team 0@6"), first, "players by score, then teams on lines 5, 6"),
                () -> assertEquals(first, rows(sb), "a second rebuild gives the same rows (team sums reset)"),
                () -> assertEquals(2, sb.bestRow(), "p2 has the best score/(life+1) = 4"),
                () -> assertEquals(0f, sb.teamScore(9), "out-of-range team scores are ignored"));
        }

        @Test
        @DisplayName("teams are inserted into score order regardless of team number")
        void teamInsertion() {
            Players p = new Players();
            for (int t = 0; t < 3; t++) {
                p.join(join(t + 1, t));
            }
            Scoreboard sb = new Scoreboard();
            sb.configure(true, false, false, 0);
            sb.setTeamScore(0, 1);
            sb.setTeamScore(1, 5);
            sb.setTeamScore(2, 3);
            sb.update(p);
            assertEquals(List.of("  1     5  Team 1@4", "  2     3  Team 2@5", "  0     1  Team 0@6"),
                rows(sb).subList(3, 6), "5, 3, 1");
        }

        @Test
        @DisplayName("team ordering by score first; dead team in limited lives; no players: no best")
        void teams() {
            Players p = new Players();
            p.join(join(1, 0));
            p.join(join(2, 1));
            p.join(join(3, 2));
            p.join(join(4, 12));
            p.setScore(1, 0, 0, Msg.Score.STATUS_OUT_OF_LIVES);
            p.setScore(2, 0, 2, 0);
            p.setScore(3, 0, 2, 0);
            Scoreboard sb = new Scoreboard();
            sb.configure(true, false, true, 0);
            sb.setTeamScore(0, 5);
            sb.setTeamScore(1, 2);
            sb.setTeamScore(2, 2);
            sb.update(p);
            List<String> teamRows = rows(sb).subList(4, rows(sb).size());
            Scoreboard empty = new Scoreboard();
            empty.update(new Players());
            Scoreboard reconfigured = new Scoreboard();
            reconfigured.setTeamScore(3, 4);
            reconfigured.configure(true, false, false, 1);
            assertAll(
                () -> assertEquals(List.of("D 0     5   0  Team 0@5", "  1     2   3  Team 1@6", "  2     2   3  Team 2@7"),
                    teamRows, "higher score first; equal scores keep team order; out-of-lives team is D; team 12 ignored"),
                () -> assertEquals(List.of(true, false, false), List.of(sb.dimmed(4), sb.dimmed(5), sb.dimmed(6)),
                    "the dead (D) team row is dimmed like a dead player (C client.c:1407)"),
                () -> assertEquals(-1, empty.bestRow(), "no players: no best row"),
                () -> assertEquals(0, empty.rowCount(), "no rows"),
                () -> assertEquals(0f, reconfigured.teamScore(3), "configure forgets team scores"));
        }
    }

    @Nested
    @DisplayName("small models")
    class Small {
        @Test
        @DisplayName("ScoreObjects: C formats, HUD text rules, positions, ageing past SCORE_OBJECT_COUNT, clear")
        void scoreObjects() {
            ScoreObjects so = new ScoreObjects();
            so.add(2.25f, "Robby", true, 3, 4);
            so.add(-1.5f, "", false, 0, 0);
            so.setDecimals(0);
            so.add(2.5f, null, false, 0, 0);
            so.add(3.5f, "x", false, 0, 0);
            List<String> huds = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                huds.add(so.hudText(i).toString());
            }
            so.tick(99);
            boolean aliveAt100 = so.active(0);
            double aliveCount = so.count(0);
            so.tick(1);
            boolean aliveAt101 = so.active(0);
            ScoreObjects c = new ScoreObjects();
            c.add(1, "a", false, 0, 0);
            c.clear();
            assertAll(
                () -> assertEquals(List.of("Robby 2.2", "", "2", "x 4"), huds,
                    "msg + %.1f (printf ties to even); empty msg: no HUD text (C); decimals 0: %d of rint; "
                        + "unknown reason: points only"),
                () -> assertEquals("2.2", so.text(0).toString(), "game-area text: the points"),
                () -> assertTrue(so.hasPosition(0), "with a place"),
                () -> assertEquals(List.of(3, 4), List.of(so.blockX(0), so.blockY(0)), "at its block"),
                () -> assertEquals("-1.5", so.text(1).toString(), "an empty msg keeps the game-area text"),
                () -> assertTrue(aliveAt100, "count 100 is still alive"),
                () -> assertEquals(100.0, aliveCount, 1e-9, "count started at 1 and aged 99 frames"),
                () -> assertFalse(aliveAt101, "count 101 > SCORE_OBJECT_COUNT dies"),
                () -> assertEquals("", so.hudText(0).toString(), "and loses its HUD text"),
                () -> assertFalse(c.active(0), "clear forgets objects"),
                () -> assertEquals(0, c.hudOrder(0), "and restarts the ring"));
        }

        @Test
        @DisplayName("GameEvents with an odd capacity index the ring after it wrapped")
        void eventsOdd() {
            GameEvents ev = new GameEvents(3);
            Msg.GameEvent e = new Msg.GameEvent();
            for (int i = 1; i <= 4; i++) {
                e.a = i;
                e.b = 10 * i;
                e.value = i;
                ev.add(e);
            }
            assertAll(
                () -> assertEquals(2, ev.a(0), "oldest held a"),
                () -> assertEquals(30, ev.b(1), "middle b"),
                () -> assertEquals(4f, ev.value(2), "newest value"));
        }

        @Test
        @DisplayName("GameEvents accessors index the ring after it wrapped")
        void eventsWrapped() {
            GameEvents ev = new GameEvents(2);
            Msg.GameEvent e = new Msg.GameEvent();
            for (int i = 1; i <= 3; i++) {
                e.a = i;
                e.b = 10 * i;
                e.value = i;
                ev.add(e);
            }
            assertAll(
                () -> assertEquals(2, ev.a(0), "oldest held a"),
                () -> assertEquals(3, ev.a(1), "newest a"),
                () -> assertEquals(20, ev.b(0), "oldest held b"),
                () -> assertEquals(30, ev.b(1), "newest b"),
                () -> assertEquals(2f, ev.value(0), "oldest held value"),
                () -> assertEquals(3f, ev.value(1), "newest value"));
        }

        @Test
        @DisplayName("TickClock: not synced at first; a sample exactly RESYNC_TICKS away is smoothed")
        void clock() {
            TickClock c = new TickClock();
            boolean before = c.synced();
            c.reset(60);
            c.sample(0, 0);
            c.sample(30, 0);
            assertAll(
                () -> assertFalse(before, "not synced before samples"),
                () -> assertEquals(3.0, c.serverTick(0), 1e-9, "30 ticks off: smoothed by 10%"));
        }

        @Test
        @DisplayName("ShipIndex: put/get, overwrite, clear, misses and collisions in a full table")
        void shipIndex() {
            ShipIndex ix = new ShipIndex(4);
            ix.put(5, 0);
            ix.put(6, 1);
            ix.put(5, 2);
            int five = ix.get(5);
            int six = ix.get(6);
            int missing = ix.get(7);
            ix.clear();
            int afterClear = ix.get(5);
            ShipIndex full = new ShipIndex(4);
            for (int i = 0; i < 4; i++) {
                full.put(1000 + i * full.tableSize(), i);
            }
            assertAll(
                () -> assertEquals(2, five, "a repeated id is overwritten"),
                () -> assertEquals(1, six, "another id"),
                () -> assertEquals(-1, missing, "absent id"),
                () -> assertEquals(-1, afterClear, "clear forgets everything"),
                () -> assertEquals(8, full.tableSize(), "table is a power of two >= 2 x capacity"),
                () -> assertEquals(3, full.get(1000 + 3 * full.tableSize()), "colliding keys probe onward"),
                () -> assertEquals(0, full.get(1000), "the first colliding key is still found"),
                () -> assertEquals(-1, full.get(999), "a miss terminates in a full-capacity table"));
        }

        @Test
        @DisplayName("ParticlePool: y motion, world-edge boundaries, accessors")
        void particles() {
            ParticlePool p = new ParticlePool(8);
            DebrisBurst d = new DebrisBurst();
            d.count = 1;
            d.minLife = 99;
            d.maxLife = 99;
            float[][] spots = {{0, 0}, {699.5f, 349.5f}, {700, 10}, {10, 350}, {-0.5f, 10}, {10, -0.5f}};
            for (float[] s : spots) {
                d.x = s[0];
                d.y = s[1];
                p.spawn(d, new Rng(0), 0, 0, 0);
            }
            d.x = 100;
            d.y = 100;
            d.vy = 2;
            p.spawn(d, new Rng(0), 0, 0, 0);
            p.step(8, 0.25f, 700, 350, false);
            List<String> kept = new ArrayList<>();
            for (int i = 0; i < p.count(); i++) {
                kept.add(p.x(i) + "," + p.y(i));
            }
            assertAll(
                () -> assertEquals(List.of("0.0,0.0", "699.5,349.5", "100.0,104.0"), kept,
                    "inside points stay, x=W, y=H and negatives leave; y moves by vy * age"),
                () -> assertEquals(8, p.capacity(), "capacity"));
        }

        @Test
        @DisplayName("ThrustSparkEmitter: hypotenuse and last bursts")
        void emitter() {
            ThrustSparkEmitter em = new ThrustSparkEmitter();
            em.configure(60, 14, 300, 400, false);
            em.emit(1, 1, 10, 10, 0, 0, 0, 45, 3, -9, 0, false, new ParticlePool(100));
            assertAll(
                () -> assertEquals(500, em.hypotenuse(), "3-4-5 diagonal"),
                () -> assertEquals(500f, em.lastNormal().hypotenuse, "normal burst uses it"),
                () -> assertTrue(em.lastAlt().count > 0, "afterburners fill the alt burst"));
        }

        @Test
        @DisplayName("MessageLog: ' <' alone is talk; empty is not; flat accessors")
        void messages() {
            MessageLog log = new MessageLog();
            log.add(Msg.Text.KIND_GAME, "bang");
            log.add(Msg.Text.KIND_GAME, "boom");
            log.add(Msg.Text.KIND_CHAT, "hi");
            boolean fresh = log.flashing(MessageLog.GAME, 0);
            log.tick(140);
            assertAll(
                () -> assertTrue(MessageLog.isTalk(Msg.Text.KIND_SERVER, " <"), "two-char ' <' is talk"),
                () -> assertFalse(MessageLog.isTalk(Msg.Text.KIND_SERVER, ""), "empty is not talk"),
                () -> assertFalse(MessageLog.isTalk(Msg.Text.KIND_SERVER, " "), "a single blank is not talk"),
                () -> assertEquals(1, log.count(MessageLog.TALK), "talk count"),
                () -> assertEquals(2, log.count(MessageLog.GAME), "game count"),
                () -> assertTrue(fresh, "a new message flashes"),
                () -> assertEquals("hi", log.text(MessageLog.TALK, 0).toString(), "talk text"),
                () -> assertEquals("boom", log.text(MessageLog.GAME, 0).toString(), "game text"),
                () -> assertEquals(140.0, log.age(MessageLog.GAME, 0), 1e-9, "age in frames"),
                () -> assertFalse(log.flashing(MessageLog.TALK, 0), "no longer flashing after 140 frames"),
                () -> assertEquals(2, log.visibleChars(MessageLog.TALK, 0, 14), "fully typed"));
        }
    }

    @Nested
    @DisplayName("ClientGame wiring")
    class GameWiring {
        @Test
        @DisplayName("WELCOME fallbacks, scoreboard mode from config, snapshot and reliable counters")
        void welcome() {
            WorldFixture f = new WorldFixture(true);
            f.options.setValue("limitedLives", "yes", true, com.fulcrologic.jxpilot.common.config.OptionOrigin.MAP);
            ClientGame g = new ClientGame();
            Msg.Welcome w = new Msg.Welcome();
            w.flags = Msg.Welcome.FLAG_TEAM_PLAY | Msg.Welcome.FLAG_TIMING;
            w.serverName.append("srv");
            g.onWelcome(w);
            g.onConfig(w, f.options, f.map);
            f.begin(100).buf.flip();
            g.onSnapshot(f.buf, WorldFixture.at(100));
            g.frame(WorldFixture.at(100), 0);
            ClientGame plain = new ClientGame();
            Msg.Welcome w2 = new Msg.Welcome();
            w2.simHz = 50;
            plain.onWelcome(w2);
            assertAll(
                () -> assertEquals(60, g.simHz(), "simHz 0 falls back to 60"),
                () -> assertEquals(60, g.snapshotHz(), "snapshotHz 0 falls back to simHz (snapshot divisor 1)"),
                () -> assertEquals(14f, g.gameFps(), "gameFps 0 falls back to 14"),
                () -> assertEquals(" TM   SCORE  LIFE NAME", g.scoreboard().heading().toString(),
                    "team play and limited lives reach the scoreboard"),
                () -> assertEquals(1, g.snapshotCount(), "snapshot counted"),
                () -> assertTrue(g.world().hasSnapshot(), "snapshot ingested"),
                () -> assertEquals("srv", g.serverName().toString(), "server name"),
                () -> assertSame(f.options, g.options(), "options kept"),
                () -> assertSame(f.map, g.map(), "map kept"),
                () -> assertTrue(g.teamPlay(), "team play"),
                () -> assertFalse(plain.teamPlay(), "no team play flag"),
                () -> assertEquals(50, plain.snapshotHz(), "snapshotHz falls back to simHz (snapshot divisor 1)"));
        }

        @Test
        @DisplayName("a new session clears events and the world's snapshots")
        void accepted() {
            ClientGame g = ClientGameTest.configured();
            WorldFixture f = new WorldFixture(true);
            f.begin(100).buf.flip();
            g.onSnapshot(f.buf, WorldFixture.at(100));
            Msg.GameEvent ev = new Msg.GameEvent();
            ev.kind = Msg.GameEvent.CRASH;
            g.onReliable(ReliableType.GAME_EVENT, ClientGameTest.payload(ev));
            g.onAccepted(9);
            assertAll(
                () -> assertFalse(g.world().hasSnapshot(), "world reset"),
                () -> assertEquals(0, g.events().size(), "events cleared"));
        }

        @Test
        @DisplayName("only closing or losing a session logs the status; frames age messages and rebuild the scoreboard")
        void statusAndFrame() {
            ClientGame g = ClientGameTest.configured();
            g.onStateChange(ConnectionState.CONNECTING, "Connecting");
            g.onStateChange(ConnectionState.JOINING, "Joining");
            g.onStateChange(ConnectionState.CONNECTING, "lost while joining");
            g.onStateChange(ConnectionState.PLAYING, "Playing");
            int logged = g.messages().gameCount();
            g.onReliable(ReliableType.PLAYER_JOIN, ClientGameTest.payload(ClientGameTest.join(7, 0, "me")));
            g.onReliable(ReliableType.SCORE, ClientGameTest.payload(ClientGameTest.score(7, 0.5f)));
            g.onReliable(ReliableType.SCORE, ClientGameTest.payload(ClientGameTest.score(7, 1)));
            g.frame(WorldFixture.at(100), 99.0 / 15);
            boolean aliveAt99 = g.scoreObjects().active(0);
            g.frame(WorldFixture.at(100), 1.0 / 15);
            boolean aliveAt100 = g.scoreObjects().active(0);
            assertAll(
                () -> assertEquals(1, logged, "only the loss while joining was logged"),
                () -> assertEquals("lost while joining", g.messages().game(0).text().toString(), "its text"),
                () -> assertEquals(2, g.scoreboard().rowCount(), "the frame rebuilt the scoreboard: the player and team 0"),
                () -> assertTrue(aliveAt99, "the score object lives 99 frames later (count 100)"),
                () -> assertFalse(aliveAt100, "and ends after exactly 100 frames (count 101 > SCORE_OBJECT_COUNT)"),
                () -> assertEquals(1024 - 100.0, g.messages().game(0).life(), 1e-9, "messages aged 100 frames"));
        }
    }
}
