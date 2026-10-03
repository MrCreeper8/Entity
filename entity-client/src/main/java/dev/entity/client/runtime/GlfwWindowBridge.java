package dev.entity.client.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Tiny fail-closed bridge to GLFW. The prepared offline compiler intentionally
 * exposes Minecraft's mapped API but not LWJGL's compile jar; Minecraft itself
 * always supplies these methods at runtime.
 */
public final class GlfwWindowBridge {
    private static final int GLFW_FALSE = 0;
    private static final int GLFW_TRUE = 1;
    private static final int GLFW_FOCUSED = 0x00020001;
    private static final int GLFW_VISIBLE = 0x00020004;
    private static final int GLFW_FOCUS_ON_SHOW = 0x0002000C;

    private GlfwWindowBridge() {
    }

    public static void applyHiddenCreationHints() {
        invoke(Holder.WINDOW_HINT, GLFW_VISIBLE, GLFW_FALSE);
        applyInputIsolatedCreationHints();
    }

    public static void applyInputIsolatedCreationHints() {
        invoke(Holder.WINDOW_HINT, GLFW_FOCUSED, GLFW_FALSE);
        invoke(Holder.WINDOW_HINT, GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
    }

    public static void hide(long handle) {
        if (handle != 0L) invoke(Holder.HIDE_WINDOW, handle);
    }

    public static boolean visible(long handle) {
        if (handle == 0L) return false;
        return ((Number) invoke(Holder.GET_WINDOW_ATTRIBUTE,
                handle, GLFW_VISIBLE)).intValue() == GLFW_TRUE;
    }

    private static Object invoke(Method method, Object... arguments) {
        try {
            return method.invoke(null, arguments);
        } catch (IllegalAccessException | InvocationTargetException error) {
            throw new IllegalStateException(
                    "Entity2 background window control is unavailable", error);
        }
    }

    private static final class Holder {
        private static final Method WINDOW_HINT;
        private static final Method HIDE_WINDOW;
        private static final Method GET_WINDOW_ATTRIBUTE;

        static {
            try {
                Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
                WINDOW_HINT = glfw.getMethod("glfwWindowHint", int.class, int.class);
                HIDE_WINDOW = glfw.getMethod("glfwHideWindow", long.class);
                GET_WINDOW_ATTRIBUTE = glfw.getMethod(
                        "glfwGetWindowAttrib", long.class, int.class);
            } catch (ClassNotFoundException | NoSuchMethodException error) {
                throw new ExceptionInInitializerError(error);
            }
        }
    }
}
