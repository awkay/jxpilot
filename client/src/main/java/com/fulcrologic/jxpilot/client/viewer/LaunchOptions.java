package com.fulcrologic.jxpilot.client.viewer;

/**
 * Parsed command line of {@code ClientMain}.
 * <pre>
 *   --view-map FILE        free-flying map viewer on FILE (Tab cycles maps in FILE's directory)
 *   --screenshot FILE.png  render, save a PNG of the framebuffer and exit
 *   --frames N             frames to render before the screenshot (default 3)
 *   --size WxH             window size in logical pixels (default 1280x800)
 *   --zoom Z               initial zoom (default 1)
 *   --filled               filled walls instead of XPilot's default outlines
 *   --at X,Y               initial camera centre in world pixels (default: first base, else map centre)
 *   --no-vsync             render as fast as possible (for measuring frame rate)
 * </pre>
 *
 * @param viewMap    map file, or null
 * @param screenshot PNG path, or null
 */
public record LaunchOptions(String viewMap, String screenshot, int frames, int width, int height,
                            double zoom, boolean filled, boolean hasCentre, double centreX, double centreY,
                            boolean vsync) {

    /**
     * Parse arguments.
     * <p>
     * BEHAVIOR: unknown flags, missing values and malformed numbers throw IllegalArgumentException
     * with a message naming the flag; frames, sizes and zoom must be positive.
     */
    public static LaunchOptions parse(String[] args) {
        String viewMap = null;
        String screenshot = null;
        int frames = 3;
        int width = 1280;
        int height = 800;
        double zoom = 1;
        boolean filled = false;
        boolean hasCentre = false;
        double cx = 0;
        double cy = 0;
        boolean vsync = true;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--view-map" -> viewMap = value(args, ++i, a);
                case "--screenshot" -> screenshot = value(args, ++i, a);
                case "--frames" -> frames = positiveInt(value(args, ++i, a), a);
                case "--size" -> {
                    String v = value(args, ++i, a);
                    int x = v.indexOf('x');
                    if (x < 0) {
                        throw new IllegalArgumentException(a + " expects WxH: " + v);
                    }
                    width = positiveInt(v.substring(0, x), a);
                    height = positiveInt(v.substring(x + 1), a);
                }
                case "--zoom" -> {
                    zoom = number(value(args, ++i, a), a);
                    if (!(zoom > 0)) {
                        throw new IllegalArgumentException(a + " must be positive: " + zoom);
                    }
                }
                case "--filled" -> filled = true;
                case "--no-vsync" -> vsync = false;
                case "--at" -> {
                    String v = value(args, ++i, a);
                    int c = v.indexOf(',');
                    if (c < 0) {
                        throw new IllegalArgumentException(a + " expects X,Y: " + v);
                    }
                    cx = number(v.substring(0, c), a);
                    cy = number(v.substring(c + 1), a);
                    hasCentre = true;
                }
                default -> throw new IllegalArgumentException("Unknown option: " + a);
            }
        }
        return new LaunchOptions(viewMap, screenshot, frames, width, height, zoom, filled, hasCentre, cx, cy, vsync);
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[i];
    }

    private static int positiveInt(String v, String flag) {
        int n;
        try {
            n = Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects an integer: " + v);
        }
        if (n <= 0) {
            throw new IllegalArgumentException(flag + " must be positive: " + n);
        }
        return n;
    }

    private static double number(String v, String flag) {
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects a number: " + v);
        }
    }
}
