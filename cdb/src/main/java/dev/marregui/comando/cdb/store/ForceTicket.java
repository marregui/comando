package dev.marregui.comando.cdb.store;

/**
 * The durability of one answered commit: done once the log force covering its record has held. Under the force per
 * commit every ticket is done when the commit is answered.
 */
public interface ForceTicket {

    /** A ticket that is already done. */
    ForceTicket DONE = _ -> true;

    /**
     * Waits at most {@code timeoutNanos} for the force covering the commit; true once it held. A force that failed
     * throws the store's {@link dev.marregui.comando.cdb.CdbException} ("commit in doubt"): the commit may be lost.
     */
    boolean await(long timeoutNanos) throws InterruptedException;
}
