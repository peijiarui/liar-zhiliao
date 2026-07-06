package org.liar.zhiliao.common.utils;

import org.liar.zhiliao.common.model.CurrentUser;

/**
 * Thread-local holder for the current authenticated user.
 * <p>
 * Callers MUST invoke {@link #clear()} in a {@code finally} block after
 * using {@link #set(CurrentUser)} to prevent memory leaks and thread
 * contamination in thread-pooled environments.
 * <pre>{@code
 *     try {
 *         UserContextHolder.set(user);
 *         // ... work that needs the current user ...
 *     } finally {
 *         UserContextHolder.clear();
 *     }
 * }</pre>
 */
public class UserContextHolder {
    private static final ThreadLocal<CurrentUser> CONTEXT = new ThreadLocal<>();

    public static void set(CurrentUser user) {
        CONTEXT.set(user);
    }

    public static CurrentUser get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
