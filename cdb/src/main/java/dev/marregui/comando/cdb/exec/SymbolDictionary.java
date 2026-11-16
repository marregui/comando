package dev.marregui.comando.cdb.exec;

/**
 * The values of one SYMBOL column by key, as a {@link Vector} that carries keys resolves them. Keys of one dictionary
 * are dense from 0 and never reassigned, so two values with keys from the same dictionary object are equal exactly when
 * their keys are.
 */
public interface SymbolDictionary {

    /** The value of {@code key}, a key this dictionary gave. */
    String valueOf(int key);
}
