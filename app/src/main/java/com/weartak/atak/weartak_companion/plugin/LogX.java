package com.weartak.atak.weartak_companion.plugin;

import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;

public final class LogX {
    private LogX() {}
    private static volatile CompanionDiagnosticLogger diagnostics;

    static void attach(CompanionDiagnosticLogger logger) {
        diagnostics = logger;
    }

    static void detach(CompanionDiagnosticLogger logger) {
        if (diagnostics == logger) diagnostics = null;
    }

    // Quiet logcat without disabling a user-requested diagnostic capture.
    public static volatile boolean ENABLED = true;

    // Use a stable prefix so Logcat filtering is easy: "WTK/"
    public static String tag(String component) {
        return "WTK/" + component;
    }

    public static void d(String tag, String msg) {
        capture("D", tag, msg, null);
        if (!ENABLED) return;
        Log.d(tag, stamp() + msg);
    }

    public static void i(String tag, String msg) {
        capture("I", tag, msg, null);
        if (!ENABLED) return;
        Log.i(tag, stamp() + msg);
    }

    public static void w(String tag, String msg) {
        capture("W", tag, msg, null);
        if (!ENABLED) return;
        Log.w(tag, stamp() + msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        capture("E", tag, msg, t);
        if (!ENABLED) return;
        Log.e(tag, stamp() + msg, t);
    }

    public static void w(String tag, String msg, Throwable t) {
        capture("W", tag, msg, t);
        if (ENABLED) Log.w(tag, stamp() + msg, t);
    }

    public static void e(String tag, String msg) {
        e(tag, msg, null);
    }

    private static void capture(String level, String tag, String msg, Throwable error) {
        CompanionDiagnosticLogger target = diagnostics;
        if (target == null || !target.isLogging()) return;
        target.record(level, tag, msg, error != null);
    }

    private static String stamp() {
        // uptime ms is ideal for timing sequences
        long ms = SystemClock.uptimeMillis();
        return String.format(Locale.US, "[t=%d] ", ms);
    }
}