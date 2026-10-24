package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;

interface RouteNavigationTransport {
    long readyConnection();
    boolean send(String json, long connection);
    default boolean canImport() { return true; }
    default void validateStart(JSONObject command, String uid, String cot) throws Exception { }
}
