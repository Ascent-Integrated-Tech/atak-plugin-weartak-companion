package com.weartak.atak.weartak_companion.plugin;

/** Edits only the single Companion-managed trailing line of ATAK's user remarks. */
final class SelfStatusRemarks {
    private SelfStatusRemarks() {}

    /** Removes the previously written managed line if the user has not edited it. */
    static String strip(String current, String managed) {
        String text = current == null ? "" : current;
        if (managed == null || managed.isEmpty()) return text;
        if (text.equals(managed)) return "";
        if (text.endsWith("\n" + managed))
            return text.substring(0, text.length() - managed.length() - 1);
        return text;
    }

    static String apply(String current, String previousManaged, String line) {
        String base = strip(current, previousManaged);
        if (line == null || line.isEmpty()) return base;
        return base.trim().isEmpty() ? line : base + "\n" + line;
    }
}
