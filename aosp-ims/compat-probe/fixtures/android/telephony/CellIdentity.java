// SPDX-License-Identifier: Apache-2.0
// HOST TEST FIXTURE ONLY. Never a framework backport or a packaged source.
// The actual BarringInfo class is compiled separately against the real A15
// framework. This fixture supplies identity objects without Android's runtime.
package android.telephony;
import android.os.Parcel;
import android.os.Parcelable;

public final class CellIdentity implements Parcelable {
    public final String privateLocation;
    public CellIdentity(String location) { privateLocation = location; }
    public CellIdentity sanitizeLocationInfo() { return new CellIdentity(null); }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel target, int flags) {
        throw new UnsupportedOperationException("No Android Parcel in this host fixture");
    }
}
