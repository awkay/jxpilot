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
package com.fulcrologic.jxpilot.server.robot;

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * C {@code Robot_talks} ({@code robot.c}): the robot manager's chatter, sent as game messages through the
 * MESSAGE event path. Messages are written straight into the event's text buffer (no allocation).
 */
final class RobotTalk {
    static final int ENTER = 0;
    static final int LEAVE = 1;
    static final int KILL = 2;
    static final int WAR = 3;

    static final String[] ENTER_MSGS = {
        "%s just can't stand you anymore.",
        "%s has come to give you a hard time.",
        "%s is looking for trouble.",
        "%s has a very loose trigger finger.",
        "Have fear, %s is here.",
        "Prepare to die by the hands of %s.",
        "%s is untouchable.",
        "%s is in a gruesome mood.",
        "%s is in a killing mood.",
        "%s wants you for dessert.",
        "%s vows to torment you in this life and the next.",
        "%s has no sense of humour.",
        "%s is back from the Sirius wars, and he's in a violent mood.",
    };
    static final String[] LEAVE_MSGS = {
        "That's it, I've had enough. :(   I'm outta here. [%s]",
        "Later people.  It's been fun. [%s]",
        "Gotta go, ... er ... this ... er ... lab is closing. [%s]",
        "Er...  Oh!  It it really that late?  I gotta go. [%s].",
        "I'm signing off now.  Bye! [%s]",
        "Gotta go...  Er... I have some work to be done. [%s]",
        "This sucks! :(  I'm going back to cyberspace. [%s]",
        "I've taken enough beating for today. [%s]",
        "Oh man.  Playing with humans sucks. [%s]",
        "You can't beat us robots; we always return, stronger than ever! [%s]",
        "Geez, this just isn't my lucky day.  See ya some other time. [%s]",
        "Wow, this game is just killing me. :( [%s]",
        "I'll be back when you stop cheating! :-( [%s]",
    };
    static final String[] KILL_MSGS = {
        "Have some %s.  Have some! [%s]",
        "You want some more %s? [%s]",
        "%s lost his stuff again.  That's just tooooooo bad. :) [%s]",
        "%s: did you like that one? [%s]",
        "Face it %s, you just can't compete with me. [%s]",
        "%s, my grandmother plays better than you. [%s]",
        "Hey %s, go play chess instead. [%s]",
        "I think Darwin would've said you're too unfit to survive %s :) [%s]",
        "Oh my, what colourful explosions you make %s. :) [%s]",
        "Hey %s, maybe its time you upgraded that old 386. [%s]",
    };
    static final String[] WAR_MSGS = {
        "UNBELIEVABLE, me shot down by %s?!?!  This means war [%s]",
        "People like %s just piss me off. [%s]",
        "Nice %s.  But now its my turn. [%s]",
        "Red alert... target: %s. [%s]",
        "$%#^@#$^#$%  That's the last time you do that %s! [%s]",
        "Enough's enough!  It's only room enough for one of us %s here. [%s]",
        "I'm sorry %s, but you must... DIE!!!!! [%s]",
        "Jihad!  Die %s!  Die! [%s]",
        "%s will be assimilated [%s]",
    };

    private final World w;
    /** C {@code static int next_msg}: -1 until the first message. */
    int nextMsg = -1;

    RobotTalk(World w) {
        this.w = w;
    }

    /**
     * C {@code Robot_talks}.
     * <p>
     * BEHAVIOR: without robotsTalk only ENTER messages are sent.
     * BEHAVIOR: the message is number {@code next_msg % n} of its list, where next_msg starts at
     * {@code (int)(rfrac() * 997)} and then counts up by one per message, wrapping from 997 to 0.
     * BEHAVIOR: ENTER and LEAVE name the robot; KILL and WAR name the other player first, then the robot.
     */
    void says(int what, CharSequence robot, CharSequence other) {
        if (!w.params.robotsTalk && what != ENTER) {
            return;
        }
        String[] msgs = switch (what) {
            case ENTER -> ENTER_MSGS;
            case LEAVE -> LEAVE_MSGS;
            case KILL -> KILL_MSGS;
            default -> WAR_MSGS;
        };
        if (nextMsg == -1) {
            nextMsg = (int) (w.rfrac() * 997);
        }
        if (++nextMsg > 997) {
            nextMsg = 0;
        }
        String fmt = msgs[nextMsg % msgs.length];
        StringBuilder out = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID);
        if (what == KILL || what == WAR) {
            format(out, fmt, other, robot);
        } else {
            format(out, fmt, robot, null);
        }
    }

    /**
     * {@code sprintf} for templates whose only conversions are {@code %s} (other {@code %} characters are
     * literal, as in the WAR message that is printed with its own text as the format).
     */
    static void format(StringBuilder out, String fmt, CharSequence first, CharSequence second) {
        int arg = 0;
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c == '%' && i + 1 < fmt.length() && fmt.charAt(i + 1) == 's') {
                out.append(arg++ == 0 ? first : second);
                i++;
            } else {
                out.append(c);
            }
        }
    }
}
