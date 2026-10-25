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
import com.fulcrologic.jxpilot.common.sim.ObjectBody;
import com.fulcrologic.jxpilot.common.sim.ObjectMover;

/**
 * A bullet's tick as the server's: its gravity (Simulation.moveObjects: the block's gravity with GRAVITY), the move
 * through the map with the server's own C {@code Move_object} ({@link ObjectMover}, common), and the expiry's tick of
 * life. The crash rules are the server's masks for a SHOT (or a CANNON_SHOT, FROMCANNON) from the server's options:
 * walls bounce with shotsWallBounce; cannons and targets crash a SHOT, and a cannon's shot with allowPlayerKilling;
 * treasures crash a bouncing shot; the bullet's team passes its own cannons (and targets) as on the server. Ties draw
 * 0.5 (the server draws its random stream), and wormholes use the countdown and last destination the client has.
 * Allocation free.
 */
public final class KernelShotMotion implements ShotMotion {
    private final KernelWorld world;
    private final ObjectMover mover;
    private final ObjectBody body = new ObjectBody();
    private boolean gravity = true;
    private boolean wallBounce;
    private boolean edgeBounce = true;
    private boolean killings = true;

    /**
     * BEHAVIOR: the shot rules from the server's visible options, C's defaults without them; the bounce speed limit
     * clamped to [0, the world's diagonal] and the brake and life factors to [0, 1], as the server does.
     */
    public KernelShotMotion(KernelWorld w, Options o) {
        world = w;
        mover = new ObjectMover(w, () -> 0.5);
        mover.objectWallBounceLifeFactor = 0.8f;
        mover.maxObjectWallBounceSpeed = 40;
        mover.objectWallBrakeFactor = 0.95f;
        if (o != null) {
            gravity = o.getBool(Opt.SHOTS_GRAVITY);
            wallBounce = o.getBool(Opt.SHOTS_WALL_BOUNCE);
            edgeBounce = o.getBool(Opt.EDGE_BOUNCE);
            killings = o.getBool(Opt.ALLOW_PLAYER_KILLING);
            // clamped as the server's Params.resolve (walls.c Move_init)
            mover.objectWallBounceLifeFactor = (float) clamp(o.getDouble(Opt.OBJECT_WALL_BOUNCE_LIFE_FACTOR), 0, 1);
            mover.maxObjectWallBounceSpeed = (float) clamp(o.getDouble(Opt.MAX_OBJECT_WALL_BOUNCE_SPEED), 0,
                w.shipParams().hypotenuse);
            mover.objectWallBrakeFactor = (float) clamp(o.getDouble(Opt.OBJECT_WALL_BOUNCE_BRAKE_FACTOR), 0, 1);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * BEHAVIOR: gravity at the bullet's block, the server's move, then one tick of life; false when the move crashed
     * it, a bounce killed it, or its life ran out.
     */
    @Override
    public boolean tick(int x, int y, float vx, float vy, long life, boolean cannon, int team, ShotStep out) {
        int px = x >> Click.SHIFT;
        int py = y >> Click.SHIFT;
        if (gravity) {
            vx += world.gravityX(px / Const.BLOCK_SZ, py / Const.BLOCK_SZ);
            vy += world.gravityY(px / Const.BLOCK_SZ, py / Const.BLOCK_SZ);
        }
        int type = cannon ? Obj.CANNON_SHOT : Obj.SHOT;
        ObjectBody b = body;
        b.cx = x;
        b.cy = y;
        b.vx = vx;
        b.vy = vy;
        b.life = life;
        b.status = (gravity ? Obj.GRAVITY : 0) | (cannon ? Obj.FROMCANNON : 0);
        b.dir = 0;
        b.type = type;
        boolean crashes = !cannon || killings;
        MoveState ms = mover.ms;
        ms.pl = null;
        ms.obj = -1;
        ms.edgeWrap = world.wrap();
        ms.edgeBounce = edgeBounce;
        ms.wallBounce = wallBounce;
        ms.cannonCrashes = crashes;
        ms.targetCrashes = crashes;
        ms.treasureCrashes = wallBounce;
        ms.wormholeWarps = true;
        ms.phased = false;
        ms.team = team;
        ms.targetTeam = team;
        world.movingPx = px;
        world.movingPy = py;
        mover.move(b);
        out.x = b.cx;
        out.y = b.cy;
        out.vx = b.vx;
        out.vy = b.vy;
        boolean crashed = mover.traced() && ms.crash != MoveState.NOT_A_CRASH;
        out.life = (long) b.life - 1;
        return !crashed && b.life - 1 > 0;
    }
}
