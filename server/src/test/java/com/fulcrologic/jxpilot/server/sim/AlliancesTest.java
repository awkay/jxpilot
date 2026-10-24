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

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Alliances: C alliance.c through {@link Alliances}, and where C applies ALLIANCE (immunity, scores, robots). */
@DisplayName("Alliances (C alliance.c)")
class AlliancesTest {
    static final String BASE = "framesPerSecond: 60\ngravity: 0\nmaxRobots: 0\n";
    /** Twelve free bases. */
    static final String[] ROOM = {
        "xxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "x                         x",
        "x _ _ _ _ _ _ _ _ _ _ _ _ x",
        "x                         x",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxx",
    };

    static Simulation sim(String extra) {
        return TestWorlds.sim(BASE + extra, ROOM);
    }

    /** Events since {@code from}: "ALLIANCE id alliance", "TO id: text" (one player) or "ALL: text". */
    static List<String> since(World w, long from) {
        List<String> out = new ArrayList<>();
        Events e = w.events;
        for (long q = from; q < e.seq(); q++) {
            int i = Events.index(q);
            if (e.kind[i] == Events.ALLIANCE) {
                out.add("ALLIANCE " + e.a[i] + " " + e.b[i]);
            } else if (e.kind[i] == Events.MESSAGE) {
                assertEquals(Msg.Text.KIND_SERVER, e.a[i], "alliance messages are server messages");
                out.add((e.c[i] == 0 ? "ALL" : "TO " + e.c[i]) + ":" + e.text[i]);
            }
        }
        return out;
    }

    static final class Fx {
        final Simulation s;
        final World w;
        final Alliances a;

        Fx(String extra) {
            s = sim(extra);
            w = s.world;
            a = s.alliances;
        }

        Player join(String name) {
            return TestWorlds.join(s, name, 0);
        }

        /** {@code inviter} invites, {@code invitee} accepts. */
        void ally(Player inviter, Player invitee) {
            a.invite(inviter, invitee);
            a.accept(invitee, inviter);
        }

        long mark() {
            return w.events.seq();
        }
    }

    @Nested
    @DisplayName("invitations")
    class Invitations {
        @Test
        @DisplayName("invite: nothing for oneself, an ally or a repeat; a mutual invitation is accepted; a new one cancels the old")
        void invite() {
            Fx f = new Fx("");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            Player cid = f.join("Cid");
            long m0 = f.mark();
            boolean self = f.a.invite(ann, ann);
            boolean first = f.a.invite(ann, bob);
            List<String> invited = since(f.w, m0);
            long m1 = f.mark();
            boolean repeat = f.a.invite(ann, bob);
            boolean other = f.a.invite(ann, cid);
            List<String> switched = since(f.w, m1);
            long m2 = f.mark();
            boolean mutual = f.a.invite(cid, ann);
            List<String> formed = since(f.w, m2);
            boolean ally = f.a.invite(ann, cid);
            assertAll(
                () -> assertFalse(self, "never oneself"),
                () -> assertTrue(first, "invited"),
                () -> assertEquals(List.of("TO " + bob.id + ": < Ann seeks an alliance with you >"), invited, "the invitation"),
                () -> assertFalse(repeat, "an invitation already made does nothing"),
                () -> assertTrue(other, "inviting another"),
                () -> assertEquals(List.of("TO " + bob.id + ": < Ann has cancelled the invitation for an alliance >",
                    "TO " + cid.id + ": < Ann seeks an alliance with you >"), switched, "cancels the old one first"),
                () -> assertTrue(mutual, "Cid inviting Ann accepts Ann's invitation"),
                () -> assertEquals(List.of("ALLIANCE " + cid.id + " 0", "ALLIANCE " + ann.id + " 0",
                    "TO " + cid.id + ": < You have formed an alliance with Ann >",
                    "TO " + ann.id + ": < You have formed an alliance with Cid >"), formed, "alliance 0"),
                () -> assertFalse(ally, "an ally cannot be invited"),
                () -> assertEquals(Obj.NO_ID, ann.invite, "no invitation left"));
        }

        @Test
        @DisplayName("cancel, refuse and refuse all: messages and counts")
        void refuse() {
            Fx f = new Fx("");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            Player cid = f.join("Cid");
            boolean cancelNone = f.a.cancelInvitation(ann);
            long m0 = f.mark();
            int noneToRefuse = f.a.refuseAll(bob);
            List<String> none = since(f.w, m0);
            f.a.invite(ann, bob);
            boolean notInvited = f.a.refuse(cid, ann);
            long m1 = f.mark();
            boolean refused = f.a.refuse(bob, ann);
            List<String> declined = since(f.w, m1);
            f.a.invite(ann, bob);
            f.a.invite(cid, bob);
            long m2 = f.mark();
            int two = f.a.refuseAll(bob);
            List<String> both = since(f.w, m2);
            f.a.invite(ann, bob);
            long m3 = f.mark();
            int one = f.a.refuseAll(bob);
            List<String> single = since(f.w, m3);
            assertAll(
                () -> assertFalse(cancelNone, "nothing to cancel"),
                () -> assertEquals(0, noneToRefuse, "nothing to refuse"),
                () -> assertEquals(List.of("TO " + bob.id + ": < You were not invited for any alliance >"), none, "said so"),
                () -> assertFalse(notInvited, "Cid was not invited"),
                () -> assertTrue(refused, "refused"),
                () -> assertEquals(List.of("TO " + ann.id + ": < Bob has declined your invitation for an alliance >"),
                    declined, "the inviter hears it"),
                () -> assertEquals(2, two, "two invitations"),
                () -> assertEquals(List.of("TO " + ann.id + ": < Bob has declined your invitation for an alliance >",
                    "TO " + cid.id + ": < Bob has declined your invitation for an alliance >",
                    "TO " + bob.id + ": < 2 invitations for alliances declined >"), both, "plural"),
                () -> assertEquals(1, one, "one"),
                () -> assertEquals("TO " + bob.id + ": < 1 invitation for an alliance declined >", single.get(1), "singular"),
                () -> assertEquals(Obj.NO_ID, ann.invite, "dropped"));
        }

        @Test
        @DisplayName("accept all says \"invitations for alliances\" even for one (C tests j > 0); none: not invited")
        void acceptAll() {
            Fx f = new Fx("");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            long m0 = f.mark();
            f.a.acceptAll(bob);
            List<String> none = since(f.w, m0);
            f.a.invite(ann, bob);
            long m1 = f.mark();
            int n = f.a.acceptAll(bob);
            List<String> got = since(f.w, m1);
            assertAll(
                () -> assertEquals(List.of("TO " + bob.id + ": < You were not invited for any alliance >"), none, "none"),
                () -> assertEquals(1, n, "one accepted"),
                () -> assertEquals("TO " + bob.id + ": < 1 invitations for alliances accepted >", got.get(got.size() - 1),
                    "C's plural"),
                () -> assertTrue(f.w.allied(ann, bob), "allied"));
        }

        @Test
        @DisplayName("an invitee who left gets no cancel message; its id may be reused by another player (C GetInd)")
        void stale() {
            Fx f = new Fx("");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            f.a.invite(ann, bob);
            int bobId = bob.id;
            f.s.ships.leave(bob);
            long m0 = f.mark();
            boolean cancelled = f.a.cancelInvitation(ann);
            assertAll(
                () -> assertTrue(cancelled, "the invitation is dropped"),
                () -> assertEquals(List.of(), since(f.w, m0), "nobody to tell"),
                () -> assertEquals(Obj.NO_ID, ann.invite, "cleared"),
                () -> assertTrue(bobId > 0, "Bob had an id"));
        }
    }

    @Nested
    @DisplayName("forming, joining, merging")
    class Forming {
        @Test
        @DisplayName("accepting joins the inviter's alliance, or the inviter joins the acceptor's, with C's messages")
        void join() {
            Fx f = new Fx("");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            Player cid = f.join("Cid");
            Player dan = f.join("Dan");
            f.ally(ann, bob);
            f.a.invite(ann, cid);
            long m0 = f.mark();
            f.a.accept(cid, ann);
            List<String> cidJoins = since(f.w, m0);
            f.a.invite(dan, bob);
            long m1 = f.mark();
            f.a.accept(bob, dan);
            List<String> danJoins = since(f.w, m1);
            assertAll(
                () -> assertEquals(List.of("TO " + ann.id + ": < Cid has joined your alliance >",
                    "TO " + bob.id + ": < Cid has joined your alliance >",
                    "TO " + cid.id + ": < You have joined Ann's alliance >", "ALLIANCE " + cid.id + " 0"), cidJoins,
                    "the members hear it first, then the joiner, then it is a member"),
                () -> assertEquals(List.of("TO " + ann.id + ": < Dan has joined your alliance >",
                    "TO " + bob.id + ": < Dan has joined your alliance >",
                    "TO " + cid.id + ": < Dan has joined your alliance >",
                    "TO " + dan.id + ": < You have joined Bob's alliance >", "ALLIANCE " + dan.id + " 0"), danJoins,
                    "an inviter outside alliances joins the acceptor's"),
                () -> assertEquals(4, f.a.memberCount(0), "four members"),
                () -> assertEquals(1, f.a.count(), "one alliance"));
        }

        @Test
        @DisplayName("two alliances merge into the inviter's; the emptied one is dissolved")
        void merge() {
            Fx f = new Fx("announceAlliances: yes\n");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            Player cid = f.join("Cid");
            Player dan = f.join("Dan");
            f.ally(ann, bob);
            f.ally(cid, dan);
            f.a.invite(ann, cid);
            long m0 = f.mark();
            f.a.accept(cid, ann);
            List<String> got = since(f.w, m0);
            assertAll(
                () -> assertEquals(List.of("ALLIANCE " + cid.id + " -1", "ALL: < Cid has joined alliance 0 >",
                    "ALLIANCE " + cid.id + " 0", "ALLIANCE " + dan.id + " -1", "ALL: < Dan has joined alliance 0 >",
                    "ALLIANCE " + dan.id + " 0", "ALL: < Alliance 1 has been dissolved >"), got,
                    "each member of alliance 1 moves with the announcement, then alliance 1 is dissolved"),
                () -> assertEquals(4, f.a.memberCount(0), "all four in alliance 0"),
                () -> assertEquals(1, f.a.count(), "one alliance left"),
                () -> assertEquals(0, f.a.memberCount(1), "alliance 1 is gone"));
        }

        @Test
        @DisplayName("announced: the formation goes to everyone with the alliance number; ids are the lowest free")
        void announced() {
            Fx f = new Fx("announceAlliances: yes\n");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            Player cid = f.join("Cid");
            Player dan = f.join("Dan");
            f.ally(ann, bob);
            f.a.invite(cid, dan);
            long m0 = f.mark();
            f.a.accept(dan, cid);
            List<String> got = since(f.w, m0);
            f.a.leave(ann);
            f.a.invite(ann, bob);
            f.a.accept(bob, ann);
            assertAll(
                () -> assertEquals(List.of("ALLIANCE " + dan.id + " 1", "ALLIANCE " + cid.id + " 1",
                    "ALL: < Dan and Cid have formed alliance 1 >"), got, "alliance 1"),
                () -> assertEquals(0, bob.alliance, "after alliance 0 dissolved, the next one is 0 again"));
        }

        @Test
        @DisplayName("no alliance is formed when all ten ids are in use")
        void full() {
            Fx f = new Fx("");
            Player[] p = new Player[12];
            for (int i = 0; i < 12; i++) {
                p[i] = f.join("P" + i);
            }
            for (int i = 0; i < 10; i++) {
                f.a.create(p[i], p[(i + 1) % 12] == p[i] ? p[11] : p[11]);
            }
            Fx g = new Fx("");
            Player[] q = new Player[12];
            for (int i = 0; i < 12; i++) {
                q[i] = g.join("Q" + i);
            }
            int made = 0;
            for (int i = 0; i + 1 < 12; i += 2) {
                made += g.a.create(q[i], q[i + 1]) ? 1 : 0;
            }
            for (int id = 0; id < 4; id++) {
                g.a.dissolve(id);
            }
            int remade = 0;
            for (int i = 0; i + 1 < 8; i += 2) {
                remade += g.a.create(q[i], q[i + 1]) ? 1 : 0;
            }
            long m0 = g.mark();
            g.a.invite(q[8], q[0]);
            boolean extra = g.a.accept(q[0], q[8]);
            final int madeF = made;
            final int remadeF = remade;
            assertAll(
                () -> assertEquals(6, madeF, "six alliances"),
                () -> assertEquals(4, remadeF, "ids freed and reused"),
                () -> assertTrue(extra, "joining an existing alliance needs no new id"));
        }
    }

    @Nested
    @DisplayName("review fixes")
    class ReviewFixes {
        @Test
        @DisplayName("H3: accepting a leftover invitation from an ally only drops it (C crashes)")
        void staleInvitationFromAlly() {
            Fx f = new Fx("");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            Player cid = f.join("Cid");
            f.ally(bob, cid);
            f.a.invite(ann, bob);
            f.a.invite(cid, ann);
            f.a.accept(ann, cid);
            long m = f.mark();
            boolean accepted = f.a.accept(bob, ann);
            assertAll(
                () -> assertFalse(accepted, "nothing to accept"),
                () -> assertEquals(Obj.NO_ID, ann.invite, "the invitation is dropped"),
                () -> assertEquals(3, f.a.memberCount(0), "alliance 0 keeps its three members"),
                () -> assertEquals(1, f.a.count(), "one alliance"),
                () -> assertEquals(List.of(), since(f.w, m), "no messages, no changes"));
        }
    }

    @Nested
    @DisplayName("return values and the alliance table")
    class Table {
        @Test
        @DisplayName("accept and leave report success; dissolving a middle alliance keeps the others findable")
        void returns() {
            Fx f = new Fx("");
            Player[] p = new Player[6];
            for (int i = 0; i < 6; i++) {
                p[i] = f.join("P" + i);
            }
            f.a.invite(p[0], p[1]);
            boolean created = f.a.accept(p[1], p[0]);
            f.ally(p[2], p[3]);
            f.ally(p[4], p[5]);
            f.a.invite(p[0], p[2]);
            boolean merged = f.a.accept(p[2], p[0]);
            boolean left = f.a.leave(p[1]);
            int id5 = p[5].alliance;
            assertAll(
                () -> assertTrue(created, "created"),
                () -> assertTrue(merged, "merged"),
                () -> assertTrue(left, "left"),
                () -> assertEquals(2, id5, "the third alliance is 2"),
                () -> assertEquals(2, f.a.memberCount(id5), "still found after alliance 1 was dissolved and 2 moved"),
                () -> assertEquals(3, f.a.memberCount(p[0].alliance), "P0, P2, P3"),
                () -> assertEquals(2, f.a.count(), "two alliances"));
        }
    }

    @Nested
    @DisplayName("leaving and dissolving")
    class Leaving {
        @Test
        @DisplayName("leave: not in one does nothing; a member leaves with messages; a pair dissolves")
        void leave() {
            Fx f = new Fx("");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            Player cid = f.join("Cid");
            boolean notIn = f.a.leave(ann);
            f.ally(ann, bob);
            f.a.invite(ann, cid);
            f.a.accept(cid, ann);
            long m0 = f.mark();
            f.a.leave(cid);
            List<String> three = since(f.w, m0);
            long m1 = f.mark();
            f.a.leave(ann);
            List<String> pair = since(f.w, m1);
            assertAll(
                () -> assertFalse(notIn, "not in an alliance"),
                () -> assertEquals(List.of("ALLIANCE " + cid.id + " -1", "TO " + ann.id + ": < Cid has left your alliance >",
                    "TO " + bob.id + ": < Cid has left your alliance >", "TO " + cid.id + ": < You have left the alliance >"),
                    three, "two stay allied"),
                () -> assertEquals(List.of("ALLIANCE " + ann.id + " -1", "TO " + bob.id + ": < Ann has left your alliance >",
                    "TO " + ann.id + ": < You have left the alliance >", "ALLIANCE " + bob.id + " -1",
                    "TO " + bob.id + ": < Your alliance has been dissolved >"), pair, "the last member is let go"),
                () -> assertEquals(0, f.a.count(), "no alliance left"));
        }

        @Test
        @DisplayName("announced: leaving and dissolving go to everyone; members get no dissolve message")
        void announced() {
            Fx f = new Fx("announceAlliances: yes\n");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            f.ally(ann, bob);
            long m0 = f.mark();
            f.a.leave(ann);
            assertEquals(List.of("ALLIANCE " + ann.id + " -1", "ALL: < Ann has left alliance 0 >", "ALLIANCE " + bob.id + " -1",
                "ALL: < Alliance 0 has been dissolved >"), since(f.w, m0), "public");
        }

        @Test
        @DisplayName("a player leaving the game leaves its alliance first (C Delete_player)")
        void leaveGame() {
            Fx f = new Fx("");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            f.ally(ann, bob);
            int annId = ann.id;
            long m0 = f.mark();
            f.s.ships.leave(ann);
            List<String> got = since(f.w, m0);
            assertAll(
                () -> assertEquals("ALLIANCE " + annId + " -1", got.get(0), "Ann leaves her alliance before the game"),
                () -> assertEquals(Player.ALLIANCE_NOT_SET, bob.alliance, "and it dissolves"),
                () -> assertEquals(0, f.a.count(), "none left"));
        }

        @Test
        @DisplayName("dissolveAll ends every alliance; rulesChanged follows allowAlliances outside team play")
        void rules() {
            Fx f = new Fx("");
            Player[] p = new Player[4];
            for (int i = 0; i < 4; i++) {
                p[i] = f.join("P" + i);
            }
            f.ally(p[0], p[1]);
            f.ally(p[2], p[3]);
            f.a.dissolveAll();
            boolean on = f.a.enabled;
            f.w.options.force(Opt.ALLOW_ALLIANCES, "no");
            f.a.rulesChanged();
            boolean off = f.a.enabled;
            Simulation team = TestWorlds.sim(BASE + "teamPlay: yes\n", "xxxxxx", "x 1 2x", "xxxxxx");
            assertAll(
                () -> assertEquals(0, f.a.count(), "all dissolved"),
                () -> assertEquals(Player.ALLIANCE_NOT_SET, p[3].alliance, "nobody allied"),
                () -> assertTrue(on, "allowed by default"),
                () -> assertFalse(off, "not after allowAlliances off"),
                () -> assertFalse(team.alliances.enabled, "never in team play"));
        }
    }

    @Nested
    @DisplayName("list")
    class ListMembers {
        @Test
        @DisplayName("not a member; humans then robots; announced prefix; long lists wrap after 80 characters")
        void list() {
            Fx f = new Fx("");
            Player ann = f.join("Ann");
            long m0 = f.mark();
            f.a.list(ann);
            List<String> none = since(f.w, m0);
            Player bot = f.s.ships.join("Robo", 0, true);
            Player bob = f.join("Bob");
            f.a.create(bot, ann);
            f.a.invite(ann, bob);
            f.a.accept(bob, ann);
            long m1 = f.mark();
            f.a.list(bob);
            List<String> mixed = since(f.w, m1);
            Fx g = new Fx("announceAlliances: yes\n");
            Player[] p = new Player[9];
            for (int i = 0; i < 9; i++) {
                p[i] = g.join("Player" + "ABCDEFGHI".charAt(i) + "xxxx");
            }
            g.a.create(p[0], p[1]);
            for (int i = 2; i < 9; i++) {
                g.a.join(p[i], p[0]);
            }
            long m2 = g.mark();
            g.a.list(p[0]);
            List<String> wrapped = since(g.w, m2);
            assertAll(
                () -> assertEquals(List.of("TO " + ann.id + ": < You are not a member of any alliance >"), none, "none"),
                () -> assertEquals(List.of("TO " + bob.id + ": < Your alliance: Ann, Bob, Robo >"), mixed,
                    "humans first, then robots"),
                () -> assertEquals(List.of(
                    "TO " + p[0].id + ": < Alliance 0:PlayerAxxxx, PlayerBxxxx, PlayerCxxxx, PlayerDxxxx, PlayerExxxx, "
                        + "PlayerFxxxx, >",
                    "TO " + p[0].id + ": <            PlayerGxxxx, PlayerHxxxx, PlayerIxxxx >"), wrapped,
                    "a line over 80 characters is sent with '>' and continues indented"));
        }
    }

    @Nested
    @DisplayName("effects (C ALLIANCE)")
    class Effects {
        @Test
        @DisplayName("allies are immune to each other with teamImmunity (Team_immune), not without")
        void immunity() {
            Fx f = new Fx("");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            Player cid = f.join("Cid");
            boolean before = f.w.teamImmune(ann.id, bob.id);
            f.ally(ann, bob);
            Fx g = new Fx("teamImmunity: no\n");
            Player x = g.join("X");
            Player y = g.join("Y");
            g.ally(x, y);
            assertAll(
                () -> assertFalse(before, "strangers"),
                () -> assertTrue(f.w.teamImmune(ann.id, bob.id), "allies"),
                () -> assertTrue(f.w.teamImmune(bob.id, ann.id), "both ways"),
                () -> assertFalse(f.w.teamImmune(ann.id, cid.id), "not with others"),
                () -> assertFalse(f.w.teamImmune(ann.id, ann.id), "never oneself"),
                () -> assertFalse(g.w.teamImmune(x.id, y.id), "teamImmunity off"),
                () -> assertTrue(f.w.allied(ann, bob), "ALLIANCE(ann, bob)"),
                () -> assertFalse(f.w.allied(cid, cid), "a player outside alliances is not its own ally"));
        }

        @Test
        @DisplayName("killing an ally costs both (Score_players); teamShareScore shares an ally's points (Alliance_score)")
        void scores() {
            Fx f = new Fx("");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            f.ally(ann, bob);
            f.s.scoring.scorePlayers(ann, 10, "x", bob, -10, "y");
            Fx g = new Fx("teamShareScore: yes\n");
            Player a = g.join("A");
            Player b = g.join("B");
            Player c = g.join("C");
            Player d = g.join("D");
            g.ally(a, b);
            g.a.invite(a, c);
            g.a.accept(c, a);
            g.s.scoring.score(a, 9, 0, 0, "");
            g.s.scoring.score(d, 9, 0, 0, "");
            assertAll(
                () -> assertEquals(-10.0, ann.score, "the winner loses the points"),
                () -> assertEquals(-10.0, bob.score, "the loser too"),
                () -> assertEquals(3.0, a.score, "a third"),
                () -> assertEquals(3.0, b.score, "a third"),
                () -> assertEquals(3.0, c.score, "a third"),
                () -> assertTrue(b.scoreDirty && c.scoreDirty, "every member's score is sent"),
                () -> assertEquals(9.0, d.score, "outside alliances: all of it"));
        }

        @Test
        @DisplayName("a robot killed by an ally does not declare war on it (robot.c:990)")
        void robotWar() {
            Fx f = new Fx("");
            Player human = f.join("Hum");
            Player bot = f.s.ships.join("Robo", 0, true);
            f.s.robots.robot();
            human.score = 1000;
            f.a.create(human, bot);
            f.s.robots.war(bot, human);
            assertEquals(Obj.NO_ID, f.s.robots.warOnPlayer(bot), "no war on an ally");
        }

        @Test
        @DisplayName("locks skip allies (Player_lock_closest), see them (lock visible, cloaking); ECM spares them")
        void locksEcmVisibility() {
            Fx f = new Fx("");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            Player cid = f.join("Cid");
            ann.initPixels(100, 100);
            bob.initPixels(130, 100);
            cid.initPixels(300, 100);
            f.ally(ann, bob);
            boolean locked = f.s.locks.lockClosest(ann, false);
            int target = ann.lockPlId;
            java.util.Arrays.fill(ann.canSee, false);
            boolean seesAlly = f.s.locks.sees(ann, bob);
            boolean seesStranger = f.s.locks.sees(ann, cid);
            bob.damaged = 0;
            cid.initPixels(140, 100);
            cid.damaged = 0;
            f.s.ecm.fireGeneral(ann, ann.team, 100, 100);
            assertAll(
                () -> assertTrue(locked, "a lock was taken"),
                () -> assertEquals(cid.id, target, "on the stranger, though the ally is closer"),
                () -> assertTrue(seesAlly, "an ally is always seen (frame.c:412, 1030)"),
                () -> assertFalse(seesStranger, "a stranger only when visible"),
                () -> assertEquals(0, bob.damaged, "ECM spares the ally (item.c:1133)"),
                () -> assertTrue(cid.damaged > 0, "not the stranger"));
        }

        @Test
        @DisplayName("a detached tank joins its owner's alliance silently, cannot be invited, and is not listed")
        void tanks() {
            Fx f = new Fx("initialTanks: 2\n");
            Player ann = f.join("Ann");
            Player bob = f.join("Bob");
            f.ally(ann, bob);
            long m0 = f.mark();
            f.s.tanks.detach(ann);
            Player tank = null;
            for (int k = 0; k < f.w.numPlayers; k++) {
                if (f.w.playerAt(k).tank) {
                    tank = f.w.playerAt(k);
                }
            }
            final Player t = tank;
            List<String> joined = since(f.w, m0);
            boolean invited = f.a.invite(bob, t);
            long m1 = f.mark();
            f.a.list(ann);
            List<String> listed = since(f.w, m1);
            assertAll(
                () -> assertEquals(ann.alliance, t.alliance, "in Ann's alliance"),
                () -> assertEquals(List.of("ALLIANCE " + t.id + " 0"), joined, "no join message"),
                () -> assertEquals(3, f.a.memberCount(0), "a member"),
                () -> assertFalse(invited, "tanks cannot be invited"),
                () -> assertEquals(List.of("TO " + ann.id + ": < Your alliance: Ann, Bob >"), listed, "not listed"));
        }
    }
}
