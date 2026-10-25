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
package com.fulcrologic.jxpilot.client.game.predict;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.sim.Click;
import com.fulcrologic.jxpilot.common.sim.KernelWorld;
import com.fulcrologic.jxpilot.common.sim.MoveState;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.sim.Segment;

/**
 * A bullet's tick as the server's {@code moveObjects} and C {@code Move_object} move a SHOT: gravity at its block with
 * shotsGravity, then free flight (wrapped) far from walls, else traced through the blocks with the common
 * {@link Segment}; a crash (wall, cannon, target, treasure) ends it; with shotsWallBounce a bounce costs life
 * (objectWallBounceLifeFactor) and speed (objectWallBounceBrakeFactor), and one faster than maxObjectWallBounceSpeed
 * ends it. A client port of the server's SHOT path onto the {@link KernelWorld} (no pool objects there, so wormholes do
 * not warp it), to be replaced by the server's object mover moved into common. Allocation free.
 */
public final class ClientShotMotion implements ShotMotion {
    private final MoveState ms = new MoveState();
    private final KernelWorld world;
    private final Segment seg;
    private boolean gravity = true;
    private boolean wallBounce;
    private boolean edgeBounce = true;
    private float bounceLifeFactor = 0.8f;
    private float maxBounceSpeed = 40;
    private float brakeFactor = 0.95f;
    private ShotStep step;

    /** BEHAVIOR: the shot rules from the server's visible options, C's defaults without them. */
    public ClientShotMotion(KernelWorld w, Options o) {
        world = w;
        seg = new Segment(w, () -> 0.5);
        if (o != null) {
            gravity = o.getBool(Opt.SHOTS_GRAVITY);
            wallBounce = o.getBool(Opt.SHOTS_WALL_BOUNCE);
            edgeBounce = o.getBool(Opt.EDGE_BOUNCE);
            bounceLifeFactor = (float) o.getDouble(Opt.OBJECT_WALL_BOUNCE_LIFE_FACTOR);
            maxBounceSpeed = (float) o.getDouble(Opt.MAX_OBJECT_WALL_BOUNCE_SPEED);
            brakeFactor = (float) o.getDouble(Opt.OBJECT_WALL_BOUNCE_BRAKE_FACTOR);
        }
    }

    /** BEHAVIOR: gravity at the bullet's block with shotsGravity, the move, one tick of life. */
    @Override
    public boolean tick(int x, int y, float svx, float svy, long l, boolean cannon, ShotStep out) {
        step = out;
        if (gravity) {
            int bx = (x >> Click.SHIFT) / Const.BLOCK_SZ;
            int by = (y >> Click.SHIFT) / Const.BLOCK_SZ;
            svx += world.gravityX(bx, by);
            svy += world.gravityY(bx, by);
        }
        step.life = l;
        return move(x, y, svx, svy, cannon) && --step.life > 0;
    }

    /** The server's block of a pixel, clamped into the map (Mover.blockOf). */
    private static int blockOf(int pixel, int n) {
        int b = Math.floorDiv(pixel, Const.BLOCK_SZ);
        return b < 0 ? 0 : (b >= n ? n - 1 : b);
    }

    /** C {@code Move_object} for a SHOT, into the scratch fields; false on a crash or a fatal bounce. */
    private boolean move(int x, int y, float mvx, float mvy, boolean cannon) {
        int tx = Click.fromPixels(mvx);
        int ty = Click.fromPixels(mvy);
        int dist = world.wallDist(blockOf(x >> Click.SHIFT, world.width()), blockOf(y >> Click.SHIFT, world.height()));
        if (dist > 2) {
            int max = ((dist - 2) * Const.BLOCK_SZ) >> 1;
            if ((float) (max * max) >= mvx * mvx + mvy * mvy) {
                step.x = world.wrapXClick(x + tx);
                step.y = world.wrapYClick(y + ty);
                step.vx = mvx;
                step.vy = mvy;
                return true;
            }
        }
        ms.pl = null;
        ms.obj = -1;
        ms.edgeWrap = world.wrap();
        ms.edgeBounce = edgeBounce;
        ms.wallBounce = wallBounce;
        ms.cannonCrashes = true;
        ms.targetCrashes = true;
        ms.treasureCrashes = wallBounce;
        ms.wormholeWarps = false;
        ms.phased = false;
        ms.team = Const.TEAM_NOT_SET;
        ms.targetTeam = Const.TEAM_NOT_SET;
        ms.status = (gravity ? Obj.GRAVITY : 0) | (cannon ? Obj.FROMCANNON : 0);
        ms.type = cannon ? Obj.CANNON_SHOT : Obj.SHOT;
        ms.start(x, y, mvx, mvy, tx, ty, 0);
        int nothingDone = 0;
        for (;;) {
            seg.move(ms);
            if ((ms.doneX | ms.doneY) == 0) {
                if (ms.crash != MoveState.NOT_A_CRASH) {
                    return false;
                }
                if (ms.bounce != MoveState.NOT_A_BOUNCE && ms.bounce != MoveState.BOUNCE_EDGE) {
                    step.life = (long) ((float) step.life * bounceLifeFactor);
                    if (step.life <= 0) {
                        return false;
                    }
                    if (ms.velX * ms.velX + ms.velY * ms.velY > maxBounceSpeed * maxBounceSpeed) {
                        return false;
                    }
                    ms.velX *= brakeFactor;
                    ms.velY *= brakeFactor;
                    ms.todoX = (int) (ms.todoX * brakeFactor);
                    ms.todoY = (int) (ms.todoY * brakeFactor);
                }
                if (++nothingDone >= 5) {
                    return false;
                }
            } else {
                ms.posX += ms.doneX;
                ms.posY += ms.doneY;
                nothingDone = 0;
            }
            if ((ms.todoX | ms.todoY) == 0) {
                break;
            }
        }
        if (ms.edgeWrap) {   // Segment can end exactly on the far edge (C Move_object's wrap)
            if (ms.posX < 0) {
                ms.posX += world.clickWidth();
            }
            if (ms.posX >= world.clickWidth()) {
                ms.posX -= world.clickWidth();
            }
            if (ms.posY < 0) {
                ms.posY += world.clickHeight();
            }
            if (ms.posY >= world.clickHeight()) {
                ms.posY -= world.clickHeight();
            }
        }
        step.x = ms.posX;
        step.y = ms.posY;
        step.vx = ms.velX;
        step.vy = ms.velY;
        return true;
    }

}
