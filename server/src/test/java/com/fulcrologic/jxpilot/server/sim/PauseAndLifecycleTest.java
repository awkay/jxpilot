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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.ShipGeom;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ShipFx.C_FRAMES;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.events;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.keys;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.tick;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.ticks;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pause (Pause_player, KEY_PAUSE, idle pause, maxPauseTime), the reserve/spawn split, Delete_player's object
 * cleanup, client settings, and key state across Go_home.
 */
@DisplayName("Pause and player lifecycle")
class PauseAndLifecycleTest {
    static final long PAUSE = keys(ControlKey.PAUSE);
    static final long THRUST = keys(ControlKey.THRUST);
    /** Two bases far apart in an open room (gravity 0). */
    static final String[] TWO = {
        "xxxxxxxxxxxxxxxx",
        "x              x",
        "x              x",
        "x  _       _   x",
        "x              x",
        "xxxxxxxxxxxxxxxx",
    };

    static Simulation sim(String extra) {
        return TestWorlds.sim(C_FRAMES + extra, TWO);
    }

    @Nested
    @DisplayName("pause key")
    class PauseKey {
        @Test
        @DisplayName("on the home base, slow: pause for at least 10*FPS frames; then the key unpauses and goes home")
        void pauseAndUnpause() {
            Simulation s = sim("");
            World w = s.world;
            Player p = TestWorlds.join(s, "a", 0);
            tick(s, p, PAUSE);
            int paused = p.status & (Obj.PAUSE | Obj.PLAYING);
            int count = p.count;
            char ch = p.mychar;
            tick(s, p, 0);
            tick(s, p, PAUSE);
            int early = p.status & Obj.PAUSE;
            ticks(s, p, 60 * 10, 0);
            int afterCount = p.status & (Obj.PAUSE | Obj.PLAYING);
            p.vx = 3;
            tick(s, p, PAUSE);
            assertAll(
                () -> assertEquals(Obj.PAUSE, paused, "PAUSE on, PLAYING off"),
                () -> assertEquals(10 * 60 - 1, count, "the pause lasts at least 10*FPS frames (one counted already)"),
                () -> assertEquals('P', ch, "C mychar 'P'"),
                () -> assertEquals(Obj.PAUSE, early, "unpausing before the count ran out does nothing"),
                () -> assertEquals(Obj.PAUSE | Obj.PLAYING, afterCount,
                    "when the count runs out C sets PLAYING (still paused, at home)"),
                () -> assertEquals(Obj.PLAYING, p.status & (Obj.PAUSE | Obj.PLAYING), "the key then unpauses"),
                () -> assertEquals(' ', p.mychar, "mychar ' '"),
                () -> assertEquals(0.0f, p.vx, "and Go_home puts the ship at rest on its base"),
                () -> assertEquals(ControlKey.PAUSE.mask(), p.prevKeys,
                    "key handling stops at the unpause with only PAUSE handled (C returns early)"));
        }

        @Test
        @DisplayName("off the home base or faster than 3 + |gravity| the key does nothing")
        void refused() {
            Simulation s = sim("gravity: -1\n");
            Player p = TestWorlds.join(s, "a", 0);
            p.velocity = 4.01f;
            s.keys.apply(p, PAUSE);
            int fast = p.status & Obj.PAUSE;
            s.keys.apply(p, 0);
            p.velocity = 4.0f;
            s.keys.apply(p, PAUSE);
            int limit = p.status & Obj.PAUSE;
            Simulation o = sim("");
            Player q = TestWorlds.join(o, "b", 0);
            q.initPixels(q.px + 35, q.py);
            o.keys.apply(q, PAUSE);
            assertAll(
                () -> assertEquals(0, fast, "speed 4.01 > 3 + 1: refused"),
                () -> assertEquals(Obj.PAUSE, limit, "speed 4.0 is allowed"),
                () -> assertEquals(0, q.status & Obj.PAUSE, "one block off the home base: refused (hover pause is M2)"));
        }

        @Test
        @DisplayName("unpausing a ship that is not paused, or with full-lives opponents, changes nothing / is in time")
        void unpauseEdges() {
            Simulation s = sim("limitedLives: yes\nworldLives: 3\n");
            Player p = TestWorlds.join(s, "a", 0);
            Player q = TestWorlds.join(s, "b", 0);
            p.count = 0;
            p.initPixels(p.px + 35, p.py);
            int px = p.px;
            s.ships.pause(p, false);
            s.ships.pause(q, true);
            q.count = 0;
            s.ships.pause(q, false);
            assertAll(
                () -> assertEquals(px, p.px, "not paused: no Go_home"),
                () -> assertEquals(Obj.PLAYING, p.status & (Obj.PLAYING | Obj.PAUSE), "still playing"),
                () -> assertEquals(Obj.PLAYING, q.status & (Obj.PLAYING | Obj.GAME_OVER), "an opponent with all lives: in time"),
                () -> assertEquals(' ', q.mychar, "mychar ' '"));
        }

        @Test
        @DisplayName("an unpausing press ends key handling; a refused or pausing press lets the rest of the mask act")
        void restOfMask() {
            long both = PAUSE | THRUST;
            Simulation s = sim("");
            Player p = TestWorlds.join(s, "a", 0);
            s.ships.pause(p, true);
            p.count = 0;
            s.keys.apply(p, both);
            long unpaused = p.prevKeys;
            int unpausedThrust = p.status & Obj.THRUSTING;
            Simulation off = sim("maxPauseTime: 0\n");
            Player q = TestWorlds.join(off, "b", 0);
            q.initPixels(q.px + 35, q.py);
            off.keys.apply(q, both);
            Simulation fast = sim("");
            Player r = TestWorlds.join(fast, "c", 0);
            r.velocity = 10;
            fast.keys.apply(r, both);
            Simulation pausing = sim("");
            Player t = TestWorlds.join(pausing, "d", 0);
            pausing.keys.apply(t, both);
            assertAll(
                () -> assertEquals(PAUSE, unpaused, "unpause: only PAUSE is handled"),
                () -> assertEquals(0, unpausedThrust, "and THRUST in the same mask is not"),
                () -> assertEquals(both, q.prevKeys, "off base: the whole mask is handled"),
                () -> assertEquals(Obj.THRUSTING, q.status & Obj.THRUSTING, "THRUST acts"),
                () -> assertEquals(both, r.prevKeys, "too fast: the whole mask is handled"),
                () -> assertEquals(Obj.THRUSTING, r.status & Obj.THRUSTING, "THRUST acts"),
                () -> assertEquals(Obj.PAUSE, t.status & Obj.PAUSE, "(t paused)"),
                () -> assertEquals(both, t.prevKeys, "pausing does not stop key handling"),
                () -> assertEquals(0, t.status & Obj.THRUSTING, "but a paused ship ignores THRUST"));
        }

        @Test
        @DisplayName("any key change except SHIELD marks the player busy (frame_last_busy)")
        void busy() {
            Simulation s = sim("");
            World w = s.world;
            Player p = TestWorlds.join(s, "a", 0);
            p.frameLastBusy = 0;
            w.frames = 500;
            s.keys.apply(p, keys(ControlKey.SHIELD));
            long shield = p.frameLastBusy;
            s.keys.apply(p, keys(ControlKey.SHIELD, ControlKey.THRUST));
            assertAll(
                () -> assertEquals(0, shield, "SHIELD alone: not busy"),
                () -> assertEquals(500, p.frameLastBusy, "THRUST: busy now"));
        }

        @Test
        @DisplayName("a paused ship slowly loses score, reported every 64th frame, and is shown paused, not moving")
        void pausedScore() {
            Simulation s = sim("");
            World w = s.world;
            Player p = TestWorlds.join(s, "a", 0);
            s.ships.pause(p, true);
            p.count = -1;
            s.emitScores();
            w.frames = 63;
            s.ships.update(p);
            boolean dirty63 = p.scoreDirty;
            w.frames = 64;
            s.ships.update(p);
            assertAll(
                () -> assertEquals(-0.02f, p.score, 1e-12, "0.01 per frame"),
                () -> assertFalse(dirty63, "not reported at frame 63"),
                () -> assertTrue(p.scoreDirty, "reported at frame 64 (C frame_loops & 63)"),
                () -> assertEquals(Msg.Score.STATUS_DEAD | Msg.Score.STATUS_PAUSED, Simulation.scoreStatus(p),
                    "SCORE status: paused (and not playing)"));
        }

        @Test
        @DisplayName("unpausing with limited lives after a non-team player lost a life: too late, waits ('W')")
        void tooLate() {
            Simulation s = sim("limitedLives: yes\nworldLives: 3\n");
            Player p = TestWorlds.join(s, "a", 0);
            Player q = TestWorlds.join(s, "b", 0);
            s.ships.pause(p, true);
            p.count = 0;
            q.life = 2;
            s.ships.pause(p, false);
            Simulation ok = sim("limitedLives: yes\nworldLives: 3\n");
            Player r = TestWorlds.join(ok, "c", 0);
            ok.ships.pause(r, true);
            r.count = 0;
            r.life = 1;
            ok.ships.pause(r, false);
            assertAll(
                () -> assertEquals('W', p.mychar, "C mychar 'W'"),
                () -> assertEquals(0, p.life, "no lives"),
                () -> assertEquals(Obj.GAME_OVER, p.status & (Obj.GAME_OVER | Obj.PAUSE | Obj.PLAYING),
                    "GAME_OVER, not paused, not playing"),
                () -> assertEquals(3, r.life, "in time: full lives again"),
                () -> assertEquals(Obj.PLAYING, r.status & (Obj.PLAYING | Obj.GAME_OVER), "and playing"));
        }
    }

    @Nested
    @DisplayName("idle pause and maxPauseTime (update.c kill loop)")
    class Idle {
        @Test
        @DisplayName("a human killed after more than 60*FPS idle frames is paused, when another human plays")
        void idlePause() {
            Simulation s = sim("");
            World w = s.world;
            Player p = TestWorlds.join(s, "a", 0);
            Player q = TestWorlds.join(s, "b", 0);
            p.frameLastBusy = w.frames - 60 * 60;
            p.status |= Obj.KILLED;
            s.killPlayers();
            int notYet = p.status & Obj.PAUSE;
            p.frameLastBusy = w.frames - 60 * 60 - 1;
            p.status |= Obj.KILLED;
            s.killPlayers();
            Simulation alone = sim("");
            Player r = TestWorlds.join(alone, "c", 0);
            r.frameLastBusy = -100000;
            r.status |= Obj.KILLED;
            alone.killPlayers();
            assertAll(
                () -> assertEquals(0, notYet, "exactly 60*FPS idle frames: not yet"),
                () -> assertEquals(Obj.PAUSE, p.status & Obj.PAUSE, "more: paused"),
                () -> assertEquals(0, q.status & Obj.PAUSE, "the busy player is not"),
                () -> assertEquals(0, r.status & Obj.PAUSE, "a lone human is never idle-paused"));
        }

        @Test
        @DisplayName("a human paused longer than maxPauseTime is kicked with a server notice (LEAVE)")
        void kick() {
            Simulation s = sim("maxPauseTime: 2\n");
            World w = s.world;
            Player p = TestWorlds.join(s, "a", 0);
            int id = p.id;
            s.ships.pause(p, true);
            p.frameLastBusy = w.frames - 120;
            long seq = w.events.seq();
            s.killPlayers();
            boolean still = p.inUse;
            p.frameLastBusy = w.frames - 121;
            s.killPlayers();
            assertAll(
                () -> assertEquals(120, w.params.maxPauseTime, "maxPauseTime is seconds * FPS frames"),
                () -> assertTrue(still, "exactly maxPauseTime: stays"),
                () -> assertFalse(p.inUse, "longer: removed"),
                () -> assertEquals(List.of("a was auto-kicked for pausing too long [*Server notice*]"),
                    messages(w, seq), "announced"),
                () -> assertTrue(events(w, seq).contains("LEAVE " + id + " 0 0"), "LEAVE tells the network layer"));
        }

        @Test
        @DisplayName("maxPauseTime only kicks paused players, and only when set; an idle player that is playing stays")
        void kickOnlyPaused() {
            Simulation s = sim("maxPauseTime: 2\n");
            World w = s.world;
            Player p = TestWorlds.join(s, "a", 0);
            p.frameLastBusy = w.frames - 10000;
            s.killPlayers();
            Simulation off = sim("maxPauseTime: 0\n");
            Player q = TestWorlds.join(off, "b", 0);
            off.ships.pause(q, true);
            q.frameLastBusy = off.world.frames - 10000;
            off.killPlayers();
            assertAll(
                () -> assertTrue(p.inUse, "not paused: stays"),
                () -> assertEquals(0, off.world.params.maxPauseTime, "(fixture: maxPauseTime 0 = off)"),
                () -> assertTrue(q.inUse, "maxPauseTime 0: never kicked"));
        }
    }

    @Nested
    @DisplayName("reserve and spawn (READY gating)")
    class ReserveSpawn {
        @Test
        @DisplayName("a reserved player has an id but is not in the game; spawn puts it in; names are clamped Latin-1")
        void split() {
            Simulation s = sim("");
            World w = s.world;
            Player p = s.ships.reserve("Abcdefghijklmnopqrstu€");
            int before = w.numPlayers;
            long seq = w.events.seq();
            s.tick(new long[w.players.length]);
            List<String> whileReserved = events(w, seq);
            boolean spawned = s.ships.spawn(p, 0);
            boolean again = s.ships.spawn(p, 0);
            assertAll(
                () -> assertEquals(1, p.id, "an id is reserved"),
                () -> assertEquals(0, before, "not in the game"),
                () -> assertEquals(List.of(), whileReserved, "nothing is simulated or announced for it"),
                () -> assertEquals("Abcdefghijklmnop", p.name.toString(), "the name is cut to MAX_NAME_LEN (16)"),
                () -> assertTrue(spawned, "spawn succeeds"),
                () -> assertFalse(again, "spawning twice fails"),
                () -> assertEquals(1, w.numPlayers, "it is in the game once"),
                () -> assertEquals(p, w.byId(1), "by its id"));
        }

        @Test
        @DisplayName("non-Latin-1 name chars become '?'; a full world refuses a reservation; leaving a reservation frees it")
        void edges() {
            Simulation s = sim("");
            World w = s.world;
            Player a = s.ships.reserve("x€y");
            Player b = s.ships.reserve("b");
            Player c = s.ships.reserve("c");
            long seq = w.events.seq();
            s.ships.leave(b);
            Player d = s.ships.reserve("d");
            assertAll(
                () -> assertEquals("x?y", a.name.toString(), "a char above 255 is '?'"),
                () -> assertNull(c, "two bases: the third reservation is refused"),
                () -> assertEquals(seq, w.events.seq(), "leaving a reservation emits nothing"),
                () -> assertEquals(b, d, "the slot is free again"));
        }

        @Test
        @DisplayName("I9: a reused slot starts with the default ship (C Init_player with an empty client shape)")
        void shipReset() {
            Simulation s = sim("");
            Player p = TestWorlds.join(s, "a", 0);
            p.ship = new ShipGeom(ShipShape.parse("(SH: 10,0 0,10 -10,0 0,-10)(EN: -10,0)(MG: 10,0)"));
            s.ships.leave(p);
            Player q = TestWorlds.join(s, "b", 0);
            assertEquals(ShipGeom.defaultShip(), q.ship, "Init_player resets the ship shape");
        }
    }

    @Nested
    @DisplayName("Delete_player object cleanup and team share")
    class Leave {
        int obj(World w, int type, int owner) {
            Objects o = w.objects;
            int h = o.alloc();
            o.type[h] = type;
            o.owner[h] = owner;
            o.life[h] = 10;
            o.mass[h] = 1;
            return h;
        }

        @Test
        @DisplayName("owned shots die (cannon shots massless), debris/sparks are disowned, every cannon shot dies")
        void cleanup() {
            Simulation s = sim("");
            World w = s.world;
            Player p = TestWorlds.join(s, "a", 0);
            Player q = TestWorlds.join(s, "b", 0);
            int shot = obj(w, Obj.SHOT, p.id);
            int spark = obj(w, Obj.SPARK, p.id);
            int debris = obj(w, Obj.DEBRIS, p.id);
            int ownCannon = obj(w, Obj.CANNON_SHOT, p.id);
            int otherCannon = obj(w, Obj.CANNON_SHOT, Obj.NO_ID);
            int otherShot = obj(w, Obj.SHOT, q.id);
            s.ships.leave(p);
            Objects o = w.objects;
            assertAll(
                () -> assertEquals(0.0, o.life[shot], "the leaver's shot dies"),
                () -> assertEquals(Obj.NO_ID, o.owner[shot], "and is disowned"),
                () -> assertEquals(1.0f, o.mass[shot], "a plain shot keeps its mass"),
                () -> assertEquals(10.0, o.life[spark], "sparks keep flying"),
                () -> assertEquals(Obj.NO_ID, o.owner[spark], "disowned"),
                () -> assertEquals(10.0, o.life[debris], "debris keeps flying"),
                () -> assertEquals(0.0f, o.mass[ownCannon], "its cannon shot loses its mass (no explosion)"),
                () -> assertEquals(0.0, o.life[otherCannon], "C quirk: every cannon shot dies when anyone leaves"),
                () -> assertEquals(0.0f, o.mass[otherCannon], "massless"),
                () -> assertEquals(10.0, o.life[otherShot], "another player's shot is untouched"),
                () -> assertEquals(q.id, o.owner[otherShot], "and keeps its owner"));
        }

        @Test
        @DisplayName("with keepShots the leaver's shots fly on, disowned, and cannon shots live")
        void keepShots() {
            Simulation s = sim("keepShots: yes\n");
            World w = s.world;
            Player p = TestWorlds.join(s, "a", 0);
            int shot = obj(w, Obj.SHOT, p.id);
            int cannon = obj(w, Obj.CANNON_SHOT, Obj.NO_ID);
            s.ships.leave(p);
            assertAll(
                () -> assertEquals(10.0, w.objects.life[shot], "the shot lives"),
                () -> assertEquals(Obj.NO_ID, w.objects.owner[shot], "disowned"),
                () -> assertEquals(10.0, w.objects.life[cannon], "cannon shots live"));
        }

        @Test
        @DisplayName("teamShareScore: the leaver's share is taken back from the team and the rest re-shared")
        void teamShare() {
            Simulation s = TestWorlds.sim(C_FRAMES + "teamPlay: yes\nteamShareScore: yes\n", "xxxxxxx", "x 1 1 x",
                "xxxxxxx");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 1);
            Player b = TestWorlds.join(s, "b", 1);
            s.scoring.teamScore(1, 30);
            s.ships.leave(b);
            assertAll(
                () -> assertEquals(15.0, w.teamScore[1], "30 - b's share of 15"),
                () -> assertEquals(15.0, a.score, "the remaining member holds the rest"),
                () -> assertEquals(1, w.teamMembers[1], "one member left"));
        }

        @Test
        @DisplayName("teamShareScore: C re-shares before the leaver leaves its place, missing the last player in order")
        void teamShareOrder() {
            Simulation s = TestWorlds.sim(C_FRAMES + "teamPlay: yes\nteamShareScore: yes\n", "xxxxxxxxx",
                "x 1 1 1 x", "xxxxxxxxx");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 1);
            Player b = TestWorlds.join(s, "b", 1);
            Player c = TestWorlds.join(s, "c", 1);
            s.scoring.teamScore(1, 30);
            a.score = 15;
            s.ships.leave(a);
            assertAll(
                () -> assertEquals(15.0, w.teamScore[1], "30 - a's 15"),
                () -> assertEquals(7.5, b.score, "b, inside C's shortened loop, gets the new share"),
                () -> assertEquals(10.0, c.score, "c, last in the order, keeps its old share (player.c:1765-1773)"));
        }
    }

    @Nested
    @DisplayName("settings, keys across Go_home, respawn score, team too-late")
    class Misc {
        @Test
        @DisplayName("Go_home removes SHIELD from have when shields are not allowed (and ships do not start shielded)")
        void goHomeWithoutShields() {
            Simulation s = sim("allowShields: no\nplayerStartsShielded: no\n");
            Player p = TestWorlds.join(s, "a", 0);
            p.have = s.world.params.defHave | Has.SHIELD;
            s.ships.goHome(p);
            assertEquals(s.world.params.defHave, p.have, "only SHIELD is removed");
        }

        @Test
        @DisplayName("without a shield timer running, an unused shield stays in have")
        void noShieldTimer() {
            Simulation s = sim("");
            Player p = TestWorlds.join(s, "a", 0);
            p.used &= ~Has.SHIELD;
            p.shieldTime = 0;
            s.ships.update(p);
            assertEquals(Has.SHIELD, p.have & Has.SHIELD, "still has the shield");
        }

        @Test
        @DisplayName("leaving releases home base 0 too; names keep Latin-1 up to 0xFF")
        void baseZeroAndNames() {
            Simulation s = sim("");
            World w = s.world;
            Player p = TestWorlds.join(s, "a", 0);
            Player q = TestWorlds.join(s, "b", 0);
            Player zero = p.homeBase == 0 ? p : q;
            long seq = w.events.seq();
            s.ships.leave(zero);
            Player r = s.ships.reserve("x\u00ff\u0100");
            assertAll(
                () -> assertEquals(0, zero.homeBase, "(fixture: base 0)"),
                () -> assertTrue(events(w, seq).contains("BASE_OWNER 0 " + Obj.NO_ID + " 0"), "base 0 is released"),
                () -> assertEquals("x\u00ff?", r.name.toString(), "0xFF is Latin-1, 0x100 is not"));
        }

        @Test
        @DisplayName("S2: applySettings assigns the six values; a power change emits THRUST")
        void settings() {
            Simulation s = sim("");
            World w = s.world;
            Player p = TestWorlds.join(s, "a", 0);
            long seq = w.events.seq();
            s.ships.applySettings(p, 50f, 40f, 0.2f, 30f, 20f, 0.1f);
            List<Float> six = List.of(p.power, p.turnSpeed, p.turnResistance, p.powerS, p.turnSpeedS, p.turnResistanceS);
            List<String> ev = events(w, seq);
            long seq2 = w.events.seq();
            s.ships.applySettings(p, 50f, 41f, 0.2f, 30f, 20f, 0.1f);
            assertAll(
                () -> assertEquals(List.of(50f, 40f, 0.2f, 30f, 20f, 0.1f), six, "all six are assigned"),
                () -> assertEquals(List.of("THRUST " + p.id + " 0 0"), ev, "a new power is announced"),
                () -> assertEquals(seq2, w.events.seq(), "an unchanged power is not"));
        }

        @Test
        @DisplayName("S9: after Go_home held keys are ignored until the client's key state changes")
        void keysAfterGoHome() {
            Simulation s = sim("");
            Player p = TestWorlds.join(s, "a", 0);
            p.used &= ~Has.SHIELD;
            tick(s, p, THRUST);
            p.status |= Obj.KILLED;
            tick(s, p, THRUST);
            ticks(s, p, 3 * 60 + 1, THRUST);
            int playingAfter = p.status & Obj.PLAYING;
            tick(s, p, THRUST);
            int heldAfterRespawn = p.status & Obj.THRUSTING;
            tick(s, p, THRUST | keys(ControlKey.SHIELD));
            assertAll(
                () -> assertEquals(Obj.PLAYING, playingAfter, "(respawned with thrust held)"),
                () -> assertEquals(0, heldAfterRespawn, "the held thrust does not act after Go_home"),
                () -> assertEquals(Obj.THRUSTING, p.status & Obj.THRUSTING,
                    "a key change makes every held key count as newly pressed"));
        }

        @Test
        @DisplayName("S4: a respawn marks the score dirty (the DEAD status bit changed)")
        void respawnScore() {
            Simulation s = sim("");
            Player p = TestWorlds.join(s, "a", 0);
            s.ships.kill(p);
            p.count = 0;
            p.scoreDirty = false;
            s.ships.update(p);
            assertAll(
                () -> assertEquals(Obj.PLAYING, p.status & Obj.PLAYING, "respawned"),
                () -> assertTrue(p.scoreDirty, "its SCORE (status) is resent"));
        }

        @Test
        @DisplayName("in team play the limited-lives check uses the slot's stale team, as C (player.c:505-521)")
        void teamTooLate() {
            Simulation s = TestWorlds.sim(C_FRAMES + "teamPlay: yes\nlimitedLives: yes\nworldLives: 3\n",
                "xxxxxxxxxxxxx", "x 1 1 1 1 2 x", "xxxxxxxxxxxxx");
            Player a = TestWorlds.join(s, "a", 1);
            a.life = 2;
            Player gone = TestWorlds.join(s, "gone", 1);
            s.ships.leave(gone);
            Player mate = TestWorlds.join(s, "mate", 1);
            Player mate2 = TestWorlds.join(s, "mate2", 1);
            Player foe = TestWorlds.join(s, "foe", 2);
            Simulation z = TestWorlds.sim(C_FRAMES + "teamPlay: yes\nlimitedLives: yes\nworldLives: 3\n"
                + "reserveRobotTeam: no\n", "xxxxxxx", "x 0 1 x", "xxxxxxx");
            Player zero = TestWorlds.join(z, "zero", 0);
            zero.life = 2;
            Player fresh = TestWorlds.join(z, "fresh", 1);
            assertAll(
                () -> assertEquals(3, mate.life, "the slot a team-1 player left: stale team 1, the joiner plays"),
                () -> assertEquals(' ', mate.mychar, "not waiting"),
                () -> assertEquals('W', mate2.mychar, "a never-used slot (stale team 0): too late even for a mate"),
                () -> assertEquals('W', foe.mychar, "and for the other team"),
                () -> assertEquals(1, mate2.team, "(mate2 is on team 1)"),
                () -> assertEquals(3, fresh.life, "stale team 0 matches a team-0 player who lost a life: plays"),
                () -> assertEquals(1, fresh.team, "(though fresh is on team 1)"),
            () -> assertEquals(0, zero.team, "(zero is on team 0)"));
        }
    }
}
