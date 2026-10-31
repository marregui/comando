package dev.marregui.comando.ui.dialogs.resource;

import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.ui.dialogs.sessions.StoredConversation;
import dev.marregui.comando.ui.kit.format.Labels;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A session's conversation, picked by what it is (record 125, 2026-10-31): a new one, the session's own, or one Claude
 * Code stored on disk, each shown by its title, folder and age, never by its id; beside a stored one, whether the next
 * start continues it or a copy of it. Edits two of the form's keys: {@code claudeSessionId} and {@code startMode}.
 * A new conversation keeps the session's own id, as NEW did: the next start replaces it.
 */
final class ConversationField extends JPanel {

    static final String NEW_ONE = "A new conversation";
    static final String CONTINUE = "Continue it";
    static final String COPY = "A copy of it";

    /** One row of the picker. */
    record Entry(Kind kind, String id, String label, String cwd) {
        enum Kind { NEW, OWN, STORED }

        @Override
        public String toString() {
            return label;
        }
    }

    private final JComboBox<Entry> pick = new JComboBox<>();
    private final JComboBox<String> how = new JComboBox<>(new String[]{CONTINUE, COPY});
    private List<StoredConversation> stored = List.of();
    private Set<String> held = Set.of();
    /** The id the form was filled with: the session's own conversation, or the one a clone copies. */
    private String own = "";
    /** Whether the form edits a saved session, whose conversation is its own, rather than a new one or a clone. */
    private boolean existing;
    private boolean filling;
    private Runnable changed = () -> { };
    private Consumer<String> picked = cwd -> { };

    ConversationField() {
        super(new BorderLayout(4, 0));
        pick.setName("claudeSessionId");
        how.setName("startMode");
        how.getAccessibleContext().setAccessibleName("Continue it or a copy of it");
        how.setToolTipText("Continue it where it left off, or start from a copy and leave it as it was");
        // the titles are long: the picker takes the column's width and never widens the form past the dialog
        pick.setPrototypeDisplayValue(new Entry(Entry.Kind.NEW, "", NEW_ONE, ""));
        pick.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                Component c = super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Entry e) {
                    setToolTipText(e.kind() == Entry.Kind.NEW ? null : e.label() + " · id " + e.id());
                }
                return c;
            }
        });
        pick.addActionListener(e -> {
            if (filling) {
                return;
            }
            Entry now = selected();
            if (now.kind() == Entry.Kind.STORED) {
                // as an import does: a conversation that may still be open elsewhere is copied, and the session runs
                // in its folder, where Claude Code keeps it
                how.setSelectedItem(COPY);
                picked.accept(now.cwd());
            } else if (now.kind() == Entry.Kind.OWN) {
                how.setSelectedItem(existing ? CONTINUE : COPY);
            }
            showHow();
            changed.run();
        });
        how.addActionListener(e -> {
            if (!filling) {
                changed.run();
            }
        });
        add(pick, BorderLayout.CENTER);
        add(how, BorderLayout.EAST);
        showHow();
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, super.getMinimumSize().height);
    }

    /** {@code r} runs when the user changes the conversation or how it is reached. */
    void onChange(Runnable r) {
        changed = r;
    }

    /** {@code c} hears the folder of a stored conversation the user picks. */
    void onPicked(Consumer<String> c) {
        picked = c;
    }

    /** The conversations Claude Code stored on disk, newest first; the picker keeps what it shows selected. */
    void stored(List<StoredConversation> conversations) {
        stored = List.copyOf(conversations);
        rebuild(selected());
    }

    /** The ids other sessions hold: not offered, as two sessions never write one conversation. */
    void held(Set<String> ids) {
        if (!ids.equals(held)) {
            held = Set.copyOf(ids);
            rebuild(selected());
        }
    }

    /** Fills the picker from the form's values; {@code existing} when the form edits a saved session. */
    void fill(Map<String, String> values, boolean existing) {
        this.existing = existing;
        own = values.getOrDefault("claudeSessionId", "").strip();
        String mode = values.getOrDefault("startMode", "").strip();
        boolean fresh = own.isEmpty() || mode.equalsIgnoreCase(StartMode.NEW.name());
        filling = true;
        try {
            rebuild(null);
            pick.setSelectedIndex(fresh ? 0 : 1);
            how.setSelectedItem(mode.equalsIgnoreCase(StartMode.FORK.name()) ? COPY : CONTINUE);
        } finally {
            filling = false;
        }
        showHow();
    }

    /** Puts the picker's {@code claudeSessionId} and {@code startMode} into {@code m}. */
    void into(Map<String, String> m) {
        Entry e = selected();
        if (e.kind() == Entry.Kind.NEW) {
            m.put("claudeSessionId", own);
            m.put("startMode", StartMode.NEW.name());
        } else {
            m.put("claudeSessionId", e.id());
            m.put("startMode", (COPY.equals(how.getSelectedItem()) ? StartMode.FORK : StartMode.RESUME).name());
        }
    }

    /** The rows: a new conversation, the form's own when it has one, then the stored ones no other session holds. */
    List<Entry> entries() {
        List<Entry> rows = new ArrayList<>();
        rows.add(new Entry(Entry.Kind.NEW, "", NEW_ONE, ""));
        long now = System.currentTimeMillis();
        if (!own.isEmpty()) {
            StoredConversation mine = stored.stream().filter(c -> c.sessionId().equals(own)).findFirst().orElse(null);
            String what = mine == null ? "stored conversation " + own.substring(0, Math.min(8, own.length())) : label(mine, now);
            rows.add(new Entry(Entry.Kind.OWN, own, (existing ? "Its own · " : "") + what, mine == null ? "" : mine.cwd()));
        }
        for (StoredConversation c : stored) {
            if (!c.sessionId().equals(own) && !held.contains(c.sessionId())) {
                rows.add(new Entry(Entry.Kind.STORED, c.sessionId(), label(c, now), c.cwd()));
            }
        }
        return rows;
    }

    /** "title · ~/folder · 2 h ago"; the last prompt, else the id's start, when it has no title. */
    static String label(StoredConversation c, long nowMillis) {
        String title = !c.title().isBlank() ? c.title().strip()
                : !c.lastPrompt().isBlank() ? c.lastPrompt().strip() : c.sessionId().substring(0, Math.min(8, c.sessionId().length()));
        return title + " · " + Labels.shortPath(c.cwd()) + " · " + Labels.ago(c.modifiedMillis(), nowMillis);
    }

    private void rebuild(Entry keep) {
        List<Entry> rows = entries();
        boolean was = filling;
        filling = true;
        try {
            pick.setModel(new DefaultComboBoxModel<>(rows.toArray(Entry[]::new)));
            int at = 0;
            if (keep != null) {
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).kind() == keep.kind() && Objects.equals(rows.get(i).id(), keep.id())) {
                        at = i;
                        break;
                    }
                }
            }
            pick.setSelectedIndex(at);
        } finally {
            filling = was;
        }
        showHow();
    }

    private Entry selected() {
        return pick.getSelectedItem() instanceof Entry e ? e : new Entry(Entry.Kind.NEW, "", NEW_ONE, "");
    }

    private void showHow() {
        how.setVisible(selected().kind() != Entry.Kind.NEW);
        revalidate();
    }

    JComboBox<Entry> picker() {
        return pick;
    }

    JComboBox<String> how() {
        return how;
    }
}
