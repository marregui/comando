package dev.marregui.comando.app;

/** Entry point: configuration, logging, the application, and an exit that closes everything once. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();
        Logging.configure(config.logDir());
        String version = Main.class.getPackage().getImplementationVersion();
        App[] app = new App[1];
        app[0] = new App(config, version == null ? "dev" : version, () -> {
            Thread.ofPlatform().name("exit").start(() -> {
                app[0].close();
                System.exit(0);
            });
        });
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(app[0]::close));
        app[0].start();
    }
}
