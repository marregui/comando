package dev.marregui.comando.frontear.store;

/**
 * One record read back from a {@link LinkLog}: its offset and its bytes. The array is the reader's own copy; a record
 * compares by the array's identity, as every record with an array component does.
 */
public record Record(long offset, byte[] bytes) {
}
