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
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Dir;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.map.Wormhole;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.World;

import static com.fulcrologic.jxpilot.server.sim.MoveState.*;

/**
 * Port of {@code Move_segment}, {@code Bounce_edge} and {@code Bounce_wall} ({@code src/server/walls.c}):
 * move one point through at most one block, detecting wall crashes and bounces exactly in click
 * coordinates. Stateless apart from the world it reads.
 */
public final class Segment {
    private static final int BC = Click.BLOCK_CLICKS;
    private final World world;
    private final WorldMap map;

    public Segment(World world) {
        this.world = world;
        this.map = world.map;
    }

    /** C {@code Bounce_edge}: reflect (edgeBounce) or stop the motion at the world edge. */
    static void bounceEdge(MoveState ms, int bounce) {
        boolean hor = bounce == BOUNCE_HOR_LO || bounce == BOUNCE_HOR_HI;
        boolean ver = bounce == BOUNCE_VER_LO || bounce == BOUNCE_VER_HI;
        if (hor) {
            if (ms.edgeBounce) {
                ms.todoX = -ms.todoX;
                ms.velX = -ms.velX;
                if (ms.pl == null) {
                    ms.dir = mod(Const.RES / 2 - ms.dir);
                }
            } else {
                ms.todoX = 0;
                ms.velX = 0;
                if (ms.pl == null) {
                    ms.dir = (ms.velY < 0) ? (3 * Const.RES / 4) : Const.RES / 4;
                }
            }
        } else if (ver) {
            if (ms.edgeBounce) {
                ms.todoY = -ms.todoY;
                ms.velY = -ms.velY;
                if (ms.pl == null) {
                    ms.dir = mod(Const.RES - ms.dir);
                }
            } else {
                ms.todoY = 0;
                ms.velY = 0;
                if (ms.pl == null) {
                    ms.dir = (ms.velX < 0) ? (Const.RES / 2) : 0;
                }
            }
        }
        ms.bounce = BOUNCE_EDGE;
    }

    /** C {@code Bounce_wall}: crash if this object does not bounce, else reflect the motion. */
    static void bounceWall(MoveState ms, int bounce) {
        if (!ms.wallBounce) {
            ms.crash = CRASH_WALL;
            return;
        }
        int tx = ms.todoX;
        int ty = ms.todoY;
        float vx = ms.velX;
        float vy = ms.velY;
        switch (bounce) {
            case BOUNCE_HOR_LO, BOUNCE_HOR_HI -> {
                ms.todoX = -tx;
                ms.velX = -vx;
                if (ms.pl == null) {
                    ms.dir = mod(Const.RES / 2 - ms.dir);
                }
            }
            case BOUNCE_VER_LO, BOUNCE_VER_HI -> {
                ms.todoY = -ty;
                ms.velY = -vy;
                if (ms.pl == null) {
                    ms.dir = mod(Const.RES - ms.dir);
                }
            }
            case BOUNCE_LEFT_DOWN, BOUNCE_RIGHT_UP -> {
                ms.todoX = -ty;
                ms.todoY = -tx;
                ms.velX = -vy;
                ms.velY = -vx;
                if (ms.pl == null) {
                    ms.dir = mod(3 * Const.RES / 4 - ms.dir);
                }
            }
            case BOUNCE_LEFT_UP, BOUNCE_RIGHT_DOWN -> {
                ms.todoX = ty;
                ms.todoY = tx;
                ms.velX = vy;
                ms.velY = vx;
                if (ms.pl == null) {
                    ms.dir = mod(Const.RES / 4 - ms.dir);
                }
            }
            default -> { }
        }
        ms.bounce = bounce;
    }

    private static int mod(int d) {
        return d & (Const.RES - 1);
    }

    /**
     * Move a point through one block (C {@code Move_segment}).
     * <p>
     * Output: {@code done} (clicks moved in this block), reduced {@code todo}, {@code crash} (nothing moved)
     * with {@code cannon/wormhole/target/treasure}, or {@code bounce} (nothing moved, motion reflected).
     * BEHAVIOR: outside the world: wrapped when edge wrapping, else CRASH_UNIVERSE.
     * BEHAVIOR: far from walls (wallDist &gt; 2) the move is done in one go up to the safe distance.
     * BEHAVIOR: at a non-wrapping world edge the point bounces off the edge.
     * BEHAVIOR: walls, fuel and targets are solid squares; diagonal blocks solid triangles; cannons a
     * triangle; treasures a lower half plus circle; entering a wall from inside crashes.
     * BEHAVIOR: a wormhole crash (warp) only for a player whose centre block is the wormhole.
     * BEHAVIOR: of several feasible bounces at a corner one is chosen at random.
     */
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:MethodLength"})
    public void move(MoveState ms) {
        ms.crash = NOT_A_CRASH;
        ms.bounce = NOT_A_BOUNCE;
        ms.doneX = 0;
        ms.doneY = 0;
        final int cw = world.clickWidth;
        final int ch = world.clickHeight;
        final int wx = world.width;
        final int wy = world.height;

        int enterX = ms.posX;
        int enterY = ms.posY;
        if (enterX < 0 || enterX >= cw || enterY < 0 || enterY >= ch) {
            if (!ms.edgeWrap) {
                ms.crash = CRASH_UNIVERSE;
                return;
            }
            if (enterX < 0) {
                enterX += cw;
                if (enterX < 0) {
                    ms.crash = CRASH_UNIVERSE;
                    return;
                }
            } else if (enterX >= cw) {
                enterX -= cw;
                if (enterX >= cw) {
                    ms.crash = CRASH_UNIVERSE;
                    return;
                }
            }
            if (enterY < 0) {
                enterY += ch;
                if (enterY < 0) {
                    ms.crash = CRASH_UNIVERSE;
                    return;
                }
            } else if (enterY >= ch) {
                enterY -= ch;
                if (enterY >= ch) {
                    ms.crash = CRASH_UNIVERSE;
                    return;
                }
            }
            ms.posX = enterX;
            ms.posY = enterY;
        }

        int signX = ms.velX < 0 ? -1 : 1;
        int signY = ms.velY < 0 ? -1 : 1;
        int blockX = enterX / BC;
        int blockY = enterY / BC;
        int wd = world.wallDist(blockX, blockY);
        if (wd > 2) {
            int maxcl = ((wd - 2) * BC) >> 1;
            if (maxcl >= signX * ms.todoX && maxcl >= signY * ms.todoY) {
                ms.doneX = ms.todoX;
                ms.doneY = ms.todoY;
            } else if (signX * ms.todoX > signY * ms.todoY) {
                ms.doneX = signX * maxcl;
                ms.doneY = ms.todoY * maxcl / (signX * ms.todoX);
            } else {
                ms.doneX = ms.todoX * maxcl / (signY * ms.todoY);
                ms.doneY = signY * maxcl;
            }
            ms.todoX -= ms.doneX;
            ms.todoY -= ms.doneY;
            return;
        }

        int offsetX = enterX - blockX * BC;
        int offsetY = enterY - blockY * BC;
        boolean inside = true;
        if (offsetX == 0) {
            inside = false;
            if (signX == -1) {
                offsetX = BC;
                if (--blockX < 0) {
                    if (ms.edgeWrap) {
                        blockX += wx;
                    } else {
                        bounceEdge(ms, BOUNCE_HOR_LO);
                        return;
                    }
                }
            }
        } else if (enterX == cw - 1 && !ms.edgeWrap && ms.velX > 0) {
            bounceEdge(ms, BOUNCE_HOR_HI);
            return;
        }
        if (offsetY == 0) {
            inside = false;
            if (signY == -1) {
                offsetY = BC;
                if (--blockY < 0) {
                    if (ms.edgeWrap) {
                        blockY += wy;
                    } else {
                        bounceEdge(ms, BOUNCE_VER_LO);
                        return;
                    }
                }
            }
        } else if (enterY == ch - 1 && !ms.edgeWrap && ms.velY > 0) {
            bounceEdge(ms, BOUNCE_VER_HI);
            return;
        }

        boolean needAdjust = false;
        int leaveX;
        int leaveY;
        if (signX == -1) {
            if (offsetX + ms.todoX < 0) {
                leaveX = enterX - offsetX;
                needAdjust = true;
            } else {
                leaveX = enterX + ms.todoX;
            }
        } else {
            if (offsetX + ms.todoX > BC) {
                leaveX = enterX + BC - offsetX;
                needAdjust = true;
            } else {
                leaveX = enterX + ms.todoX;
            }
            if (leaveX == cw && !ms.edgeWrap) {
                leaveX--;
                needAdjust = true;
            }
        }
        if (signY == -1) {
            if (offsetY + ms.todoY < 0) {
                leaveY = enterY - offsetY;
                needAdjust = true;
            } else {
                leaveY = enterY + ms.todoY;
            }
        } else {
            if (offsetY + ms.todoY > BC) {
                leaveY = enterY + BC - offsetY;
                needAdjust = true;
            } else {
                leaveY = enterY + ms.todoY;
            }
            if (leaveY == ch && !ms.edgeWrap) {
                leaveY--;
                needAdjust = true;
            }
        }
        if (needAdjust && ms.todoY != 0 && ms.todoX != 0) {
            double wxr = (double) (leaveX - enterX) / ms.todoX;
            double wyr = (double) (leaveY - enterY) / ms.todoY;
            if (wxr > wyr) {
                leaveX = enterX + Click.roundHalfAway(ms.todoX * wyr);
            } else if (wxr < wyr) {
                leaveY = enterY + Click.roundHalfAway(ms.todoY * wxr);
            }
        }

        int deltaX = leaveX - enterX;
        int deltaY = leaveY - enterY;
        int blockType = map.block(blockX, blockY);
        int wallBounce = 0;

        if (!ms.phased) {
            sw:
            switch (blockType) {
                case BlockType.WORMHOLE: {
                    if (!ms.wormholeWarps) {
                        break;
                    }
                    int hole = map.itemId(blockX, blockY);
                    if (map.wormholes().get(hole).type() == Wormhole.Type.OUT) {
                        break;
                    }
                    if (ms.pl != null) {
                        int b2x = ms.pl.bx;
                        int b2y = ms.pl.by;
                        if ((ms.pl.status & Obj.WARPED) != 0) {
                            if (inMap(b2x, b2y) && map.block(b2x, b2y) == BlockType.WORMHOLE) {
                                int oldhole = map.itemId(b2x, b2y);
                                if (map.wormholes().get(oldhole).type() == Wormhole.Type.NORMAL
                                    && ms.pl.wormHoleDest == oldhole) {
                                    break;
                                }
                            }
                            ms.pl.status &= ~Obj.WARPED;
                        }
                        if (b2x == blockX && b2y == blockY) {
                            ms.wormhole = hole;
                            ms.crash = CRASH_WORMHOLE;
                            return;
                        }
                    } else {
                        int last = world.wormLastDest[hole];
                        Objects o = world.objects;
                        int obx = Math.floorDiv(o.px[ms.obj], Const.BLOCK_SZ);
                        int oby = Math.floorDiv(o.py[ms.obj], Const.BLOCK_SZ);
                        if (last >= 0
                            && (world.wormCountdown[hole] > 0 || world.params.wormTime == 0)
                            && last < map.wormholes().size()
                            && map.wormholes().get(last).type() != Wormhole.Type.IN
                            && last != hole
                            && (obx != blockX || oby != blockY)) {
                            ms.doneX += (map.wormholes().get(last).bx() - map.wormholes().get(hole).bx()) * BC;
                            ms.doneY += (map.wormholes().get(last).by() - map.wormholes().get(hole).by()) * BC;
                        }
                    }
                    break;
                }
                case BlockType.CANNON: {
                    if (!ms.cannonCrashes) {
                        break;
                    }
                    boolean teamPlay = world.params.teamPlay;
                    if ((ms.status & Obj.FROMCANNON) != 0 && !teamPlay) {
                        break;
                    }
                    int i = map.itemId(blockX, blockY);
                    ms.cannon = i;
                    if (teamPlay && (world.params.teamImmunity || (ms.status & Obj.FROMCANNON) != 0)
                        && ms.team == map.cannons().get(i).team()) {
                        break;
                    }
                    int res = cannonSegment(ms, map.cannons().get(i).dir(), offsetX, offsetY, deltaX, deltaY,
                        signX, signY);
                    if (res == CANNON_CRASH) {
                        ms.crash = CRASH_CANNON;
                        return;
                    }
                    if (res == CANNON_CLEAR) {
                        break;
                    }
                    deltaX = cannonDeltaX;
                    deltaY = cannonDeltaY;
                    break;
                }
                case BlockType.TREASURE:
                case BlockType.TARGET:
                case BlockType.FUEL:
                case BlockType.FILLED: {
                    if (blockType == BlockType.TREASURE && ms.treasureCrashes) {
                        double r = 0.5 * BC;
                        int off2X = offsetX + deltaX;
                        int off2Y = offsetY + deltaY;
                        int midX = (offsetX + off2X) / 2;
                        int midY = (offsetY + off2Y) / 2;
                        if (offsetY > r && off2Y > r
                            && sqr(midX - r) + sqr(midY - r) > sqr(r)
                            && sqr(off2X - r) + sqr(off2Y - r) > sqr(r)
                            && sqr(offsetX - r) + sqr(offsetY - r) > sqr(r)) {
                            break;
                        }
                        ms.treasure = map.itemId(blockX, blockY);
                        ms.crash = CRASH_TREASURE;
                        return;
                    }
                    if (blockType == BlockType.TARGET && ms.targetCrashes) {
                        int i = map.itemId(blockX, blockY);
                        ms.target = i;
                        boolean skip = false;
                        if (!world.params.targetTeamCollision) {
                            int team = ms.pl != null ? ms.pl.team : ms.team;
                            if (team == map.targets().get(i).team()) {
                                skip = true;
                            }
                        }
                        if (!skip && ms.pl == null) {
                            ms.crash = CRASH_TARGET;
                            return;
                        }
                        if (skip) {
                            break;
                        }
                    }
                    if (inside) {
                        ms.crash = CRASH_WALL;
                        return;
                    }
                    if (offsetX == 0) {
                        if (ms.velX > 0) {
                            wallBounce |= BOUNCE_HOR_LO;
                        }
                    } else if (offsetX == BC) {
                        if (ms.velX < 0) {
                            wallBounce |= BOUNCE_HOR_HI;
                        }
                    }
                    if (offsetY == 0) {
                        if (ms.velY > 0) {
                            wallBounce |= BOUNCE_VER_LO;
                        }
                    } else if (offsetY == BC) {
                        if (ms.velY < 0) {
                            wallBounce |= BOUNCE_VER_HI;
                        }
                    }
                    if (wallBounce != 0) {
                        break;
                    }
                    if ((ms.todoX | ms.todoY) == 0) {
                        break;
                    }
                    if (ms.todoX == 0 && (offsetX == 0 || offsetX == BC)) {
                        break;
                    }
                    if (ms.todoY == 0 && (offsetY == 0 || offsetY == BC)) {
                        break;
                    }
                    ms.crash = CRASH_WALL;
                    return;
                }
                case BlockType.REC_LD: {
                    if (offsetX == 0) {
                        if (ms.velX > 0) {
                            wallBounce |= BOUNCE_HOR_LO;
                        }
                        if (offsetY == BC && ms.velX + ms.velY < 0) {
                            wallBounce |= BOUNCE_LEFT_DOWN;
                        }
                    }
                    if (offsetY == 0) {
                        if (ms.velY > 0) {
                            wallBounce |= BOUNCE_VER_LO;
                        }
                        if (offsetX == BC && ms.velX + ms.velY < 0) {
                            wallBounce |= BOUNCE_LEFT_DOWN;
                        }
                    }
                    if (wallBounce != 0) {
                        break;
                    }
                    if (offsetX + offsetY < BC) {
                        ms.crash = CRASH_WALL;
                        return;
                    }
                    if (offsetX + deltaX + offsetY + deltaY >= BC) {
                        break;
                    }
                    if (signX * ms.todoX >= signY * ms.todoY) {
                        double w = (double) ms.todoY / ms.todoX;
                        deltaX = (int) ((BC - offsetX - offsetY) / (1 + w));
                        deltaY = (int) (deltaX * w);
                        if (offsetX + deltaX + offsetY + deltaY < BC) {
                            deltaX++;
                            deltaY = (int) (deltaX * w);
                        }
                        if (deltaX == 0) {
                            wallBounce |= BOUNCE_LEFT_DOWN;
                            break;
                        }
                    } else {
                        double w = (double) ms.todoX / ms.todoY;
                        deltaY = (int) ((BC - offsetX - offsetY) / (1 + w));
                        deltaX = (int) (deltaY * w);
                        if (offsetX + deltaX + offsetY + deltaY < BC) {
                            deltaY++;
                            deltaX = (int) (deltaY * w);
                        }
                        if (deltaY == 0) {
                            wallBounce |= BOUNCE_LEFT_DOWN;
                            break;
                        }
                    }
                    break;
                }
                case BlockType.REC_LU: {
                    if (offsetX == 0) {
                        if (ms.velX > 0) {
                            wallBounce |= BOUNCE_HOR_LO;
                        }
                        if (offsetY == 0 && ms.velX < ms.velY) {
                            wallBounce |= BOUNCE_LEFT_UP;
                        }
                    }
                    if (offsetY == BC) {
                        if (ms.velY < 0) {
                            wallBounce |= BOUNCE_VER_HI;
                        }
                        if (offsetX == BC && ms.velX < ms.velY) {
                            wallBounce |= BOUNCE_LEFT_UP;
                        }
                    }
                    if (wallBounce != 0) {
                        break;
                    }
                    if (offsetX < offsetY) {
                        ms.crash = CRASH_WALL;
                        return;
                    }
                    if (offsetX + deltaX >= offsetY + deltaY) {
                        break;
                    }
                    if (signX * ms.todoX >= signY * ms.todoY) {
                        double w = (double) ms.todoY / ms.todoX;
                        deltaX = (int) ((offsetY - offsetX) / (1 - w));
                        deltaY = (int) (deltaX * w);
                        if (offsetX + deltaX < offsetY + deltaY) {
                            deltaX++;
                            deltaY = (int) (deltaX * w);
                        }
                        if (deltaX == 0) {
                            wallBounce |= BOUNCE_LEFT_UP;
                            break;
                        }
                    } else {
                        double w = (double) ms.todoX / ms.todoY;
                        deltaY = (int) ((offsetX - offsetY) / (1 - w));
                        deltaX = (int) (deltaY * w);
                        if (offsetX + deltaX < offsetY + deltaY) {
                            deltaY--;
                            deltaX = (int) (deltaY * w);
                        }
                        if (deltaY == 0) {
                            wallBounce |= BOUNCE_LEFT_UP;
                            break;
                        }
                    }
                    break;
                }
                case BlockType.REC_RD: {
                    if (offsetX == BC) {
                        if (ms.velX < 0) {
                            wallBounce |= BOUNCE_HOR_HI;
                        }
                        if (offsetY == BC && ms.velX > ms.velY) {
                            wallBounce |= BOUNCE_RIGHT_DOWN;
                        }
                    }
                    if (offsetY == 0) {
                        if (ms.velY > 0) {
                            wallBounce |= BOUNCE_VER_LO;
                        }
                        if (offsetX == 0 && ms.velX > ms.velY) {
                            wallBounce |= BOUNCE_RIGHT_DOWN;
                        }
                    }
                    if (wallBounce != 0) {
                        break;
                    }
                    if (offsetX > offsetY) {
                        ms.crash = CRASH_WALL;
                        return;
                    }
                    if (offsetX + deltaX <= offsetY + deltaY) {
                        break;
                    }
                    if (signX * ms.todoX >= signY * ms.todoY) {
                        double w = (double) ms.todoY / ms.todoX;
                        deltaX = (int) ((offsetY - offsetX) / (1 - w));
                        deltaY = (int) (deltaX * w);
                        if (offsetX + deltaX > offsetY + deltaY) {
                            deltaX--;
                            deltaY = (int) (deltaX * w);
                        }
                        if (deltaX == 0) {
                            wallBounce |= BOUNCE_RIGHT_DOWN;
                            break;
                        }
                    } else {
                        double w = (double) ms.todoX / ms.todoY;
                        deltaY = (int) ((offsetX - offsetY) / (1 - w));
                        deltaX = (int) (deltaY * w);
                        if (offsetX + deltaX > offsetY + deltaY) {
                            deltaY++;
                            deltaX = (int) (deltaY * w);
                        }
                        if (deltaY == 0) {
                            wallBounce |= BOUNCE_RIGHT_DOWN;
                            break;
                        }
                    }
                    break;
                }
                case BlockType.REC_RU: {
                    if (offsetX == BC) {
                        if (ms.velX < 0) {
                            wallBounce |= BOUNCE_HOR_HI;
                        }
                        if (offsetY == 0 && ms.velX + ms.velY > 0) {
                            wallBounce |= BOUNCE_RIGHT_UP;
                        }
                    }
                    if (offsetY == BC) {
                        if (ms.velY < 0) {
                            wallBounce |= BOUNCE_VER_HI;
                        }
                        if (offsetX == 0 && ms.velX + ms.velY > 0) {
                            wallBounce |= BOUNCE_RIGHT_UP;
                        }
                    }
                    if (wallBounce != 0) {
                        break;
                    }
                    if (offsetX + offsetY > BC) {
                        ms.crash = CRASH_WALL;
                        return;
                    }
                    if (offsetX + deltaX + offsetY + deltaY <= BC) {
                        break;
                    }
                    if (signX * ms.todoX >= signY * ms.todoY) {
                        double w = (double) ms.todoY / ms.todoX;
                        deltaX = (int) ((BC - offsetX - offsetY) / (1 + w));
                        deltaY = (int) (deltaX * w);
                        if (offsetX + deltaX + offsetY + deltaY > BC) {
                            deltaX--;
                            deltaY = (int) (deltaX * w);
                        }
                        if (deltaX == 0) {
                            wallBounce |= BOUNCE_RIGHT_UP;
                            break;
                        }
                    } else {
                        double w = (double) ms.todoX / ms.todoY;
                        deltaY = (int) ((BC - offsetX - offsetY) / (1 + w));
                        deltaX = (int) (deltaY * w);
                        if (offsetX + deltaX + offsetY + deltaY > BC) {
                            deltaY--;
                            deltaX = (int) (deltaY * w);
                        }
                        if (deltaY == 0) {
                            wallBounce |= BOUNCE_RIGHT_UP;
                            break;
                        }
                    }
                    break;
                }
                default:
                    break sw;
            }

            if (wallBounce != 0) {
                wallBounce = resolveBounce(ms, wallBounce, blockType, blockX, blockY, offsetX, offsetY);
                if (ms.crash != NOT_A_CRASH) {
                    return;
                }
            }
        }

        if (wallBounce != 0) {
            bounceWall(ms, wallBounce);
        } else {
            ms.doneX += deltaX;
            ms.doneY += deltaY;
            ms.todoX -= deltaX;
            ms.todoY -= deltaY;
        }
    }

    private boolean inMap(int bx, int by) {
        return bx >= 0 && bx < world.width && by >= 0 && by < world.height;
    }

    private static double sqr(double v) {
        return v * v;
    }

    /**
     * Choose among possible bounces (the part of Move_segment after the block switch).
     * <p>
     * BEHAVIOR: a side bounce is infeasible when the neighbour across that side covers it (walls, fuel,
     * targets/treasures that do not crash this object, matching diagonals); infeasible ones are dropped.
     * BEHAVIOR: with none feasible a two-side corner becomes the diagonal bounce; a lone diagonal-block
     * corner case may be cleared; anything else crashes (CRASH_WALL).
     * BEHAVIOR: with several feasible, one is picked with rfrac().
     */
    int resolveBounce(MoveState ms, int wallBounce, int blockType, int blockX, int blockY, int offsetX,
                      int offsetY) {
        int count = 0;
        int save = wallBounce;
        int blockMask = BlockType.bit(BlockType.FILLED) | BlockType.bit(BlockType.FUEL);
        if (!ms.targetCrashes) {
            blockMask |= BlockType.bit(BlockType.TARGET);
        }
        if (!ms.treasureCrashes) {
            blockMask |= BlockType.bit(BlockType.TREASURE);
        }
        for (int bit = 1; bit <= wallBounce; bit <<= 1) {
            if ((wallBounce & bit) == 0) {
                continue;
            }
            wallBounce &= ~bit;
            switch (bit) {
                case BOUNCE_HOR_LO -> {
                    int b2x = blockX - 1;
                    if (b2x < 0) {
                        if (!ms.edgeWrap) {
                            continue;
                        }
                        b2x += world.width;
                    }
                    if (covers(b2x, blockY, blockMask | BlockType.bit(BlockType.REC_RU)
                        | BlockType.bit(BlockType.REC_RD))) {
                        continue;
                    }
                }
                case BOUNCE_HOR_HI -> {
                    int b2x = blockX + 1;
                    if (b2x >= world.width) {
                        if (!ms.edgeWrap) {
                            continue;
                        }
                        b2x -= world.width;
                    }
                    if (covers(b2x, blockY, blockMask | BlockType.bit(BlockType.REC_LU)
                        | BlockType.bit(BlockType.REC_LD))) {
                        continue;
                    }
                }
                case BOUNCE_VER_LO -> {
                    int b2y = blockY - 1;
                    if (b2y < 0) {
                        if (!ms.edgeWrap) {
                            continue;
                        }
                        b2y += world.height;
                    }
                    if (covers(blockX, b2y, blockMask | BlockType.bit(BlockType.REC_RU)
                        | BlockType.bit(BlockType.REC_LU))) {
                        continue;
                    }
                }
                case BOUNCE_VER_HI -> {
                    int b2y = blockY + 1;
                    if (b2y >= world.height) {
                        if (!ms.edgeWrap) {
                            continue;
                        }
                        b2y -= world.height;
                    }
                    if (covers(blockX, b2y, blockMask | BlockType.bit(BlockType.REC_RD)
                        | BlockType.bit(BlockType.REC_LD))) {
                        continue;
                    }
                }
                default -> { }
            }
            wallBounce |= bit;
            count++;
        }

        if (count == 0) {
            wallBounce = save;
            switch (wallBounce) {
                case BOUNCE_HOR_LO | BOUNCE_VER_LO -> wallBounce = BOUNCE_LEFT_DOWN;
                case BOUNCE_HOR_LO | BOUNCE_VER_HI -> wallBounce = BOUNCE_LEFT_UP;
                case BOUNCE_HOR_HI | BOUNCE_VER_LO -> wallBounce = BOUNCE_RIGHT_DOWN;
                case BOUNCE_HOR_HI | BOUNCE_VER_HI -> wallBounce = BOUNCE_RIGHT_UP;
                default -> {
                    // C operator precedence: a ? b : (c && d && vel-test)
                    switch (blockType) {
                        case BlockType.REC_LD -> {
                            if ((offsetX == 0) ? (offsetY == BC)
                                : (offsetX == BC && offsetY == 0 && ms.velX + ms.velY >= 0)) {
                                wallBounce = 0;
                            }
                        }
                        case BlockType.REC_LU -> {
                            if ((offsetX == 0) ? (offsetY == 0)
                                : (offsetX == BC && offsetY == BC && ms.velX >= ms.velY)) {
                                wallBounce = 0;
                            }
                        }
                        case BlockType.REC_RD -> {
                            if ((offsetX == 0) ? (offsetY == 0)
                                : (offsetX == BC && offsetY == BC && ms.velX <= ms.velY)) {
                                wallBounce = 0;
                            }
                        }
                        case BlockType.REC_RU -> {
                            if ((offsetX == 0) ? (offsetY == BC)
                                : (offsetX == BC && offsetY == 0 && ms.velX + ms.velY <= 0)) {
                                wallBounce = 0;
                            }
                        }
                        default -> { }
                    }
                    if (wallBounce != 0) {
                        ms.crash = CRASH_WALL;
                        return 0;
                    }
                }
            }
        } else if (count > 1) {
            count = (int) (world.rfrac() * count);
            for (int bit = 1; bit <= wallBounce; bit <<= 1) {
                if ((wallBounce & bit) != 0) {
                    if (count == 0) {
                        wallBounce = bit;
                        break;
                    }
                    count--;
                }
            }
        }
        return wallBounce;
    }

    private boolean covers(int bx, int by, int mask) {
        return (BlockType.bit(map.block(bx, by)) & mask) != 0;
    }

    // ---------------------------------------------------------------- cannons

    static final int CANNON_CRASH = 0;
    static final int CANNON_CLEAR = 1;
    static final int CANNON_PARTIAL = 2;
    private int cannonDeltaX;
    private int cannonDeltaY;

    /**
     * The cannon block part of Move_segment: transform the cannon to the up-facing case, find how far the
     * point can move before touching the triangle.
     * <p>
     * BEHAVIOR: CANNON_CRASH if the start point is inside the triangle (or the allowed move is zero);
     * CANNON_CLEAR if both ends are clearly outside on the same half; else CANNON_PARTIAL with the
     * shortened delta in {@link #cannonDeltaX}/{@link #cannonDeltaY}.
     */
    int cannonSegment(MoveState ms, int cannonDir, int offsetX, int offsetY, int deltaX, int deltaY,
                      int signX, int signY) {
        int mxx;
        int mxy;
        int myx;
        int myy;
        int mirxx = 0;
        int mirxy = 0;
        int miryx = 0;
        int miryy = 0;
        switch (cannonDir) {
            case Dir.UP -> { mxx = 1; mxy = 0; myx = 0; myy = 1; }
            case Dir.DOWN -> { mxx = 1; mxy = 0; myx = 0; myy = -1; miryy = BC; }
            case Dir.RIGHT -> { mxx = 0; mxy = 1; myx = -1; myy = 0; miryx = BC; }
            default -> { mxx = 0; mxy = -1; myx = 1; myy = 0; mirxy = BC; }
        }
        int startX = mirxx + mxx * offsetX + miryx + myx * offsetY;
        int startY = mirxy + mxy * offsetX + miryy + myy * offsetY;
        int diffX = mxx * deltaX + myx * deltaY;
        int diffY = mxy * deltaX + myy * deltaY;
        int dirX = mxx * signX + myx * signY;
        int dirY = mxy * signX + myy * signY;
        int todoX = mxx * ms.todoX + myx * ms.todoY;
        int todoY = mxy * ms.todoX + myy * ms.todoY;
        int endX = startX + diffX;
        int endY = startY + diffY;

        if (startX <= BC / 2) {
            if (3 * startY <= 2 * startX) {
                return CANNON_CRASH;
            }
            if (endX <= BC / 2 && 3 * endY > 2 * endX) {
                return CANNON_CLEAR;
            }
        } else {
            if (3 * startY <= 2 * (BC - startX)) {
                return CANNON_CRASH;
            }
            if (endX > BC / 2 && 3 * endY > 2 * (BC - endX)) {
                return CANNON_CLEAR;
            }
        }

        int doneX = diffX;
        int doneY = diffY;
        if (dirX * diffX >= dirY * diffY) {
            double w = (double) todoY / todoX;
            if (3 * todoY != 2 * todoX) {
                double d = (3 * startY - 2 * startX) / (2 - 3 * w);
                int ax = Click.roundHalfAway(d);
                int ay = (int) (ax * w);
                if (dirX * ax < dirX * doneX && dirX * ax >= 0 && startY + ay <= BC / 3) {
                    doneX = ax;
                    doneY = ay;
                    if ((doneX | doneY) == 0) {
                        return CANNON_CRASH;
                    }
                }
            }
            if (-3 * todoY != 2 * todoX) {
                double d = (2 * BC - 2 * startX - 3 * startY) / (2 + 3 * w);
                int bxx = Click.roundHalfAway(d);
                int byy = (int) (bxx * w);
                if (dirX * bxx < dirX * doneX && dirX * bxx >= 0 && startY + byy <= BC / 3) {
                    doneX = bxx;
                    doneY = byy;
                    if ((doneX | doneY) == 0) {
                        return CANNON_CRASH;
                    }
                }
            }
        } else {
            double w = (double) todoX / todoY;
            double d = (2 * startX - 3 * startY) / (3 - 2 * w);
            int ay = Click.roundHalfAway(d);
            int ax = (int) (ay * w);
            if (dirY * ay < dirY * doneY && dirY * ay >= 0 && startY + ay <= BC / 3) {
                doneX = ax;
                doneY = ay;
                if ((doneX | doneY) == 0) {
                    return CANNON_CRASH;
                }
            }
            d = (2 * BC - 2 * startX - 3 * startY) / (3 + 2 * w);
            int byy = Click.roundHalfAway(d);
            int bxx = (int) (byy * w);
            if (dirY * byy < dirY * doneY && dirY * byy >= 0 && startY + byy <= BC / 3) {
                doneX = bxx;
                doneY = byy;
                if ((doneX | doneY) == 0) {
                    return CANNON_CRASH;
                }
            }
        }
        cannonDeltaX = mxx * doneX + mxy * doneY;
        cannonDeltaY = myx * doneX + myy * doneY;
        return CANNON_PARTIAL;
    }
}
