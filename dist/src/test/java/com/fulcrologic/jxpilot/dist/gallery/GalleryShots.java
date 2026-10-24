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
package com.fulcrologic.jxpilot.dist.gallery;

import com.fulcrologic.jxpilot.client.map.MapState;
import com.fulcrologic.jxpilot.client.paint.Particles;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.client.paint.ShipPainter;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.map.Dir;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.map.Wormhole;
import com.fulcrologic.jxpilot.common.math.ShipShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Every picture of the guide's gallery: where the camera looks on {@code docs/guide/gallery/features.xp},
 * at what zoom, which map state and which scripted scene objects, and how to crop. Feature positions come
 * from the parsed map (fuel stations, cannons, wormholes...) or from the fixture's 8x8-block cell grid.
 */
final class GalleryShots {
    /** Where a crop is measured from. */
    enum Anchor { CENTRE, TOP_LEFT, BOTTOM_LEFT }

    /** Fills the scene for frame counter {@code loops}. */
    interface SceneFn {
        void fill(SceneData s, long loops);
    }

    /** HUD values for shots with the HUD on. */
    record Hud(String lockName, int lockDist, int lockDir, int[] items, int timeLeft, String mods, String scoreText) {}

    /** Scripted map state: fuel per station, dead cannons, target damage, own team, next checkpoint. */
    static final class State implements MapState {
        long loops;
        double fuel = Const.MAX_STATION_FUEL;
        boolean cannonsAlive = true;
        double targetDamage = Const.TARGET_DAMAGE;
        int selfTeam = Const.TEAM_NOT_SET;
        int next = 1;

        @Override public double fuel(int i) { return fuel; }
        @Override public boolean cannonAlive(int i) { return cannonsAlive; }
        @Override public boolean targetAlive(int i) { return true; }
        @Override public double targetDamage(int i) { return targetDamage; }
        @Override public int selfTeam() { return selfTeam; }
        @Override public int nextCheckpoint() { return next; }
        @Override public long loops() { return loops; }
    }

    /** One picture (frames == 1) or animation (frames > 1, frame f has loops0 + f). */
    record Shot(String name, double x, double y, double zoom, double cropW, double cropH, double cropX, double cropY,
                Anchor anchor, boolean filled, boolean map, boolean hud, boolean radar, int frames, long loops0,
                State state, SceneFn scene, Hud hudData) {}

    static final int S = Const.BLOCK_SZ;
    static final SceneFn NOTHING = (s, l) -> { };

    private final WorldMap map;
    private final List<Shot> shots = new ArrayList<>();
    private final ShipShape ship = ShipShape.defaultShip();

    /**
     * The pictures taken on {@code map}: everything on the features fixture, the checkpoints on the race
     * fixture (race mode turns team play off, so they cannot share a map).
     */
    GalleryShots(WorldMap map) {
        this.map = map;
        if (map.timing()) {
            checkpoints();
        } else {
            mapFeatures();
            objects();
            hudParts();
        }
    }

    List<Shot> all() {
        return shots;
    }

    // ------------------------------------------------------------------ positions

    /** World x of the centre of the fixture's cell column {@code cx} (cells are 8x8 blocks). */
    double cellX(int cx) {
        return (8 * cx + 4) * S + S / 2.0;
    }

    /** World y of the centre of cell row {@code cy} (row 0 at the top of the map file). */
    double cellY(int cy) {
        return (map.height() - 1 - (8 * cy + 4)) * S + S / 2.0;
    }

    static double centre(int block) {
        return block * S + S / 2.0;
    }

    /** The object stage: an empty cell. */
    double stageX() {
        return cellX(4);
    }

    double stageY() {
        return cellY(5);
    }

    // ------------------------------------------------------------------ builders

    private State state() {
        return new State();
    }

    /** A still map-feature picture: zoom 2, {@code blocks} wide/high around (x, y). */
    private void feature(String name, double x, double y, double blocks, State st) {
        double side = blocks * S * 2;
        shots.add(new Shot(name, x, y, 2, side, side, 0, 0, Anchor.CENTRE, false, true, false, false, 1, 0, st,
            NOTHING, null));
    }

    private void animatedFeature(String name, double x, double y, double blocks, int frames) {
        double side = blocks * S * 2;
        shots.add(new Shot(name, x, y, 2, side, side, 0, 0, Anchor.CENTRE, false, true, false, false, frames, 0,
            state(), NOTHING, null));
    }

    /** An object picture at the stage, zoom {@code zoom}, {@code w x h} logical pixels. */
    private void object(String name, double zoom, double w, double h, int frames, SceneFn fn) {
        shots.add(new Shot(name, stageX(), stageY(), zoom, w, h, 0, 0, Anchor.CENTRE, false, true, false, false,
            frames, 0, state(), fn, null));
    }

    // ------------------------------------------------------------------ map features

    private void mapFeatures() {
        feature("walls-outline", cellX(0), cellY(0), 4, state());
        Shot outline = shots.get(shots.size() - 1);
        shots.add(new Shot("walls-filled", outline.x(), outline.y(), 2, outline.cropW(), outline.cropH(), 0, 0,
            Anchor.CENTRE, true, true, false, false, 1, 0, state(), NOTHING, null));
        String[] diag = {"wall-x", "wall-s", "wall-a", "wall-w", "wall-q"};
        for (int i = 0; i < diag.length; i++) {
            feature(diag[i], cellX(1 + i), cellY(0), 1.8, state());
        }
        feature("decor", cellX(6), cellY(0), 4, state());

        double fx = centre(map.fuels().get(0).bx());
        double fy = centre(map.fuels().get(0).by());
        double[] levels = {Const.MAX_STATION_FUEL, Const.MAX_STATION_FUEL / 2, 0};
        String[] fuelNames = {"fuel-full", "fuel-half", "fuel-empty"};
        for (int i = 0; i < 3; i++) {
            State st = state();
            st.fuel = levels[i];
            feature(fuelNames[i], fx, fy, 1.8, st);
        }
        // bases: 1 faces up, 2 down, 3 right, 4 left (by their team digits)
        String[] baseNames = {"base-up", "base-down", "base-right", "base-left"};
        for (int t = 1; t <= 4; t++) {
            for (var b : map.bases()) {
                if (b.teamDigit() == t) {
                    feature(baseNames[t - 1], centre(b.bx()), centre(b.by()), 2.2, state());
                }
            }
        }
        for (Cannon c : map.cannons()) {
            String n = c.dir() == Dir.UP ? "up" : c.dir() == Dir.DOWN ? "down" : c.dir() == Dir.LEFT ? "left" : "right";
            feature("cannon-" + n, centre(c.bx()), centre(c.by()), 2.2, state());
            if (c.dir() == Dir.UP) {
                State dead = state();
                dead.cannonsAlive = false;
                feature("cannon-dead", centre(c.bx()), centre(c.by()), 2.2, dead);
            }
        }
        var target = map.targets().get(0);
        double[] damage = {Const.TARGET_DAMAGE, Const.TARGET_DAMAGE * 0.6, Const.TARGET_DAMAGE * 0.2};
        String[] targetNames = {"target", "target-damaged", "target-badly-damaged"};
        for (int i = 0; i < 3; i++) {
            State st = state();
            st.targetDamage = damage[i];
            feature(targetNames[i], centre(target.bx()), centre(target.by()), 1.8, st);
        }
        State own = state();
        own.selfTeam = target.team();
        feature("target-own-team", centre(target.bx()), centre(target.by()), 1.8, own);
        for (var t : map.treasures()) {
            double tx = centre(t.bx());
            double ty = centre(t.by());
            if (t.empty()) {
                feature("treasure-empty", tx, ty, 2, state());
            } else {
                shots.add(new Shot("treasure", tx, ty, 2, 2 * S * 2, 2 * S * 2, 0, 0, Anchor.CENTRE, false, true,
                    false, false, 1, 0, state(), (s, l) -> s.addBall((float) tx,
                    (float) (t.by() * S + Const.BALL_RADIUS + 2)), null));
            }
        }
        String[] grav = {"gravity-plus", "gravity-minus", "gravity-clockwise", "gravity-anticlockwise",
            "gravity-up", "gravity-down", "gravity-left", "gravity-right"};
        for (int i = 0; i < grav.length; i++) {
            feature(grav[i], cellX(i), cellY(3), 1.8, state());
        }
        // '@' and '(' look the same; ')' (exit only) is not drawn at all
        for (Wormhole w : map.wormholes()) {
            if (w.type() == Wormhole.Type.NORMAL) {
                animatedFeature("wormhole", centre(w.bx()), centre(w.by()), 1.8, 24);
            }
        }
        var ic = map.itemConcentrators().get(0);
        animatedFeature("item-concentrator", centre(ic.bx()), centre(ic.by()), 1.8, 28);
        var ac = map.asteroidConcentrators().get(0);
        animatedFeature("asteroid-concentrator", centre(ac.bx()), centre(ac.by()), 1.8, 28);
    }

    /** Checkpoints A, B and C with B next: a wide picture of all three. */
    private void checkpoints() {
        var ca = map.checkpoints().get(0);
        var cc = map.checkpoints().get(2);
        double cx = (centre(ca.bx()) + centre(cc.bx())) / 2;
        shots.add(new Shot("checkpoints", cx, centre(ca.by()), 2, 11 * S * 2, 2 * S * 2, 0, 0,
            Anchor.CENTRE, false, true, false, false, 1, 0, state(), NOTHING, null));
    }

    // ------------------------------------------------------------------ objects

    private void addShip(SceneData s, double dx, double dy, double heading, int id, int flags, int color,
                         boolean locked, String name) {
        s.addShip((float) (stageX() + dx), (float) (stageY() + dy), heading, ship, id, flags, color, locked, name);
    }

    private void objects() {
        double up = Math.PI / 2;
        object("ships", 2, 300, 120, 1, (s, l) -> {
            s.selfId = 0;
            addShip(s, -50, 0, up, 0, 0, ShipPainter.shipColor(false, true, false), false, null);
            addShip(s, 0, 0, up, 1, 0, ShipPainter.shipColor(true, false, false), false, "mate");
            addShip(s, 50, 0, up, 2, 0, ShipPainter.shipColor(false, false, false), false, "enemy");
        });
        object("ship-shield", 2, 120, 120, 1, (s, l) ->
            addShip(s, 0, 0, up, 1, ShipPainter.SHIELD, Palette.WHITE, false, null));
        object("ship-emergency-shield", 2, 120, 120, 1, (s, l) ->
            addShip(s, 0, 0, up, 1, ShipPainter.SHIELD | ShipPainter.EMERGENCY_SHIELD, Palette.WHITE, false, null));
        object("ship-deflector", 2, 120, 120, 1, (s, l) ->
            addShip(s, 0, 0, up, 1, ShipPainter.DEFLECTOR, Palette.WHITE, false, null));
        object("ship-cloaked", 2, 120, 120, 1, (s, l) ->
            addShip(s, 0, 0, up, 1, ShipPainter.CLOAKED, Palette.WHITE, false, null));
        object("ship-phasing", 2, 120, 120, 1, (s, l) ->
            addShip(s, 0, 0, up, 1, ShipPainter.PHASING, Palette.WHITE, false, null));
        object("ship-lights", 2, 120, 120, 28, (s, l) -> {
            s.selfId = 1;
            addShip(s, 0, 0, up, 1, 0, Palette.WHITE, false, null);
        });
        object("ship-locked", 2, 160, 120, 1, (s, l) ->
            addShip(s, 0, 0, up, 1, 0, Palette.WHITE, true, "target"));
        object("shots", 2, 200, 120, 1, (s, l) -> {
            for (int i = 0; i < 6; i++) {
                s.addShot((float) (stageX() - 40 + 8 * i), (float) (stageY() + 10 + (i % 2) * 6), Palette.WHITE, false);
            }
            for (int i = 0; i < 4; i++) {
                s.addShot((float) (stageX() + 10 + 10 * i), (float) (stageY() - 12 + (i % 2) * 6), Palette.BLUE, true);
            }
        });
        object("sparks-debris", 2, 160, 160, 12, (s, l) -> {
            for (int i = 0; i < 160; i++) {
                double a = i * 2.39996;
                int age = (int) ((i * 7 + l) % 30);
                double d = 3 + age * 1.3;
                s.addParticle((float) (stageX() + d * Math.cos(a)), (float) (stageY() + d * Math.sin(a)),
                    Particles.colorIndex(30 - age, false));
            }
        });
        object("mines", 2, 200, 120, 1, (s, l) -> {
            s.addMine((float) (stageX() - 30), (float) stageY(), false, null);
            s.addMine((float) (stageX() + 30), (float) stageY(), true, "mate");
        });
        object("missiles", 2, 200, 120, 1, (s, l) -> {
            for (int i = 0; i < 4; i++) {
                s.addMissile((float) (stageX() - 45 + 30 * i), (float) stageY(), 15, (i * 32 + 16) & 127);
            }
        });
        object("laser", 2, 240, 120, 1, (s, l) -> {
            addShip(s, -50, 0, 0, 1, 0, Palette.WHITE, false, null);
            s.addLaser((float) (stageX() - 35), (float) stageY(), 90, 0, Palette.RED);
        });
        object("ecm", 2, 200, 200, 20, (s, l) -> {
            addShip(s, 0, 0, up, 1, 0, Palette.WHITE, false, null);
            s.addEcm((float) stageX(), (float) stageY(), 10 + (l % 20) * 4);
        });
        object("tractor-beam", 2, 280, 120, 1, (s, l) -> {
            addShip(s, -50, 0, 0, 1, 0, Palette.WHITE, false, null);
            addShip(s, 50, 0, Math.PI, 2, 0, Palette.WHITE, false, "enemy");
            s.addLink((float) (stageX() - 50), (float) stageY(), (float) (stageX() + 50), (float) stageY(),
                SceneData.LINK_TRACTOR);
        });
        object("transporter-beam", 2, 260, 120, 16, (s, l) -> {
            addShip(s, -50, 0, 0, 1, 0, Palette.WHITE, false, null);
            addShip(s, 50, 0, Math.PI, 2, 0, Palette.WHITE, false, "enemy");
            s.addLink((float) (stageX() - 50), (float) stageY(), (float) (stageX() + 50), (float) stageY(),
                SceneData.LINK_TRANSPORTER);
        });
        var f = map.fuels().get(0);
        double fx = centre(f.bx());
        double fy = centre(f.by());
        shots.add(new Shot("refuel-beam", fx + 35, fy, 2, 200, 120, 0, 0, Anchor.CENTRE, false, true, false, false,
            16, 0, state(), (s, l) -> {
                s.addShip((float) (fx + 70), (float) fy, Math.PI, ship, 1, 0, Palette.WHITE, false, null);
                s.addLink((float) (fx + 70), (float) fy, (float) fx, (float) fy, SceneData.LINK_REFUEL);
            }, null));
        object("ball-tether", 2, 240, 160, 1, (s, l) -> {
            addShip(s, -40, 20, 0, 1, 0, Palette.WHITE, false, null);
            s.addBall((float) (stageX() + 50), (float) (stageY() - 20));
            s.addLink((float) (stageX() - 40), (float) (stageY() + 20), (float) (stageX() + 50),
                (float) (stageY() - 20), SceneData.LINK_BALL);
        });
        object("ball-connecting", 2, 240, 160, 1, (s, l) -> {
            addShip(s, -40, 20, 0, 1, 0, Palette.WHITE, false, null);
            s.addBall((float) (stageX() + 50), (float) (stageY() - 20));
            s.addLink((float) (stageX() + 50), (float) (stageY() - 20), (float) (stageX() - 40),
                (float) (stageY() + 20), SceneData.LINK_CONNECTOR);
        });
        object("paused", 2, 120, 120, 20, (s, l) -> s.addPaused((float) stageX(), (float) stageY(), 10));
        object("wreckage", 2.5, 320, 160, 1, (s, l) -> {
            for (int i = 0; i < 3; i++) {
                s.addWreck((float) (stageX() - 50 + 50 * i), (float) stageY(), i == 1, i, 20 + 40 * i, 200);
            }
        });
        object("asteroids", 1, 300, 160, 1, (s, l) -> {
            for (int i = 0; i < 3; i++) {
                s.addAsteroid((float) (stageX() - 90 + 90 * i), (float) stageY(), i, 10 + 30 * i, 1 + i);
            }
        });
        object("items-in-play", 2, 250, 90, 1, (s, l) -> {
            int[] types = {Item.FUEL.ordinal(), Item.MISSILE.ordinal(), Item.MINE.ordinal()};
            for (int i = 0; i < types.length; i++) {
                s.addItem((float) (stageX() - 50 + 50 * i), (float) stageY(), types[i]);
            }
        });
    }

    // ------------------------------------------------------------------ HUD and panel

    private void hudParts() {
        int[] items = new int[Item.COUNT];
        Arrays.fill(items, -1);
        items[Item.MISSILE.ordinal()] = 4;
        items[Item.MINE.ordinal()] = 2;
        items[Item.CLOAK.ordinal()] = 1;
        items[Item.ECM.ordinal()] = 3;
        Hud hud = new Hud("enemy", 420, 20, items, 754, "NC V2 Z1", "+12"); // C frame.c modifier string format
        // the own ship among the features, as in a game, at a cell corner clear of all of them
        double hx = centre(40);
        double hy = centre(map.height() - 1 - 32);
        SceneFn game = (s, l) -> {
            s.selfId = 0;
            s.addShip((float) hx, (float) hy, Math.PI / 2, ship, 0, 0, Palette.WHITE, false, null);
            s.addShip((float) (hx + 320), (float) (hy + 140), Math.PI, ship, 1, 0, Palette.WHITE, false, "enemy");
            s.addShip((float) (hx - 260), (float) (hy - 120), 0, ship, 2, 0, Palette.BLUE, false, "mate");
        };
        hud("hud-box", hx, hy, hud, game, Anchor.CENTRE, 300, 220, 0, 0, true);
        hud("hud-lock", hx, hy, hud, game, Anchor.CENTRE, 200, 70, 45, -75, true);
        hud("hud-items", hx, hy, hud, game, Anchor.CENTRE, 70, 120, -85, -30, true);
        hud("hud-fuel-meter", hx, hy, hud, game, Anchor.CENTRE, 50, 160, 80, 0, true);
        hud("hud-time-left", hx, hy, hud, game, Anchor.CENTRE, 70, 28, -85, -82, true);
        hud("hud-modifiers", hx, hy, hud, game, Anchor.CENTRE, 100, 28, -90, 83, true);
        hud("score-popup", hx, hy, hud, game, Anchor.CENTRE, 100, 50, 255, 105, true);
        // the screen, the panel (radar, button bar, score list), the talk line, messages, windows and look options
        // come from the real client: capture-gallery.sh runs DevRun for them
    }

    private void hud(String name, double x, double y, Hud hud, SceneFn game, Anchor anchor, double w, double h,
                     double cx, double cy, boolean withMap) {
        shots.add(new Shot(name, x, y, 1, w, h, cx, cy, anchor, false, withMap, true, true, 1, 40, state(), game,
            hud));
    }
}
