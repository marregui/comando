package dev.marregui.comando.cdb.exec;

/**
 * The values of one SYMBOL column by key, as a {@link Vector} that carries keys resolves them. Keys of one dictionary
 * are dense from 0 and never reassigned, so two values with keys from the same dictionary object are equal exactly when
 * their keys are.
 */
public interface SymbolDictionary {

    /** The value of {@code key}, a key this dictionary gave. */
    String valueOf(int key);

    /**
     * The number of keys given so far, so every key given is below it; it only grows, and a value read before a call
     * has a key below the call's answer.
     */
    int size();

    /** The key of {@code value} without adding it: -1 when the dictionary does not hold it, or for null. */
    int keyOf(String value);
}
