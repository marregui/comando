package dev.marregui.comando.core.port;

/** Wall-clock time, behind a port so tests control it. */
@FunctionalInterface
public interface Clock {

    Clock SYSTEM = System::currentTimeMillis;

    long millis();
}
