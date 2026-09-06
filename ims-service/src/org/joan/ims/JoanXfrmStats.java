package org.joan.ims;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Best-effort, key-free IPsec diagnostics. /proc/net/xfrm_stat contains
 * network-namespace-wide error counters, NOT per-call/SA packet counts.
 * No change does not prove that a packet was sent, received or accepted.
 * See https://docs.kernel.org/networking/xfrm_proc.html for counter meanings.
 */
final class JoanXfrmStats {
    private static final int MAX_BYTES = 16 * 1024;
    private static final String PREFIX = "xfrm_scope=netns xfrm=";

    private JoanXfrmStats() {}

    static String capture() {
        // File permissions alone are not sufficient: SELinux can deny this
        // to an Android app. Unavailable is reported explicitly, never zero.
        try (InputStream in = new FileInputStream("/proc/net/xfrm_stat")) {
            return readBounded(in);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    static String readBounded(InputStream in) throws IOException {
        byte[] bytes = new byte[MAX_BYTES + 1];
        int used = 0;
        while (used < bytes.length) {
            int n = in.read(bytes, used, bytes.length - used);
            if (n < 0) {
                return new String(bytes, 0, used, StandardCharsets.US_ASCII);
            }
            if (n == 0) {
                // Handle short/zero reads without spinning indefinitely.
                int one = in.read();
                if (one < 0) {
                    return new String(bytes, 0, used, StandardCharsets.US_ASCII);
                }
                bytes[used++] = (byte) one;
            } else {
                used += n;
            }
        }
        return null; // Refuse a truncated snapshot.
    }

    private static SortedMap<String, Long> parse(String text) {
        if (text.length() > MAX_BYTES) {
            return null;
        }
        SortedMap<String, Long> values = new TreeMap<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length != 2 || !parts[0].matches("Xfrm[A-Za-z0-9]{1,64}")
                    || !parts[1].matches("[0-9]+")) {
                return null;
            }
            try {
                if (values.put(parts[0], Long.parseLong(parts[1])) != null) {
                    return null;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return values.isEmpty() ? null : values;
    }

    static String delta(String before, String after) {
        if (before == null || after == null) {
            return PREFIX + "unavailable";
        }
        SortedMap<String, Long> oldValues = parse(before);
        SortedMap<String, Long> newValues = parse(after);
        if (oldValues == null || newValues == null) {
            return PREFIX + "invalid";
        }
        if (!oldValues.keySet().equals(newValues.keySet())) {
            return PREFIX + "incomplete";
        }
        boolean reset = false;
        StringBuilder changed = new StringBuilder();
        for (Map.Entry<String, Long> entry : oldValues.entrySet()) {
            String name = entry.getKey();
            long oldValue = entry.getValue();
            long newValue = newValues.get(name);
            if (newValue < oldValue) {
                reset = true;
                changed.append(' ').append(name).append("=reset");
            } else if (newValue > oldValue) {
                changed.append(' ').append(name).append("=+").append(newValue - oldValue);
            }
        }
        String state = reset ? "reset" : changed.length() == 0 ? "unchanged" : "changed";
        return PREFIX + state + changed;
    }
}
