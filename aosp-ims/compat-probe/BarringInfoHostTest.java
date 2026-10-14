// SPDX-License-Identifier: Apache-2.0
// Executes the real patched BarringInfo class with a host-only CellIdentity
// fixture. Not an Android runtime/Parcel test or validation of LTE redaction.
import android.telephony.BarringInfo;
import android.telephony.CellIdentity;

public final class BarringInfoHostTest {
    private static int checks;
    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }
    public static void main(String[] args) {
        BarringInfo empty = new BarringInfo(null, null);
        check(empty.getCellIdentity() == null, "null stays null");
        check(empty.createLocationInfoSanitizedCopy() == empty,
                "null sanitization still returns the existing object");
        CellIdentity first = new CellIdentity("first-private-location");
        CellIdentity second = new CellIdentity("second-private-location");
        BarringInfo one = new BarringInfo(first, null);
        BarringInfo two = new BarringInfo(second, null);
        check(one.getCellIdentity() == first, "exact stored reference is returned");
        check(two.getCellIdentity() == second, "instances do not share cell state");
        check(one.getCellIdentity() != two.getCellIdentity(), "different identities remain different");
        check(one.getCellIdentity() == first, "repeated getter has no mutation");
        BarringInfo redacted = one.createLocationInfoSanitizedCopy();
        check(redacted.getCellIdentity() != first, "sanitized copy does not return original identity");
        check(redacted.getCellIdentity().privateLocation == null,
                "getter preserves the sanitized cell supplied by the existing sanitizer");
        check(one.getCellIdentity() == first, "sanitization does not mutate original BarringInfo");
        System.out.println("checks=" + checks);
    }
}
