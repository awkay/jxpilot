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
package com.fulcrologic.jxpilot.client.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TextureFilesTest {
    static final String ONE_BLUE = "/* XPM */\n\"1 1 1 1\",\n\"x c #0000ff\",\n\"x\"\n";

    @Test
    void builtinImagesAreXpilotsRockAndBall() {
        XpmImage rock = TextureFiles.builtin(TextureFiles.ROCK);
        XpmImage ball = TextureFiles.builtin(TextureFiles.BALL);
        assertAll(
            () -> assertEquals(256, rock.width, "rock4.xpm is 256 wide"),
            () -> assertEquals(256, rock.height, "and 256 high"),
            () -> assertEquals(20, ball.width, "ball.xpm is 20x20"),
            () -> assertEquals(Palette.pack(0, 0, 0, 255), ball.pixels[0], "its corner is black"),
            () -> assertThrows(IllegalStateException.class, () -> TextureFiles.builtin("/textures/missing.xpm"),
                "a missing resource is a build error"));
    }

    @Test
    void aFileIsFoundAsGivenOrOnTheTexturePath(@TempDir Path dir) throws Exception {
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        Files.writeString(b.resolve("t.xpm"), ONE_BLUE);
        String path = a + "::" + b;
        assertAll(
            () -> assertEquals(b.resolve("t.xpm"), TextureFiles.find(b.resolve("t.xpm").toString(), null), "plain path"),
            () -> assertEquals(b.resolve("t.xpm"), TextureFiles.find("t.xpm", path), "second path directory, empty one skipped"),
            () -> assertNull(TextureFiles.find("t.xpm", a.toString()), "not in the path"),
            () -> assertNull(TextureFiles.find("t.xpm", null), "no path"),
            () -> assertNull(TextureFiles.find("/no/such/t.xpm", path), "an absolute name is not searched"),
            () -> assertNull(TextureFiles.find(b.toString(), null), "a directory is not a texture"));
    }

    @Test
    void loadFallsBackToTheBuiltinImageAndReports(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("good.xpm"), ONE_BLUE);
        Files.writeString(dir.resolve("bad.xpm"), "garbage");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream log = new PrintStream(out, true);
        XpmImage good = TextureFiles.load(" good.xpm ", dir.toString(), TextureFiles.BALL, log);
        String afterGood = out.toString();
        XpmImage bad = TextureFiles.load("bad.xpm", dir.toString(), TextureFiles.BALL, log);
        XpmImage missing = TextureFiles.load("nope.xpm", dir.toString(), TextureFiles.BALL, log);
        XpmImage none = TextureFiles.load("", dir.toString(), TextureFiles.BALL, log);
        XpmImage nul = TextureFiles.load(null, null, TextureFiles.BALL, log);
        String text = out.toString();
        assertAll(
            () -> assertEquals(1, good.width, "a good file is loaded (name trimmed)"),
            () -> assertEquals("", afterGood, "without a message"),
            () -> assertEquals(20, bad.width, "a bad file gives the built-in ball"),
            () -> assertTrue(text.contains("Can't load texture \"" + dir.resolve("bad.xpm")), "and is reported"),
            () -> assertEquals(20, missing.width, "a missing file gives the built-in ball"),
            () -> assertTrue(text.contains("Can't find texture \"nope.xpm\""), "reported as C does"),
            () -> assertEquals(20, none.width, "an empty name is the built-in image"),
            () -> assertEquals(20, nul.width, "so is null"),
            () -> assertEquals(2, text.lines().count(), "only the bad and the missing file are reported"));
    }
}
