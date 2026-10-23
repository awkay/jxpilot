package com.fulcrologic.jxpilot.client;

import com.fulcrologic.jxpilot.client.gl.MapViewerApp;
import com.fulcrologic.jxpilot.client.viewer.LaunchOptions;

/**
 * Client entry point. For now only the map viewer exists:
 * {@code ClientMain --view-map maps/default.xp [--screenshot out.png --frames N] [--filled] ...}
 * (see {@link LaunchOptions}). On macOS the JVM needs {@code -XstartOnFirstThread}.
 */
public final class ClientMain {
    private ClientMain() {}

    public static void main(String[] args) throws Exception {
        LaunchOptions opts;
        try {
            opts = LaunchOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            usage();
            System.exit(2);
            return;
        }
        if (opts.viewMap() == null) {
            usage();
            System.exit(2);
            return;
        }
        new MapViewerApp(opts).run();
    }

    private static void usage() {
        System.err.println("usage: ClientMain --view-map FILE [--screenshot FILE.png] [--frames N] [--size WxH]"
            + " [--zoom Z] [--at X,Y] [--filled] [--no-vsync]");
    }
}
