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
package com.fulcrologic.jxpilot.client.game.options;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes resources into a {@code ~/.xpilotrc} text the way the C client's "Save Configuration" does
 * ({@code configure.c} {@code Config_save}: each as {@code xpilot.NAME:\t\tVALUE}), but without dropping the
 * user's other lines: C keeps only the {@code xpilot.} / {@code XPilot.} resource lines of the old file;
 * this keeps every line, replaces each saved resource's line in place and appends the new ones at the end.
 */
public final class RcWriter {
    private RcWriter() {}

    /** One resource to save. */
    public record Resource(String name, String value) {}

    /** C's line: {@code xpilot.NAME:\t\tVALUE}. */
    public static String line(Resource r) {
        return "xpilot." + r.name() + ":\t\t" + r.value();
    }

    /**
     * The new file text.
     * <p>
     * BEHAVIOR: lines are kept as they are (comments, blank lines, other applications' and other resources'
     * lines); the first line of a saved resource (any prefix the reader accepts: none, {@code xpilot.},
     * {@code xpilot*}, {@code XPilot.}, {@code *}; the name ignoring case) is replaced by C's line, later lines
     * for it are removed, and a backslash-continued line is replaced or removed with its continuation lines;
     * resources not in the old text are appended in order; the result ends with a newline.
     */
    public static String update(String old, List<Resource> resources) {
        Map<String, Resource> byName = new HashMap<>();
        for (Resource r : resources) {
            byName.put(r.name().toLowerCase(Locale.ROOT), r);
        }
        Set<String> written = new HashSet<>();
        StringBuilder out = new StringBuilder(old.length() + 64 * resources.size());
        String[] lines = old.isEmpty() ? new String[0] : old.split("\r?\n", -1);
        int n = lines.length;
        if (n > 0 && lines[n - 1].isEmpty()) {
            n--;
        }
        for (int i = 0; i < n; i++) {
            int first = i;
            while (lines[i].endsWith("\\") && i + 1 < n) {
                i++;
            }
            String name = resourceName(lines[first]);
            Resource r = name == null ? null : byName.get(name);
            if (r == null) {
                for (int k = first; k <= i; k++) {
                    out.append(lines[k]).append('\n');
                }
                continue;
            }
            if (written.add(name)) {
                out.append(line(r)).append('\n');
            }
        }
        for (Resource r : resources) {
            if (written.add(r.name().toLowerCase(Locale.ROOT))) {
                out.append(line(r)).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * BEHAVIOR: the lower-case resource name of an xpilot resource line, or null for comments ('!' first),
     * directives ('#'), lines without a colon and other applications' resources.
     */
    static String resourceName(String line) {
        String t = line.stripLeading();
        if (t.isEmpty() || t.charAt(0) == '!' || t.charAt(0) == '#') {
            return null;
        }
        int colon = t.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String raw = t.substring(0, colon).trim();
        int cut = Math.max(raw.lastIndexOf('.'), raw.lastIndexOf('*'));
        if (cut >= 0) {
            String app = raw.substring(0, cut).replace("*", "").replace(".", "").toLowerCase(Locale.ROOT);
            if (!app.isEmpty() && !app.equals("xpilot")) {
                return null;
            }
            raw = raw.substring(cut + 1).trim();
        }
        return raw.isEmpty() ? null : raw.toLowerCase(Locale.ROOT);
    }

    /**
     * Save into {@code file} as C does: the new text goes to {@code FILE.new}, the old file (when there is one)
     * becomes {@code FILE.bak}, and {@code FILE.new} becomes {@code FILE}.
     * <p>
     * BEHAVIOR: a missing file is created; the text is ISO-8859-1 (C's resource files are bytes).
     */
    public static void save(Path file, List<Resource> resources) throws IOException {
        String old = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.ISO_8859_1) : "";
        Path tmp = file.resolveSibling(file.getFileName() + ".new");
        Files.writeString(tmp, update(old, resources), StandardCharsets.ISO_8859_1);
        if (Files.exists(file)) {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
