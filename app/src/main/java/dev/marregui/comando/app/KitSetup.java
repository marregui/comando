package dev.marregui.comando.app;

import dev.marregui.comando.claude.Kit;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.ui.shell.Setup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** The menus' {@link Setup}: the tool settings file and the Claude Code kit. */
final class KitSetup implements Setup {

    private final Kit kit;
    private final ToolsFile tools;

    KitSetup(Kit kit, ToolsFile tools) {
        this.kit = kit;
        this.tools = tools;
    }

    static KitOffer offer(Kit.Status status) {
        return new KitOffer(Kit.PLUGIN, status.version(), status.installedVersion(), status.enabled(), status.loadsFrom(),
                status.existing(), status.changes(), List.of());
    }

    @Override
    public Path toolSettings() throws IOException {
        return tools.ensure();
    }

    @Override
    public KitOffer kit() throws IOException {
        return offer(kit.status());
    }

    @Override
    public List<String> installKit() throws IOException {
        return kit.install();
    }

    @Override
    public List<FolderRoot> kitFolders() {
        return kit.folders();
    }
}
