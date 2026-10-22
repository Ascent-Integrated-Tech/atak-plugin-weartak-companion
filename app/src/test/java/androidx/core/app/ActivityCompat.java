package androidx.core.app;

import android.content.Context;

/** Host-provided AndroidX boundary for local JVM tests; delegates to the supplied test context. */
public final class ActivityCompat {
    private ActivityCompat() { }

    public static int checkSelfPermission(Context context, String permission) {
        return context.checkSelfPermission(permission);
    }
}
