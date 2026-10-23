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
package com.fulcrologic.jxpilot.client;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in (needs a display and a GPU; run with {@code -Djxpilot.glTest=true}): runs the real map
 * viewer in a child JVM for a self-terminating screenshot run and asserts that the render thread
 * allocated nothing after a warm-up, and that the PNG was written.
 * <p>
 * The warm-up is {@value #WARMUP} frames: the JIT keeps deoptimising and recompiling the frame
 * methods for the first few thousand frames, and an install can allocate a little inside the JVM.
 * The child's output goes to a file (no pipe to fill up), and the child is the only process this test
 * can stop, and only on its own timeout.
 */
@EnabledIfSystemProperty(named = "jxpilot.glTest", matches = "true")
class GlSmokeTest {
    static final int WARMUP = 10_000;
    static final int FRAMES = WARMUP + 4_000;
    static final Pattern ALLOC = Pattern.compile("render thread allocated (\\d+) bytes over frames (\\d+)\\.\\.");
    static final Pattern COPIES = Pattern.compile("(\\d+) world copies");

    @ParameterizedTest(name = "{0} zoom {1}")
    @CsvSource({"planetx.xp, 1, 1", "newdarkhell.xp, 0.1, 4"})
    void realRenderLoopAllocatesNothingAfterWarmUp(String map, String zoom, int minCopies)
        throws IOException, InterruptedException {
        Path dir = Path.of("target", "screens").toAbsolutePath();
        Files.createDirectories(dir);
        String base = "smoke-" + map.replace(".xp", "");
        Path png = dir.resolve(base + ".png");
        Path log = dir.resolve(base + ".log");
        Files.deleteIfExists(png);
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            cmd.add("-XstartOnFirstThread");
        }
        cmd.addAll(List.of("--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC", "-cp", System.getProperty("java.class.path"),
            "com.fulcrologic.jxpilot.client.ClientMain", "--view-map", Fixtures.MAPS.resolve(map).toString(),
            "--zoom", zoom, "--no-vsync", "--frames", Integer.toString(FRAMES), "--warmup", Integer.toString(WARMUP),
            "--screenshot", png.toString()));
        Process child = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done;
        try {
            done = child.waitFor(180, TimeUnit.SECONDS);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
        String out = Files.readString(log, StandardCharsets.UTF_8);
        Matcher m = ALLOC.matcher(out);
        boolean found = m.find();
        Matcher c = COPIES.matcher(out);
        boolean copies = c.find();
        assertAll(map,
            () -> assertTrue(done, "the viewer exits by itself after its frames"),
            () -> assertEquals(0, child.exitValue(), "cleanly:\n" + out),
            () -> assertTrue(Files.exists(png) && Files.size(png) > 0, "the screenshot was written"),
            () -> assertTrue(found, "the run reports its allocation:\n" + out),
            () -> assertEquals(Integer.toString(WARMUP), found ? m.group(2) : "?", "measured from the end of the warm-up"),
            () -> assertEquals("0", found ? m.group(1) : "?", "the render thread allocated 0 bytes after the warm-up"),
            () -> assertTrue(copies && Integer.parseInt(c.group(1)) >= minCopies,
                "the view spans at least " + minCopies + " world copies:\n" + out));
    }
}
