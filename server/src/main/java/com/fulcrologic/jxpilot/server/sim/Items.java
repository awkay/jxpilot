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
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Concentrator;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ItemParams;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * The item world: ports of {@code Place_item}, {@code Make_item}, {@code Choose_random_item},
 * {@code Throw_items}, {@code Detonate_items}, {@code Item_damage}, {@code Item_update_flags} and
 * {@code do_lose_item} ({@code src/server/item.c}), the random item spawn of {@code Update_objects}
 * (update.c:517-523), {@code Player_collides_with_item} and the item counting helpers of
 * {@code src/server/collision.c}, the {@code KEY_SELECT_ITEM} key (event.c) and the item case of
 * {@code Delete_shot} (shot.c:1332-1361, 1378-1404).
 */
public final class Items {
    /** C {@code ITEM_SIZE}. */
    static final int ITEM_SIZE = 16;
    /** C {@code WARN_TIME}: seconds a missile/mine pack blinks before it goes rogue. */
    static final int WARN_TIME = 2;
    /** C {@code SPACE_BLOCKS} (walls.c:58). */
    static final int SPACE_BLOCKS = BlockType.bit(BlockType.SPACE) | BlockType.bit(BlockType.BASE)
        | BlockType.bit(BlockType.WORMHOLE) | BlockType.bit(BlockType.POS_GRAV) | BlockType.bit(BlockType.NEG_GRAV)
        | BlockType.bit(BlockType.CWISE_GRAV) | BlockType.bit(BlockType.ACWISE_GRAV)
        | BlockType.bit(BlockType.UP_GRAV) | BlockType.bit(BlockType.DOWN_GRAV) | BlockType.bit(BlockType.RIGHT_GRAV)
        | BlockType.bit(BlockType.LEFT_GRAV) | BlockType.bit(BlockType.DECOR_LU) | BlockType.bit(BlockType.DECOR_LD)
        | BlockType.bit(BlockType.DECOR_RU) | BlockType.bit(BlockType.DECOR_RD)
        | BlockType.bit(BlockType.DECOR_FILLED) | BlockType.bit(BlockType.CHECK)
        | BlockType.bit(BlockType.ITEM_CONCENTRATOR) | BlockType.bit(BlockType.FRICTION)
        | BlockType.bit(BlockType.ASTEROID_CONCENTRATOR);
    /** C {@code IsOffensiveItem}. */
    static final int OFFENSIVE = Item.WIDEANGLE.bit() | Item.REARSHOT.bit() | Item.MINE.bit() | Item.MISSILE.bit()
        | Item.LASER.bit();
    /** C {@code IsDefensiveItem}. */
    static final int DEFENSIVE = Item.CLOAK.bit() | Item.ECM.bit() | Item.TRANSPORTER.bit() | Item.TRACTOR_BEAM.bit()
        | Item.EMERGENCY_SHIELD.bit() | Item.MIRROR.bit() | Item.DEFLECTOR.bit() | Item.HYPERJUMP.bit()
        | Item.PHASING.bit() | Item.TANK.bit() | Item.ARMOR.bit();
    static final int FUEL_OR_TANK = Item.FUEL.bit() | Item.TANK.bit();
    /** C {@code ITEM_*} ordinals used in hot paths. */
    static final int FUEL = Item.FUEL.ordinal();
    static final int TANK = Item.TANK.ordinal();
    static final int MINE = Item.MINE.ordinal();
    static final int MISSILE = Item.MISSILE.ordinal();

    private final World w;
    private final Simulation sim;

    public Items(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    /** C {@code ENERGY_PACK_FUEL}: (500 + (randomMT() &amp; 511)) fuel units in fixed point. */
    double energyPackFuel() {
        return (500 + (w.randomMT() & 511)) << 8;
    }

    // ================================================================ spawning

    /**
     * The random item spawn of {@code Update_objects} (update.c:517-523).
     * <p>
     * BEHAVIOR: for each item kind with fewer than its world maximum floating and a chance &gt; 0, an item is
     * placed when rfrac() * chance &lt; 1 (one rfrac() per eligible kind, in item order).
     */
    public void spawn() {
        ItemParams ip = w.items;
        for (int i = 0; i < Item.COUNT; i++) {
            if (w.itemCount[i] < ip.max[i] && ip.chance[i] > 0 && (w.rfrac() * ip.chance[i]) < 1.0f) {
                place(i, null);
            }
        }
    }

    /**
     * C {@code Choose_random_item}: an item kind weighted by the item probabilities.
     * <p>
     * BEHAVIOR: with no positive probability sum the result is {@link Item#COUNT} (C returns NUM_ITEMS);
     * otherwise the first kind where the running sum reaches rfrac() * total, or FUEL if rounding runs past
     * the end.
     */
    public int chooseRandom() {
        return chooseRandom(w.rfrac());
    }

    /** {@link #chooseRandom()} with the random draw given (the snapshot builder uses its own RNG). */
    public int chooseRandom(double r) {
        ItemParams ip = w.items;
        float total = 0;
        for (int i = 0; i < Item.COUNT; i++) {
            total += ip.prob[i];
        }
        if (total > 0.0) {
            float sum = (float) (total * r);
            int i;
            for (i = 0; i < Item.COUNT; i++) {
                sum -= ip.prob[i];
                if (sum <= 0) {
                    break;
                }
            }
            return i >= Item.COUNT ? FUEL : i;
        }
        return Item.COUNT;
    }

    /**
     * C {@code Place_item}: a random item in the world ({@code pl == null}), or one dropped by a player.
     * <p>
     * BEHAVIOR: a full pool loses the item: a living player's count drops by one, nothing is made.
     * BEHAVIOR: a killed player loses everything above the initial count; the pack holds
     * (int)(lost * dropItemOnKillProb) unless that is below the minimum pack size (then nothing drops).
     * BEHAVIOR: a living player drops a pack of a random size between the pack limits, at most what it has.
     * BEHAVIOR: a player's item appears at its previous position (a living player's offset by a block plus up
     * to 7 px against its motion), wrapped; on a non-space block nothing is made. It falls with GRAVITY and
     * moves with the ship (a living player's at 0.75 of it minus up to 7 px/frame, a dead player's plus a
     * random 0-6 px/frame push).
     * BEHAVIOR: a world item is moving (GRAVITY) with movingItemProb, a RANDOM_ITEM with randomItemProb, and
     * near a random item concentrator with itemConcentratorProb (radius itemConcentratorRadius blocks); it gets
     * up to 8 tries for a space or cannon block, else nothing is made. A moving one starts against the local
     * gravity times Gravity (C multiplies by it twice) plus a random -3..4 px/frame.
     */
    public void place(int item, Player pl) {
        Objects o = w.objects;
        ItemParams ip = w.items;
        if (o.count() >= o.capacity) {
            if (pl != null && (pl.status & Obj.KILLED) == 0) {
                pl.items[item]--;
            }
            return;
        }
        int numPerPack;
        if (pl != null) {
            if ((pl.status & Obj.KILLED) != 0) {
                int numLose = pl.items[item] - ip.initial[item];
                if (numLose <= 0) {
                    return;
                }
                pl.items[item] -= numLose;
                numPerPack = (int) (numLose * ip.dropItemOnKillProb);
                if (numPerPack < ip.minPerPack[item]) {
                    return;
                }
            } else {
                int numLose = pl.items[item];
                if (numLose <= 0) {
                    return;
                }
                numPerPack = packSize(item);
                if (numPerPack > numLose) {
                    numPerPack = numLose;
                } else {
                    numLose = numPerPack;
                }
                pl.items[item] -= numLose;
            }
        } else {
            numPerPack = packSize(item);
        }

        int grav;
        int rand;
        int px;
        int py;
        int bx;
        int by;
        if (pl != null) {
            grav = Obj.GRAVITY;
            rand = 0;
            px = pl.prevX;
            py = pl.prevY;
            if ((pl.status & Obj.KILLED) == 0) {
                if (pl.vx >= 0) {
                    px -= Const.BLOCK_SZ + (int) (w.rfrac() * 8);
                } else {
                    px += Const.BLOCK_SZ + (int) (w.rfrac() * 8);
                }
                if (pl.vy >= 0) {
                    py -= Const.BLOCK_SZ + (int) (w.rfrac() * 8);
                } else {
                    py += Const.BLOCK_SZ + (int) (w.rfrac() * 8);
                }
            }
            if (px < 0) {
                px += w.pixelWidth;
            } else if (px >= w.pixelWidth) {
                px -= w.pixelWidth;
            }
            if (py < 0) {
                py += w.pixelHeight;
            } else if (py >= w.pixelHeight) {
                py -= w.pixelHeight;
            }
            bx = px / Const.BLOCK_SZ;
            by = py / Const.BLOCK_SZ;
            if (!inBlocks(bx, by, SPACE_BLOCKS)) {
                return;
            }
        } else {
            grav = w.rfrac() < ip.movingItemProb ? Obj.GRAVITY : 0;
            rand = w.rfrac() < ip.randomItemProb ? Obj.RANDOM_ITEM : 0;
            Concentrator con = null;
            int ncon = w.map.itemConcentrators().size();
            if (ncon > 0 && w.rfrac() < ip.itemConcentratorProb) {
                con = w.map.itemConcentrators().get((int) (w.rfrac() * ncon));
            }
            int placeCount = 0;
            for (;; placeCount++) {
                if (placeCount >= 8) {
                    return;
                }
                if (con != null) {
                    int dir = (int) (w.rfrac() * Const.RES);
                    int dist = (int) (w.rfrac() * ((ip.itemConcentratorRadius * Const.BLOCK_SZ) + 1));
                    px = (int) ((con.bx() + 0.5) * Const.BLOCK_SZ + dist * Trig.tcos(dir));
                    py = (int) ((con.by() + 0.5) * Const.BLOCK_SZ + dist * Trig.tsin(dir));
                    if (w.wrap) {
                        if (px < 0) {
                            px += w.pixelWidth;
                        }
                        if (px >= w.pixelWidth) {
                            px -= w.pixelWidth;
                        }
                        if (py < 0) {
                            py += w.pixelHeight;
                        }
                        if (py >= w.pixelHeight) {
                            py -= w.pixelHeight;
                        }
                    }
                    if (px < 0 || px >= w.pixelWidth || py < 0 || py >= w.pixelHeight) {
                        continue;
                    }
                } else {
                    px = (int) (w.rfrac() * w.pixelWidth);
                    py = (int) (w.rfrac() * w.pixelHeight);
                }
                bx = px / Const.BLOCK_SZ;
                by = py / Const.BLOCK_SZ;
                if (inBlocks(bx, by, SPACE_BLOCKS | BlockType.bit(BlockType.CANNON))) {
                    break;
                }
            }
        }
        float vx = 0;
        float vy = 0;
        if (grav != 0) {
            if (pl != null) {
                vx += pl.vx;
                vy += pl.vy;
                if ((pl.status & Obj.KILLED) == 0) {
                    float vl = (float) StrictMath.hypot(vx, vy);
                    int dvx = (int) (w.rfrac() * 8);
                    int dvy = (int) (w.rfrac() * 8);
                    final float dropSpeedFactor = 0.75f;
                    vx *= dropSpeedFactor;
                    vy *= dropSpeedFactor;
                    if (vl < 1.0f) {
                        vx -= (pl.vx >= 0) ? dvx : -dvx;
                        vy -= (pl.vy >= 0) ? dvy : -dvy;
                    } else {
                        vx -= dvx * (vx / vl);
                        vy -= dvy * (vy / vl);
                    }
                } else {
                    float vel = (float) (w.rfrac() * 6);
                    int dir = (int) (w.rfrac() * Const.RES);
                    vx += Trig.tcos(dir) * vel;
                    vy += Trig.tsin(dir) * vel;
                }
            } else {
                // C item.c:332-333: Gravity * World.gravity[bx][by], where the field already includes Gravity
                vx -= ip.gravity * w.gravityX(bx, by);
                vy -= ip.gravity * w.gravityY(bx, by);
                vx += (int) (w.rfrac() * 8) - 3;
                vy += (int) (w.rfrac() * 8) - 3;
            }
        }
        make(px, py, (int) vx, (int) vy, item, numPerPack, grav | rand);
    }

    /** BEHAVIOR: min_per_pack when both pack limits agree (no rfrac() draw), else min + (int)(rfrac*(1+max-min)). */
    int packSize(int item) {
        ItemParams ip = w.items;
        if (ip.minPerPack[item] == ip.maxPerPack[item]) {
            return ip.maxPerPack[item];
        }
        return ip.minPerPack[item] + (int) (w.rfrac() * (1 + ip.maxPerPack[item] - ip.minPerPack[item]));
    }

    private boolean inBlocks(int bx, int by, int mask) {
        if (bx < 0 || bx >= w.width || by < 0 || by >= w.height) {
            return false;
        }
        return (BlockType.bit(w.map.block(bx, by)) & mask) != 0;
    }

    /**
     * C {@code Make_item} (velocity passed as C ints).
     * <p>
     * BEHAVIOR: nothing when the world already holds the maximum of this kind or the pool is full.
     * BEHAVIOR: an OBJ_ITEM of mass 10, life 1500 + (int)(rfrac*512), red, unowned, TEAM_NOT_SET, with the pack
     * size in {@code count}, the kind in {@code info}, pl_range = pl_radius = ITEM_SIZE/2; the kind's world
     * count goes up.
     *
     * @return the object handle or -1
     */
    public int make(int px, int py, int vx, int vy, int item, int numPerPack, int status) {
        if (w.itemCount[item] >= w.items.max[item]) {
            return -1;
        }
        Objects o = w.objects;
        int h = o.alloc();
        if (h < 0) {
            return -1;
        }
        o.type[h] = Obj.ITEM;
        o.info[h] = item;
        o.color[h] = Records.ObjColor.RED;
        o.status[h] = status;
        o.owner[h] = Obj.NO_ID;
        o.team[h] = Const.TEAM_NOT_SET;
        o.initPixels(h, px, py);
        o.vx[h] = vx;
        o.vy[h] = vy;
        o.accX[h] = 0;
        o.accY[h] = 0;
        o.mass[h] = 10.0f;
        o.life[h] = 1500 + (int) (w.rfrac() * 512);
        o.counter[h] = numPerPack;
        o.plRange[h] = ITEM_SIZE / 2;
        o.plRadius[h] = ITEM_SIZE / 2;
        w.itemCount[item]++;
        return h;
    }

    // ================================================================ death and damage

    /**
     * C {@code Throw_items}: a killed player drops what it has above the initial counts.
     * <p>
     * BEHAVIOR: nothing without dropItemOnKillProb; fuel and tanks never drop; for each other kind packs are
     * placed while the excess shrinks; the ability flags are updated at the end.
     */
    public void throwItems(Player pl) {
        if (w.items.dropItemOnKillProb == 0) {
            return;
        }
        for (int item = 0; item < Item.COUNT; item++) {
            if (((1 << item) & FUEL_OR_TANK) != 0) {
                continue;
            }
            int remain;
            int toThrow;
            do {
                toThrow = pl.items[item] - w.items.initial[item];
                if (toThrow <= 0) {
                    break;
                }
                place(item, pl);
                remain = pl.items[item] - w.items.initial[item];
            } while (remain > 0 && remain < toThrow);
        }
        updateFlags(pl);
    }

    /**
     * C {@code Detonate_items}: a killed player's mines and missiles above the initial counts may go off.
     * <p>
     * BEHAVIOR: nothing unless KILLED. Mine and missile counts drop by their initial counts (not below 0),
     * shields drop.
     * BEHAVIOR: each mine goes off with detonateItemOnKillProb as a GRAVITY mine at the ship with its
     * velocity plus up to 4 px/frame in a random direction (nuclear only with at least nukeMinMines left).
     * BEHAVIOR: each missile goes off with detonateItemOnKillProb while the shot limit allows, locked on a
     * random player, as a random torpedo, heat-seeker or smart missile in a random direction (nuclear only
     * with at least nukeMinSmarts left). As C's swapped arguments, they have team 0 and are cannon shots
     * (FROMCANNON) unless the ship is in team 0.
     * BEHAVIOR: as C, a tank's mines and missiles are launched for the tank's owner: the owner pays their fuel
     * and items and launches them (from the tank's position).
     */
    public void detonate(Player pl) {
        if ((pl.status & Obj.KILLED) == 0) {
            return;
        }
        Player owner = pl.tank ? w.byId(pl.lockPlId) : pl;
        if (owner == null) {
            owner = pl;
        }
        ItemParams ip = w.items;
        if ((pl.items[MINE] -= ip.initial[MINE]) < 0) {
            pl.items[MINE] = 0;
        }
        if ((pl.items[MISSILE] -= ip.initial[MISSILE]) < 0) {
            pl.items[MISSILE] = 0;
        }
        pl.used &= ~Has.SHIELD;
        for (int i = 0; i < pl.items[MINE]; i++) {
            if (w.rfrac() < ip.detonateItemOnKillProb) {
                int dir = (int) (w.rfrac() * Const.RES);
                float vel = (float) (w.rfrac() * 4.0f);
                int mods = pl.mods;
                if (Mods.isNuclear(mods) && pl.items[MINE] < ip.nukeMinMines) {
                    mods = Mods.withNuclear(mods, Mods.nuclear(mods) & ~Mods.NUCLEAR);
                }
                sim.mines.placeGeneral(owner, pl.team, Obj.GRAVITY, pl.px, pl.py,
                    pl.vx + vel * Trig.tcos(dir), pl.vy + vel * Trig.tsin(dir), mods);
            }
        }
        for (int i = 0; i < pl.items[MISSILE]; i++) {
            if (w.rfrac() < ip.detonateItemOnKillProb) {
                if (pl.shots >= pl.shotMax) {
                    break;
                }
                pl.lockTagged |= Player.LOCK_PLAYER;
                pl.lockPlId = w.playerAt((int) (w.rfrac() * w.numPlayers)).id;
                int type;
                switch ((int) (w.rfrac() * 3)) {
                    case 0 -> type = Obj.TORPEDO;
                    case 1 -> type = Obj.HEAT_SHOT;
                    default -> type = Obj.SMART_SHOT;
                }
                int mods = pl.mods;
                if (Mods.isNuclear(mods) && pl.items[MISSILE] < ip.nukeMinSmarts) {
                    mods = Mods.withNuclear(mods, Mods.nuclear(mods) & ~Mods.NUCLEAR);
                }
                // C item.c:484 calls Fire_general_shot(owner_ind, 0, pl->team, ...) against the signature
                // (ind, team, cannon, ...): team 0, and a cannon shot unless the ship is in team 0. Kept.
                sim.missiles.fire(owner, 0, pl.team != 0, pl.px, pl.py, type, (int) (w.rfrac() * Const.RES),
                    mods, -1);
            }
        }
    }

    /**
     * C {@code Item_damage}: after a collision or bounce the player may lose items.
     * <p>
     * BEHAVIOR: nothing when prob &gt;= 1. Otherwise for every kind but fuel and tanks that the player has, one
     * rfrac() draw; below prob the count becomes (int)(count * prob + 0.5). Flags are then updated.
     */
    public void damage(Player pl, float prob) {
        if (prob < 1.0f) {
            float loss = prob < 0 ? 0 : prob;
            for (int i = 0; i < Item.COUNT; i++) {
                if (((1 << i) & FUEL_OR_TANK) == 0 && pl.items[i] != 0) {
                    float f = (float) w.rfrac();
                    if (f < loss) {
                        pl.items[i] = (int) (pl.items[i] * loss + 0.5f);
                    }
                }
            }
            updateFlags(pl);
        }
    }

    /**
     * C {@code Item_update_flags}: drop the abilities whose items are gone.
     * <p>
     * BEHAVIOR: cloak (asking for a visibility update), mirror, deflector, afterburner, tractor beam and armor
     * go with their last item; phasing, emergency thrust and emergency shield only when also not in use and
     * not running; losing the last emergency shield also drops shields if they are not a default ability and
     * the shield timer ran out; losing the autopilot switches it off.
     */
    public void updateFlags(Player pl) {
        int[] it = pl.items;
        if (it[Item.CLOAK.ordinal()] <= 0 && (pl.have & Has.CLOAKING_DEVICE) != 0) {
            pl.have &= ~Has.CLOAKING_DEVICE;
            pl.updateVisibility = true;
        }
        if (it[Item.MIRROR.ordinal()] <= 0) {
            pl.have &= ~Has.MIRROR;
        }
        if (it[Item.DEFLECTOR.ordinal()] <= 0) {
            pl.have &= ~Has.DEFLECTOR;
        }
        if (it[Item.AFTERBURNER.ordinal()] <= 0) {
            pl.have &= ~Has.AFTERBURNER;
        }
        if (it[Item.PHASING.ordinal()] <= 0 && (pl.used & Has.PHASING_DEVICE) == 0 && pl.phasingLeft == 0) {
            pl.have &= ~Has.PHASING_DEVICE;
        }
        if (it[Item.EMERGENCY_THRUST.ordinal()] <= 0 && (pl.used & Has.EMERGENCY_THRUST) == 0
            && pl.emergencyThrustLeft == 0) {
            pl.have &= ~Has.EMERGENCY_THRUST;
        }
        if (it[Item.EMERGENCY_SHIELD.ordinal()] <= 0 && (pl.used & Has.EMERGENCY_SHIELD) == 0
            && pl.emergencyShieldLeft == 0) {
            if ((pl.have & Has.EMERGENCY_SHIELD) != 0) {
                pl.have &= ~Has.EMERGENCY_SHIELD;
                if ((w.params.defHave & Has.SHIELD) == 0 && pl.shieldTime <= 0) {
                    pl.have &= ~Has.SHIELD;
                    pl.used &= ~Has.SHIELD;
                }
            }
        }
        if (it[Item.TRACTOR_BEAM.ordinal()] <= 0) {
            pl.have &= ~Has.TRACTOR_BEAM;
        }
        if (it[Item.AUTOPILOT.ordinal()] <= 0) {
            if ((pl.used & Has.AUTOPILOT) != 0) {
                sim.devices.autopilot(pl, false);
            }
            pl.have &= ~Has.AUTOPILOT;
        }
        if (it[Item.ARMOR.ordinal()] <= 0) {
            pl.have &= ~Has.ARMOR;
        }
    }

    // ================================================================ keys

    /**
     * C {@code do_lose_item} (KEY_LOSE_ITEM).
     * <p>
     * BEHAVIOR: fuel, tanks and items the player does not have are not lost. Otherwise the item is dropped as a
     * pack ({@link #place}), or just destroyed with loseItemDestroys or while phased; flags are updated.
     */
    public void loseItem(Player pl) {
        int item = pl.loseItem;
        if (item < 0 || item >= Item.COUNT || ((1 << item) & FUEL_OR_TANK) != 0 || pl.items[item] <= 0) {
            return;
        }
        if (!w.items.loseItemDestroys && (pl.used & Has.PHASING_DEVICE) == 0) {
            place(item, pl);
        } else {
            pl.items[item]--;
        }
        updateFlags(pl);
    }

    /**
     * C KEY_SELECT_ITEM press.
     * <p>
     * BEHAVIOR: steps lose_item to the next kind (wrapping, skipping fuel and tanks) the player has, and shows
     * it (lose_item_state 2); with nothing to lose the selection ends where it started.
     */
    public void selectItem(Player pl) {
        for (int i = 0; i < Item.COUNT; i++) {
            if (++pl.loseItem >= Item.COUNT) {
                pl.loseItem = 0;
            }
            if (((1 << pl.loseItem) & FUEL_OR_TANK) != 0) {
                continue;
            }
            if (pl.items[pl.loseItem] > 0) {
                pl.loseItemState = 2;
                break;
            }
        }
    }

    // ================================================================ pickup

    /** The C {@code *_PICKUP_SOUND} each item case of {@code Player_collides_with_item} plays, by item ordinal. */
    private static final SoundEvent[] PICKUP_SOUND = {
        SoundEvent.ENERGY_PACK_PICKUP, SoundEvent.WIDEANGLE_SHOT_PICKUP, SoundEvent.BACK_SHOT_PICKUP,
        SoundEvent.AFTERBURNER_PICKUP, SoundEvent.CLOAKING_DEVICE_PICKUP, SoundEvent.SENSOR_PACK_PICKUP,
        SoundEvent.TRANSPORTER_PICKUP, SoundEvent.TANK_PICKUP, SoundEvent.MINE_PACK_PICKUP,
        SoundEvent.ROCKET_PACK_PICKUP, SoundEvent.ECM_PICKUP, SoundEvent.LASER_PICKUP,
        SoundEvent.EMERGENCY_THRUST_PICKUP, SoundEvent.TRACTOR_BEAM_PICKUP, SoundEvent.AUTOPILOT_PICKUP,
        SoundEvent.EMERGENCY_SHIELD_PICKUP, SoundEvent.DEFLECTOR_PICKUP, SoundEvent.HYPERJUMP_PICKUP,
        SoundEvent.PHASING_DEVICE_PICKUP, SoundEvent.MIRROR_PICKUP, SoundEvent.ARMOR_PICKUP
    };

    /** C {@code CountOffensiveItems}. */
    static int countOffensive(Player pl) {
        int[] it = pl.items;
        return it[Item.WIDEANGLE.ordinal()] + it[Item.REARSHOT.ordinal()] + it[MINE] + it[MISSILE]
            + it[Item.LASER.ordinal()];
    }

    /** C {@code CountDefensiveItems}: defensive items, tanks, and one each for a running emergency shield/phasing. */
    static int countDefensive(Player pl) {
        int[] it = pl.items;
        int n = it[Item.CLOAK.ordinal()] + it[Item.ECM.ordinal()] + it[Item.ARMOR.ordinal()]
            + it[Item.TRANSPORTER.ordinal()] + it[Item.TRACTOR_BEAM.ordinal()] + it[Item.EMERGENCY_SHIELD.ordinal()]
            + pl.numTanks + it[Item.DEFLECTOR.ordinal()] + it[Item.HYPERJUMP.ordinal()] + it[Item.PHASING.ordinal()]
            + it[Item.MIRROR.ordinal()];
        if (pl.emergencyShieldLeft != 0) {
            n++;
        }
        if (pl.phasingLeft != 0) {
            n++;
        }
        return n;
    }

    /**
     * C {@code Player_collides_with_item}.
     * <p>
     * BEHAVIOR: a player at the offensive (defensive) item limit only bounces off the item (Delta_mv) and the
     * item stays; a pack bigger than the room left is cut to fit.
     * BEHAVIOR: the count is added and limited to the item limit; armor adds 1; cloak, mirror, deflector,
     * phasing, afterburner, emergency thrust/shield, tractor beam, autopilot and armor grant their ability
     * when the count is positive; sensors and cloaks ask for a visibility update.
     * BEHAVIOR: an energy pack adds ENERGY_PACK_FUEL; a tank adds a tank with TANK_FUEL fuel, or that much
     * fuel when the tank limit is reached.
     * BEHAVIOR: the first emergency shield on a map without shields turns it on at once.
     * BEHAVIOR: each pickup plays its item's pickup sound at the player (collision.c 872-1014), after the item
     * is added (before an emergency shield is turned on).
     * BEHAVIOR: the item's life ends (it was picked up).
     */
    public void pickup(Player pl, int h) {
        Objects o = w.objects;
        ItemParams ip = w.items;
        int item = o.info[h];
        int bit = 1 << item;
        if ((bit & OFFENSIVE) != 0) {
            int off = countOffensive(pl);
            if (off >= ip.maxOffensiveItems) {
                sim.collisions.deltaMv(pl, h);
                return;
            } else if (o.counter[h] > 1 && off + o.counter[h] > ip.maxOffensiveItems) {
                o.counter[h] = ip.maxOffensiveItems - off;
            }
        } else if ((bit & DEFENSIVE) != 0) {
            int def = countDefensive(pl);
            if (def >= ip.maxDefensiveItems) {
                sim.collisions.deltaMv(pl, h);
                return;
            } else if (o.counter[h] > 1 && def + o.counter[h] > ip.maxDefensiveItems) {
                o.counter[h] = ip.maxDefensiveItems - def;
            }
        }
        Item kind = Item.of(item);
        switch (kind) {
            case FUEL -> pl.addFuel(energyPackFuel());
            case TANK -> {
                if (pl.numTanks < ip.limit[TANK]) {
                    sim.tanks.add(pl, sim.tanks.tankFuel(pl.numTanks + 1));
                } else {
                    pl.addFuel(sim.tanks.tankFuel(Player.MAX_TANKS));
                }
            }
            case ARMOR -> {
                pl.items[item]++;
                limit(pl, item);
                grantIfAny(pl, item, Has.ARMOR);
            }
            case EMERGENCY_SHIELD -> {
                int oldHave = pl.have;
                pl.items[item] += o.counter[h];
                limit(pl, item);
                grantIfAny(pl, item, Has.EMERGENCY_SHIELD);
                Sounds.play(w, pl.px, pl.py, SoundEvent.EMERGENCY_SHIELD_PICKUP);
                if ((oldHave & (Has.SHIELD | Has.EMERGENCY_SHIELD)) == 0 && pl.items[item] == 1) {
                    sim.devices.emergencyShield(pl, true);
                }
            }
            default -> {
                pl.items[item] += o.counter[h];
                limit(pl, item);
                switch (kind) {
                    case MIRROR -> grantIfAny(pl, item, Has.MIRROR);
                    case DEFLECTOR -> grantIfAny(pl, item, Has.DEFLECTOR);
                    case PHASING -> grantIfAny(pl, item, Has.PHASING_DEVICE);
                    case SENSOR -> pl.updateVisibility = true;
                    case AFTERBURNER -> grantIfAny(pl, item, Has.AFTERBURNER);
                    case CLOAK -> {
                        grantIfAny(pl, item, Has.CLOAKING_DEVICE);
                        pl.updateVisibility = true;
                    }
                    case EMERGENCY_THRUST -> grantIfAny(pl, item, Has.EMERGENCY_THRUST);
                    case TRACTOR_BEAM -> grantIfAny(pl, item, Has.TRACTOR_BEAM);
                    case AUTOPILOT -> grantIfAny(pl, item, Has.AUTOPILOT);
                    default -> { }
                }
            }
        }
        if (kind != Item.EMERGENCY_SHIELD) {
            Sounds.play(w, pl.px, pl.py, PICKUP_SOUND[item]);
        }
        o.life[h] = 0;
    }

    private void limit(Player pl, int item) {
        int v = pl.items[item];
        int lim = w.items.limit[item];
        pl.items[item] = v > lim ? lim : (v < 0 ? 0 : v);
    }

    private static void grantIfAny(Player pl, int item, int ability) {
        if (pl.items[item] > 0) {
            pl.have |= ability;
        }
    }

    // ================================================================ expiry

    /**
     * The OBJ_ITEM case of C {@code Delete_shot}, then free the object.
     * <p>
     * BEHAVIOR: a missile or mine pack whose life ran out while not yet white turns white and lives FPS *
     * WARN_TIME more frames (it blinks before going rogue), and is not deleted.
     * BEHAVIOR: otherwise the kind's world count drops and the object is freed; an expiring white missile pack
     * becomes a rogue heat-seeker with rogueHeatProb, a mine pack a rogue mine with rogueMineProb (with random
     * cluster, implosion, velocity and power modifiers as the rules allow), at the item's position.
     */
    public void delete(int h) {
        Objects o = w.objects;
        int item = o.info[h];
        boolean addMine = false;
        boolean addHeat = false;
        if (item == MISSILE || item == MINE) {
            if (o.life[h] == 0 && o.color[h] != Records.ObjColor.WHITE) {
                o.color[h] = Records.ObjColor.WHITE;
                o.life[h] = w.params.fps * WARN_TIME;
                return;
            }
            if (o.life[h] == 0) {
                float r = (float) w.rfrac();
                if (item == MISSILE) {
                    addHeat = r < w.items.rogueHeatProb;
                } else {
                    addMine = r < w.items.rogueMineProb;
                }
            }
        }
        w.itemCount[item]--;
        int x = o.px[h];
        int y = o.py[h];
        o.free(h);
        if (addMine || addHeat) {
            ItemParams ip = w.items;
            int mods = 0;
            if (ip.allowClusters && w.rfrac() <= 0.333f) {
                mods |= Obj.MOD_CLUSTER;
            }
            if (ip.allowModifiers && w.rfrac() <= 0.333f) {
                mods |= Obj.MOD_IMPLOSION;
            }
            if (ip.allowModifiers) {
                mods = Mods.withVelocity(mods, (int) (w.rfrac() * (Mods.VELOCITY_MAX + 1)));
            }
            if (ip.allowModifiers) {
                mods = Mods.withPower(mods, (int) (w.rfrac() * (Mods.POWER_MAX + 1)));
            }
            if (addMine) {
                int gravity = w.rfrac() < 0.5f ? Obj.GRAVITY : 0;
                sim.mines.placeGeneral(null, Const.TEAM_NOT_SET, gravity, x, y, 0, 0, mods);
            } else {
                sim.missiles.fire(null, Const.TEAM_NOT_SET, false, x, y, Obj.HEAT_SHOT,
                    (int) (w.rfrac() * Const.RES), mods, -1);
            }
        }
    }

    /**
     * Initialize this class's enum switch table now (javac's synthetic SwitchMap class), so the first item switch
     * in a tick does not allocate.
     */
    static int warmSwitches() {
        return switch (Item.FUEL) {
            case FUEL -> 0;
            default -> 1;
        };
    }
}
