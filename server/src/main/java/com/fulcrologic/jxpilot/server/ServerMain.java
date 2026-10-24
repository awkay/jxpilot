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
package com.fulcrologic.jxpilot.server;

import com.fulcrologic.jxpilot.server.lobby.LobbyServer;
import com.fulcrologic.jxpilot.server.net.Server;

import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.CompletionException;

/**
 * Headless server entry point:
 * {@code ServerMain [--map NAME|FILE] [--port N] [--bind sel ...] [--bindWait] [--defaults FILE|none] [config file]}.
 * A plain NAME is a bundled map ({@code --list-maps} lists them), a value with a path is a file; without
 * {@code --map} (or {@code mapFileName} in the config file) the server plays the bundled {@code default}.
 * Any XPilot server option may also be given as {@code -name value}. Runs until interrupted (Ctrl-C)
 * or until the server loop dies, in which case the process exits with status 2 instead of lingering.
 */
public final class ServerMain {
    /** Exit status for bad arguments, a missing map or a failed bind. */
    static final int EXIT_USAGE = 1;
    /** Exit status when the server loop died. */
    static final int EXIT_LOOP_FAILED = 2;

    private ServerMain() {}

    public static void main(String[] args) {
        int status = run(args, new Server.Settings(), System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * Start the server and wait for it to stop.
     * <p>
     * BEHAVIOR: bad arguments or a failed start print the error and usage and return 1.
     * BEHAVIOR: a loop that dies returns 2 after printing its cause; a normal stop (the shutdown hook,
     * on Ctrl-C) returns 0. The shutdown hook is removed again when the server stops on its own.
     */
    static int run(String[] args, Server.Settings settings, PrintStream err) {
        return run(args, settings, System.out, err);
    }

    /**
     * BEHAVIOR: an information request ({@link ServerInfo}: -help, -version, -dump...) prints to out and returns 1,
     * as C exits 1 when Parser returns false after one (server.c:139-140).
     */
    static int run(String[] args, Server.Settings settings, PrintStream out, PrintStream err) {
        String info = ServerInfo.request(args, ServerInfo.version());
        if (info != null) {
            out.print(info);
            out.flush();
            return EXIT_USAGE;
        }
        if (LobbyServer.requested(args)) {
            return runLobby(args, settings, err);
        }
        Server server;
        try {
            server = Server.start(args, settings);
        } catch (IllegalArgumentException | IOException e) {
            err.println("jxpilot server: " + e.getMessage());
            err.println(USAGE);
            return EXIT_USAGE;
        }
        return await(server.termination(), server::close, err, "server loop failed: ");
    }

    private static final String USAGE =
        "usage: ServerMain [--map NAME|FILE] [--port N] [--bind sel ...] [--bindWait] [--defaults FILE|none]"
            + " [config file]\n       ServerMain --lobby [--lobbyConfig FILE] [--lobbyMapsDir DIR] [--port N]"
            + " [--bind sel ...]\n       ServerMain --list-maps";

    /**
     * BEHAVIOR: {@code --lobby} serves the lobby ({@link LobbyServer}) until the process is stopped; bad arguments
     * or a failed start print the error and usage and return 1; a loop that dies returns 2.
     */
    private static int runLobby(String[] args, Server.Settings settings, PrintStream err) {
        LobbyServer lobby;
        try {
            lobby = LobbyServer.start(args, settings);
        } catch (IllegalArgumentException | IOException e) {
            err.println("jxpilot server: " + e.getMessage());
            err.println(USAGE);
            return EXIT_USAGE;
        }
        return await(lobby.termination(), lobby::close, err, "lobby loop failed: ");
    }

    /** An action that may be interrupted. */
    @FunctionalInterface
    private interface Stopper {
        void stop() throws InterruptedException;
    }

    /**
     * Wait for a server's loop to end; Ctrl-C (the shutdown hook) stops it cleanly.
     * <p>
     * BEHAVIOR: a normal end returns 0; a loop that died prints its cause and returns 2 after stopping the server.
     * The shutdown hook is removed again when the server stops on its own.
     */
    private static int await(java.util.concurrent.CompletableFuture<Void> termination, Stopper stopper,
                             PrintStream err, String failedText) {
        Thread hook = new Thread(() -> {
            try {
                stopper.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "jxpilot-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        int status;
        try {
            termination.join();
            status = 0;
        } catch (CompletionException e) {
            err.println("jxpilot server: " + failedText + e.getCause());
            status = EXIT_LOOP_FAILED;
            try {
                stopper.stop();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // the hook is running: it is what stopped the server
        }
        return status;
    }
}
