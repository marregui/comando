package dev.marregui.comando.frontear.protocol;

/**
 * The names frontear reserves on its gateway, and the content type of the JSON it publishes there: one place, so the
 * gateway that guards the prefix and the components that publish under it cannot drift apart.
 */
public final class Names {

    /** The prefix under which only frontear itself publishes and subscribes. */
    public static final String RESERVED = "$frontear/";
    /** A client's presence, {@code {"online":true}} retained while it is connected: {@code PRESENCE + clientId}. */
    public static final String PRESENCE = RESERVED + "presence/";
    /** The state frontear publishes about its own components. */
    public static final String STATE = RESERVED + "state/";
    /** The commands frontear accepts. */
    public static final String CONTROL = RESERVED + "control/";
    /** The Content Type (§3.3.2.3.9) of every JSON payload frontear publishes. */
    public static final String JSON = "application/json";
    /**
     * The User Property (§3.3.2.3.7) that names the machine a message came from, {@code @<machine>}: the gateway sets
     * it on every PUBLISH and will of a remote connection, in place of any the client sent; a message from this
     * machine carries none the gateway set.
     */
    public static final String ORIGIN = "frontear-origin";

    private Names() {
    }
}
