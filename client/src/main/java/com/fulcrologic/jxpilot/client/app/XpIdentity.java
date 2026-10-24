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

import com.fulcrologic.jxpilot.client.xp.XpProto;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The real name, host and display an original XPilot server is told at login (C {@code xpilot.c} and
 * {@code default.c} {@code Parse_options}, {@code common/checknames.c}).
 *
 * @param realName C {@code realName}: $XPILOTUSER or the login name, the {@code user} resource over both
 * @param host     C {@code hostName}: $XPILOTHOST or the local host name, the {@code host} resource over both
 * @param display  C {@code dispName}: the {@code display} resource, else $DISPLAY, else ":0.0"
 */
public record XpIdentity(String realName, String host, String display) {
    /** C {@code DISPLAY_DEF}. */
    public static final String DEFAULT_DISPLAY = ":0.0";

    /**
     * Resolve as C does.
     * <p>
     * BEHAVIOR: a non-empty resource wins; else the environment variable when set; else the login name / local host
     * / ":0.0". The real name and the host are then fixed ({@link #fixRealName}, {@link #fixHostName}).
     *
     * @param env       environment lookup (null when unset)
     * @param localHost the local host name (C {@code sock_get_local_hostname}), asked only when needed
     */
    public static XpIdentity resolve(String user, String host, String display, Function<String, String> env,
                                     String loginName, Supplier<String> localHost) {
        String r = !user.isEmpty() ? user : env.apply("XPILOTUSER") != null ? env.apply("XPILOTUSER") : loginName;
        String h = !host.isEmpty() ? host : env.apply("XPILOTHOST") != null ? env.apply("XPILOTHOST") : localHost.get();
        String d = !display.isEmpty() ? display : env.apply("DISPLAY") != null ? env.apply("DISPLAY") : DEFAULT_DISPLAY;
        return new XpIdentity(fixRealName(r), fixHostName(h), d);
    }

    /**
     * C {@code Fix_real_name} on the name's UTF-8 bytes.
     * <p>
     * BEHAVIOR: cut to MAX_NAME_LEN - 1 (15) bytes; empty is "X"; every byte outside '!'..'~' (C isgraph) is 'x'.
     */
    public static String fixRealName(String name) {
        byte[] b = cut(name, XpProto.MAX_NAME_LEN - 1);
        if (b.length == 0) {
            return "X";
        }
        StringBuilder sb = new StringBuilder(b.length);
        for (byte c : b) {
            sb.append(graph(c) ? (char) c : 'x');
        }
        return sb.toString();
    }

    /**
     * C {@code Fix_host_name} on the name's UTF-8 bytes.
     * <p>
     * BEHAVIOR: cut to MAX_HOST_LEN - 1 (63) bytes; a first byte that is not a letter or digit makes it "xxx.xxx";
     * else every byte that is not a letter, digit, '.' or '-' is 'x', and so is a '.' or '-' followed by '.', '-'
     * or the end.
     */
    public static String fixHostName(String name) {
        byte[] b = cut(name, XpProto.MAX_HOST_LEN - 1);
        if (b.length == 0 || !alnum(b[0])) {
            return "xxx.xxx";
        }
        StringBuilder sb = new StringBuilder(b.length);
        for (int i = 0; i < b.length; i++) {
            byte c = b[i];
            if (alnum(c)) {
                sb.append((char) c);
            } else if ((c == '.' || c == '-') && i + 1 < b.length && b[i + 1] != '.' && b[i + 1] != '-') {
                sb.append((char) c);
            } else {
                sb.append('x');
            }
        }
        return sb.toString();
    }

    private static byte[] cut(String s, int max) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return b.length <= max ? b : java.util.Arrays.copyOf(b, max);
    }

    private static boolean graph(byte c) {
        return c >= '!' && c <= '~';
    }

    private static boolean alnum(byte c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
    }
}
