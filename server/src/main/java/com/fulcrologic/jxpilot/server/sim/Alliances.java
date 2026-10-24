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
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Alliances between players, a port of {@code src/server/alliance.c}: invitations, forming, joining, merging,
 * leaving and dissolving alliances, with C's messages. A player's alliance is {@link Player#alliance} (C
 * {@code pl->alliance}), its pending invitation {@link Player#invite}; {@link World#allied} is C
 * {@code ALLIANCE(i, j)}. Each change of a player's alliance emits {@link Events#ALLIANCE} (C
 * {@code updateScores}, which resends the score with the alliance mark).
 * <p>
 * Alliances exist only when {@link #enabled} (C {@code BIT(World.rules->mode, ALLIANCES)}: {@code allowAlliances}
 * outside team play, latched when the world rules are computed). Only commands and players leaving change
 * alliances, never the tick, so allocation does not matter here (none happens anyway).
 */
public final class Alliances {
    /** C {@code MAX_TEAMS}: alliance ids are 0..MAX_TEAMS-1, at most that many alliances. */
    static final int MAX = Const.MAX_TEAMS;

    private final World w;
    private final Simulation sim;
    /** C {@code Alliances[i]->id}, {@code [0, count)}. */
    private final int[] ids = new int[MAX];
    /** C {@code Alliances[i]->NumMembers}. */
    private final int[] members = new int[MAX];
    /** C {@code NumAlliances}. */
    private int count;
    /** C {@code BIT(World.rules->mode, ALLIANCES)}: allowAlliances and not team play, as of the last rules update. */
    public boolean enabled;

    Alliances(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
        this.enabled = w.options.getBool(Opt.ALLOW_ALLIANCES) && !w.params.teamPlay;
    }

    /** C {@code NumAlliances}. */
    public int count() {
        return count;
    }

    private boolean announce() {
        return w.options.getBool(Opt.ANNOUNCE_ALLIANCES);
    }

    // ================================================================ invitations

    /**
     * C {@code Invite_player}: {@code pl} invites {@code ally}.
     * <p>
     * BEHAVIOR: nothing (false) when inviting oneself, a tank, an ally, or the player already invited.
     * BEHAVIOR: when {@code ally} has invited {@code pl}, {@code pl} accepts instead (true).
     * BEHAVIOR: otherwise an earlier invitation of {@code pl} is cancelled, the new one recorded, and a robot
     * decides at once ({@code RobotManager.invite}, C {@code Robot_invite}) while a human gets
     * " < NAME seeks an alliance with you >" (true).
     */
    public boolean invite(Player pl, Player ally) {
        if (pl == ally || ally.tank || w.allied(pl, ally) || pl.invite == ally.id) {
            return false;
        }
        if (ally.invite == pl.id) {
            accept(pl, ally);
            return true;
        }
        if (pl.invite != Obj.NO_ID) {
            cancelInvitation(pl);
        }
        pl.invite = ally.id;
        if (ally.robot) {
            sim.robots.invite(ally, pl);
        } else if (human(ally)) {
            tell(ally).append(" < ").append(pl.name).append(" seeks an alliance with you >");
        }
        return true;
    }

    /**
     * C {@code Cancel_invitation}.
     * <p>
     * BEHAVIOR: without an invitation nothing happens (false); otherwise it is dropped and a human invitee gets
     * " < NAME has cancelled the invitation for an alliance >" (true). An invitee who has left the game (its id
     * free, C reads a stale slot) gets nothing.
     */
    public boolean cancelInvitation(Player pl) {
        if (pl.invite == Obj.NO_ID) {
            return false;
        }
        Player ally = w.byId(pl.invite);
        pl.invite = Obj.NO_ID;
        if (ally != null && ally.spawned && human(ally)) {
            tell(ally).append(" < ").append(pl.name).append(" has cancelled the invitation for an alliance >");
        }
        return true;
    }

    /**
     * C {@code Refuse_alliance}: {@code pl} refuses the invitation of {@code ally}.
     * <p>
     * BEHAVIOR: false when {@code ally} has not invited {@code pl}; otherwise the invitation is dropped and a
     * human {@code ally} gets " < NAME has declined your invitation for an alliance >" (true).
     */
    public boolean refuse(Player pl, Player ally) {
        if (ally.invite != pl.id) {
            return false;
        }
        ally.invite = Obj.NO_ID;
        if (human(ally)) {
            tell(ally).append(" < ").append(pl.name).append(" has declined your invitation for an alliance >");
        }
        return true;
    }

    /**
     * C {@code Refuse_all_alliances}.
     * <p>
     * BEHAVIOR: refuses every invitation of {@code pl}, in player order; a human then gets
     * " < You were not invited for any alliance >" or " < N invitation(s) for an alliance/alliances declined >";
     * returns how many.
     */
    public int refuseAll(Player pl) {
        int j = 0;
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (o.invite == pl.id) {
                refuse(pl, o);
                j++;
            }
        }
        if (human(pl)) {
            StringBuilder sb = tell(pl);
            if (j == 0) {
                sb.append(" < You were not invited for any alliance >");
            } else {
                sb.append(" < ").append(j).append(" invitation").append(j > 1 ? "s" : "").append(" for ")
                    .append(j > 1 ? "alliances" : "an alliance").append(" declined >");
            }
        }
        return j;
    }

    /**
     * C {@code Accept_alliance}: {@code pl} accepts the invitation of {@code ally}.
     * <p>
     * BEHAVIOR: false when {@code ally} has not invited {@code pl}; otherwise the invitation is dropped, and an
     * invitation left over from a player already in {@code pl}'s alliance ends there (false); otherwise: both
     * in alliances: {@code pl}'s alliance merges into {@code ally}'s; only {@code ally} in one: {@code pl} joins
     * it; only {@code pl}: {@code ally} joins {@code pl}'s; neither: a new alliance (false when none is left).
     */
    public boolean accept(Player pl, Player ally) {
        if (ally.invite != pl.id) {
            return false;
        }
        ally.invite = Obj.NO_ID;
        if (w.allied(pl, ally)) {
            return false; // a leftover invitation from an ally: C merges the alliance into itself and crashes
        }
        if (ally.alliance != Player.ALLIANCE_NOT_SET) {
            if (pl.alliance != Player.ALLIANCE_NOT_SET) {
                merge(ally, pl.alliance);
            } else {
                join(pl, ally);
            }
        } else if (pl.alliance != Player.ALLIANCE_NOT_SET) {
            join(ally, pl);
        } else {
            return create(pl, ally);
        }
        return true;
    }

    /**
     * C {@code Accept_all_alliances}.
     * <p>
     * BEHAVIOR: accepts every invitation of {@code pl}, in player order; a human then gets
     * " < You were not invited for any alliance >" or " < N invitations for alliances accepted >" (C's test is
     * {@code j > 0}, so even one invitation reads "1 invitations for alliances"); returns how many.
     */
    public int acceptAll(Player pl) {
        int j = 0;
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (o.invite == pl.id) {
                accept(pl, o);
                j++;
            }
        }
        if (human(pl)) {
            StringBuilder sb = tell(pl);
            if (j == 0) {
                sb.append(" < You were not invited for any alliance >");
            } else {
                sb.append(" < ").append(j).append(" invitations for alliances accepted >");
            }
        }
        return j;
    }

    // ================================================================ membership

    /**
     * C {@code Get_alliance_member_count}.
     * <p>
     * BEHAVIOR: the alliance's member count, 0 for an unknown id or ALLIANCE_NOT_SET.
     */
    public int memberCount(int id) {
        int i = find(id);
        return i < 0 ? 0 : members[i];
    }

    /** C {@code Find_alliance}: the index of alliance {@code id} in {@code [0, count)}, or -1. */
    private int find(int id) {
        if (id != Player.ALLIANCE_NOT_SET) {
            for (int i = 0; i < count; i++) {
                if (ids[i] == id) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** C {@code New_alliance_ID}: the lowest id not in use, or ALLIANCE_NOT_SET when all MAX_TEAMS are. */
    private int newId() {
        for (int id = 0; id < MAX; id++) {
            if (find(id) < 0) {
                return id;
            }
        }
        return Player.ALLIANCE_NOT_SET;
    }

    /**
     * C {@code Create_alliance}.
     * <p>
     * BEHAVIOR: false (nothing changes) when all MAX_TEAMS alliance ids are in use; otherwise both players are
     * added (pl1 first), then with announceAlliances everyone gets " < A and B have formed alliance N >", else
     * each gets " < You have formed an alliance with OTHER >" (true).
     */
    boolean create(Player pl1, Player pl2) {
        int id = newId();
        if (id == Player.ALLIANCE_NOT_SET) {
            return false; // C: warn("Maximum number of alliances reached.")
        }
        ids[count] = id;
        members[count] = 0;
        count++;
        add(id, pl1);
        add(id, pl2);
        if (announce()) {
            all().append(" < ").append(pl1.name).append(" and ").append(pl2.name).append(" have formed alliance ")
                .append(id).append(" >");
        } else {
            tell(pl1).append(" < You have formed an alliance with ").append(pl2.name).append(" >");
            tell(pl2).append(" < You have formed an alliance with ").append(pl1.name).append(" >");
        }
        return true;
    }

    /**
     * C {@code Player_join_alliance}: {@code pl} joins the alliance of {@code ally}.
     * <p>
     * BEHAVIOR: a tank joins silently; otherwise announced first (so the joiner does not get two messages): with
     * announceAlliances everyone gets
     * " < NAME has joined alliance N >"; else the human members get " < NAME has joined your alliance >" and a
     * human joiner " < You have joined ALLY's alliance >". Then {@code pl} is added.
     */
    public void join(Player pl, Player ally) {
        int id = ally.alliance;
        if (pl.tank) {
            add(id, pl);
            return;
        }
        if (announce()) {
            all().append(" < ").append(pl.name).append(" has joined alliance ").append(id).append(" >");
        } else {
            tellAlliance(id, msg().append(" < ").append(pl.name).append(" has joined your alliance >"));
            if (human(pl)) {
                tell(pl).append(" < You have joined ").append(ally.name).append("'s alliance >");
            }
        }
        add(id, pl);
    }

    /**
     * C {@code Alliance_add_player}.
     * <p>
     * BEHAVIOR: invitations of other players for {@code pl} are cancelled (with their message); then {@code pl}
     * is a member (ALLIANCE event) and the member count grows.
     */
    private void add(int id, Player pl) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (o.invite == pl.id) {
                cancelInvitation(o);
            }
        }
        pl.alliance = id;
        members[find(id)]++;
        changed(pl);
    }

    /**
     * C {@code Leave_alliance}.
     * <p>
     * BEHAVIOR: false outside an alliance. Otherwise {@code pl} is removed, then with announceAlliances everyone
     * gets " < NAME has left alliance N >"; else the remaining human members get " < NAME has left your
     * alliance >" and a human leaver " < You have left the alliance >". An alliance left with at most one
     * member is dissolved. True.
     */
    public boolean leave(Player pl) {
        int id = pl.alliance;
        if (id == Player.ALLIANCE_NOT_SET) {
            return false;
        }
        remove(id, pl);
        if (pl.tank) {
            // a tank leaves silently (C !IS_TANK_IND)
        } else if (announce()) {
            all().append(" < ").append(pl.name).append(" has left alliance ").append(id).append(" >");
        } else {
            tellAlliance(id, msg().append(" < ").append(pl.name).append(" has left your alliance >"));
            if (human(pl)) {
                tell(pl).append(" < You have left the alliance >");
            }
        }
        int i = find(id);
        if (members[i] <= 1) {
            dissolve(id);
        }
        return true;
    }

    /** C {@code Alliance_remove_player}: BEHAVIOR: a member of {@code id} leaves it (ALLIANCE event); others are ignored. */
    private boolean remove(int id, Player pl) {
        if (pl.alliance != id) {
            return false;
        }
        pl.alliance = Player.ALLIANCE_NOT_SET;
        members[find(id)]--;
        changed(pl);
        return true;
    }

    /**
     * C {@code Dissolve_alliance}.
     * <p>
     * BEHAVIOR: every remaining member leaves the alliance (without announceAlliances a human member gets
     * " < Your alliance has been dissolved >"); the last alliance in the list takes its place; with
     * announceAlliances everyone gets " < Alliance N has been dissolved >".
     */
    void dissolve(int id) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (o.alliance == id) {
                remove(id, o);
                if (!announce() && human(o)) {
                    tell(o).append(" < Your alliance has been dissolved >");
                }
            }
        }
        int i = find(id);
        ids[i] = ids[count - 1];
        members[i] = members[count - 1];
        if (announce()) {
            all().append(" < Alliance ").append(id).append(" has been dissolved >");
        }
        count--;
    }

    /** C {@code Dissolve_all_alliances}: BEHAVIOR: dissolves every alliance, last first. */
    public void dissolveAll() {
        for (int i = count - 1; i >= 0; i--) {
            dissolve(ids[i]);
        }
    }

    /**
     * C {@code Merge_alliances}: every member of alliance {@code id2} joins {@code ally}'s alliance (with the
     * join messages), then alliance {@code id2}, now empty, is dissolved.
     */
    private void merge(Player ally, int id2) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (o.alliance == id2) {
                remove(id2, o);
                join(o, ally);
            }
        }
        dissolve(id2);
    }

    /**
     * C {@code Alliance_player_list}: the member list for {@code pl}.
     * <p>
     * BEHAVIOR: outside an alliance: " < You are not a member of any alliance >".
     * BEHAVIOR: otherwise " < Alliance N:" (announceAlliances) or " < Your alliance: ", then the human members, then
     * the robots (tanks are not listed), each followed by ", "; whenever the line is longer than 80 characters before a name, it is sent
     * with ">" appended and continues as " <            "; the last ", " is cut and " >" ends the last line.
     */
    public void list(Player pl) {
        if (pl.alliance == Player.ALLIANCE_NOT_SET) {
            tell(pl).append(" < You are not a member of any alliance >");
            return;
        }
        StringBuilder msg = new StringBuilder(Const.MSG_LEN);
        if (announce()) {
            msg.append(" < Alliance ").append(pl.alliance).append(':');
        } else {
            msg.append(" < Your alliance: ");
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < w.numPlayers; k++) {
                Player o = w.playerAt(k);
                if (o.alliance == pl.alliance && (pass == 0 ? human(o) : o.robot)) {
                    if (msg.length() > 80) {
                        msg.append('>');
                        tell(pl).append(msg);
                        msg.setLength(0);
                        msg.append(" <            ");
                    }
                    msg.append(o.name).append(", ");
                }
            }
        }
        int n = msg.length();
        if (n >= 2 && msg.charAt(n - 2) == ',' && msg.charAt(n - 1) == ' ') {
            msg.setLength(n - 2);
        }
        msg.append(" >");
        tell(pl).append(msg);
    }

    /**
     * The world rules changed (C {@code Set_world_rules}): alliances are allowed iff allowAlliances and not team
     * play. Existing alliances are kept (C dissolves them only in the allowAlliances tuner).
     */
    public void rulesChanged() {
        enabled = w.options.getBool(Opt.ALLOW_ALLIANCES) && !w.params.teamPlay;
    }

    // ================================================================ helpers

    /** C {@code IS_HUMAN_PTR}: neither a robot nor a tank. */
    private static boolean human(Player p) {
        return !p.robot && !p.tank;
    }

    private void changed(Player pl) {
        w.events.add(Events.ALLIANCE, pl.id, pl.alliance, 0, 0f);
    }

    /** C {@code Set_player_message}. */
    private StringBuilder tell(Player to) {
        return w.events.playerMessage(Msg.Text.KIND_SERVER, Obj.NO_ID, to.id);
    }

    /** C {@code Set_message}. */
    private StringBuilder all() {
        return w.events.message(Msg.Text.KIND_SERVER, Obj.NO_ID);
    }

    /** C {@code Set_alliance_message}: {@code text} to every human member of alliance {@code id}, in player order. */
    private void tellAlliance(int id, CharSequence text) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (human(o) && o.alliance == id) {
                tell(o).append(text);
            }
        }
    }

    private final StringBuilder msg = new StringBuilder(Const.MSG_LEN);

    /** The cleared scratch line for {@link #tellAlliance}. */
    private StringBuilder msg() {
        msg.setLength(0);
        return msg;
    }
}
