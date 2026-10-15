package dev.marregui.comando.ui.kit.async;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import javax.swing.SwingUtilities;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs each test method on the event dispatch thread, as the application runs its components. A test that drives a
 * component from JUnit's thread races the component's own Swing timers, which fire on the EDT.
 */
public final class OnEdt implements InvocationInterceptor {

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                invocation.proceed();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
