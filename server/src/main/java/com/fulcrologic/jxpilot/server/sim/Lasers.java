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
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Pulses;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Lasers: ports of {@code Fire_laser} and {@code Fire_general_laser} ({@code src/server/shot.c}) and
 * {@code Laser_pulse_collision} with its helpers ({@code src/server/laser.c}). Pulses move PULSE_SPEED px per
 * frame; each is sampled every PULSE_SAMPLE_DISTANCE px along its length with a scratch OBJ_PULSE object moved
 * through the walls ({@link Mover#moveObject}), as C does.
 */
public final class Lasers {

    // ---- message texts as fields: resolved when the class loads, never inside a tick (no allocation)
    private static final String T_EMPTY = Texts.of("");
    private static final String T_CANNON = Texts.of("Cannon");
    private static final String T_GOT_PARALYSED_BY = Texts.of(" got paralysed by ");
    private static final String T_GOT_PARALYSED_BY_A_STUN = Texts.of(" got paralysed by a stun laser.");
    private static final String T_GOT_ROASTED_ALIVE_BY = Texts.of(" got roasted alive by ");
    private static final String T_GOT_ROASTED_ALIVE_BY_CANNONFIRE = Texts.of(" got roasted alive by cannonfire.");
    private static final String T_HOW_STRANGE = Texts.of(" How strange!");
    private static final String T_S_LASER = Texts.of("'s laser.");
    private static final String T_S_STUN_LASER = Texts.of("'s stun laser.");

    /** C {@code PULSE_SPEED}, {@code PULSE_SAMPLE_DISTANCE}, {@code PULSE_LENGTH}. */
    public static final int PULSE_SPEED = 90;
    public static final int PULSE_SAMPLE_DISTANCE = 5;
    public static final int PULSE_LENGTH = PULSE_SPEED - PULSE_SAMPLE_DISTANCE;
    /** C {@code ED_LASER}, {@code ED_LASER_HIT}. */
    static final long ED_LASER = -10 * 256;
    static final long ED_LASER_HIT = -100 * 256;
    /** C {@code ED_LASER_HIT} as the double score.h:44 defines (the asteroid fuel-hit formula uses it uncast). */
    static final double ED_LASER_HIT_F = -100.0 * 256;

    private final World w;
    private final Simulation sim;
    // ---- victim buffer (C vicbuf_t), preallocated
    private final Player[] vic;
    private final float[] vicX;
    private final float[] vicY;
    private final float[] vicPrev;
    private int numVic;
    /** Set by {@link #hitPlayer} when the pulse was reflected by a mirror. */
    private boolean refl;

    public Lasers(World w, Simulation sim) {
        this.nearAst = new int[w.modes.asteroidList.length];
        this.w = w;
        this.sim = sim;
        int n = w.players.length;
        vic = new Player[n];
        vicX = new float[n];
        vicY = new float[n];
        vicPrev = new float[n];
    }

    /** C {@code PULSE_LIFE(lasers)} = 4.5 + lasers / 4 (integer division). */
    static double pulseLife(int lasers) {
        return 4.5 + (lasers / 4);
    }

    /**
     * C {@code Fire_laser} (every frame while HAS_LASER is used).
     * <p>
     * BEHAVIOR: fires only with more lasers than own pulses in flight and slower than PULSE_SPEED -
     * PULSE_SAMPLE_DISTANCE; with 10 or less fuel the laser goes off instead. The pulse starts at the main gun one
     * frame ahead (wrapped), if that is in the world.
     */
    public void fire(Player pl) {
        if (pl.items[Item.LASER.ordinal()] > pl.numPulses && pl.velocity < PULSE_SPEED - PULSE_SAMPLE_DISTANCE) {
            if (pl.fuel <= -ED_LASER) {
                pl.used &= ~Has.LASER;
            } else {
                float x = pl.px + pl.ship.gunX[pl.dir] + pl.vx;
                float y = pl.py + pl.ship.gunY[pl.dir] + pl.vy;
                x = (float) w.wrapXPixel(x);
                y = (float) w.wrapYPixel(y);
                if (x >= 0 && x < w.pixelWidth && y >= 0 && y < w.pixelHeight) {
                    fireGeneral(pl, pl.team, x, y, pl.dir, pl.mods);
                }
            }
        }
    }

    /**
     * C {@code Fire_general_laser}.
     * <p>
     * BEHAVIOR: a player pays ED_LASER (even when the pulse list is full), FIRE_LASER plays at (x, y) and its
     * pulse lives
     * (int)PULSE_LIFE(lasers); a cannon's lives (int)PULSE_LIFE(1). The pulse starts one PULSE_SPEED back (it moves
     * before it is checked), length PULSE_LENGTH, not reflected; the player's pulse count goes up.
     */
    public void fireGeneral(Player pl, int team, float x, float y, int dir, int mods) {
        int life;
        if (pl != null) {
            pl.addFuel(ED_LASER);
            Sounds.play(w, x, y, SoundEvent.FIRE_LASER);
            life = (int) pulseLife(pl.items[Item.LASER.ordinal()]);
        } else {
            life = (int) pulseLife(1);
        }
        Pulses p = w.pulses;
        int i = p.add();
        if (i < 0) {
            return;
        }
        p.owner[i] = pl != null ? pl.id : Obj.NO_ID;
        p.team[i] = team;
        p.dir[i] = dir;
        p.len[i] = PULSE_LENGTH;
        p.life[i] = life;
        p.mods[i] = mods;
        p.refl[i] = false;
        p.x[i] = x - PULSE_SPEED * Trig.tcos(dir);
        p.y[i] = y - PULSE_SPEED * Trig.tsin(dir);
        if (pl != null) {
            pl.numPulses++;
        }
    }

    /** C {@code Laser_pulse_destroy_one}: the owner's pulse count drops; the last pulse fills the slot. */
    void destroy(int i) {
        Pulses p = w.pulses;
        if (p.owner[i] != Obj.NO_ID) {
            Player pl = w.byId(p.owner[i]);
            if (pl != null) {
                pl.numPulses--;
            }
        }
        p.remove(i);
    }

    /** C Delete_player's pulse cleanup: every pulse of {@code pl} goes and its count is 0. */
    public void removeOwnedBy(Player pl) {
        Pulses p = w.pulses;
        for (int i = 0; i < p.count(); i++) {
            if (p.owner[i] == pl.id) {
                p.remove(i);
                i--;
            }
        }
        pl.numPulses = 0;
    }

    /**
     * C {@code Laser_pulse_collision}: move every pulse (last first) and check it against walls and players.
     * <p>
     * BEHAVIOR: a scratch OBJ_PULSE object is allocated for wall checks (when the pool is full every pulse is
     * destroyed instead) and left with life 0 to be deleted with the expired objects, as C does.
     * BEHAVIOR: a pulse past its life or shorter than PULSE_LENGTH is destroyed; otherwise it advances
     * PULSE_SPEED. On wrapping maps it wraps; otherwise one starting outside the world gets length 0 and one
     * reaching an edge is cut there.
     * BEHAVIOR: outside the round delay the players near the pulse's midpoint are its possible victims. The pulse
     * is sampled from its start every PULSE_SAMPLE_DISTANCE px: the scratch object moves through the walls (a
     * wall or cannon ends the pulse there, cannons are destroyed as by shots) and the first victim within SHIP_SZ
     * is hit ({@link #hitPlayer}); victims moving away are dropped.
     * BEHAVIOR: a pulse that stopped early and was not reflected is shortened to where it stopped.
     */
    public void collide() {
        Objects o = w.objects;
        Pulses p = w.pulses;
        int h = o.alloc();
        if (h < 0) {
            for (int i = p.count() - 1; i >= 0; --i) {
                destroy(i);
            }
            return;
        }
        numVic = 0;
        for (int i = p.count() - 1; i >= 0; --i) {
            if (--p.life[i] < 0 || p.len[i] < PULSE_LENGTH) {
                destroy(i);
                continue;
            }
            p.x[i] += Trig.tcos(p.dir[i]) * PULSE_SPEED;
            p.y[i] += Trig.tsin(p.dir[i]) * PULSE_SPEED;
            float x1;
            float y1;
            float x2;
            float y2;
            if (w.wrap) {
                if (p.x[i] < 0) {
                    p.x[i] += w.pixelWidth;
                } else if (p.x[i] >= w.pixelWidth) {
                    p.x[i] -= w.pixelWidth;
                }
                if (p.y[i] < 0) {
                    p.y[i] += w.pixelHeight;
                } else if (p.y[i] >= w.pixelHeight) {
                    p.y[i] -= w.pixelHeight;
                }
                x1 = p.x[i];
                y1 = p.y[i];
                x2 = x1 + Trig.tcos(p.dir[i]) * p.len[i];
                y2 = y1 + Trig.tsin(p.dir[i]) * p.len[i];
            } else {
                x1 = p.x[i];
                y1 = p.y[i];
                if (x1 < 0 || x1 >= w.pixelWidth || y1 < 0 || y1 >= w.pixelHeight) {
                    p.len[i] = 0;
                    continue;
                }
                x2 = x1 + Trig.tcos(p.dir[i]) * p.len[i];
                if (x2 < 0) {
                    p.len[i] = (int) (p.len[i] * (0 - x1) / (x2 - x1));
                    x2 = x1 + Trig.tcos(p.dir[i]) * p.len[i];
                }
                if (x2 >= w.pixelWidth) {
                    p.len[i] = (int) (p.len[i] * (w.pixelWidth - 1 - x1) / (x2 - x1));
                    x2 = x1 + Trig.tcos(p.dir[i]) * p.len[i];
                }
                y2 = y1 + Trig.tsin(p.dir[i]) * p.len[i];
                if (y2 < 0) {
                    p.len[i] = (int) (p.len[i] * (0 - y1) / (y2 - y1));
                    x2 = x1 + Trig.tcos(p.dir[i]) * p.len[i];
                    y2 = y1 + Trig.tsin(p.dir[i]) * p.len[i];
                }
                if (y2 > w.pixelHeight) {
                    p.len[i] = (int) (p.len[i] * (w.pixelHeight - 1 - y1) / (y2 - y1));
                    x2 = x1 + Trig.tcos(p.dir[i]) * p.len[i];
                    y2 = y1 + Trig.tsin(p.dir[i]) * p.len[i];
                }
                if (p.len[i] <= 0) {
                    p.len[i] = 0;
                    continue;
                }
            }
            float dx = (float) w.wrapDx(x2 - x1);
            float dy = (float) w.wrapDy(y2 - y1);
            int max = (int) Math.max(Math.abs(dx), Math.abs(dy));
            if (max == 0) {
                continue;
            }
            float midx = (float) (x1 + (dx * 0.5));
            float midy = (float) (y1 + (dy * 0.5));
            midx = (float) w.wrapXPixel(midx);
            midy = (float) w.wrapYPixel(midy);
            if (w.roundDelay == 0) {
                findVictims(i, midx, midy);
            }
            nearAsteroids(i, midx, midy);
            o.type[h] = Obj.PULSE;
            o.life[h] = 1;
            o.owner[h] = p.owner[i];
            o.team[h] = p.team[i];
            o.counter[h] = 0;
            o.status[h] = p.owner[i] == Obj.NO_ID ? Obj.FROMCANNON : 0;
            o.initPixels(h, x1, y1);
            refl = false;
            int s;
            int hits = 0;
            for (s = 0; s <= max; s += PULSE_SAMPLE_DISTANCE) {
                float x = x1 + (s * dx) / max;
                float y = y1 + (s * dy) / max;
                o.vx[h] = x - (float) Click.toFloat(o.cx[h]);
                o.vy[h] = y - (float) Click.toFloat(o.cy[h]);
                sim.mover.moveObject(h);
                if (o.life[h] == 0) {
                    break;
                }
                if (w.wrap) {
                    if (x < 0) {
                        x += w.pixelWidth;
                        x1 += w.pixelWidth;
                    } else if (x >= w.pixelWidth) {
                        x -= w.pixelWidth;
                        x1 -= w.pixelWidth;
                    }
                    if (y < 0) {
                        y += w.pixelHeight;
                        y1 += w.pixelHeight;
                    } else if (y >= w.pixelHeight) {
                        y -= w.pixelHeight;
                        y1 -= w.pixelHeight;
                    }
                }
                hitAsteroid(i, h, x, y);
                if (o.life[h] == 0) {
                    continue; // C: "pulse hit asteroid"; the next sample's move ends the pulse
                }
                if (w.roundDelay > 0) {
                    continue;
                }
                hits = checkPlayerHits(i, h, x, y);
                if (hits > 0) {
                    break;
                }
            }
            if (s < max && !refl) {
                p.len[i] = (p.len[i] * s) / max;
            }
        }
        o.type[h] = Obj.DEBRIS;
        o.life[h] = 0;
    }

    /** C {@code Laser_pulse_get_object_list}'s result: asteroid handles near the current pulse. */
    private final int[] nearAst;
    private int numNearAst;

    /**
     * C {@code Laser_pulse_get_object_list} (laser.c:375-415): the asteroids, in list order, whose centre is
     * closer than {@code pl_radius + len / 2} to the pulse midpoint.
     */
    void nearAsteroids(int i, float midx, float midy) {
        com.fulcrologic.jxpilot.server.world.ModeState m = w.modes;
        numNearAst = 0;
        Objects o = w.objects;
        int len = w.pulses.len[i];
        for (int k = 0; k < m.asteroidCount; k++) {
            int a = m.asteroidList[k];
            double dx = w.wrapDx(midx - Click.toFloat(o.cx[a]));
            double dy = w.wrapDy(midy - Click.toFloat(o.cy[a]));
            int range = o.plRadius[a] + len / 2;
            if (dx * dx + dy * dy < range * range) {
                nearAst[numNearAst++] = a;
            }
        }
    }

    /**
     * The asteroid part of C's pulse sampling (laser.c:600-630): the first shortlisted asteroid whose radius covers
     * the sample point ends the pulse ({@code life} 0) and loses ASTEROID_FUEL_HIT(ED_LASER_HIT, size) life (at least
     * down to 0); an asteroid brought to 0 by a player's pulse scores asteroidPoints for that player while its score
     * is at most asteroidMaxScore.
     */
    void hitAsteroid(int i, int h, float x, float y) {
        Objects o = w.objects;
        for (int k = 0; k < numNearAst; k++) {
            int a = nearAst[k];
            double adx = w.wrapDx(x - Click.toFloat(o.cx[a]));
            double ady = w.wrapDy(y - Click.toFloat(o.cy[a]));
            if (adx * adx + ady * ady <= (double) o.plRadius[a] * o.plRadius[a]) {
                o.life[h] = 0;
                o.life[a] = (long) (o.life[a] + Asteroids.fuelHit(ED_LASER_HIT_F, o.size[a]));
                if (o.life[a] < 0) {
                    o.life[a] = 0;
                }
                Player pl = w.pulses.owner[i] == Obj.NO_ID ? null : w.byId(w.pulses.owner[i]);
                com.fulcrologic.jxpilot.server.world.ModeParams mp = w.modeParams;
                if (o.life[a] == 0 && pl != null && mp.asteroidPoints > 0 && pl.score <= mp.asteroidMaxScore) {
                    sim.scoring.score(pl, mp.asteroidPoints, o.px[a] / Const.BLOCK_SZ, o.py[a] / Const.BLOCK_SZ,
                        T_EMPTY);
                }
                return;
            }
        }
    }

    /**
     * C {@code Laser_pulse_find_victims}: playing, unphased players within len/2 + SHIP_SZ of the pulse midpoint,
     * except: self with selfImmunity, own tanks with selfImmunity, team-immune players, a cannon pulse's team
     * with team immunity, and the owner unless reflected.
     */
    void findVictims(int i, float midx, float midy) {
        Pulses p = w.pulses;
        com.fulcrologic.jxpilot.server.world.Params q = w.params;
        numVic = 0;
        int owner = p.owner[i];
        for (int k = 0; k < w.numPlayers; k++) {
            Player v = w.playerAt(k);
            if ((v.status & (Obj.PLAYING | Obj.GAME_OVER | Obj.KILLED | Obj.PAUSE)) != Obj.PLAYING) {
                continue;
            }
            if ((v.used & Has.PHASING_DEVICE) != 0) {
                continue;
            }
            if (v.id == owner && q.selfImmunity) {
                continue;
            }
            if (q.selfImmunity && v.tank && v.lockPlId == owner) {
                continue;
            }
            if (w.teamImmune(v.id, owner)) {
                continue;
            }
            if (owner == Obj.NO_ID && q.teamImmunity && q.teamPlay && p.team[i] == v.team) {
                continue;
            }
            if (v.id == owner && !p.refl[i]) {
                continue;
            }
            float dist = w.wrapLength(v.px - midx, v.py - midy);
            if (dist > p.len[i] / 2 + Const.SHIP_SZ) {
                continue;
            }
            vic[numVic] = v;
            vicX[numVic] = v.px;
            vicY[numVic] = v.py;
            vicPrev[numVic] = 1e10f;
            numVic++;
        }
    }

    /**
     * C {@code Laser_pulse_check_player_hits}: the first victim (last first) within SHIP_SZ of the sample point is
     * hit; victims no closer than at the previous sample are dropped.
     *
     * @return the number of hits (0 or 1)
     */
    int checkPlayerHits(int i, int h, float x, float y) {
        for (int j = numVic - 1; j >= 0; --j) {
            float dist = w.wrapLength(x - vicX[j], y - vicY[j]);
            if (dist <= Const.SHIP_SZ) {
                hitPlayer(i, h, x, y, vic[j]);
                return 1;
            } else if (dist >= vicPrev[j]) {
                --numVic;
                vic[j] = vic[numVic];
                vicX[j] = vicX[numVic];
                vicY[j] = vicY[numVic];
                vicPrev[j] = vicPrev[numVic];
            } else {
                vicPrev[j] = dist;
            }
        }
        return 0;
    }

    /**
     * C {@code Laser_pulse_hits_player}.
     * <p>
     * BEHAVIOR: the victim is seen (forceVisible+1). A mirror reflects the pulse with chance 1 - 1/(2*mirrors):
     * it restarts half a sample back, turns about the line to the victim, gains a frame per mirror, gets its full
     * length back and is marked reflected.
     * BEHAVIOR: an unreflected hit plays PLAYER_EAT_LASER at the victim. A fully shielded victim is unhurt;
     * nothing happens unless pulses are killing shots.
     * BEHAVIOR: a stun pulse (or laserIsStunGun without laser modifiers) paralyses a shielded, lasering, shooting
     * or thrusting victim for 5 more frames ("X got paralysed by Y's stun laser."); a blinding pulse blinds for
     * FPS+6 frames (and makes it visible, the shooter recorded as a shover until then).
     * BEHAVIOR: otherwise the hit costs ED_LASER_HIT fuel; an unshielded victim without armor is roasted
     * (PLAYER_ROASTED plays at it): a
     * player's laser scores Rate(shooter, victim) * laserKillScoreMult (a kill), the own laser
     * -Rate(0)*laserKillScoreMult*selfKillScoreMult ("How strange!"), a cannon's -Rate(CANNON_SCORE)/4 for the
     * cannon's team; an unshielded victim with armor loses an armor.
     */
    void hitPlayer(int i, int h, float x, float y, Player v) {
        Pulses p = w.pulses;
        Player pl = p.owner[i] != Obj.NO_ID ? w.byId(p.owner[i]) : null;
        v.forceVisible++;
        if ((v.have & Has.MIRROR) != 0 && (w.rfrac() * (2 * v.items[Item.MIRROR.ordinal()])) >= 1) {
            p.x[i] = (float) (x - Trig.tcos(p.dir[i]) * 0.5 * PULSE_SAMPLE_DISTANCE);
            p.y[i] = (float) (y - Trig.tsin(p.dir[i]) * 0.5 * PULSE_SAMPLE_DISTANCE);
            p.dir[i] = sim.missiles.wrapFindDir(v.px - p.x[i], v.py - p.y[i]) * 2 - Const.RES / 2 - p.dir[i];
            p.dir[i] = Trig.mod(p.dir[i]);
            p.life[i] += v.items[Item.MIRROR.ordinal()];
            p.len[i] = PULSE_LENGTH;
            p.refl[i] = true;
            refl = true;
            return;
        }
        Sounds.play(w, v.px, v.py, SoundEvent.PLAYER_EAT_LASER);
        if (v.fullyShielded()) {
            return;
        }
        if ((w.objects.type[h] & w.params.killingShots) == 0) {
            return;
        }
        int mods = p.mods[i];
        if ((Mods.laser(mods) & Mods.STUN) != 0 || (w.items.laserIsStunGun && !w.items.allowLaserModifiers)) {
            if ((v.used & (Has.SHIELD | Has.LASER | Has.SHOT)) != 0 || (v.status & Obj.THRUSTING) != 0) {
                StringBuilder msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(v.name);
                if (pl != null) {
                    msg.append(T_GOT_PARALYSED_BY).append(pl.name).append(T_S_STUN_LASER);
                    if (v.id == pl.id) {
                        msg.append(T_HOW_STRANGE);
                    }
                } else {
                    msg.append(T_GOT_PARALYSED_BY_A_STUN);
                }
                v.used &= ~(Has.SHIELD | Has.LASER | Has.SHOT);
                v.status &= ~Obj.THRUSTING;
                v.stunned += 5;
            }
        } else if ((Mods.laser(mods) & Mods.BLIND) != 0) {
            int fps = w.params.fps;
            v.damaged += fps + 6;
            v.forceVisible += fps + 6;
            if (pl != null) {
                sim.ships.recordShove(v, pl, w.frames + fps + 6);
            }
        } else {
            v.addFuel(ED_LASER_HIT);
            if ((v.used & Has.SHIELD) == 0 && (v.have & Has.ARMOR) == 0) {
                v.status |= Obj.KILLED;
                StringBuilder msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(v.name);
                if (pl != null) {
                    msg.append(T_GOT_ROASTED_ALIVE_BY).append(pl.name).append(T_S_LASER);
                    if (v.id == pl.id) {
                        w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, Obj.NO_ID, v.id, 0f);
                        float sc = Scoring.rate(0, pl.score) * w.items.laserKillScoreMult * w.params.selfKillScoreMult;
                        sim.scoring.score(v, -sc, v.bx, v.by, v.name);
                        msg.append(T_HOW_STRANGE);
                    } else {
                        w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, pl.id, v.id, 0f);
                        float sc = Scoring.rate(pl.score, v.score) * w.items.laserKillScoreMult;
                        sim.scoring.scorePlayers(pl, sc, v.name, v, -sc, pl.name);
                    }
                } else {
                    w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, Obj.NO_ID, v.id, 0f);
                    float sc = Scoring.rate(Scoring.CANNON_SCORE, v.score) / 4;
                    sim.scoring.score(v, -sc, v.bx, v.by, T_CANNON);
                    if (w.params.teamPlay && v.team != p.team[i]) {
                        sim.scoring.teamScore(p.team[i], sc);
                    }
                    msg.append(T_GOT_ROASTED_ALIVE_BY_CANNONFIRE);
                }
                Sounds.play(w, v.px, v.py, SoundEvent.PLAYER_ROASTED);
                if (pl != null && pl.id != v.id) {
                    pl.kills++;
                }
            }
            if ((v.used & Has.SHIELD) == 0 && (v.have & Has.ARMOR) != 0) {
                sim.ships.hitArmor(v);
            }
        }
    }
}
