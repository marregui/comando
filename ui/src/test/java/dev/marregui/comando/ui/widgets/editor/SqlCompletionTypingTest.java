package dev.marregui.comando.ui.widgets.editor;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [SqlCompletion]'s list opening on its own once typing pauses, the pause's Swing timer left to fire on the EDT.
/// - letters typed in SQL open the list on the words their prefix starts after the pause, nothing put in
class SqlCompletionTypingTest {

    /// `s`, `e`, `l` typed one by one, each before the pause ends, open the list on `SELECT` alone once typing
    /// pauses; the text stays `sel`.
    @Test
    void lettersTypedInSqlOpenTheListAfterThePause() throws InterruptedException, InvocationTargetException {
        final RSyntaxTextArea[] area = new RSyntaxTextArea[1];
        final SqlCompletion completion = onEdt(() -> {
            area[0] = new RSyntaxTextArea();
            final SqlCompletion c = new SqlCompletion();
            c.install(area[0], () -> true);
            for (final String letter : new String[]{"s", "e", "l"}) {
                area[0].replaceSelection(letter);
            }
            return c;
        });
        assertThat(isTrue(() -> completion.popup().pending())).isTrue();
        assertThat(isTrue(() -> completion.popup().isOpen())).isFalse();

        // 5 s: the pause is 400 ms; the rest is headroom for a loaded machine
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> isTrue(() -> completion.popup().isOpen()));
        assertThat(onEdt(() -> completion.popup().list().getModel().getElementAt(0).text())).isEqualTo("SELECT");
        assertThat(onEdt(() -> completion.popup().list().getModel().getSize())).isEqualTo(1);
        assertThat(onEdt(() -> area[0].getText())).isEqualTo("sel");
        SwingUtilities.invokeAndWait(completion::uninstall);
    }

    private static boolean isTrue(final BooleanSupplier check) throws InterruptedException, InvocationTargetException {
        final AtomicBoolean out = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> out.set(check.getAsBoolean()));
        return out.get();
    }

    private static <T> T onEdt(final Supplier<T> task) throws InterruptedException, InvocationTargetException {
        final AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(task.get()));
        return out.get();
    }
}
