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
import org.pitest.mutationtest.config.PluginServices;
import org.pitest.mutationtest.config.ReportOptions;
import org.pitest.mutationtest.tooling.AnalysisResult;
import org.pitest.mutationtest.tooling.EntryPoint;
import org.pitest.util.Glob;
import org.pitest.util.Verbosity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

/**
 * PIT on the link emulator, which lives in common's test classes (shared through the test-jar), where
 * pitest-maven cannot mutate: it only mutates the main output directory. Run by the pit-link profile
 * ({@code MAVEN_OPTS= mvn -q -o -pl common test -Ppit-link}) as a single-file program on the test classpath.
 * <p>
 * Arguments: report directory, test-classes directory, source directory, minimum mutation score (percent).
 * Exits 1 below the threshold or on an error.
 */
public class PitLink {
    static final String PKG = "com.fulcrologic.jxpilot.common.net.link.";

    public static void main(String[] a) {
        ReportOptions o = new ReportOptions();
        o.setReportDir(a[0]);
        o.setCodePaths(List.of(a[1]));
        o.setTargetClasses(List.of(PKG + "LinkRandom", PKG + "LinkProfile", PKG + "LinkDirection",
            PKG + "LinkEmulator", PKG + "VirtualClock"));
        o.setTargetTests(Glob.toGlobPredicates(List.of(PKG + "Link*Test*")));
        o.setSourceDirs(List.of(Path.of(a[2])));
        o.setClassPathElements(List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
        o.setMutators(List.of("DEFAULTS"));
        o.addOutputFormats(List.of("HTML", "XML"));
        o.setNumberOfThreads(4);
        o.setTimeoutConstant(4000);
        o.setTimeoutFactor(1.25f);
        o.setVerbosity(Verbosity.QUIET);
        o.setShouldCreateTimestampedReports(false);
        o.setFailWhenNoMutations(true);
        o.setIncludeLaunchClasspath(true);
        o.setProjectBase(Path.of(".").toAbsolutePath());
        o.setExportLineCoverage(false);
        o.setMutationUnitSize(0);
        o.setFeatures(List.of());
        o.setArgLine("");
        o.setGroupConfig(org.pitest.testapi.TestGroupConfig.emptyConfig());
        o.setExcludedRunners(List.of());
        o.setIncludedTestMethods(List.of());
        o.setExcludedMethods(List.of());
        o.setExcludedClasses(List.of());
        o.setExcludedTestClasses(List.of());
        o.setInputEncoding(StandardCharsets.UTF_8);
        o.setOutputEncoding(StandardCharsets.UTF_8);
        AnalysisResult r = new EntryPoint().execute(new File("."), o, PluginServices.makeForContextLoader(),
            new HashMap<>());
        if (r.getError().isPresent()) {
            r.getError().get().printStackTrace();
            System.exit(1);
        }
        var st = r.getStatistics().get().getMutationStatistics();
        int score = st.getPercentageDetected();
        System.out.println("pit-link: " + st.getTotalMutations() + " mutations, " + st.getTotalDetectedMutations()
            + " killed, " + st.getTotalSurvivingMutations() + " survived, score " + score + "% (report " + a[0] + ")");
        if (score < Integer.parseInt(a[3])) {
            System.out.println("pit-link: below the " + a[3] + "% threshold");
            System.exit(1);
        }
    }
}
