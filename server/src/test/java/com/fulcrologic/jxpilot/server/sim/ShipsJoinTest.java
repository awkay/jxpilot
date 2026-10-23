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
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ShipFx.C_FRAMES;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.ROOM;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.events;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ships: join (Init_player + Pick_startpos + Go_home), leave, pickStartPos, goHome (player.c, netserver.c). */
class ShipsJoinTest {
    /** Three free bases on one row: indices 0, 1, 2 at bx 2, 5, 8 (C order is x-major). */
    static final String[] THREE = {
        "xxxxxxxxxxx",
        "x         x",
        "x         x",
        "x _  _  _ x",
        "x         x",
        "xxxxxxxxxxx",
    };
    /** Team play: team 1 has bases at bx 2 and 4, team 2 one base at bx 8. */
    static final String[] TEAMS = {
        "xxxxxxxxxxx",
        "x         x",
        "x 1 1   2 x",
        "x         x",
        "xxxxxxxxxxx",
    };

    @Nested
    @DisplayName("join")
    class Join {
        @Test
        void aJoiningPlayerIsInitialisedAsCInitPlayerAndPlacedOnItsBase() {
            Simulation s = sim(C_FRAMES, ROOM);
            World w = s.world;
            Player p = s.ships.join("ann", 3);
            assertNotNull(p);
            // C Init_player / Go_home: base (5,5) centre is pixel 192.5 -> clicks (int)(192.5*64)
            assertAll(
                () -> assertEquals("ann", p.name.toString(), "the name is copied"),
                () -> assertEquals(1, p.id, "the first player gets id 1"),
                () -> assertEquals(Const.TEAM_NOT_SET, p.team, "outside team play the team request is ignored"),
                () -> assertEquals(0, p.homeBase, "the only base is home"),
                () -> assertEquals(12320, p.cx, "x is the base block centre in clicks"),
                () -> assertEquals(12320, p.cy, "y is the base block centre in clicks"),
                () -> assertEquals(32, p.dir, "faces the base direction (up)"),
                () -> assertEquals(1000 * 256.0, p.fuel, "fuel is initialFuel in fixed point"),
                () -> assertEquals(2600 * 256.0, p.fuelMax, "the tank holds MAX_PLAYER_FUEL"),
                () -> assertEquals(20 + 1000 * 256.0 * 0.005 / 256, p.mass, "mass is ship mass plus FUEL_MASS"),
                () -> assertEquals(45.0, p.power, "power 45"),
                () -> assertEquals(30.0, p.turnSpeed, "turnspeed 30"),
                () -> assertEquals(0.12, p.turnResistance, "turnresistance 0.12"),
                () -> assertEquals(35.0, p.powerS, "alternate power 35"),
                () -> assertEquals(25.0, p.turnSpeedS, "alternate turnspeed 25"),
                () -> assertEquals(0.12, p.turnResistanceS, "alternate turnresistance 0.12"),
                () -> assertEquals(-1.0, p.count, "no countdown running"),
                () -> assertEquals(Obj.PLAYING | Obj.GRAVITY, p.status, "PLAYING with gravity"),
                () -> assertEquals(w.params.defHave, p.have, "abilities are DEF_HAVE"),
                () -> assertEquals(Has.SHIELD | Has.COMPASS, p.used, "shields and compass are in use (DEF_USED)"),
                () -> assertEquals(256, p.shotMax, "shot max is maxPlayerShots"),
                () -> assertTrue(p.scoreDirty, "a SCORE event is due"),
                () -> assertEquals(1, w.numPlayers, "the player is in the game"));
        }

        @Test
        void joiningEmitsJoinBaseOwnerAndSpawnThenAScoreOnTheNextTick() {
            Simulation s = sim(C_FRAMES, ROOM);
            Player p = s.ships.join("ann", 0);
            List<String> joined = events(s.world, 0);
            s.tick(new long[1]);
            assertAll(
                () -> assertEquals(List.of("JOIN 1 0 0", "BASE_OWNER 0 1 0", "GAME_EVENT " + Msg.GameEvent.SPAWN + " 1 0"),
                    joined, "the join, the base owner, then the spawn (clients learn the id before it spawns)"),
                () -> assertEquals("SCORE 1 -1 0 0.0", events(s.world, 3).get(0),
                    "the tick reports score 0, unlimited lives, alive"),
                () -> assertFalse(p.scoreDirty, "the score is reported once"));
        }

        @Test
        void aFullWorldRefusesThePlayer() {
            Simulation s = sim(C_FRAMES, ROOM);
            s.ships.join("ann", 0);
            long seq = s.world.events.seq();
            Player late = s.ships.join("bob", 0);
            assertAll(
                () -> assertNull(late, "one slot per base: the second player is refused"),
                () -> assertEquals(1, s.world.numPlayers, "the game still has one player"),
                () -> assertEquals(seq, s.world.events.seq(), "nothing is announced"));
        }

        @Test
        void initialFuelAboveTheTankIsCappedAndInitialItemsAreGiven() {
            Simulation s = sim(C_FRAMES + "initialFuel: 3000\ninitialArmor: 2\ninitialMines: 3\n", ROOM);
            Player p = s.ships.join("ann", 0);
            // C Player_init_fuel: MIN(fuel, TANK_CAP(0)); Player_set_mass: armor ShipMass/14, others minItemMass
            assertAll(
                () -> assertEquals(2600 * 256.0, p.fuel, "fuel is capped at MAX_PLAYER_FUEL"),
                () -> assertEquals(2, p.items[Item.ARMOR.ordinal()], "initial armor is given"),
                () -> assertEquals(3, p.items[Item.MINE.ordinal()], "initial mines are given"),
                () -> assertEquals(20 + 2600 * 256.0 * 0.005 / 256 + 2 * (20.0 / 14) + 3 * 1.0, p.mass,
                    1e-12, "mass counts fuel, armor and other items (rounding only)"),
                () -> assertTrue((p.have & Has.ARMOR) != 0, "initial armor grants HAS_ARMOR"));
        }

        @Test
        void withLimitedLivesAPlayerJoiningAfterSomeoneLostALifeIsGameOver() {
            Simulation s = sim(C_FRAMES + "limitedLives: yes\nworldLives: 3\n", THREE);
            Player a = s.ships.join("ann", 0);
            Player early = s.ships.join("bob", 0);
            a.life = 2;
            Player late = s.ships.join("cid", 0);
            assertAll(
                () -> assertEquals(3, early.life, "a player joining before anyone died gets worldLives"),
                () -> assertEquals(0, early.status & Obj.GAME_OVER, "and plays"),
                () -> assertEquals(0, late.life, "a late joiner has no lives (C Init_player too_late)"),
                () -> assertEquals(Obj.GAME_OVER, late.status & Obj.GAME_OVER, "and waits as GAME_OVER"));
        }

        @Test
        void inTeamPlayAValidTeamIsKeptAndABaselessOrInvalidOneGetsTheTeamWithMostFreeBases() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\n", TEAMS);
            World w = s.world;
            Player two = s.ships.join("two", 2);
            Player none = s.ships.join("none", 7);
            Player bad = s.ships.join("bad", -5);
            assertAll(
                () -> assertEquals(2, two.team, "team 2 has a free base: the request is kept"),
                () -> assertEquals(2, two.homeBase, "and the player gets team 2's base"),
                () -> assertEquals(1, none.team, "team 7 has no base: team 1 (two free bases) is picked"),
                () -> assertEquals(1, bad.team, "an invalid team is replaced by the team with most free bases"),
                () -> assertEquals(2, w.teamMembers[1], "team 1 counts its two members"),
                () -> assertEquals(1, w.teamMembers[2], "team 2 counts its member"));
        }

        @Test
        void withTeamShareScoreTheFirstMemberResetsTheTeamScoreAndEveryJoinSharesItOut() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\nteamShareScore: yes\n", TEAMS);
            World w = s.world;
            w.teamScore[1] = 50;
            Player a = s.ships.join("a", 1);
            w.teamScore[1] = 30;
            Player b = s.ships.join("b", 1);
            // netserver.c Handle_login: NumMembers == 1 -> score = 0; TEAM_SCORE(team, 0) shares score / members
            assertAll(
                () -> assertEquals(15.0, a.score, "the earlier member gets half of the team score"),
                () -> assertEquals(15.0, b.score, "the new member gets half of the team score"),
                () -> assertEquals(30.0, w.teamScore[1], "a later join keeps the team score"),
                () -> assertTrue(w.teamScoreDirty[1], "the team score is reported"));
        }

        @Test
        void withoutTeamShareScoreJoiningLeavesTheTeamScoreAlone() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\n", TEAMS);
            s.world.teamScore[1] = 50;
            Player a = s.ships.join("a", 1);
            assertAll(
                () -> assertEquals(50.0, s.world.teamScore[1], "the team score is kept"),
                () -> assertEquals(0.0, a.score, "the player starts at 0"),
                () -> assertFalse(s.world.teamScoreDirty[1], "no team score is reported"));
        }
    }

    @Nested
    @DisplayName("leave")
    class Leave {
        @Test
        void leavingDisownsObjectsForgetsShovesFreesTheIdAndAnnounces() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\n", TEAMS);
            World w = s.world;
            Player a = s.ships.join("a", 1);
            Player b = s.ships.join("b", 2);
            int mine = w.objects.alloc();
            w.objects.owner[mine] = a.id;
            int theirs = w.objects.alloc();
            w.objects.owner[theirs] = b.id;
            s.ships.recordShove(b, a);
            s.ships.recordShove(b, b);
            long seq = w.events.seq();
            s.ships.leave(a);
            Player again = s.ships.join("c", 1);
            assertAll(
                () -> assertEquals(Obj.NO_ID, w.objects.owner[mine], "the leaver's objects lose their owner"),
                () -> assertEquals(b.id, w.objects.owner[theirs], "other players' objects keep theirs"),
                () -> assertEquals(Obj.NO_ID, b.shovePusher[0], "shoves by the leaver are forgotten"),
                () -> assertEquals(b.id, b.shovePusher[1], "other shoves are kept"),
                () -> assertEquals("LEAVE 1 0 0", events(w, seq).get(0), "LEAVE is announced with the id"),
                () -> assertEquals(1, w.teamMembers[1], "team 1 lost a member and gained the new one"),
                () -> assertEquals(1, again.id, "the freed id 1 is reused"),
                () -> assertTrue(a == again && again.inUse, "the freed slot is reused for the new player"));
        }

        @Test
        void leavingTwiceDoesNothingTheSecondTime() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\n", TEAMS);
            Player a = s.ships.join("a", 1);
            s.ships.leave(a);
            long seq = s.world.events.seq();
            s.ships.leave(a);
            assertAll(
                () -> assertEquals(seq, s.world.events.seq(), "no second LEAVE"),
                () -> assertEquals(0, s.world.teamMembers[1], "the team count is not decremented again"));
        }
    }

    @Nested
    @DisplayName("pickStartPos")
    class PickStartPos {
        @Test
        void aRandomFreeBaseIsPickedWithTheSeededRng() {
            for (int seed = 1; seed <= 6; seed++) {
                Simulation s = sim(C_FRAMES, THREE);
                s.world.rng.seed(seed);
                int pick = (int) (new Rng(seed).nextFloat() * 3);
                Player p = s.ships.join("a", 0);
                int expected = pick;
                int sd = seed;
                assertEquals(expected, p.homeBase, "C Pick_startpos: the (int)(rfrac*numFree)-th free base, seed " + sd);
            }
        }

        @Test
        void takenBasesAreSkipped() {
            Simulation s = sim(C_FRAMES, THREE);
            Player a = s.ships.join("a", 0);
            Player b = s.ships.join("b", 0);
            a.homeBase = 0;
            b.homeBase = 2;
            Player c = s.ships.join("c", 0);
            boolean again = s.ships.pickStartPos(a);
            assertAll(
                () -> assertEquals(1, c.homeBase, "the only free base is taken"),
                () -> assertTrue(again, "a player can re-pick: its own base counts as free"),
                () -> assertEquals(0, a.homeBase, "and its own base is the only free one"));
        }

        @Test
        void noFreeBaseForTheTeamReturnsFalseAndKeepsTheHome() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\n", TEAMS);
            Player a = s.ships.join("a", 2);
            Player b = s.ships.join("b", 1);
            b.team = 2;
            b.homeBase = 0;
            assertAll(
                () -> assertFalse(s.ships.pickStartPos(b), "team 2's only base is taken by another player"),
                () -> assertEquals(0, b.homeBase, "the home base is unchanged"),
                () -> assertEquals(2, a.homeBase, "the owner keeps it"));
        }

        @Test
        void inRaceModeBasesAreTakenInBaseOrder() {
            // checkpoint A next to the right-most base: base order is by distance to checkpoint A
            Simulation s = sim(C_FRAMES + "timing: yes\n",
                "xxxxxxxxxxx",
                "x         x",
                "x _  _  _Ax",
                "x         x",
                "xxxxxxxxxxx");
            s.world.rng.seed(3);
            Player a = s.ships.join("a", 0);
            Player b = s.ships.join("b", 0);
            Player c = s.ships.join("c", 0);
            assertAll(
                () -> assertTrue(s.world.map.timing(), "race mode is on (a checkpoint exists)"),
                () -> assertEquals(2, a.homeBase, "first: the base closest to checkpoint A"),
                () -> assertEquals(1, b.homeBase, "second: the next closest"),
                () -> assertEquals(0, c.homeBase, "third: the farthest"));
        }
    }

    @Nested
    @DisplayName("goHome")
    class GoHome {
        @Test
        void goingHomeStopsTheShipOnItsBaseAndResetsControls() {
            Simulation s = sim(C_FRAMES, THREE);
            World w = s.world;
            Player p = s.ships.join("a", 0);
            p.homeBase = 2;
            p.setClicks(5000, 5000);
            p.vx = 3;
            p.vy = -4;
            p.velocity = 5;
            p.accX = 1;
            p.accY = 1;
            p.turnAcc = 4;
            p.turnVel = 2;
            p.prevKeys = 0xFF;
            p.dir = 5;
            p.used = Has.REFUEL | Has.SHOT | Has.COMPASS;
            p.status |= Obj.THRUSTING;
            long seq = w.events.seq();
            s.ships.goHome(p);
            // base 2 is block (8, 2): centre pixel (297.5, 87.5)
            assertAll(
                () -> assertEquals((int) (297.5 * 64), p.cx, "x on the base centre"),
                () -> assertEquals((int) (87.5 * 64), p.cy, "y on the base centre"),
                () -> assertEquals(297, p.prevX, "the previous position is the base too"),
                () -> assertEquals(32, p.dir, "facing the base direction"),
                () -> assertEquals(32.0, p.floatDir, "float heading too"),
                () -> assertEquals(0.0, p.vx, "at rest (x)"),
                () -> assertEquals(0.0, p.vy, "at rest (y)"),
                () -> assertEquals(0.0, p.velocity, "speed 0"),
                () -> assertEquals(0.0, p.accX + p.accY, "no acceleration"),
                () -> assertEquals(0.0, p.turnAcc, "no turn acceleration"),
                () -> assertEquals(0.0, p.turnVel, "no turn velocity"),
                () -> assertEquals(0L, p.prevKeys, "key state is forgotten (held keys re-trigger)"),
                () -> assertEquals(Has.COMPASS | Has.SHIELD, p.used, "USED_KILL abilities off, shields raised"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "not thrusting"),
                () -> assertEquals(List.of("GAME_EVENT " + Msg.GameEvent.SPAWN + " " + p.id + " 2"), events(w, seq),
                    "a SPAWN event names the player and base"));
        }

        @Test
        void withoutPlayerStartsShieldedNoShieldIsRaised() {
            Simulation s = sim(C_FRAMES + "playerStartsShielded: no\n", ROOM);
            Player p = s.ships.join("a", 0);
            p.used = Has.COMPASS;
            s.ships.goHome(p);
            assertAll(
                () -> assertEquals(Has.COMPASS, p.used, "shields stay down"),
                () -> assertEquals(0.0, p.shieldTime, "no shield timer"));
        }

        @Test
        void withoutAllowShieldsAStartShieldLasts2SecondsOfFrames() {
            Simulation s = sim(C_FRAMES + "allowShields: no\n", ROOM);
            Player p = s.ships.join("a", 0);
            assertAll(
                () -> assertEquals(0, s.world.params.defHave & Has.SHIELD, "shields are not a default ability"),
                () -> assertEquals(Has.SHIELD, p.used & Has.SHIELD, "the start shield is up"),
                () -> assertEquals(Has.SHIELD, p.have & Has.SHIELD, "and temporarily owned"),
                () -> assertEquals(120.0, p.shieldTime, "for 2*FPS frames (C Go_home)"));
        }
    }

    @Nested
    @DisplayName("team choice (Handle_login + Pick_team)")
    class TeamChoice {
        /** Teams 1, 2 and 3 with two bases each. */
        static final String[] SIX = {"xxxxxxxxxxxxxxx", "x1 1 2 2 3 3  x", "xxxxxxxxxxxxxxx"};
        /** Team 0 and team 1, one base each. */
        static final String[] ZERO_ONE = {"xxxxxxx", "x0  1 x", "xxxxxxx"};

        @Test
        void withOneTeamPlayingAnEmptyTeamIsPickedAndTiesGoToTheLowestTeam() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\n", SIX);
            Player a = s.ships.join("a", 1);
            Player b = s.ships.join("b", 99);
            assertAll(
                () -> assertEquals(1, a.team, "a valid request is kept"),
                () -> assertEquals(2, b.team, "only team 1 plays: the empty teams 2 and 3 are candidates, 2 wins the tie"));
        }

        @Test
        void withSeveralTeamsPlayingTheSmallestTeamWithTheLowestTruncatedScoreIsPicked() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\n", SIX);
            Player a = s.ships.join("a", 1);
            Player b = s.ships.join("b", 2);
            a.score = 0.9;
            b.score = 0.5;
            Player c = s.ships.join("c", 99);
            b.score = -1.5;
            Player d = s.ships.join("d", 99);
            // server.c Pick_team: least_players = 1 (teams 1 and 2); team 3 (0 players) is not a candidate;
            // team_score is a long: 0.9 and 0.5 both truncate to 0, so the first candidate (team 1) wins
            assertAll(
                () -> assertEquals(1, c.team, "equal truncated scores: the lower team"),
                () -> assertEquals(2, d.team, "team 2 now has fewer players than team 1 (1 vs 2)"));
        }

        @Test
        void pausedPlayersDoNotMakeTheirTeamPlaying() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\n", SIX);
            s.ships.join("a", 1);
            Player b = s.ships.join("b", 2);
            b.status |= Obj.PAUSE;
            Player c = s.ships.join("c", 99);
            assertEquals(2, c.team, "only team 1 plays: empty-looking team 2 wins the tie with team 3");
        }

        @Test
        void aFullTeamRequestIsReplaced() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\n", SIX);
            s.ships.join("a", 2);
            s.ships.join("b", 2);
            Player c = s.ships.join("c", 2);
            assertEquals(1, c.team, "team 2's two bases are used: Pick_team gives the other empty team 1");
        }

        @Test
        void theRobotTeamIsReservedForRobotsByDefault() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\n", ZERO_ONE);
            Player a = s.ships.join("a", 0);
            Player b = s.ships.join("b", 1);
            Simulation open = sim(C_FRAMES + "teamPlay: yes\nreserveRobotTeam: no\n", ZERO_ONE);
            Player c = open.ships.join("c", 0);
            assertAll(
                () -> assertEquals(1, a.team, "a request for robot team 0 is replaced"),
                () -> assertEquals(null, b, "with team 1 full and team 0 reserved, no team can be picked"),
                () -> assertEquals(1, s.world.numPlayers, "the refused player is removed again"),
                () -> assertEquals(0, c.team, "without reserveRobotTeam team 0 can be requested"));
        }

        @Test
        void amongSeveralCandidatesTheRobotTeamIsNeverPicked() {
            Simulation s = sim(C_FRAMES + "teamPlay: yes\nreserveRobotTeam: no\n", ZERO_ONE);
            Player a = s.ships.join("a", 99);
            Simulation other = sim(C_FRAMES + "teamPlay: yes\nreserveRobotTeam: no\nrobotTeam: 5\n", ZERO_ONE);
            Player b = other.ships.join("b", 99);
            assertAll(
                () -> assertEquals(1, a.team, "teams 0 and 1 tie, but robotTeam 0 is skipped"),
                () -> assertEquals(0, b.team, "with robotTeam 5 the tie goes to team 0"));
        }
    }

    @Nested
    @DisplayName("pickTeam (server.c Pick_team, PickForHuman)")
    class PickTeam {
        /** Bases: team 0: 1, team 1: 3, team 2: 2, team 3: 2, team 4: 1 (9 slots). */
        static final String[] MANY = {"xxxxxxxxxxxxxxxxxxxxx", "x0 1 1 1 2 2 3 3 4  x", "xxxxxxxxxxxxxxxxxxxxx"};

        Simulation s;

        Simulation world(String extra) {
            s = sim(C_FRAMES + "teamPlay: yes\n" + extra, MANY);
            return s;
        }

        /** A player of {@code team} counted as a member (C NumMembers) without a base. */
        Player member(int team, double score) {
            Player p = s.world.addPlayer();
            p.team = team;
            p.status = Obj.PLAYING;
            p.score = score;
            s.world.teamMembers[team]++;
            return p;
        }

        @Test
        void withNobodyPlayingTheLowestFreeNonRobotTeamIsPicked() {
            world("");
            int t = s.ships.pickTeam(null);
            s.world.teamMembers[1] = 3;
            int full1 = s.ships.pickTeam(null);
            assertAll(
                () -> assertEquals(1, t, "teams 1-4 tie at score 0; team 0 is the reserved robot team"),
                () -> assertEquals(2, full1, "free bases = bases - members: a full team 1 is skipped"));
        }

        @Test
        void theReservedRobotTeamHasNoFreeBases() {
            world("");
            for (int t = 1; t <= 4; t++) {
                s.world.teamMembers[t] = s.world.map.teamBaseCount(t);
            }
            int reserved = s.ships.pickTeam(null);
            s.world.params.reserveRobotTeam = false;
            int open = s.ships.pickTeam(null);
            s.world.params.reserveRobotTeam = true;
            s.world.params.robotTeam = Const.MAX_TEAMS;
            int outOfRange = s.ships.pickTeam(null);
            s.world.params.robotTeam = -1;
            int negative = s.ships.pickTeam(null);
            assertAll(
                () -> assertEquals(Const.TEAM_NOT_SET, reserved, "only robot team 0 has room: no team"),
                () -> assertEquals(0, open, "not reserved: the single candidate 0 is picked"),
                () -> assertEquals(0, outOfRange, "an out-of-range robotTeam reserves nothing"),
                () -> assertEquals(0, negative, "a negative robotTeam reserves nothing"));
        }

        @Test
        void withOnePlayingTeamWithRoomButNoEmptyTeamTheFallbackPicksIt() {
            world("");
            member(1, 0);
            for (int t = 2; t <= 4; t++) {
                s.world.teamMembers[t] = s.world.map.teamBaseCount(t);
            }
            assertEquals(1, s.ships.pickTeam(null), "no empty team has room: any team with room (1)");
        }

        @Test
        void withTwoPlayingTeamsTheSmallestWithRoomIsTheOnlyCandidate() {
            world("");
            member(1, 10);
            member(2, 0);
            member(2, 0);
            // least_players = 1 (team 1 has room; team 2 is full); empty teams 3, 4 are not candidates
            assertEquals(1, s.ships.pickTeam(null), "team 1 despite its higher score");
        }

        @Test
        void whenNoPlayingTeamHasRoomEveryTeamWithRoomIsACandidate() {
            world("");
            member(4, 0);
            member(2, 0);
            member(2, 0);
            Player paused = member(1, 0);
            paused.status |= Obj.PAUSE;
            // playing: team 4 (full) and team 2 (full); team 1 has a paused member (not playing, 2 free)
            // least_players stays NumPlayers: candidates 1 and 3 by score
            s.world.teamMembers[3] = 0;
            assertEquals(1, s.ships.pickTeam(null), "teams 1 and 3 tie: 1");
        }

        @Test
        void amongCandidatesTheLowestTruncatedScoreSumWins() {
            world("");
            member(1, 10);
            member(2, -10);
            member(3, 4.9);
            member(3, -5.8);
            // equal team sizes (1, 1, 2): least = 1 -> candidates 1 and 2; sums 10 and -10
            int low = s.ships.pickTeam(null);
            s.world.playerAt(1).score = 20;
            int other = s.ships.pickTeam(null);
            assertAll(
                () -> assertEquals(2, low, "team 2 has the lowest score"),
                () -> assertEquals(1, other, "after team 2 gains, team 1 is lowest"));
        }

        @Test
        void aFullPlayingTeamDoesNotSetTheFewestPlayers() {
            world("");
            member(4, 0);
            member(1, 10);
            member(1, 10);
            // team 4 (1 player) is full, so least_players = 2 (team 1, one base free): team 1 is the only
            // candidate although teams 2 and 3 have lower scores
            assertEquals(1, s.ships.pickTeam(null), "least_players counts only teams with free bases");
        }

        @Test
        void aFullTeamWithTheFewestPlayersIsNotACandidate() {
            world("");
            member(4, 0);
            member(1, 10);
            // least_players = 1 (team 1); team 4 also has 1 player but no free base
            assertEquals(1, s.ships.pickTeam(null), "team 1 is the only candidate");
        }

        @Test
        void onlyThisCallsCandidatesAreConsidered() {
            world("");
            int first = s.ships.pickTeam(null); // candidates 1, 2, 3, 4
            member(1, 10);
            member(2, 5);
            member(3, 0);
            member(3, 0);
            assertAll(
                () -> assertEquals(1, first, "the first pick"),
                () -> assertEquals(2, s.ships.pickTeam(null),
                    "candidates 1 and 2 (one player each): 2 has the lower score; team 3 is not a candidate"));
        }

        @Test
        void theJoiningPlayerItselfIsNotCounted() {
            world("");
            Player j = s.world.addPlayer();
            j.team = 3;
            j.status = Obj.PLAYING;
            member(2, 0);
            assertAll(
                () -> assertEquals(1, s.ships.pickTeam(j), "without j only team 2 plays: empty team 1 is picked"),
                () -> assertEquals(2, s.ships.pickTeam(null), "counting j, teams 2 and 3 play: 2 wins the tie"));
        }

        @Test
        void anOutOfRangeTeamRequestIsReplaced() {
            world("");
            Player p = s.ships.join("a", Const.MAX_TEAMS);
            assertEquals(1, p.team, "team 10 does not exist");
        }
    }

    @Nested
    @DisplayName("slot reuse")
    class Reuse {
        @Test
        void aReusedSlotStartsClean() {
            Simulation s = sim(C_FRAMES, THREE);
            Player a = s.ships.join("alice", 0);
            Player b = s.ships.join("bob", 0);
            Player c = s.ships.join("cid", 0);
            s.ships.recordShove(a, b);
            s.ships.recordShove(a, c);
            s.ships.recordShove(a, c);
            s.ships.recordShove(a, b);
            a.score = 9;
            s.ships.leave(a);
            Player d = s.ships.join("dan", 0);
            assertAll(
                () -> assertEquals(a, d, "the slot is reused"),
                () -> assertEquals("dan", d.name.toString(), "the old name is replaced"),
                () -> assertEquals(java.util.List.of(Obj.NO_ID, Obj.NO_ID, Obj.NO_ID, Obj.NO_ID),
                    java.util.List.of(d.shovePusher[0], d.shovePusher[1], d.shovePusher[2], d.shovePusher[3]),
                    "no shoves are remembered"),
                () -> assertEquals(0.0, d.score, "the score starts at 0"),
                () -> assertEquals(3, s.world.numPlayers, "a full world again"));
        }

        @Test
        void leavingAFullWorldForgetsTheLeaversShovesOnEveryone() {
            Simulation s = sim(C_FRAMES, THREE);
            Player a = s.ships.join("a", 0);
            Player b = s.ships.join("b", 0);
            Player c = s.ships.join("c", 0);
            s.ships.recordShove(c, a);
            s.ships.leave(a);
            assertAll(
                () -> assertEquals(Obj.NO_ID, c.shovePusher[0], "the last player in order is updated too"),
                () -> assertEquals(2, s.world.numPlayers, "two remain"),
                () -> assertEquals(b, s.world.playerAt(0), "in order"));
        }
    }
}
