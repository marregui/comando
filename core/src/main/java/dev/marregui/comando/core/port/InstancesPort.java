package dev.marregui.comando.core.port;

/**
 * What the user does from the Instances tab to the other comando instances (record 124). The tab reads them from
 * {@code AppState.instances()}; these act, off the caller's thread, and the outcome comes back as a notice and in
 * that state.
 */
public interface InstancesPort {

    /** Asks {@code instance} (its id or name) {@code text}, as the user, from no session; the reply lands in the tab. */
    void ask(String instance, String text);

    /** Refuses, or stops refusing, the questions of {@code instance} (its id): the data root's {@code agents.json}. */
    void deny(String instance, boolean denied);

    /** Forgets {@code instance} (its id), an instance seen before and no longer present: the tab lists it no more. */
    void forget(String instance);

    /** Sets what this instance is for: the data root's {@code instance.json} {@code role}, sent in its presence. */
    void role(String role);

    /** For a window without instances (the replay tests): asks nothing, refuses nothing. */
    InstancesPort NONE = new InstancesPort() {
        @Override
        public void ask(final String instance, final String text) {
        }

        @Override
        public void deny(final String instance, final boolean denied) {
        }

        @Override
        public void forget(final String instance) {
        }

        @Override
        public void role(final String role) {
        }
    };
}
