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
package com.fulcrologic.jxpilot.common.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-JVM determinism of the kernel (docs/latency-fixes.md §3): {@code + - * /}, {@code sqrt} and casts are
 * bit-identical on every JVM (JEP 306), {@code StrictMath} too, but {@code java.lang.Math}'s transcendentals may be
 * intrinsics that differ by an ulp between JVMs and CPUs. No class of the kernel package may reference them.
 */
class KernelPurityTest {
    /** The {@code java.lang.Math} methods whose results may differ between JVMs. */
    static final Set<String> FORBIDDEN = Set.of("sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sinh", "cosh",
        "tanh", "hypot", "pow", "exp", "expm1", "log", "log10", "log1p", "cbrt", "random");

    /** The forbidden Math calls a class file makes, as "Class.method". */
    static List<String> forbiddenCalls(byte[] classFile) {
        ClassModel cm = ClassFile.of().parse(classFile);
        List<String> out = new ArrayList<>();
        for (PoolEntry e : cm.constantPool()) {
            if (e instanceof MemberRefEntry ref && ref.owner().asInternalName().equals("java/lang/Math")
                && FORBIDDEN.contains(ref.name().stringValue())) {
                out.add(cm.thisClass().asInternalName() + " -> Math." + ref.name().stringValue());
            }
        }
        return out;
    }

    static byte[] bytes(Class<?> c) throws IOException {
        try (InputStream in = c.getResourceAsStream(c.getName().substring(c.getName().lastIndexOf('.') + 1) + ".class")) {
            return in.readAllBytes();
        }
    }

    /** Uses a forbidden transcendental: the scanner must see it. */
    static final class Impure {
        static double f(double x) {
            return Math.sin(x) + Math.hypot(x, x) + StrictMath.cos(x) + Math.sqrt(x) + Math.abs(x);
        }
    }

    @Test
    @DisplayName("no class of the kernel package references a java.lang.Math transcendental (the scanner sees one)")
    void noTranscendentals() throws IOException, URISyntaxException {
        Path dir = Path.of(ShipKernel.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve("com/fulcrologic/jxpilot/common/sim");
        List<String> bad = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                bad.addAll(forbiddenCalls(Files.readAllBytes(f)));
                scanned++;
            }
        }
        int n = scanned;
        List<String> control = forbiddenCalls(bytes(Impure.class));
        assertAll(
            () -> assertEquals(List.of(), bad, "the kernel package calls no Math transcendental"),
            () -> assertTrue(n >= 15, "every kernel class was scanned (" + n + ")"),
            () -> assertEquals(Set.of("com/fulcrologic/jxpilot/common/sim/KernelPurityTest$Impure -> Math.sin",
                    "com/fulcrologic/jxpilot/common/sim/KernelPurityTest$Impure -> Math.hypot"), Set.copyOf(control),
                "the scanner finds Math.sin and Math.hypot, and not StrictMath, sqrt or abs"));
    }
}
