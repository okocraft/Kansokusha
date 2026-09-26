package net.okocraft.kansokusha.paper.builtin;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;

final class PaperListenerTestSupport {

    private PaperListenerTestSupport() {
    }

    /**
     * Calls the listener's handlers for the event in priority order, skipping handlers with
     * {@code ignoreCancelled = true} when the event is cancelled, as Bukkit does.
     */
    static void fire(Listener listener, Event event) {
        var handlers = Arrays.stream(listener.getClass().getMethods())
            .filter(method -> method.isAnnotationPresent(EventHandler.class))
            .filter(method -> method.getParameterCount() == 1)
            .filter(method -> method.getParameterTypes()[0].isInstance(event))
            .sorted(Comparator.comparing(method -> handler(method).priority()))
            .toList();

        for (var method : handlers) {
            if (
                handler(method).ignoreCancelled()
                    && event instanceof Cancellable cancellable
                    && cancellable.isCancelled()
            ) {
                continue;
            }
            invoke(method, listener, event);
        }
    }

    private static EventHandler handler(Method method) {
        return method.getAnnotation(EventHandler.class);
    }

    private static void invoke(Method method, Listener listener, Event event) {
        try {
            method.invoke(listener, event);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new AssertionError(e.getCause());
        }
    }
}
