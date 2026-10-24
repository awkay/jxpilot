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
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.map.Checkpoint;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.ModeParams;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Race mode ({@code timing} maps): ports of the checkpoint part of {@code PlayerCollision} and
 * {@code Player_pass_checkpoint} ({@code src/server/collision.c}), the race part of {@code Compute_game_status}
 * and {@code Race_game_over} ({@code player.c}), and the race respawn of {@code Go_home} /
 * {@code Transport_to_home}. Times count frames; messages show them as seconds with two decimals like C's
 * {@code %.2f} of a float.
 */
public final class Race {
    private final World w;
    private final Simulation sim;
    /** Scratch for Race_game_over (C mallocs it). */
    private final int[] order;
    private final int[] baseOrder;
    private final StringBuilder label = new StringBuilder(32);

    public Race(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
        this.order = new int[w.players.length];
        this.baseOrder = w.map.baseOrder();
    }

    /**
     * Within checkpointRadius blocks of checkpoint {@code check}'s block corner (C measures to
     * {@code World.check[i] * BLOCK_SZ}).
     * <p>
     * BEHAVIOR: the wrapped distance is compared strictly.
     */
    public boolean nearCheckpoint(int px, int py, int check) {
        Checkpoint c = w.map.checkpoints().get(check);
        return w.wrapLength(px - c.bx() * Const.BLOCK_SZ, py - c.by() * Const.BLOCK_SZ)
            < w.modeParams.checkpointRadius * Const.BLOCK_SZ;
    }

    /**
     * The checkpoint part of C {@code PlayerCollision} for a ship that was alive at the start of its collision
     * pass (it may have died in it).
     * <p>
     * BEHAVIOR: only in race mode; a ship that started its first lap counts one more race frame.
     * BEHAVIOR: a ship (not a tank) still alive near its next checkpoint passes it (not in a ball race).
     */
    public void playerFrame(Player pl) {
        ModeParams mp = w.modeParams;
        if (!mp.timing || (pl.status & (Obj.PAUSE | Obj.GAME_OVER)) != 0) {
            return;
        }
        if (pl.round != 0) {
            pl.time++;
        }
        if ((pl.status & (Obj.PLAYING | Obj.KILLED)) == Obj.PLAYING && !pl.tank && !mp.ballrace
            && nearCheckpoint(pl.px, pl.py, pl.check)) {
            passCheckpoint(pl);
        }
    }

    /**
     * C {@code Player_pass_checkpoint}.
     * <p>
     * BEHAVIOR: passing checkpoint 0 starts a lap: the round counts up, the lap time is the race time since the
     * last lap, a faster lap (not the first, not at time 0) is the new best lap, and: after raceLaps laps the
     * ship finishes ("NAME finished the race. Last lap time: L s. Personal race best lap time: B s.", it is
     * reset, 'D', GAME_OVER|FINISH, and in a ball race its balls lose their owner); after a later lap "NAME
     * completes lap N in L s. Personal race best lap time: B s."; at the start "NAME starts lap 1 of N".
     * BEHAVIOR: the next checkpoint follows (wrapping to 0), the heading is remembered for the respawn, a
     * CHECKPOINT game event (value = the lap time in frames) and a TIMING update follow.
     */
    public void passCheckpoint(Player pl) {
        ModeParams mp = w.modeParams;
        if (pl.check == 0) {
            pl.round++;
            pl.lastLapTime = pl.time - pl.lastLap;
            if ((pl.bestLap > pl.lastLapTime || pl.bestLap == 0) && pl.time != 0 && pl.round != 1) {
                pl.bestLap = pl.lastLapTime;
            }
            pl.lastLap = pl.time;
            int fps = w.params.fps;
            if (pl.round > mp.raceLaps) {
                if (mp.ballrace) {
                    Objects o = w.objects;
                    for (int k = 0; k < o.count(); k++) {
                        int h = o.active[k];
                        if (o.type[h] == Obj.BALL && w.modes.ballOwner[h] == pl.id) {
                            w.modes.ballOwner[h] = Obj.NO_ID;
                        }
                    }
                }
                sim.ships.deathReset(pl);
                pl.mychar = 'D';
                pl.status |= Obj.GAME_OVER | Obj.FINISH;
                StringBuilder m = message().append(pl.name).append(" finished the race. Last lap time: ");
                appendSeconds(m, pl.lastLapTime, fps);
                m.append("s. Personal race best lap time: ");
                appendSeconds(m, pl.bestLap, fps);
                m.append("s.");
            } else if (pl.round > 1) {
                StringBuilder m = message().append(pl.name).append(" completes lap ").append(pl.round - 1)
                    .append(" in ");
                appendSeconds(m, pl.lastLapTime, fps);
                m.append("s. Personal race best lap time: ");
                appendSeconds(m, pl.bestLap, fps);
                m.append("s.");
            } else {
                message().append(pl.name).append(" starts lap 1 of ").append(mp.raceLaps);
            }
        }
        if (++pl.check == w.map.numChecks()) {
            pl.check = 0;
        }
        pl.lastCheckDir = pl.dir;
        pl.timingDirty = true;
        pl.scoreDirty = true;
        w.events.add(Events.GAME_EVENT, Msg.GameEvent.CHECKPOINT, pl.id, pl.check, pl.lastLapTime);
    }

    private StringBuilder message() {
        return w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID);
    }

    /**
     * Append {@code frames / fps} as C's {@code %.2f} of {@code (DFLOAT) frames / FPS}.
     * <p>
     * BEHAVIOR: the float quotient times 100 is exact in double and rounds half to even, like printf.
     */
    static void appendSeconds(StringBuilder sb, int frames, int fps) {
        appendFixed2((float) frames / fps, sb);
    }

    /** BEHAVIOR: C {@code %.2f} of a float value (exact: the float times 100 fits a double). */
    static void appendFixed2(float f, StringBuilder sb) {
        double d = Math.rint((double) f * 100.0);
        if (d < 0) {
            sb.append('-');
            d = -d;
        }
        long c = (long) d;
        sb.append(c / 100).append('.');
        long frac = c % 100;
        if (frac < 10) {
            sb.append('0');
        }
        sb.append(frac);
    }

    // ================================================================ respawn

    /**
     * The checkpoint a racing ship is carried back to (C {@code Go_home}/{@code Transport_to_home} in race
     * mode): the last checkpoint passed, or -1 when the ship respawns at its base.
     * <p>
     * BEHAVIOR: only in race mode, after the first checkpoint, and (Go_home only) not GAME_OVER.
     */
    public int respawnCheck(Player pl, boolean goHome) {
        if (!w.modeParams.timing || pl.round == 0 || (goHome && (pl.status & Obj.GAME_OVER) != 0)) {
            return -1;
        }
        return pl.check != 0 ? pl.check - 1 : w.map.numChecks() - 1;
    }

    // ================================================================ game status

    private static boolean waiting(Player pl) {
        return (pl.status & Obj.GAME_OVER) != 0 && pl.mychar == 'W';
    }

    /**
     * The race part of C {@code Compute_game_status}.
     * <p>
     * BEHAVIOR: counts non-paused ships that are not tanks: alive, waiting ('W'), finished this frame (FINISH), finished earlier
     * (RACE_OVER, each pushes the position down) and active (all but waiting); nothing to do without active
     * or waiting ships.
     * BEHAVIOR: ships that finished this frame share position P: each gets total/finishers points where total
     * sums (10 + 2*active) &gt;&gt; (P-1+i) over the finishers ("NAME finishes [jointly ]in position P scoring X
     * point[s].", "[Position P[ (jointly)]]"; with 0 points just the position), and become RACE_OVER.
     * BEHAVIOR: the race ends ({@link #gameOver}): on the timer ("Timer expired. Race ends now."); with
     * limited lives when at most one ship is alive and, if one, it has started a lap and someone finished;
     * without limited lives as soon as someone finished.
     */
    public void gameStatus() {
        int alive = 0;
        int active = 0;
        int finished = 0;
        int waitingCount = 0;
        int position = 1;
        Player lastAlive = null;
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if ((pl.status & Obj.PAUSE) != 0 || pl.tank) {
                continue;
            }
            if ((pl.status & Obj.GAME_OVER) == 0) {
                alive++;
            } else if (pl.mychar == 'W') {
                waitingCount++;
                continue;
            }
            if ((pl.status & Obj.RACE_OVER) != 0) {
                position++;
            } else if ((pl.status & Obj.FINISH) != 0) {
                finished++;
            } else if ((pl.status & Obj.GAME_OVER) == 0) {
                lastAlive = pl;
            }
            active++;
        }
        int raceOver = position - 1;
        if (active == 0 && waitingCount == 0) {
            return;
        }
        if (finished > 0) {
            int totalPts = 0;
            for (int i = 0; i < finished; i++) {
                totalPts += (10 + 2 * active) >> (position - 1 + i);
            }
            float pts = totalPts / finished;
            // only the first ship over the line gets the fanfare
            if (position == 1) {
                Sounds.playAll(w, SoundEvent.PLAYER_WIN);
            }
            for (int k = 0; k < w.numPlayers; k++) {
                Player pl = w.playerAt(k);
                if ((pl.status & Obj.PAUSE) != 0 || waiting(pl) || pl.tank || (pl.status & Obj.FINISH) == 0) {
                    continue;
                }
                pl.status &= ~Obj.FINISH;
                pl.status |= Obj.RACE_OVER;
                StringBuilder m = message().append(pl.name).append(" finishes ")
                    .append(finished == 1 ? "" : "jointly ").append("in position ").append(position);
                if (pts > 0) {
                    m.append(" scoring ");
                    appendFixed2(pts, m);
                    m.append(" point").append(pts == 1 ? "" : "s").append('.');
                    label.setLength(0);
                    label.append("[Position ").append(position).append(finished == 1 ? "" : " (jointly)")
                        .append(']');
                    sim.scoring.score(pl, pts, pl.bx, pl.by, label);
                } else {
                    m.append('.');
                }
            }
        }
        if (w.params.maxRoundTime > 0 && w.roundTime == 0) {
            message().append("Timer expired. Race ends now.");
            gameOver();
            return;
        }
        if (w.params.limitedLives) {
            if (alive > 1) {
                return;
            }
            if (alive == 1) {
                if (finished + raceOver == 0) {
                    return;
                }
                if (lastAlive == null || lastAlive.round == 0) {
                    return;
                }
            }
        } else if (finished == 0) {
            return;
        }
        gameOver();
    }

    /**
     * C {@code Race_game_over}.
     * <p>
     * BEHAVIOR: home bases are reassigned in race start order by personal best lap (ships without a lap,
     * paused or waiting keep their place in the player order); a changed home is announced (BASE_OWNER) and a
     * paused ship is moved there.
     * BEHAVIOR: every active ship is reset (killed if still racing); the best lap time holders get
     * 5 + active points ("NAME had/shares the best lap time of T s", "[Fastest lap]"/"[Joint fastest lap]");
     * with no lap completed and humans racing: "No-one even managed to complete one lap, you should be ashamed
     * of yourselves.". Then every player is reset for the next race and the round is counted.
     * BEHAVIOR: tanks take no part (C IS_TANK_PTR); a ship the reset deleted (a robot out of lives) is skipped.
     */
    public void gameOver() {
        int n = w.numPlayers;
        int numOrdered = 0;
        for (int i = 0; i < n; i++) {
            Player pl = w.playerAt(i);
            if (pl.tank) {
                // C skips tanks but goes on inserting at player index i, which leaves an unset slot it then
                // reads; we insert among the ordered players (the same order when no tank comes first)
                continue;
            }
            int j;
            if ((pl.status & Obj.PAUSE) != 0 || waiting(pl) || pl.bestLap <= 0) {
                j = numOrdered;
            } else {
                for (j = 0; j < numOrdered; j++) {
                    Player o = w.playerAt(order[j]);
                    if (pl.bestLap < o.bestLap) {
                        break;
                    }
                    if ((o.status & Obj.PAUSE) != 0 || waiting(o)) {
                        break;
                    }
                }
            }
            for (int k = numOrdered - 1; k >= j; k--) {
                order[k + 1] = order[k];
            }
            order[j] = i;
            numOrdered++;
        }
        for (int i = 0; i < numOrdered && i < baseOrder.length; i++) {
            Player pl = w.playerAt(order[i]);
            if (pl.homeBase != baseOrder[i]) {
                pl.homeBase = baseOrder[i];
                w.events.add(Events.BASE_OWNER, pl.homeBase, pl.id, 0, 0f);
                if ((pl.status & Obj.PAUSE) != 0) {
                    sim.ships.goHome(pl);
                }
            }
        }
        int bestLap = 0;
        int numBest = 0;
        int active = 0;
        for (int i = n - 1; i >= 0; i--) {
            Player pl = w.playerAt(i);
            pl.status &= ~(Obj.RACE_OVER | Obj.FINISH);
            if ((pl.status & Obj.PAUSE) != 0 || waiting(pl) || pl.tank) {
                continue;
            }
            active++;
            if ((pl.status & Obj.GAME_OVER) == 0) {
                sim.ships.kill(pl);
            } else {
                sim.ships.deathReset(pl);
            }
            if (!pl.inUse) {
                continue; // deleted by Kill_player (a robot out of lives): C's pl != Players[i] (player.c:1182)
            }
            if ((pl.bestLap < bestLap || bestLap == 0) && pl.bestLap > 0) {
                bestLap = pl.bestLap;
                numBest = 0;
            }
            if (pl.bestLap == bestLap) {
                numBest++;
            }
        }
        if (bestLap > 0) {
            for (int i = 0; i < n; i++) {
                Player pl = w.playerAt(i);
                if ((pl.status & Obj.PAUSE) != 0 || waiting(pl) || pl.tank) {
                    continue;
                }
                if (pl.bestLap == bestLap) {
                    StringBuilder m = message().append(pl.name).append(numBest == 1 ? " had" : " shares")
                        .append(" the best lap time of ");
                    appendSeconds(m, bestLap, w.params.fps);
                    m.append('s');
                    sim.scoring.score(pl, 5 + active, pl.bx, pl.by,
                        numBest == 1 ? "[Fastest lap]" : "[Joint fastest lap]");
                }
            }
        } else if (active > robots()) {
            message().append("No-one even managed to complete one lap, you should be ashamed of yourselves.");
        }
        sim.rounds.resetAllPlayers();
        sim.rounds.countRounds();
    }

    private int robots() {
        int r = 0;
        for (int k = 0; k < w.numPlayers; k++) {
            if (w.playerAt(k).robot) {
                r++;
            }
        }
        return r;
    }

    /**
     * The race part of C {@code Reset_all_players} for one player: race progress starts over; a ship that is
     * not paused waits RECOVERY_DELAY frames before it starts (race mode only).
     */
    public void resetPlayer(Player pl) {
        pl.round = 0;
        pl.check = 0;
        pl.time = 0;
        pl.bestLap = 0;
        pl.lastLap = 0;
        pl.lastLapTime = 0;
        pl.timingDirty = true;
        if ((pl.status & Obj.PAUSE) == 0 && w.modeParams.timing) {
            pl.count = 3 * w.params.fps;
        }
    }
}
