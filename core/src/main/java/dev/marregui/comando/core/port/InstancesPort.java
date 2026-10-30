package dev.marregui.comando.core.port;

/**
 * What the user does from the Instances tab to the other comando instances (record 124). The tab reads them from
 * {@code AppState.instances()}; these two act, off the caller's thread, and the outcome comes back as a notice and in
 * that state.
 */
public interface InstancesPort {

    /** Asks {@code instance} (its id or name) {@code text}, as the user, from no session; the reply lands in the tab. */
    void ask(String instance, String text);

    /** Refuses, or stops refusing, the questions of {@code instance} (its id): the data root's {@code agents.json}. */
    void deny(String instance, boolean denied);

    /** For a window without instances (the replay tests): asks nothing, refuses nothing. */
    InstancesPort NONE = new InstancesPort() {
        @Override
        public void ask(String instance, String text) {
        }

        @Override
        public void deny(String instance, boolean denied) {
        }
    };
}
