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
package com.fulcrologic.jxpilot.dist;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The release jars are self-contained: run from a directory that holds nothing but the jar, they find the bundled
 * maps by name. Runs only in the release build ({@code -Prelease verify}), after {@code package} has built the jars
 * (dist/pom.xml passes their paths as {@code jxpilot.serverJar} and {@code jxpilot.clientJar}).
 */
@DisplayName("Release jars")
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class ReleaseJarTest {
    static Path serverJar;
    static Path clientJar;

    @BeforeAll
    static void jars() {
        serverJar = Path.of(System.getProperty("jxpilot.serverJar", "missing-server.jar")).toAbsolutePath();
        clientJar = Path.of(System.getProperty("jxpilot.clientJar", "missing-client.jar")).toAbsolutePath();
        assertTrue(Files.isRegularFile(serverJar), "the server jar was built: " + serverJar);
        assertTrue(Files.isRegularFile(clientJar), "the client jar was built: " + clientJar);
    }

    /** A copy of {@code jar} alone in {@code dir}. */
    private static Path alone(Path jar, Path dir) throws IOException {
        return Files.copy(jar, dir.resolve(jar.getFileName()));
    }

    private static ProcessBuilder java(Path dir, Path jar, String... args) {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", jar.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("JXPILOT_NO_RELAUNCH", "1");
        return pb;
    }

    /** Run to completion; the exit status and the output. */
    private record Run(int status, String out) {}

    private static Run run(ProcessBuilder pb) throws IOException, InterruptedException {
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.waitFor(), out);
    }

    @Test
    @DisplayName("the server jar alone in a directory serves the bundled map 'default'")
    void serverStartsOnBundledDefault(@TempDir Path dir) throws Exception {
        Path jar = alone(serverJar, dir);
        Process p = java(dir, jar, "--map", "default", "--port", "0", "--bind", "loopback").start();
        List<String> lines = new ArrayList<>();
        String serving = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                lines.add(line);
                if (line.startsWith("serving map ")) {
                    serving = line;
                    break;
                }
            }
        } finally {
            p.destroy(); // our own child: its shutdown hook closes the server
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        }
        String s = serving;
        assertAll(
            () -> assertEquals(List.of(jar), listDir(dir), "the directory holds only the jar: no maps/"),
            () -> assertTrue(s != null && s.startsWith("serving map 'The Newbie Globe' (170x170 blocks)"),
                "serves default.xp from inside the jar: " + lines));
    }

    @Test
    @DisplayName("both jars list the same bundled maps with --list-maps")
    void listMaps(@TempDir Path dir) throws Exception {
        Run server = run(java(dir, alone(serverJar, dir), "--list-maps"));
        Run client = run(java(dir, alone(clientJar, dir), "--list-maps"));
        assertAll(
            () -> assertEquals(0, server.status(), "the server exits 0: " + server.out()),
            () -> assertEquals(0, client.status(), "the client exits 0: " + client.out()),
            () -> assertTrue(server.out().startsWith("Bundled maps"), "the server prints the listing: " + server.out()),
            () -> assertEquals(19 + 2, server.out().lines().count(), "a heading, a header and 19 maps"),
            () -> assertTrue(server.out().lines().anyMatch(l -> l.startsWith("tourmination ")), "tourmination is listed"),
            () -> assertEquals(server.out(), client.out(), "the client prints the same listing"));
    }

    @Test
    @DisplayName("an unknown map name fails the server jar with the bundled names")
    void unknownMap(@TempDir Path dir) throws Exception {
        Run r = run(java(dir, alone(serverJar, dir), "--map", "no-such-map", "--port", "0", "--bind", "loopback"));
        assertAll(
            () -> assertEquals(1, r.status(), "exit 1 (usage)"),
            () -> assertTrue(r.out().contains("Bundled maps: blood-music2, CAMD, "), "lists the bundled maps: " + r.out()),
            () -> assertFalse(r.out().contains("serving map"), "serves nothing"));
    }

    private static List<Path> listDir(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.toList();
        }
    }
}
