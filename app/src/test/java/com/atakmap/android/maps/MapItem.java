package com.atakmap.android.maps;

/** Metadata-listener boundary for local JVM tests without the ATAK runtime. */
public class MapItem {
    public interface OnMetadataChangedListener {
        void onMetadataChanged(MapItem item, String key);
    }
    public void addOnMetadataChangedListener(String key, OnMetadataChangedListener listener) {}
    public void removeOnMetadataChangedListener(String key, OnMetadataChangedListener listener) {}
}
