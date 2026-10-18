package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WatchPointLocationTest {
    private WatchPointLocation location(JSONObject payload) throws Exception {
        return new WatchPointLocation(payload, 35, -106, 10, 5, 6);
    }

    @Test public void watchSelectedPointDoesNotMoveToPhoneLocation() throws Exception {
        WatchPointLocation point = location(new JSONObject()
                .put("lat", 38.5).put("lon", -77.2).put("hae", 42));
        assertEquals(38.5, point.lat, 0);
        assertEquals(-77.2, point.lon, 0);
        assertEquals(42, point.hae, 0);
        assertEquals(5, point.ce, 0);
        assertEquals(6, point.le, 0);
    }

    @Test public void legacyPayloadWithoutCoordinatesUsesPhoneFix() throws Exception {
        WatchPointLocation point = location(new JSONObject());
        assertEquals(35, point.lat, 0);
        assertEquals(-106, point.lon, 0);
    }

    @Test public void malformedOrPartialLocationIsRejectedInsteadOfMovingPoint() throws Exception {
        for (String json : new String[]{"{\"lat\":38}", "{\"lon\":-77}",
                "{\"lat\":91,\"lon\":0}", "{\"lat\":0,\"lon\":181}",
                "{\"lat\":\"bad\",\"lon\":0}", "{\"lat\":null,\"lon\":0}"}) {
            try {
                location(new JSONObject(json));
                fail("Accepted invalid point: " + json);
            } catch (org.json.JSONException | IllegalArgumentException expected) { }
        }
    }
}
