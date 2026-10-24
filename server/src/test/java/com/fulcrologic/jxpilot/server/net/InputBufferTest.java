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
package com.fulcrologic.jxpilot.server.net;

import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEHAVIOR (InputBuffer): entries are applied at their own tick whatever the arrival order; duplicates and
 * reordered older copies change nothing; a later copy from a newer packet revises the entry; past and far-future
 * entries are dropped and counted; a missing entry holds the last keys without taps or aim and counts a
 * starvation (not before the first entry is applied); the applied tick stays at the held one; a late arrival for a starved
 * tick is counted once and its margin enters the slack; the slack is the smallest margin of first copies and of
 * copies that change the entry over the window, NO_SLACK when none;
 * lead is the newest stored entry over the next tick; the tick counter wraps; reset forgets everything.
 */
@DisplayName("InputBuffer")
class InputBufferTest {
    static final long A = 1;
    static final long B = 2;
    static final long C = 4;

    static InputBuffer buf() {
        return new InputBuffer(14, 4);
    }

    @Test
    @DisplayName("in order: each tick takes its own entry, keys, taps and aim")
    void inOrder() {
        InputBuffer b = buf();
        int s1 = b.offer(10, A, B, 3, 10, 10, 8);
        int s2 = b.offer(11, B, 0, -2, 11, 10, 24);
        boolean t10 = b.take(10);
        long k10 = b.keys(), p10 = b.taps();
        int a10 = b.aimTurn(), ap10 = b.appliedTick();
        boolean t11 = b.take(11);
        assertAll(
            () -> assertEquals(InputBuffer.STORED, s1, "a new entry is stored"),
            () -> assertEquals(InputBuffer.STORED, s2, "the next one too"),
            () -> assertTrue(t10, "tick 10 had its entry"),
            () -> assertEquals(A, k10, "keys of 10"),
            () -> assertEquals(B, p10, "taps of 10"),
            () -> assertEquals(3, a10, "aim of 10"),
            () -> assertEquals(10, ap10, "applied tick 10"),
            () -> assertTrue(t11, "tick 11 had its entry"),
            () -> assertEquals(B, b.keys(), "keys of 11"),
            () -> assertEquals(0L, b.taps(), "no taps in 11"),
            () -> assertEquals(-2, b.aimTurn(), "aim of 11"),
            () -> assertEquals(11, b.appliedTick(), "applied tick 11"),
            () -> assertEquals(11, b.lastTaken(), "last taken 11"),
            () -> assertEquals(0L, b.starvations(), "no starvation"));
    }

    @Test
    @DisplayName("reordered: a later tick arriving first is kept; an older packet's copy of an entry is stale")
    void reordered() {
        InputBuffer b = buf();
        b.offer(12, C, 0, 0, 12, 10, 40);
        b.offer(11, B, 0, 0, 12, 10, 24);
        int older = b.offer(11, A, 0, 0, 11, 10, 24);
        b.offer(10, A, 0, 0, 11, 10, 8);
        b.take(10);
        long k10 = b.keys();
        b.take(11);
        long k11 = b.keys();
        b.take(12);
        assertAll(
            () -> assertEquals(InputBuffer.STALE, older, "packet 11's copy of tick 11 is older than packet 12's"),
            () -> assertEquals(A, k10, "10 in order"),
            () -> assertEquals(B, k11, "11 keeps packet 12's copy"),
            () -> assertEquals(C, b.keys(), "12 arrived first and is applied at 12"),
            () -> assertEquals(0L, b.starvations(), "no starvation"));
    }

    @Test
    @DisplayName("duplicated: a copy changes nothing; a copy with other data revises the entry; only changes count for the slack")
    void duplicated() {
        InputBuffer b = buf();
        b.offer(10, A, 0, 0, 10, 10, 16);
        int dup = b.offer(10, A, 0, 0, 10, 10, 2);
        int rev = b.offer(10, B, C, 5, 10, 10, 6);
        int redundant = b.offer(10, B, C, 5, 11, 10, 1);
        int slack = b.slack16();
        b.take(10);
        assertAll(
            () -> assertEquals(InputBuffer.REVISED, dup, "the same packet again rewrites the same data"),
            () -> assertEquals(InputBuffer.REVISED, rev, "a copy with other data revises the entry"),
            () -> assertEquals(InputBuffer.REVISED, redundant, "a newer packet's copy rewrites it"),
            () -> assertEquals(B, b.keys(), "the revised keys are applied"),
            () -> assertEquals(C, b.taps(), "and taps"),
            () -> assertEquals(5, b.aimTurn(), "and aim"),
            () -> assertEquals(6, slack,
                "the copy that changed the entry counts (6); repeats of what is stored (2, 1) do not"));
    }

    @Test
    @DisplayName("late: an entry for a tick already taken is dropped; counted late once if that tick starved")
    void late() {
        InputBuffer b = buf();
        b.offer(10, A, 0, 0, 10, 10, 8);
        b.take(10);
        b.take(11);
        int lateStarved = b.offer(11, B, 0, 0, 11, 12, -4);
        int again = b.offer(11, B, 0, 0, 12, 12, -20);
        int lateTaken = b.offer(10, C, 0, 0, 12, 12, -30);
        int lateSlack = b.slack16();
        b.take(12);
        assertAll(
            () -> assertEquals(InputBuffer.PAST, lateStarved, "11 was taken: PAST"),
            () -> assertEquals(InputBuffer.PAST, again, "and again"),
            () -> assertEquals(InputBuffer.PAST, lateTaken, "10 was taken with its entry: PAST"),
            () -> assertEquals(1L, b.late(), "counted once, for the starved 11 only"),
            () -> assertEquals(A, b.keys(), "the held keys are still 10's"),
            () -> assertEquals(2L, b.starvations(), "11 and 12 starved"),
            () -> assertEquals(-4, lateSlack, "the first late copy of the starved 11 enters the slack; later ones do not"));
    }

    @Test
    @DisplayName("far future: more than maxAhead ticks ahead of the next tick is dropped and counted")
    void farFuture() {
        InputBuffer b = buf();
        int edge = b.offer(24, A, 0, 0, 24, 10, 0);
        int over = b.offer(25, B, 0, 0, 25, 10, 0);
        assertAll(
            () -> assertEquals(InputBuffer.STORED, edge, "exactly maxAhead (14) ahead is kept"),
            () -> assertEquals(InputBuffer.FAR_FUTURE, over, "15 ahead is dropped"),
            () -> assertEquals(1L, b.farFuture(), "counted"),
            () -> assertEquals(14, b.lead(10), "lead: the newest stored entry over the next tick"),
            () -> assertEquals(0, new InputBuffer(14, 4).lead(10), "lead 0 with nothing stored"),
            () -> assertEquals(0, b.lead(30), "lead 0 when nothing is ahead"));
    }

    @Test
    @DisplayName("starvation: the held keys without taps or aim; applied tick stays; nothing counts before the first entry")
    void starvation() {
        InputBuffer b = buf();
        boolean before = b.take(8);
        long keysBefore = b.keys();
        b.take(9);
        long none = b.starvations();
        b.offer(10, A, B, 7, 10, 10, 8);
        b.take(10);
        boolean had = b.take(11);
        assertAll(
            () -> assertFalse(before, "no entry for 8"),
            () -> assertEquals(0L, keysBefore, "no keys before any entry"),
            () -> assertEquals(0L, none, "the ticks before the first entry are no starvation"),
            () -> assertFalse(had, "no entry for 11"),
            () -> assertEquals(A, b.keys(), "11 holds 10's keys"),
            () -> assertEquals(0L, b.taps(), "without taps"),
            () -> assertEquals(0, b.aimTurn(), "without aim"),
            () -> assertEquals(10, b.appliedTick(), "the applied tick stays 10: the client sees the starvation"),
            () -> assertEquals(1L, b.starvations(), "one starvation"),
            () -> assertTrue(b.started(), "started"));
    }

    @Test
    @DisplayName("an entry waiting for a future tick: the ticks before it are no starvation; the buffer starts at it")
    void futureFirst() {
        InputBuffer b = buf();
        b.offer(13, A, 0, 0, 13, 10, 48);
        b.take(10);
        b.take(11);
        b.take(12);
        boolean before = b.started();
        long starvedBefore = b.starvations();
        boolean had = b.take(13);
        b.take(14);
        assertAll(
            () -> assertFalse(before, "not started while the first entry waits"),
            () -> assertEquals(0L, starvedBefore, "10..12 are no starvation"),
            () -> assertTrue(had, "13 has its entry"),
            () -> assertTrue(b.started(), "started at 13"),
            () -> assertEquals(1L, b.starvations(), "14 is the first starvation"));
    }

    @Test
    @DisplayName("slack: the smallest first-arrival margin over the window; NO_SLACK when none arrived in it")
    void slack() {
        InputBuffer b = new InputBuffer(14, 3);
        int none = b.slack16();
        b.offer(10, A, 0, 0, 10, 10, 20);
        b.offer(11, A, 0, 0, 11, 10, 5);
        int min = b.slack16();
        b.take(10);
        b.offer(12, A, 0, 0, 12, 11, 30);
        b.take(11);
        int stillMin = b.slack16();
        b.take(12);
        int aged = b.slack16();
        b.take(13);
        b.take(14);
        int empty = b.slack16();
        assertAll(
            () -> assertEquals(SnapshotWriter.NO_SLACK, none, "no arrival yet"),
            () -> assertEquals(5, min, "the smallest of 20 and 5"),
            () -> assertEquals(5, stillMin, "5 is still within the 3-tick window"),
            () -> assertEquals(30, aged, "after 3 ticks only the later arrival (30) is left"),
            () -> assertEquals(SnapshotWriter.NO_SLACK, empty, "no arrival in the last 3 ticks"),
            () -> assertEquals(SnapshotWriter.NO_SLACK, new InputBuffer(14, 0).slack16(), "window clamps to 1"));
    }

    @Test
    @DisplayName("the tick counter wraps: MAX_VALUE is followed by MIN_VALUE")
    void wrap() {
        InputBuffer b = buf();
        int max = Integer.MAX_VALUE;
        int min = Integer.MIN_VALUE;
        b.offer(max, A, 0, 0, min, max, 8);
        int next = b.offer(min, B, 0, 0, min, max, 24);
        int far = b.offer(min + 14, C, 0, 0, min + 14, max, 0);
        int past = b.offer(max - 1, C, 0, 0, min, max, -8);
        b.take(max);
        long kMax = b.keys();
        int lead = b.lead(min);
        b.take(min);
        assertAll(
            () -> assertEquals(InputBuffer.STORED, next, "MIN_VALUE is one ahead of MAX_VALUE"),
            () -> assertEquals(InputBuffer.FAR_FUTURE, far, "MIN_VALUE + 14 is 15 ahead"),
            () -> assertEquals(InputBuffer.PAST, past, "MAX_VALUE - 1 is past"),
            () -> assertEquals(A, kMax, "MAX_VALUE's keys"),
            () -> assertEquals(0, lead, "nothing beyond MIN_VALUE"),
            () -> assertEquals(B, b.keys(), "MIN_VALUE's keys"),
            () -> assertEquals(min, b.appliedTick(), "applied MIN_VALUE"));
    }

    @Test
    @DisplayName("reset forgets entries, held keys, counters and slack; maxAhead clamps to the ring")
    void reset() {
        InputBuffer b = buf();
        b.offer(10, A, 0, 0, 10, 10, 8);
        b.offer(11, A, 0, 0, 11, 10, 8);
        b.take(10);
        b.offer(30, A, 0, 0, 30, 11, 8);
        b.take(11);
        b.take(12);
        b.reset();
        boolean had = b.take(11);
        InputBuffer wide = new InputBuffer(1000, 4);
        int over = wide.offer(10 + InputBuffer.RING, A, 0, 0, 0, 10, 0);
        int edge = wide.offer(10 + InputBuffer.RING - 1, A, 0, 0, 0, 10, 0);
        assertAll(
            () -> assertFalse(had, "the stored 11 is gone"),
            () -> assertEquals(0L, b.keys(), "no held keys"),
            () -> assertEquals(0L, b.starvations(), "starvations reset (and none counted before a new entry)"),
            () -> assertEquals(0L, b.farFuture(), "far-future count reset"),
            () -> assertEquals(0L, b.late(), "late count reset"),
            () -> assertEquals(0, b.appliedTick(), "applied tick reset"),
            () -> assertFalse(b.started(), "not started"),
            () -> assertEquals(SnapshotWriter.NO_SLACK, b.slack16(), "no slack report"),
            () -> assertEquals(InputBuffer.FAR_FUTURE, over, "maxAhead clamps to RING - 1"),
            () -> assertEquals(InputBuffer.STORED, edge, "RING - 1 ahead is kept"));
    }
}
