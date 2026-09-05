package org.joan.ims;

import java.util.ArrayList;
import java.util.List;

/**
 * Conference participants for the AOSP conference-state callback,
 * built from RFC 4579 conference-info NOTIFY bodies.
 */
public final class JoanConfState {
    /** Minimal participant: just the URI the focus reports. */
    public final List<String> users = new ArrayList<>();

    static JoanConfState fromUsers(List<String> entities) {
        JoanConfState s = new JoanConfState();
        if (entities != null) {
            s.users.addAll(entities);
        }
        return s;
    }
}
