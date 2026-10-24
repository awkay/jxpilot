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

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.CannonItems;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.ItemParams;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static com.fulcrologic.jxpilot.server.sim.SoundSitesTest.heard;
import static com.fulcrologic.jxpilot.server.sim.SoundSitesTest.quiet;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The C {@code sound_play_*} sites of the item code (collision.c item pickup and mine hit, item.c, laser.c,
 * shot.c, ship.c Tank_handle_detach, update.c device switches, warp and cannon timers, cannon.c): which event
 * is queued for a human listener, in which order, at which place.
 */
@DisplayName("item sound emit sites")
class ItemSoundSitesTest {
    static int itemObj(World w, Item kind, double x, double y) {
        int h = obj(w, Obj.ITEM, Obj.NO_ID, x, y, 0, 0, 1, 100);
        w.objects.info[h] = kind.ordinal();
        w.objects.counter[h] = 1;
        return h;
    }

    @Nested
    @DisplayName("item pickup (collision.c Player_collides_with_item)")
    class Pickup {
        @Test
        @DisplayName("each item plays its pickup sound at the ship; a refused pickup plays nothing")
        void pickup() {
            Simulation s = sim(FRAME, 20, 20, "__");
            World w = s.world;
            Arrays.fill(w.items.limit, 10);
            w.items.maxOffensiveItems = 100;
            w.items.maxDefensiveItems = 100;
            Player p = ready(s, "P", 0, 300, 300);
            List<List<String>> got = new ArrayList<>();
            for (Item kind : Item.values()) {
                p.have &= ~(Has.SHIELD | Has.EMERGENCY_SHIELD);
                p.items[Item.EMERGENCY_SHIELD.ordinal()] = 0;
                quiet(p);
                s.items.pickup(p, itemObj(w, kind, 300, 300));
                got.add(heard(p));
            }
            w.items.maxOffensiveItems = 0;
            quiet(p);
            s.items.pickup(p, itemObj(w, Item.WIDEANGLE, 300, 300));
            List<String> refused = heard(p);
            assertAll(
                () -> assertEquals(List.of(List.of("ENERGY_PACK_PICKUP:100"), List.of("WIDEANGLE_SHOT_PICKUP:100"),
                    List.of("BACK_SHOT_PICKUP:100"), List.of("AFTERBURNER_PICKUP:100"),
                    List.of("CLOAKING_DEVICE_PICKUP:100"), List.of("SENSOR_PACK_PICKUP:100"),
                    List.of("TRANSPORTER_PICKUP:100"), List.of("TANK_PICKUP:100"), List.of("MINE_PACK_PICKUP:100"),
                    List.of("ROCKET_PACK_PICKUP:100"), List.of("ECM_PICKUP:100"), List.of("LASER_PICKUP:100"),
                    List.of("EMERGENCY_THRUST_PICKUP:100"), List.of("TRACTOR_BEAM_PICKUP:100"),
                    List.of("AUTOPILOT_PICKUP:100"), List.of("EMERGENCY_SHIELD_PICKUP:100", "EMERGENCY_SHIELD_ON:100"),
                    List.of("DEFLECTOR_PICKUP:100"), List.of("HYPERJUMP_PICKUP:100"),
                    List.of("PHASING_DEVICE_PICKUP:100"), List.of("MIRROR_PICKUP:100"), List.of("ARMOR_PICKUP:100")),
                    got, "every item kind plays its own pickup sound; the first emergency shield without shields "
                        + "plays its pickup before it switches on"),
                () -> assertEquals(List.of(), refused, "an item bounced off at the offensive limit plays nothing"));
        }
    }

    @Nested
    @DisplayName("mines (shot.c Place_general_mine, collision.c Player_collides_with_mine)")
    class MineSounds {
        @Test
        @DisplayName("dropping, throwing, nuking and hitting a mine")
        void mines() {
            Simulation s = sim(FRAME, 20, 20, "__");
            World w = s.world;
            ItemParams ip = w.items;
            Player p = ready(s, "P", 0, 50, 50);
            Player far = ready(s, "F", 0, 650, 650);
            p.items[Item.MINE.ordinal()] = 10;
            p.fuel = 1e6;
            quiet(p, far);
            s.mines.placeGeneral(p, 0, 0, p.px, p.py, 0, 0, 0);
            List<String> drop = heard(p);
            List<String> dropFar = heard(far);
            quiet(p, far);
            s.mines.placeGeneral(p, 0, Obj.GRAVITY, p.px, p.py, 1, 1, 0);
            List<String> thrown = heard(p);
            ip.nukeMinSmarts = 1;
            ip.nukeMinMines = 2;
            quiet(p, far);
            s.mines.placeGeneral(p, 0, 0, p.px, p.py, 0, 0, Mods.withNuclear(0, Mods.NUCLEAR));
            List<String> nuke = heard(p);
            List<String> nukeFar = heard(far);
            quiet(p, far);
            s.mines.placeGeneral(null, 0, Obj.FROMCANNON, p.px, p.py, 0, 0, 0);
            List<String> cannon = heard(p);
            p.fuel = 0;
            s.mines.placeGeneral(p, 0, 0, p.px, p.py, 0, 0, 0);
            List<String> noFuel = heard(p);
            int h = obj(w, Obj.MINE, Obj.NO_ID, p.px, p.py, 0, 0, 1, 10);
            w.objects.mineOwner[h] = Obj.NO_ID;
            s.mines.playerHit(p, h);
            List<String> hit = heard(p);
            assertAll(
                () -> assertEquals(List.of("DROP_MINE:100"), drop, "a dropped mine plays DROP_MINE at the player"),
                () -> assertEquals(List.of(), dropFar, "out of hearing range nothing is queued"),
                () -> assertEquals(List.of("DROP_MOVING_MINE:100"), thrown, "a thrown mine plays DROP_MOVING_MINE"),
                () -> assertEquals(List.of("NUKE_LAUNCH:100"), nuke, "a nuclear mine plays NUKE_LAUNCH"),
                () -> assertEquals(List.of("NUKE_LAUNCH:100"), nukeFar, "to every player at full volume"),
                () -> assertEquals(List.of(), cannon, "a mine without a player plays nothing here"),
                () -> assertEquals(List.of(), noFuel, "a mine refused for lack of fuel plays nothing"),
                () -> assertEquals(List.of("PLAYER_HIT_MINE:100"), hit, "a mine hit plays PLAYER_HIT_MINE at the victim"));
        }
    }

    @Nested
    @DisplayName("missiles (shot.c Fire_general_shot)")
    class MissileSounds {
        @Test
        @DisplayName("torpedo, heat-seeker and nuke launches; refused and cannon missiles")
        void missiles() {
            Simulation s = sim(FRAME, 20, 20, "__");
            ItemParams ip = s.world.items;
            ip.allowHeatSeekers = true;
            ip.allowTorpedoes = true;
            Player p = ready(s, "P", 0, 50, 50);
            Player far = ready(s, "F", 0, 650, 650);
            p.items[Item.MISSILE.ordinal()] = 10;
            p.fuel = 1e6;
            quiet(p, far);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, 0, -1);
            List<String> torpedo = heard(p);
            quiet(p);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.HEAT_SHOT, 0, 0, -1);
            List<String> heat = heard(p);
            p.fuel = 0;
            quiet(p);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.HEAT_SHOT, 0, 0, -1);
            List<String> heatNoFuel = heard(p);
            quiet(p);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, 0, -1);
            List<String> torpedoNoFuel = heard(p);
            p.fuel = 1e6;
            ip.nukeMinSmarts = 2;
            quiet(p, far);
            s.missiles.fire(p, 0, false, p.px, p.py, Obj.TORPEDO, 0, Mods.withNuclear(0, Mods.NUCLEAR), -1);
            List<String> nuke = heard(p);
            List<String> nukeFar = heard(far);
            quiet(p);
            s.missiles.fire(null, 0, true, p.px, p.py, Obj.TORPEDO, 0, 0, -1);
            List<String> cannon = heard(p);
            assertAll(
                () -> assertEquals(List.of("FIRE_TORPEDO:100"), torpedo, "a torpedo plays FIRE_TORPEDO"),
                () -> assertEquals(List.of("FIRE_HEAT_SHOT:100"), heat, "a heat-seeker plays FIRE_HEAT_SHOT"),
                () -> assertEquals(List.of("FIRE_HEAT_SHOT:100"), heatNoFuel,
                    "the heat-seeker sound comes before the fuel check, so it plays even when refused (as C)"),
                () -> assertEquals(List.of(), torpedoNoFuel, "a torpedo refused for lack of fuel plays nothing"),
                () -> assertEquals(List.of("NUKE_LAUNCH:100"), nuke, "a nuclear torpedo plays NUKE_LAUNCH only"),
                () -> assertEquals(List.of("NUKE_LAUNCH:100"), nukeFar, "to every player"),
                () -> assertEquals(List.of(), cannon, "a missile without a player plays nothing here"));
        }
    }

    @Nested
    @DisplayName("lasers (shot.c Fire_general_laser, laser.c Laser_pulse_hits_player)")
    class LaserSounds {
        @Test
        @DisplayName("firing plays FIRE_LASER; a hit PLAYER_EAT_LASER, a roast also PLAYER_ROASTED")
        void lasers() {
            Simulation s = sim(FRAME, 20, 20, "__");
            World w = s.world;
            w.params.killingShots = Obj.KILLING_SHOTS;
            w.items.laserIsStunGun = false;
            Player p = ready(s, "P", 0, 300, 300);
            Player v = ready(s, "V", 0, 300, 300);
            p.items[Item.LASER.ordinal()] = 1;
            p.fuel = 1e6;
            quiet(p, v);
            s.lasers.fireGeneral(p, 0, 300, 300, 0, 0);
            List<String> fired = heard(p);
            quiet(p);
            s.lasers.fireGeneral(null, 0, 300, 300, 0, 0);
            List<String> cannon = heard(p);
            int i = w.pulses.count() - 1;
            int h = obj(w, Obj.PULSE, p.id, 300, 300, 0, 0, 0, 1);
            List<List<String>> got = new ArrayList<>();
            for (int used : new int[] {Has.SHIELD | Has.EMERGENCY_SHIELD, Has.SHIELD, 0}) {
                v.used = used;
                v.status &= ~Obj.KILLED;
                v.fuel = 1e6;
                quiet(v);
                s.lasers.hitPlayer(i, h, 300, 300, v);
                got.add(heard(v));
            }
            assertAll(
                () -> assertEquals(List.of("FIRE_LASER:100"), fired, "a player's laser plays FIRE_LASER"),
                () -> assertEquals(List.of(), cannon, "a laser without a player plays nothing here"),
                () -> assertEquals(List.of("PLAYER_EAT_LASER:100"), got.get(0), "a fully shielded victim only eats it"),
                () -> assertEquals(List.of("PLAYER_EAT_LASER:100"), got.get(1), "a shielded victim is not roasted"),
                () -> assertEquals(List.of("PLAYER_EAT_LASER:100", "PLAYER_ROASTED:100"), got.get(2),
                    "an unshielded victim without armor is roasted"));
        }
    }

    @Nested
    @DisplayName("devices (update.c switches, item.c beams, transporter, ECM; ship.c tank detach)")
    class DeviceSounds {
        @Test
        @DisplayName("each switch plays its on/off sound only when it changes; cloak and deflector to the player only")
        void switches() {
            Simulation s = sim(FRAME, 20, 20, "__");
            Devices d = s.devices;
            Player p = ready(s, "P", 0, 300, 300);
            Player o = ready(s, "O", 0, 300, 300);
            p.have &= ~Has.EMERGENCY_SHIELD;
            p.items[Item.PHASING.ordinal()] = 1;
            p.items[Item.CLOAK.ordinal()] = 1;
            p.items[Item.DEFLECTOR.ordinal()] = 1;
            p.items[Item.EMERGENCY_THRUST.ordinal()] = 1;
            List<String> got = new ArrayList<>();
            Runnable[] steps = {
                () -> d.phasing(p, true), () -> d.phasing(p, false),
                () -> d.cloak(p, true), () -> d.cloak(p, false), () -> d.cloak(p, false),
                () -> d.deflector(p, true), () -> d.deflector(p, false), () -> d.deflector(p, false),
                () -> d.emergencyThrust(p, true), () -> d.emergencyThrust(p, true),
                () -> d.emergencyThrust(p, false), () -> d.emergencyThrust(p, false),
                () -> {
                    p.have |= Has.EMERGENCY_SHIELD;
                    p.items[Item.EMERGENCY_SHIELD.ordinal()] = 1;
                    d.emergencyShield(p, true);
                },
                () -> d.emergencyShield(p, false), () -> d.emergencyShield(p, false),
                () -> d.autopilot(p, true), () -> d.autopilot(p, false),
            };
            List<String> other = new ArrayList<>();
            for (Runnable r : steps) {
                quiet(p, o);
                r.run();
                got.add(String.join(",", heard(p)));
                other.add(String.join(",", heard(o)));
            }
            assertAll(
                () -> assertEquals(List.of("PHASING_ON:100", "PHASING_OFF:100",
                    "CLOAK:100", "CLOAK:100", "",
                    "DEFLECTOR:100", "DEFLECTOR:100", "",
                    "EMERGENCY_THRUST_ON:100", "", "EMERGENCY_THRUST_OFF:100", "",
                    "EMERGENCY_SHIELD_ON:100", "EMERGENCY_SHIELD_OFF:100", "",
                    "AUTOPILOT_ON:100", "AUTOPILOT_OFF:100"), got,
                    "phasing, cloak, deflector, emergency thrust/shield and autopilot sounds; nothing when already in that state"),
                () -> assertEquals(List.of("PHASING_ON:100", "PHASING_OFF:100", "", "", "", "", "", "",
                    "EMERGENCY_THRUST_ON:100", "", "EMERGENCY_THRUST_OFF:100", "",
                    "EMERGENCY_SHIELD_ON:100", "EMERGENCY_SHIELD_OFF:100", "",
                    "AUTOPILOT_ON:100", "AUTOPILOT_OFF:100"), other,
                    "another player nearby hears all but the cloak and deflector (sound_play_player)"));
        }

        @Test
        @DisplayName("tractor/pressor beams, transporter success and failure, ECM and tank detach")
        void tools() {
            Simulation s = sim(FRAME, 20, 20, "__");
            World w = s.world;
            Devices d = s.devices;
            Player p = ready(s, "P", 0, 100, 100);
            Player v = ready(s, "V", 0, 100, 150);
            p.fuel = 1e6;
            quiet(p);
            d.generalTractorBeam(p, p.px, p.py, 1, v, false);
            List<String> tractor = heard(p);
            quiet(p);
            d.generalTractorBeam(p, p.px, p.py, 1, v, true);
            List<String> pressor = heard(p);
            quiet(p);
            d.generalTractorBeam(p, p.px, p.py - 500, 1, v, true);
            List<String> outOfRange = heard(p);
            Arrays.fill(v.items, 0);
            v.fuel = 0;
            v.numTanks = 0;
            quiet(p);
            d.generalTransporter(p, p.px, p.py, v);
            List<String> bald = heard(p);
            v.items[Item.WIDEANGLE.ordinal()] = 1;
            quiet(p);
            d.generalTransporter(p, p.px, p.py, v);
            List<String> stole = heard(p);
            v.initPixels(600, 600);
            p.items[Item.TRANSPORTER.ordinal()] = 1;
            quiet(p);
            d.transporter(p);
            List<String> nobody = heard(p);
            p.items[Item.ECM.ordinal()] = 1;
            quiet(p);
            s.ecm.fireGeneral(p, 0, p.px, p.py);
            List<String> ecm = heard(p);
            quiet(p);
            s.ecm.fireGeneral(null, 0, p.px, p.py);
            List<String> cannonEcm = heard(p);
            quiet(p);
            s.tanks.detach(p);
            List<String> noTank = heard(p);
            s.tanks.add(p, 1000);
            quiet(p);
            s.tanks.detach(p);
            List<String> detach = heard(p);
            assertAll(
                // the beam source is the listener; 50 px from the victim is within 200 + 50 px
                () -> assertEquals(List.of("TRACTOR_BEAM:100"), tractor, "a tractor beam plays TRACTOR_BEAM at its source"),
                () -> assertEquals(List.of("PRESSOR_BEAM:100"), pressor, "a pressor beam plays PRESSOR_BEAM"),
                () -> assertEquals(List.of(), outOfRange, "a beam out of range plays nothing"),
                () -> assertEquals(List.of("TRANSPORTER_FAIL:100"), bald, "stealing from a victim with nothing fails"),
                () -> assertEquals(List.of("TRANSPORTER_SUCCESS:100"), stole, "stealing an item succeeds"),
                () -> assertEquals(List.of("TRANSPORTER_FAIL:100"), nobody, "a transporter with nobody in range fails"),
                () -> assertEquals(List.of("ECM:100"), ecm, "a player's ECM plays ECM"),
                () -> assertEquals(List.of(), cannonEcm, "an ECM without a player plays nothing here"),
                () -> assertEquals(List.of(), noTank, "without a tank nothing detaches or plays"),
                () -> assertEquals(List.of("TANK_DETACH:100"), detach, "a detached tank plays TANK_DETACH at the ship"));
        }

        @Test
        @DisplayName("a hyperjump plays HYPERJUMP where the ship was and WORM_HOLE where it lands (update.c)")
        void hyperjump() {
            Simulation s = sim(FRAME, 20, 20, "__");
            Player p = ready(s, "P", 0, 300, 300);
            Player o = ready(s, "O", 0, 300, 300);
            p.status |= Obj.WARPING;
            p.wormHoleHit = -1;
            quiet(p, o);
            s.ships.warp(p);
            List<String> watcher = heard(o);
            assertAll(
                () -> assertEquals(List.of("HYPERJUMP:100", "WORM_HOLE:100"), heard(p),
                    "the jumper hears the jump and its arrival"),
                () -> assertEquals("HYPERJUMP:100", watcher.get(0), "a player at the start hears HYPERJUMP first"));
        }
    }

    @Nested
    @DisplayName("cannons (cannon.c Cannon_fire, Cannon_defend; update.c cannon timers)")
    class CannonSounds {
        Simulation s;
        World w;
        CannonItems ci;
        Player listener;
        Player target;

        void setup(double targetDy) {
            s = CannonsTest.sim(FRAME, "__");
            w = s.world;
            ci = w.cannonItems;
            listener = ready(s, "L", 0, CannonsTest.CX, CannonsTest.CY);
            target = ready(s, "T", 0, CannonsTest.CX, CannonsTest.CY + targetDy);
            target.items[Item.WIDEANGLE.ordinal()] = 1;
        }

        List<String> fire(int weapon, Item item, double targetDy) {
            setup(targetDy);
            if (item != null) {
                ci.add(0, item, 1);
            }
            w.items.allowSmartMissiles = false;
            w.items.allowHeatSeekers = false;
            quiet(listener);
            s.cannonWeapons.fire(0, weapon, target, 16);
            return heard(listener);
        }

        boolean thrownMine() {
            Objects o = w.objects;
            for (int k = 0; k < o.count(); k++) {
                int h = o.active[k];
                if (o.type[h] == Obj.MINE) {
                    return (o.status[h] & Obj.GRAVITY) != 0;
                }
            }
            throw new AssertionError("no mine");
        }

        @Test
        @DisplayName("each weapon plays its sound at the cannon after firing")
        void weapons() {
            List<String> shot = fire(CannonWeapons.CW_SHOT, null, 100);
            List<String> mine = fire(CannonWeapons.CW_MINE, Item.MINE, 100);
            String mineSound = (thrownMine() ? "DROP_MOVING_MINE" : "DROP_MINE") + ":100";
            List<String> missile = fire(CannonWeapons.CW_MISSILE, Item.MISSILE, 100);
            List<String> laser = fire(CannonWeapons.CW_LASER, Item.LASER, 100);
            List<String> ecm = fire(CannonWeapons.CW_ECM, Item.ECM, 100);
            List<String> tractor = fire(CannonWeapons.CW_TRACTORBEAM, Item.TRACTOR_BEAM, 100);
            List<String> transClose = fire(CannonWeapons.CW_TRANSPORTER, Item.TRANSPORTER, 50);
            List<String> transFar = fire(CannonWeapons.CW_TRANSPORTER, Item.TRANSPORTER, 400);
            List<String> jet = fire(CannonWeapons.CW_GASJET, Item.FUEL, 100);
            assertAll(
                () -> assertEquals(List.of("CANNON_FIRE:100"), shot, "shots play CANNON_FIRE"),
                () -> assertEquals(List.of(mineSound), mine, "a placed mine plays DROP_MINE, a thrown one DROP_MOVING_MINE"),
                () -> assertEquals(List.of("FIRE_TORPEDO:100"), missile, "a torpedo plays FIRE_TORPEDO"),
                () -> assertEquals(List.of("FIRE_LASER:100"), laser, "a laser plays FIRE_LASER"),
                () -> assertEquals(List.of("ECM:100"), ecm, "an ECM plays ECM"),
                () -> assertEquals(List.of(), tractor, "starting a tractor beam plays nothing"),
                () -> assertEquals(List.of("TRANSPORTER_SUCCESS:100"), transClose,
                    "a transporter in range plays only the transporter's own sound"),
                () -> assertEquals(List.of("TRANSPORTER_FAIL:100"), transFar, "a transporter out of range fails"),
                () -> assertEquals(List.of("THRUST:100"), jet, "a gas jet plays THRUST"));
        }

        @Test
        @DisplayName("defenses play their ON sound when used and their OFF sound when they time out, at the cannon")
        void defenses() {
            setup(100);
            Cannon cn = w.map.cannons().get(0);
            w.params.cannonsUseItems = true;
            w.items.cannonsDefend = true;
            ci.add(0, Item.EMERGENCY_SHIELD, 1);
            obj(w, Obj.SHOT, target.id, cn.x(), cn.y() + 10, 0, 0, 1, 100);
            quiet(listener);
            for (int k = 0; k < 50 && (ci.used[0] & Has.EMERGENCY_SHIELD) == 0; k++) {
                s.cannonWeapons.defend(0);
            }
            List<String> on = heard(listener);
            ci.emergencyShieldLeft[0] = 2;
            quiet(listener);
            s.cannonWeapons.tick(0);
            List<String> running = heard(listener);
            s.cannonWeapons.tick(0);
            List<String> shieldOff = heard(listener);
            ci.used[0] |= Has.PHASING_DEVICE;
            ci.phasingLeft[0] = 1;
            quiet(listener);
            s.cannonWeapons.tick(0);
            List<String> phasingOff = heard(listener);
            assertAll(
                () -> assertTrue((ci.used[0] & Has.EMERGENCY_SHIELD) == 0, "(the shield ran out)"),
                // the cannon is at (717.5, 676.655), the listener at (717, 676): 100 - 100 * 0.655 / 525 = 99.9
                () -> assertEquals(List.of("EMERGENCY_SHIELD_ON:99"), on, "using the emergency shield plays its ON sound"),
                () -> assertEquals(List.of(), running, "a shield still running plays nothing"),
                () -> assertEquals(List.of("EMERGENCY_SHIELD_OFF:99"), shieldOff, "a timed-out shield plays its OFF sound"),
                () -> assertEquals(List.of("PHASING_OFF:99"), phasingOff, "timed-out phasing plays PHASING_OFF"));
        }
    }
}
