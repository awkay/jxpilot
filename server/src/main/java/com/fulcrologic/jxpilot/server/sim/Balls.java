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
import com.fulcrologic.jxpilot.common.map.Treasure;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ModeParams;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Balls and treasures: ports of {@code Make_treasure_ball}, {@code Move_ball} and the ball part of
 * {@code Delete_shot} ({@code src/server/shot.c}), {@code Detach_ball} ({@code player.c}), the connector part
 * of {@code PlayerCollision}, {@code Player_collides_with_ball} and {@code BallCollision}
 * ({@code collision.c}), the treasure reactions of {@code Move_segment} ({@code walls.c}) and
 * {@code Punish_team} ({@code play.c}).
 * <p>
 * A ball is a pool object of type {@link Obj#BALL}. {@link Objects#owner} is C's {@code ball->id}, the player
 * the ball is connected to ({@link Obj#NO_ID} when loose); {@link ModeState#ballOwner} is C's {@code ball->owner},
 * the last player who connected to it, who gets the credit. C keeps a ball alive with {@code life = LONG_MAX}
 * and reports {@code LONG_MAX - life} as the frames it was loose; a double cannot count down from LONG_MAX, so
 * {@link #BALL_LIFE} (2^52, exact) stands in for it.
 */
public final class Balls implements Segment.TreasureHook, Ecm.BallDetacher {
    /** Stand-in for C's {@code LONG_MAX} ball life (exactly representable, so it counts down frame by frame). */
    public static final double BALL_LIFE = 0x1p52;
    /** C {@code BALL_RADIUS}. */
    public static final int BALL_RADIUS = 10;
    /** C {@code ED_BALL_HIT}. */
    static final double ED_BALL_HIT = -50.0 * 256;

    private final World w;
    private final Simulation sim;

    public Balls(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    /** C {@code Treasure_init}: a ball in every treasure that is not empty. */
    public void init() {
        for (int i = 0; i < w.map.treasures().size(); i++) {
            makeTreasureBall(i);
        }
    }

    /**
     * C {@code Make_treasure_ball}: a loose ball resting in its treasure.
     * <p>
     * BEHAVIOR: nothing for an empty treasure, a treasure that already has its ball, or a full pool.
     * BEHAVIOR: the ball sits at the treasure's x centre, 10 px above its bottom, at rest, without gravity
     * (status RECREATE), mass ballMass, radius 10, team = the treasure's team, no owner; the treasure has it.
     *
     * @return the ball's handle, or -1
     */
    public int makeTreasureBall(int treasure) {
        Treasure t = w.map.treasures().get(treasure);
        ModeState m = w.modes;
        if (t.empty() || m.treasureHave[treasure]) {
            return -1;
        }
        Objects o = w.objects;
        int h = o.alloc();
        if (h < 0) {
            return -1;
        }
        m.ballLength[h] = w.modeParams.ballConnectorLength;
        o.life[h] = BALL_LIFE;
        o.mass[h] = w.modeParams.ballMass;
        o.initPixels(h, (t.bx() + 0.5) * Const.BLOCK_SZ, (t.by() * Const.BLOCK_SZ) + 10);
        o.owner[h] = Obj.NO_ID;
        m.ballOwner[h] = Obj.NO_ID;
        o.team[h] = m.treasureTeam[treasure];
        o.type[h] = Obj.BALL;
        o.color[h] = Records.ObjColor.WHITE;
        o.plRange[h] = BALL_RADIUS;
        o.plRadius[h] = BALL_RADIUS;
        o.status[h] = Obj.RECREATE;
        m.ballTreasure[h] = treasure;
        m.treasureHave[treasure] = true;
        return h;
    }

    // ================================================================ connector

    /**
     * The ball part of C {@code PlayerCollision} for one alive ship: connecting the connector.
     * <p>
     * BEHAVIOR: without the connector in use, or phased, the ship reaches for no ball.
     * BEHAVIOR: reaching for a ball that died, was taken by someone else or is no longer a ball: stop reaching.
     * BEHAVIOR: once the reached ball is at least ballConnectorLength away the ship takes it: the ball is
     * connected to the ship (id, team), becomes its owner (a ball never owned before restarts its loose-frame
     * count), gets the current length and gravity, leaves its treasure, and the ship has a ball.
     * BEHAVIOR: reaching for nothing: the nearest loose ball closer than (int) ballConnectorLength (distances
     * truncated to int) is picked, except in team play a ball of the ship's own team that was never owned.
     */
    public void connect(Player pl) {
        Objects o = w.objects;
        ModeState m = w.modes;
        ModeParams mp = w.modeParams;
        if ((pl.used & Has.CONNECTOR) == 0 || (pl.used & Has.PHASING_DEVICE) != 0) {
            pl.ball = -1;
        } else if (pl.ball >= 0) {
            int h = pl.ball;
            if (!isBall(h) || o.life[h] <= 0 || o.owner[h] != Obj.NO_ID) {
                pl.ball = -1;
                return;
            }
            float distance = w.wrapLength(pl.px - o.px[h], pl.py - o.py[h]);
            if (distance >= mp.ballConnectorLength) {
                o.owner[h] = pl.id;
                o.team[h] = pl.team;
                if (m.ballOwner[h] == Obj.NO_ID) {
                    o.life[h] = BALL_LIFE;
                }
                m.ballOwner[h] = pl.id;
                m.ballLength[h] = distance;
                o.status[h] |= Obj.GRAVITY;
                m.treasureHave[m.ballTreasure[h]] = false;
                pl.have |= Has.BALL;
                pl.ball = -1;
                Sounds.play(w, pl.px, pl.py, SoundEvent.CONNECT_BALL);
            }
        } else {
            int mindist = (int) mp.ballConnectorLength;
            for (int k = 0; k < o.count(); k++) {
                int h = o.active[k];
                if ((o.type[h] & Obj.BALL) == 0 || o.owner[h] != Obj.NO_ID) {
                    continue;
                }
                int dist = (int) w.wrapLength(pl.px - o.px[h], pl.py - o.py[h]);
                if (dist < mindist) {
                    int bteam = m.treasureTeam[m.ballTreasure[h]];
                    if (!w.params.teamPlay || m.ballOwner[h] != Obj.NO_ID || pl.team != bteam) {
                        pl.ball = h;
                        mindist = dist;
                    }
                }
            }
        }
    }

    private boolean isBall(int h) {
        return w.objects.isLive(h) && w.objects.type[h] == Obj.BALL;
    }

    /**
     * C {@code Detach_ball(ind, obj)}: {@code h} = -1 drops every ball.
     * <p>
     * BEHAVIOR: dropping all, or the ball the ship reaches for: it stops reaching and the connector is off.
     * BEHAVIOR: with a ball: the dropped ball(s) are loose (the owner stays, so balls can be thrown); the ship
     * keeps HAS_BALL only while another ball is still connected to it.
     */
    public void detach(Player pl, int h) {
        Objects o = w.objects;
        if (h == -1 || h == pl.ball) {
            pl.ball = -1;
            pl.used &= ~Has.CONNECTOR;
        }
        if ((pl.have & Has.BALL) != 0) {
            int cnt = 0;
            for (int k = 0; k < o.count(); k++) {
                int b = o.active[k];
                if (o.type[b] == Obj.BALL && o.owner[b] == pl.id) {
                    if (h == -1 || h == b) {
                        o.owner[b] = Obj.NO_ID;
                    } else {
                        cnt++;
                    }
                }
            }
            if (cnt == 0) {
                pl.have &= ~Has.BALL;
            } else {
                Sounds.play(w, pl.px, pl.py, SoundEvent.DROP_BALL);
            }
        }
    }

    /**
     * The ball part of an ECM blast (item.c:1168-1181) on ship {@code p} at {@code range} (1 at the centre).
     * <p>
     * BEHAVIOR: every ball whose C owner (the last ship it was connected to) is {@code p}, connected or not,
     * draws once and detaches from {@code p} ({@link #detach}) when (int)(rfrac()*100) &lt; (int)(20*range)+5.
     */
    @Override
    public void ecmDetach(Player p, float range) {
        Objects o = w.objects;
        ModeState m = w.modes;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if ((o.type[h] & Obj.BALL) == 0 || m.ballOwner[h] != p.id) {
                continue;
            }
            if ((int) (w.rfrac() * 100.0f) < ((int) (20 * range) + 5)) {
                detach(p, h);
            }
        }
    }

    /**
     * C {@code Move_ball} (the spring connector since XPilot 3.4.0), for a connected ball, C floats.
     * <p>
     * BEHAVIOR: with D the unit vector from ball to ship and ratio = (ballConnectorLength - length) /
     * ballConnectorLength: a string connector ({@code connectorIsString}) shorter than its length does nothing;
     * |ratio| &gt; maxBallConnectorRatio detaches the ball; otherwise the length is stored and with
     * a = ballConnectorSpringConstant * ratio + both dampings (-ballConnectorDamping * the velocity component
     * along the connector, each towards the other end) the ship gets D * a / ship mass and the ball
     * -D * a / ball mass.
     */
    public void move(int h) {
        Objects o = w.objects;
        ModeParams mp = w.modeParams;
        Player pl = w.byId(o.owner[h]);
        if (pl == null) {
            return;
        }
        float dx = (float) w.wrapDx(pl.px - o.px[h]);
        float dy = (float) w.wrapDy(pl.py - o.py[h]);
        float length = (float) StrictMath.hypot(dx, dy);
        if (length > 0.0) {
            dx /= length;
            dy /= length;
        } else {
            dx = dy = 0.0f;
        }
        float ratio = (mp.ballConnectorLength - length) / mp.ballConnectorLength;
        float force = mp.ballConnectorSpringConstant * ratio;
        if (mp.connectorIsString && ratio > 0.0) {
            return;
        }
        if (Math.abs(ratio) > mp.maxBallConnectorRatio) {
            detach(pl, h);
            return;
        }
        w.modes.ballLength[h] = length;
        float cosine = (pl.vx * dx) + (pl.vy * dy);
        float plDamping = -mp.ballConnectorDamping * cosine;
        cosine = (o.vx[h] * -dx) + (o.vy[h] * -dy);
        float ballDamping = -mp.ballConnectorDamping * cosine;
        float accell = (force + plDamping + ballDamping) / pl.mass;
        pl.vx += dx * accell;
        pl.vy += dy * accell;
        accell = (force + ballDamping + plDamping) / o.mass[h];
        o.vx[h] += -dx * accell;
        o.vy[h] += -dy * accell;
    }

    // ================================================================ deletion

    /**
     * The ball case of C {@code Delete_shot}, before the object is freed.
     * <p>
     * BEHAVIOR: a connected ball is detached from its ship; a loose one stops every ship reaching for it.
     * BEHAVIOR: a ball never owned leaves its treasure and is recreated (it was knocked out of it).
     * BEHAVIOR: a ball to be recreated (RECREATE) explodes into 10-20 red debris from its previous position
     * (unless NOEXPLOSION), and its treasure index is returned so the caller can {@link #makeTreasureBall}
     * after freeing it; otherwise -1.
     */
    public int deleting(int h) {
        Objects o = w.objects;
        ModeState m = w.modes;
        if (o.owner[h] != Obj.NO_ID) {
            Player pl = w.byId(o.owner[h]);
            if (pl != null) {
                detach(pl, h);
            }
        } else {
            for (int k = 0; k < w.numPlayers; k++) {
                Player pl = w.playerAt(k);
                if (pl.ball == h) {
                    pl.ball = -1;
                }
            }
        }
        if (m.ballOwner[h] == Obj.NO_ID) {
            m.treasureHave[m.ballTreasure[h]] = false;
            o.status[h] |= Obj.RECREATE;
        }
        if ((o.status[h] & Obj.RECREATE) == 0) {
            return -1;
        }
        if ((o.status[h] & Obj.NOEXPLOSION) == 0) {
            Sounds.play(w, o.px[h], o.py[h], SoundEvent.EXPLODE_BALL);
            sim.debris.make(o.prevX[h], o.prevY[h], o.vx[h], o.vy[h], o.owner[h], o.team[h], Obj.DEBRIS,
                Shots.DEBRIS_MASS, Obj.GRAVITY, Records.ObjColor.RED, Records.BurstKind.DEBRIS, 8, 10, 20, 0,
                Const.RES - 1, 10, 50, 10, 2 * (w.params.fps + 15));
        }
        return m.ballTreasure[h];
    }

    // ================================================================ treasures

    /**
     * The ball part of the TREASURE case of C {@code Move_segment}, after the move hit treasure
     * {@code ms.treasure} (crash already CRASH_TREASURE).
     * <p>
     * BEHAVIOR: its own treasure, outside team play, unowned, or owned by another team: it rests there again
     * (life restarts, no crash) and keeps moving (true).
     * BEHAVIOR: its own treasure brought home by a member of the treasure's team: the ball dies quietly
     * (NOEXPLOSION|RECREATE) and comes back, the player scores 5 ("Treasure: ") and " < NAME (team T) has
     * replaced the treasure >"; the move continues (true).
     * BEHAVIOR: another treasure: a never-owned ball dies; in team play a ball brought to a treasure of its
     * owner's team says " < The ball was loose for N frames >", then either (captureTheFlag, that treasure
     * missing its own ball and not empty) tells the owner "Your treasure must be safe before you can cash an
     * opponent's!", or {@link #punishTeam} and a destroyed treasure is not recreated; the ball dies. The
     * move stops (false).
     *
     * @return true when the move continues through the block (C {@code break}), false when it ends (C
     * {@code return})
     */
    @Override
    public boolean atTreasure(MoveState ms) {
        Objects o = w.objects;
        ModeState m = w.modes;
        int h = ms.obj;
        int ballTreasure = m.ballTreasure[h];
        int ownerId = m.ballOwner[h];
        Player pl = ownerId == Obj.NO_ID ? null : w.byId(ownerId);
        if (ms.treasure == ballTreasure) {
            if (!w.params.teamPlay || pl == null || pl.team != m.treasureTeam[ballTreasure]) {
                o.life[h] = BALL_LIFE;
                ms.crash = MoveState.NOT_A_CRASH;
                return true;
            }
            o.life[h] = 0;
            o.status[h] |= Obj.NOEXPLOSION | Obj.RECREATE;
            Treasure tt = w.map.treasures().get(ms.treasure);
            sim.scoring.score(pl, 5, tt.bx(), tt.by(), "Treasure: ");
            w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(" < ").append(pl.name).append(" (team ")
                .append(pl.team).append(") has replaced the treasure >");
            return true;
        }
        if (ownerId == Obj.NO_ID) {
            o.life[h] = 0;
            return false;
        }
        if (w.params.teamPlay && pl != null && m.treasureTeam[ms.treasure] == pl.team) {
            w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(" < The ball was loose for ")
                .append((long) (BALL_LIFE - o.life[h])).append(" frames >");
            if (w.modeParams.captureTheFlag && !m.treasureHave[ms.treasure]
                && !w.map.treasures().get(ms.treasure).empty()) {
                w.events.playerMessage(Msg.Text.KIND_GAME, Obj.NO_ID, pl.id)
                    .append("Your treasure must be safe before you can cash an opponent's!");
            } else if (punishTeam(pl, ballTreasure, ms.treasure)) {
                o.status[h] &= ~Obj.RECREATE;
            }
        }
        o.life[h] = 0;
        return false;
    }

    /** C's "skip" test of Punish_team and Object_hits_target: tanks, counted-out pause, waiting with no score. */
    static boolean ignored(Player p) {
        return p.tank
            || ((p.status & Obj.PAUSE) != 0 && p.count <= 0)
            || ((p.status & Obj.GAME_OVER) != 0 && p.mychar == 'W' && p.score == 0);
    }

    /**
     * C {@code Punish_team}: {@code pl} brought the ball of treasure {@code destroyed} to treasure
     * {@code target} of its own team.
     * <p>
     * BEHAVIOR: a ball of the player's own team punishes nobody (false).
     * BEHAVIOR: " < NAME's (T) team has destroyed team D treasure >"; if no member of the losing team is
     * still in the game the player only gets Rate(score, CANNON_SCORE)/2 ("Treasure:") and false.
     * BEHAVIOR: otherwise the treasure is destroyed (counted, the losing team has one treasure fewer, the
     * winning team one more destroyed): with sc = 3 * Rate(winners' summed score, losers' summed score) (sums
     * truncated to int like C) and por = sc * losers / (2 * winners + 1), each loser loses sc (and dies with
     * treasureKillTeam), the player gets 3 * por and each team mate 2 * por ("Treasure: "); with
     * treasureKillTeam the player gets a kill. A TREASURE_DESTROYED game event precedes the scores. True.
     */
    public boolean punishTeam(Player pl, int destroyed, int target) {
        ModeState m = w.modes;
        int tdTeam = m.treasureTeam[destroyed];
        int ttTeam = m.treasureTeam[target];
        Treasure tt = w.map.treasures().get(target);
        if (tdTeam == pl.team) {
            return false;
        }
        int winScore = 0;
        int loseScore = 0;
        int winMembers = 0;
        int loseMembers = 0;
        boolean somebody = false;
        if (w.params.teamPlay) {
            for (int k = 0; k < w.numPlayers; k++) {
                Player p = w.playerAt(k);
                if (ignored(p)) {
                    continue;
                }
                if (p.team == tdTeam) {
                    loseScore = (int) (loseScore + p.score);
                    loseMembers++;
                    if ((p.status & Obj.GAME_OVER) == 0) {
                        somebody = true;
                    }
                } else if (p.team == ttTeam) {
                    winScore = (int) (winScore + p.score);
                    winMembers++;
                }
            }
        }
        Sounds.playAll(w, SoundEvent.DESTROY_BALL);
        w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(" < ").append(pl.name).append("'s (")
            .append(pl.team).append(") team has destroyed team ").append(tdTeam).append(" treasure >");
        if (!somebody) {
            sim.scoring.score(pl, Scoring.rate(pl.score, Scoring.CANNON_SCORE) / 2, tt.bx(), tt.by(), "Treasure:");
            return false;
        }
        m.treasureDestroyed[destroyed]++;
        if (tdTeam >= 0 && tdTeam < Const.MAX_TEAMS) {
            m.teamTreasuresLeft[tdTeam]--;
        }
        if (ttTeam >= 0 && ttTeam < Const.MAX_TEAMS) {
            m.teamTreasuresDestroyed[ttTeam]++;
        }
        w.events.add(Events.GAME_EVENT, Msg.GameEvent.TREASURE_DESTROYED, pl.id, destroyed, 0f);
        double sc = 3 * Scoring.rate(winScore, loseScore);
        double por = (sc * loseMembers) / (2 * winMembers + 1);
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (ignored(p)) {
                continue;
            }
            if (p.team == tdTeam) {
                sim.scoring.score(p, -sc, tt.bx(), tt.by(), "Treasure: ");
                if (w.modeParams.treasureKillTeam) {
                    p.status |= Obj.KILLED;
                }
            } else if (p.team == ttTeam && (p.team != Const.TEAM_NOT_SET || p == pl)) {
                sim.scoring.score(p, p == pl ? 3 * por : 2 * por, tt.bx(), tt.by(), "Treasure: ");
            }
        }
        if (w.modeParams.treasureKillTeam) {
            pl.kills++;
        }
        return true;
    }

    // ================================================================ collisions

    /**
     * C {@code Player_collides_with_ball}: the ship bounces off the ball (Obj_repel at {@code radius}).
     * <p>
     * BEHAVIOR: unless fully shielded the ship loses 50 fuel and, with treasureCollisionDestroys, the ball dies.
     * BEHAVIOR: with fuel left the ship survives unless treasureCollisionMayKill and unshielded; armor then
     * takes the hit instead.
     * BEHAVIOR: otherwise the ship dies: "NAME was killed by a ball." (unowned ball, Rate(0, score) *
     * ballKillScoreMult * unownedKillScoreMult, "Ball"), "... owned by OWNER.  How strange!" (own ball,
     * * selfKillScoreMult) or "... owned by OWNER." (the owner gets the kill and Rate(owner, victim) *
     * ballKillScoreMult). A KILL event precedes the scores.
     */
    public void playerHit(Player pl, int h, int radius) {
        Objects o = w.objects;
        sim.objPhysics.repel(pl, h, radius);
        if (!pl.fullyShielded()) {
            pl.addFuel(ED_BALL_HIT);
            if (w.modeParams.treasureCollisionDestroys) {
                o.life[h] = 0;
            }
        }
        if (pl.fuel > 0) {
            if (!w.modeParams.treasureCollisionMayKill || (pl.used & Has.SHIELD) != 0) {
                return;
            }
            if ((pl.have & Has.ARMOR) != 0) {
                sim.ships.hitArmor(pl);
                return;
            }
        }
        float mult = w.modeParams.ballKillScoreMult;
        int ownerId = w.modes.ballOwner[h];
        Player killer = ownerId == Obj.NO_ID ? null : w.byId(ownerId);
        StringBuilder msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID);
        if (killer == null) {
            msg.append(pl.name).append(" was killed by a ball.");
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, Obj.NO_ID, pl.id, 0f);
            sim.scoring.score(pl, -(Scoring.rate(0, pl.score) * mult * w.params.unownedKillScoreMult), pl.bx, pl.by,
                "Ball");
        } else {
            msg.append(pl.name).append(" was killed by a ball owned by ").append(killer.name).append('.');
            if (killer == pl) {
                msg.append("  How strange!");
                w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, Obj.NO_ID, pl.id, 0f);
                sim.scoring.score(pl, -(Scoring.rate(0, pl.score) * mult * w.params.selfKillScoreMult), pl.bx,
                    pl.by, killer.name);
            } else {
                killer.kills++;
                double sc = Scoring.rate(killer.score, pl.score) * mult;
                w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, killer.id, pl.id, 0f);
                sim.scoring.scorePlayers(killer, sc, pl.name, pl, -sc, killer.name);
            }
        }
        pl.status |= Obj.KILLED;
    }

    /** A ball connected to a phased ship (C: phased balls do not collide). */
    boolean phased(int h) {
        int id = w.objects.owner[h];
        if (id == Obj.NO_ID) {
            return false;
        }
        Player p = w.byId(id);
        return p != null && (p.used & Has.PHASING_DEVICE) != 0;
    }

    /**
     * C {@code BallCollision}: every ball that is alive, not phased and not resting in a treasure.
     * <p>
     * BEHAVIOR: ball race: an owned ball (connected to its owner with ballraceConnected) within
     * checkpointRadius blocks of the owner's next checkpoint passes it for the owner.
     * BEHAVIOR: with ballCollisions, nearby objects (not ships, asteroids, mines, items; sparks only with
     * ballSparkCollisions) whose path came within both radii: another free, unphased ball (each pair once)
     * bounces off (Obj_repel), or both die when their relative speed exceeds maxObjectWallBounceSpeed;
     * shots, missiles, sparks, debris and wreckage are absorbed (Delta_mv, the object dies).
     */
    public void collide() {
        Objects o = w.objects;
        ModeState m = w.modes;
        ModeParams mp = w.modeParams;
        int ignored = Obj.PLAYER | Obj.ASTEROID | Obj.MINE | Obj.ITEM;
        if (!mp.ballSparkCollisions) {
            ignored |= Obj.SPARK;
        }
        for (int k = 0; k < o.count(); k++) {
            int ball = o.active[k];
            if (o.type[ball] != Obj.BALL || o.life[ball] <= 0 || phased(ball)
                || m.treasureHave[m.ballTreasure[ball]]) {
                continue;
            }
            if (mp.timing && mp.ballrace && m.ballOwner[ball] != Obj.NO_ID) {
                Player owner = w.byId(m.ballOwner[ball]);
                if (owner != null && (!mp.ballraceConnect || o.owner[ball] == owner.id)
                    && sim.race.nearCheckpoint(o.px[ball], o.py[ball], owner.check)) {
                    sim.race.passCheckpoint(owner);
                }
            }
            if (!mp.ballCollisions) {
                continue;
            }
            w.grid.query(o.px[ball] / Const.BLOCK_SZ, o.py[ball] / Const.BLOCK_SZ, 4, 300);
            int n = w.grid.resultCount;
            for (int j = 0; j < n; j++) {
                int h = w.grid.result[j];
                int type = o.type[h];
                if ((type & ignored) != 0 || o.life[h] <= 0) {
                    continue;
                }
                if (type == Obj.BALL && h <= ball) {
                    continue;
                }
                if (!sim.collisions.inRange(o.prevX[ball], o.prevY[ball], o.px[ball], o.py[ball],
                    o.prevX[h], o.prevY[h], o.px[h], o.py[h], o.plRadius[ball] + o.plRadius[h])) {
                    continue;
                }
                switch (type) {
                    case Obj.BALL -> {
                        if (m.treasureHave[m.ballTreasure[h]] || phased(h)) {
                            break;
                        }
                        float dvx = o.vx[ball] - o.vx[h];
                        float dvy = o.vy[ball] - o.vy[h];
                        float max = w.params.maxObjectWallBounceSpeed;
                        if (dvx * dvx + dvy * dvy > max * max) {
                            o.life[ball] = 0;
                            o.life[h] = 0;
                        } else {
                            sim.objPhysics.repel(ball, h, o.plRadius[ball] + o.plRadius[h]);
                        }
                    }
                    case Obj.SPARK, Obj.TORPEDO, Obj.SMART_SHOT, Obj.HEAT_SHOT, Obj.SHOT, Obj.CANNON_SHOT,
                         Obj.DEBRIS, Obj.WRECKAGE -> {
                        sim.objPhysics.deltaMv(ball, h);
                        o.life[h] = 0;
                    }
                    default -> { }
                }
            }
        }
    }

    // ================================================================ round reset

    /**
     * The ball and treasure part of C {@code Reset_all_players} (team play): every ball is deleted without
     * being recreated, then every treasure gets a fresh ball and the team treasure counters start over.
     * <p>
     * BEHAVIOR: balls are deleted last first, detached (C sets owner 0, which matters only for the never-owned
     * check, so they do not explode either); treasures lose their destroyed count.
     */
    public void resetRound() {
        Objects o = w.objects;
        ModeState m = w.modes;
        for (int k = o.count() - 1; k >= 0; k--) {
            int h = o.active[k];
            if ((o.type[h] & Obj.BALL) != 0) {
                o.owner[h] = Obj.NO_ID;
                o.life[h] = 0;
                m.ballOwner[h] = 0;
                o.status[h] &= ~Obj.RECREATE;
                sim.shots.delete(h);
            }
        }
        for (int i = 0; i < m.treasureHave.length; i++) {
            m.treasureDestroyed[i] = 0;
            m.treasureHave[i] = false;
            makeTreasureBall(i);
        }
        for (int t = 0; t < Const.MAX_TEAMS; t++) {
            m.teamTreasuresDestroyed[t] = 0;
            m.teamTreasuresLeft[t] = m.teamTreasures[t] - m.teamEmptyTreasures[t];
        }
    }
}
