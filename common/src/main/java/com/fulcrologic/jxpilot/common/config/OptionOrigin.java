package com.fulcrologic.jxpilot.common.config;

/**
 * Where an option value came from ({@code optOrigin} in C). The {@link #bit} values
 * match the C permission flags so they can be tested against {@link Opt#flags()}.
 */
public enum OptionOrigin {
    /** Built-in default. */
    INIT(0),
    /** Map file. */
    MAP(1),
    /** Server defaults/config file. */
    DEFAULTS(2),
    /** Command line. */
    COMMAND(4),
    /** Password file. */
    PASSWORD(8);

    /** Permission bit. */
    public final int bit;

    OptionOrigin(int bit) {
        this.bit = bit;
    }
}
