package dev.marregui.comando.app;

import dev.marregui.comando.core.loop.Processes;
import dev.marregui.comando.ui.kit.desktop.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Where connection passwords live. On macOS that is the login keychain, through the {@code security}
 * tool, under service {@code comando} and the connection's name; elsewhere, nowhere, and the store keeps
 * the password in its file as before.
 */
interface Secrets {

    /** False when there is no secret store; the password then stays in the connections file. */
    boolean available();

    /**
     * The stored password, or empty when the store holds none for {@code name}.
     *
     * @throws Unreadable when the store could not be asked (locked, no user to unlock it, a timeout), so an
     *                    empty answer never stands for a password that may still be there
     */
    Optional<String> read(String name);

    /** The secret store did not answer; whether it holds a password is unknown. */
    final class Unreadable extends RuntimeException {
        Unreadable(String message) {
            super(message);
        }
    }

    /** Stores the password; false when the store refused it. */
    boolean write(String name, String password);

    void delete(String name);

    Secrets NONE = new Secrets() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Optional<String> read(String name) {
            return Optional.empty();
        }

        @Override
        public boolean write(String name, String password) {
            return false;
        }

        @Override
        public void delete(String name) {
        }
    };

    static Secrets forPlatform() {
        Path tool = Path.of("/usr/bin/security");
        return Platform.isMac() && Files.isExecutable(tool) ? new Keychain(tool, "comando") : NONE;
    }

    /**
     * The macOS login keychain. The password is passed to {@code security} as an argument, so it is visible
     * to the user's own processes for the few milliseconds the tool runs; it never reaches a file.
     */
    record Keychain(Path tool, String service) implements Secrets {

        private static final System.Logger LOG = System.getLogger(Keychain.class.getName());
        /** {@code security}'s exit code for errSecItemNotFound. */
        static final int ITEM_NOT_FOUND = 44;
        static final long TIMEOUT_SECONDS = 10;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Optional<String> read(String name) {
            // -g, not -w: -w prints a password with non-ASCII bytes as bare hex, which cannot be told apart
            // from a password made of hex digits; -g marks the hex form with 0x
            Result r = run(List.of(tool.toString(), "find-generic-password", "-s", service, "-a", name, "-g"));
            return switch (r.exit()) {
                case 0 -> Optional.of(password(r.out()));
                case ITEM_NOT_FOUND -> Optional.empty();
                default -> throw new Unreadable("the keychain did not answer for " + name + " [exit=" + r.exit() + ']');
            };
        }

        @Override
        public boolean write(String name, String password) {
            Result r = run(List.of(tool.toString(), "add-generic-password", "-U", "-s", service, "-a", name, "-w", password));
            if (r.exit() != 0) {
                LOG.log(System.Logger.Level.WARNING, "the keychain refused the password of " + name + " [exit=" + r.exit() + ']');
            }
            return r.exit() == 0;
        }

        @Override
        public void delete(String name) {
            run(List.of(tool.toString(), "delete-generic-password", "-s", service, "-a", name));
        }

        private record Result(int exit, String out) {
        }

        /**
         * Runs the tool for at most {@link #TIMEOUT_SECONDS}. Its output is read on a thread of its own, so an
         * unlock prompt nobody answers ends in a timeout instead of blocking the caller (the store's thread, or
         * startup) for as long as the prompt stays open.
         */
        private static Result run(List<String> cmd) {
            try {
                Processes.Result r = Processes.run(cmd, Duration.ofSeconds(TIMEOUT_SECONDS), MAX_OUT);
                if (r.timedOut()) {
                    LOG.log(System.Logger.Level.WARNING, "the keychain did not answer within " + TIMEOUT_SECONDS + " s [command=" + cmd.get(1) + ']');
                    return new Result(-1, "");
                }
                // -g writes the password line to standard error; the other commands write nothing needed there
                return new Result(r.exitCode(), r.out() + r.stderr());
            } catch (IOException e) {
                return new Result(-1, "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(-1, "");
            }
        }

        /** The most of the tool's output read: a password and a few lines about the item. */
        private static final int MAX_OUT = 64 * 1024;

        /**
         * The password from {@code -g} output: {@code password: "text"} for printable ASCII, or
         * {@code password: 0x<hex>  "<escaped>"} for anything else, decoded as UTF-8.
         */
        static String password(String output) {
            for (String line : output.split("\n")) {
                if (!line.startsWith("password: ")) {
                    continue;
                }
                String v = line.substring("password: ".length());
                if (v.startsWith("0x")) {
                    int end = 2;
                    while (end < v.length() && Character.digit(v.charAt(end), 16) >= 0) {
                        end++;
                    }
                    return new String(HexFormat.of().parseHex(v.substring(2, end)), StandardCharsets.UTF_8);
                }
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    return v.substring(1, v.length() - 1);
                }
                return "";
            }
            throw new Unreadable("no password line in the keychain's answer");
        }
    }
}
