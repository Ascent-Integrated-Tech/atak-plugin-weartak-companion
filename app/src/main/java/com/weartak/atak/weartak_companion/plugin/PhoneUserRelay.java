package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** PLI copies are session-scoped users, never revision-authorized dropped points. */
final class PhoneUserRelay {
    static final int CAPACITY = 512;
    interface Writer {
        boolean write(String envelope, long session);
    }

    static final class User {
        final String uid, xml;
        final long stale;

        User(String uid, String xml, long stale) {
            this.uid = uid;
            this.xml = xml;
            this.stale = stale;
        }
    }

    private final Map<String, User> sent = new LinkedHashMap<>();
    private long session = -1;

    void reset(long readySession) {
        if (session == readySession) return;
        session = readySession;
        sent.clear();
    }

    boolean contains(String uid) {
        return sent.containsKey(uid);
    }

    static boolean eligible(String uid, String phoneUid, String watchUid, String type,
                            boolean userDetails, boolean excluded, double lat, double lon,
                            long stale, long now) {
        return uid != null && !uid.trim().isEmpty() && uid.length() <= 256
                && phoneUid != null && !phoneUid.isEmpty() && !uid.equals(phoneUid)
                && watchUid != null && !watchUid.isEmpty() && !uid.equals(watchUid)
                && type != null && type.startsWith("a-") && userDetails && !excluded
                && Double.isFinite(lat) && Math.abs(lat) <= 90
                && Double.isFinite(lon) && Math.abs(lon) <= 180 && stale > now;
    }

    boolean forward(User user, String phoneUid, long expectedSession, long readySession,
                    long now, Writer writer) throws JSONException {
        if (expectedSession < 0 || expectedSession != readySession) return false;
        reset(readySession);
        if (user.stale <= now) return remove(user.uid, phoneUid, readySession, writer);
        User previous = sent.get(user.uid);
        if (previous != null && previous.xml.equals(user.xml)) return true;
        if (previous == null && sent.size() >= CAPACITY) {
            throw new IllegalStateException("Live user relay capacity reached (512)");
        }
        String json = new JSONObject().put("msg_type", "phone_user_cot")
                .put("payload", new JSONObject().put("cot", user.xml).put("phoneUid", phoneUid)).toString();
        if (!writer.write(json, readySession)) return false;
        sent.put(user.uid, user);
        return true;
    }

    boolean remove(String uid, String phoneUid, long readySession, Writer writer) throws JSONException {
        if (readySession < 0 || session != readySession || !sent.containsKey(uid)) return true;
        String json = new JSONObject().put("msg_type", "phone_user_remove")
                .put("payload", new JSONObject().put("targetUid", uid).put("phoneUid", phoneUid)).toString();
        if (!writer.write(json, readySession)) return false;
        sent.remove(uid);
        return true;
    }

    void expire(String phoneUid, long readySession, long now, Writer writer) throws JSONException {
        for (User user : new ArrayList<>(sent.values())) {
            if (user.stale <= now) {
                remove(user.uid, phoneUid, readySession, writer);
                return;
            }
        }
    }
}
