package androidx.core.content;

import android.content.Context;

/** Host AndroidX boundary for local JVM tests. */
public final class ContextCompat {
    private ContextCompat() { }

    public static int checkSelfPermission(Context context, String permission) {
        return context.checkSelfPermission(permission);
    }
}
