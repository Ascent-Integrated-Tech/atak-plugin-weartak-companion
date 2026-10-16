package com.weartak.atak.weartak_companion.plugin;

import com.atakmap.android.contact.ContactUtil;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.Marker;
import com.atakmap.comms.NetConnectString;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;

final class PhoneUserCot {
    private PhoneUserCot() {}

    static boolean isUser(MapItem item, CotEvent event) {
        CotDetail contact = event == null ? null : event.findDetail("contact");
        return ContactUtil.isTakContact(item)
                || (item instanceof Marker
                    && Double.isFinite(((Marker) item).getTrackHeading()))
                || (event != null
                    && (event.findDetail("__group") != null || event.findDetail("track") != null))
                || (contact != null && contact.getAttribute("endpoint") != null)
                || Contacts.getInstance().getContactByUuid(item.getUID()) instanceof IndividualContact;
    }

    static CotEvent export(Marker marker) {
        CotEvent event = CotEventFactory.createCotEvent(marker);
        if (event == null || !isUser(marker, event)) return event;
        CotDetail detail = event.getDetail();
        if (detail == null) {
            detail = new CotDetail("detail");
            event.setDetail(detail);
        }
        CotDetail contact = event.findDetail("contact");
        if (contact == null || contact.getAttribute("endpoint") == null) {
            Contact known = Contacts.getInstance().getContactByUuid(marker.getUID());
            if (known instanceof IndividualContact) {
                NetConnectString endpoint = ContactUtil.getIpAddress((IndividualContact) known);
                if (endpoint != null) {
                    if (contact == null) {
                        contact = new CotDetail("contact");
                        detail.addChild(contact);
                    }
                    contact.setAttribute("endpoint", endpoint.toString());
                }
            }
        }
        // ATAK 5.6 imports these PLI fields but omits them on generic Marker export.
        String team = marker.getMetaString("team", null);
        if (event.findDetail("__group") == null && team != null && !team.isEmpty()) {
            CotDetail group = new CotDetail("__group");
            group.setAttribute("name", team);
            String role = marker.getMetaString("atakRoleType", null);
            if (role != null && !role.isEmpty()) group.setAttribute("role", role);
            detail.addChild(group);
        }
        double heading = marker.getTrackHeading(), speed = marker.getTrackSpeed();
        if (event.findDetail("track") == null && Double.isFinite(heading)
                && Double.isFinite(speed) && speed >= 0) {
            CotDetail track = new CotDetail("track");
            track.setAttribute("course", Double.toString(heading));
            track.setAttribute("speed", Double.toString(speed));
            detail.addChild(track);
        }
        return event;
    }
}
