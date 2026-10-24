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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.client.game.Players;
import com.fulcrologic.jxpilot.client.map.MapState;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.Records;

/**
 * {@link MapState} over the game: cannon, fuel and target state from the {@link ClientWorld}, the own
 * team from {@link Players}, and the frame counter set each frame. Allocation free.
 */
public final class WorldMapState implements MapState {
    private final ClientGame game;
    private long loops;

    public WorldMapState(ClientGame game) {
        this.game = game;
    }

    /** Set the frame counter ({@code SceneData.loops}). */
    public void setLoops(long loops) {
        this.loops = loops;
    }

    /** BEHAVIOR: the station's level times {@link Const#MAX_STATION_FUEL}. */
    @Override
    public double fuel(int i) {
        return game.world().fuelLevel(i) * Const.MAX_STATION_FUEL;
    }

    /** BEHAVIOR: alive unless the cannon's DEAD flag is set. */
    @Override
    public boolean cannonAlive(int i) {
        return (game.world().cannonFlags(i) & Records.CannonState.FLAG_DEAD) == 0;
    }

    /** BEHAVIOR: alive unless the target's DEAD flag is set. */
    @Override
    public boolean targetAlive(int i) {
        return (game.world().targetFlags(i) & Records.TargetState.FLAG_DEAD) == 0;
    }

    /** BEHAVIOR: the strength left times {@link Const#TARGET_DAMAGE}. */
    @Override
    public double targetDamage(int i) {
        return game.world().targetDamageLeft(i) * Const.TARGET_DAMAGE;
    }

    /** BEHAVIOR: the own player's team, {@link Const#TEAM_NOT_SET} when the own player is unknown. */
    @Override
    public int selfTeam() {
        Players p = game.players();
        int s = p.slotOf(p.selfId());
        return s < 0 ? Const.TEAM_NOT_SET : p.team(s);
    }

    /** F4: the next checkpoint from the own SELF (C {@code nextCheckPoint}, guimap.c / paintradar.c). */
    @Override
    public int nextCheckpoint() {
        return game.world().selfCheck();
    }

    /** BEHAVIOR: the owner's name when the base has an owner the player list knows, else null. */
    @Override
    public CharSequence baseOwnerName(int i) {
        int id = game.world().baseOwner(i);
        if (id == com.fulcrologic.jxpilot.common.net.Protocol.NO_ID) {
            return null;
        }
        Players p = game.players();
        int s = p.slotOf(id);
        return s < 0 ? null : p.name(s);
    }

    @Override
    public long loops() {
        return loops;
    }

    /** BEHAVIOR: the temporary wormholes of the newest snapshot. */
    @Override
    public int tempWormholes() {
        return game.world().tempWormholes();
    }

    @Override
    public int tempWormholeBx(int i) {
        return game.world().tempWormholeBx(i);
    }

    @Override
    public int tempWormholeBy(int i) {
        return game.world().tempWormholeBy(i);
    }
}
