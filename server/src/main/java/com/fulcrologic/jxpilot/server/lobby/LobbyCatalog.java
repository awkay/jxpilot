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
package com.fulcrologic.jxpilot.server.lobby;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import com.fulcrologic.jxpilot.common.map.BundledMaps;
import com.fulcrologic.jxpilot.common.map.MapSource;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.LobbyMsg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The maps a lobby offers: the bundled maps and the {@code .xp}/{@code .map} files of some directories, minus the
 * excluded names. Built once at startup; a map's number (its {@link LobbyEntry#id()}) is its place in the
 * alphabetical list and stays valid while the server runs.
 */
public final class LobbyCatalog {
    /** A map on offer and where to load it from. {@code info} is the entry of the idle map. */
    public record Offer(int id, String name, MapSource source, LobbyEntry info) { }

    private final List<Offer> offers;

    private LobbyCatalog(List<Offer> offers) {
        this.offers = List.copyOf(offers);
    }

    /**
     * Build the catalog.
     * <p>
     * BEHAVIOR: a map is known by its file name without {@code .xp}/{@code .map}; names are unique ignoring case.
     * BEHAVIOR: {@code bundled} (null for none) comes first, then each directory in order, so a file of the same
     * name replaces the bundled map or a file of an earlier directory. In one directory {@code x.xp} wins over
     * {@code x.map}. Only regular files with those extensions (any case) count; subdirectories are not searched.
     * BEHAVIOR: names in {@code exclude} (with or without the extension, any case) are left out.
     * BEHAVIOR: a map that does not parse into a playable map (no map data, no bases), and a directory that
     * cannot be read, are reported to {@code warn} and skipped.
     * BEHAVIOR: ids are assigned in name order (ignoring case) from 0; at most {@link LobbyMsg#MAX_MAPS} maps
     * are kept (the rest are reported).
     * BEHAVIOR: each offer's info has the map's title and author, its size in blocks, one player per base, and
     * is idle; the name, title and author are cut to {@link #MAX_TEXT} characters (the offer's own name is whole).
     */
    public static LobbyCatalog build(BundledMaps bundled, List<Path> dirs, Collection<String> exclude,
                                     Consumer<String> warn) {
        Map<String, MapSource> byName = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        if (bundled != null) {
            for (String file : bundled.files()) {
                String name = baseName(file);
                byName.put(key(name), MapSource.bundled(file));
                display.put(key(name), name);
            }
        }
        for (Path dir : dirs) {
            Map<String, Path> found = new LinkedHashMap<>();
            try (Stream<Path> files = Files.list(dir)) {
                List<Path> sorted = files.filter(Files::isRegularFile).filter(p -> hasMapExtension(p.getFileName().toString()))
                    .sorted((a, b) -> extensionRank(a).compareTo(extensionRank(b))).toList();
                for (Path p : sorted) {
                    found.putIfAbsent(key(baseName(p.getFileName().toString())), p);
                }
            } catch (IOException e) {
                warn.accept("cannot read the maps directory " + dir + ": " + e);
                continue;
            }
            for (Map.Entry<String, Path> f : found.entrySet()) {
                byName.put(f.getKey(), MapSource.of(f.getValue()));
                display.put(f.getKey(), baseName(f.getValue().getFileName().toString()));
            }
        }
        for (String x : exclude) {
            byName.remove(key(baseName(x.strip())));
        }
        List<String> keys = new ArrayList<>(byName.keySet());
        keys.sort(String::compareTo);
        List<Offer> offers = new ArrayList<>();
        for (String k : keys) {
            if (offers.size() >= LobbyMsg.MAX_MAPS) {
                warn.accept("more than " + LobbyMsg.MAX_MAPS + " maps: " + display.get(k) + " and the rest are not offered");
                break;
            }
            MapSource src = byName.get(k);
            try {
                offers.add(new Offer(offers.size(), display.get(k), src, describe(offers.size(), display.get(k), src)));
            } catch (IOException | RuntimeException e) {
                warn.accept("map " + display.get(k) + " (" + src.describe() + ") is not offered: " + e.getMessage());
            }
        }
        return new LobbyCatalog(renumber(offers));
    }

    /** Ids must be the list positions even when a map was skipped after taking its number. */
    private static List<Offer> renumber(List<Offer> in) {
        List<Offer> out = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            Offer o = in.get(i);
            LobbyEntry e = o.info();
            out.add(new Offer(i, o.name(), o.source(), new LobbyEntry(i, e.name(), e.title(), e.author(), e.width(),
                e.height(), e.maxPlayers(), false, 0)));
        }
        return out;
    }

    private static LobbyEntry describe(int id, String name, MapSource src) throws IOException {
        Options o = new Options();
        src.parseInto(o);
        WorldMap m = WorldMap.fromOptions(o);
        if (m.bases().isEmpty()) {
            throw new IllegalArgumentException("it has no bases, so nobody could play it");
        }
        return new LobbyEntry(id, cut(name), cut(o.getString(Opt.MAP_NAME)), cut(o.getString(Opt.MAP_AUTHOR)), m.width(),
            m.height(), m.bases().size(), false, 0);
    }

    /** Longest name, title or author a list entry carries (the lists of a thousand maps stay small). */
    static final int MAX_TEXT = 60;

    private static String cut(String s) {
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    /** The offers in id order. */
    public List<Offer> offers() {
        return offers;
    }

    /** The offer with this id, or null. */
    public Offer offer(int id) {
        return id >= 0 && id < offers.size() ? offers.get(id) : null;
    }

    /** Every offer as an idle list entry, in id order. */
    public List<LobbyEntry> entries() {
        List<LobbyEntry> out = new ArrayList<>(offers.size());
        for (Offer o : offers) {
            out.add(o.info());
        }
        return out;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean hasMapExtension(String file) {
        String f = file.toLowerCase(Locale.ROOT);
        return f.endsWith(".xp") || f.endsWith(".map");
    }

    private static Integer extensionRank(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xp") ? 0 : 1;
    }

    /** The file name without a trailing {@code .xp} or {@code .map} (any case). */
    static String baseName(String file) {
        String f = file.toLowerCase(Locale.ROOT);
        if (f.endsWith(".xp")) {
            return file.substring(0, file.length() - 3);
        }
        if (f.endsWith(".map")) {
            return file.substring(0, file.length() - 4);
        }
        return file;
    }
}
