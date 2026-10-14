#!/usr/bin/env python3
"""Generate the Android 15 IARI backport from exact pinned LineageOS 22.2 inputs.

Design rules (same discipline as make_barring_patch.py):

* The pinned Android 15 sources are INPUTS and are never modified. Patched
  copies are written to a fresh attempt directory.
* Every insertion is anchored. An anchor that is missing, or that matches more
  than once, aborts the whole run. A silent partial backport is worse than none.
* Android 17 is a REFERENCE, not a base. Only the IARI logic is ported; the
  A17-only aconfig gate is deliberately dropped (see DROPPED below).
* Nothing here is compiled, installed, flashed or shipped.

DROPPED (intentional, recorded so a reviewer sees it):
  * A17 gates requestUiccIari on the aconfig flag
    FLAG_SUPPORT_IMS_REGISTRATION_EVENT_DOWNLOAD. Verified ABSENT from the
    pinned A15 frameworks/base (0 hits in TelephonyManager.java and in
    system-current.txt), so copying @FlaggedApi(...) would not compile. The
    backported API is therefore ALWAYS AVAILABLE on A15, unlike A17 where it is
    flag-gated. That is a real behavioural difference, not an oversight.
  * A17's PhoneSubInfoController.getUiccIari early-returns on
    mFeatureFlags.supportImsRegistrationEventDownload(). Dropped for the same
    reason.
"""
import difflib
import hashlib
import json
from pathlib import Path
import sys
import tempfile

HERE = Path(__file__).resolve().parent
IN = HERE.parent / 'work/shim-proof/iari-chain-01'

AIDL = 'a15-IPhoneSubInfo.aidl'
TM = 'a15-TelephonyManager.java'
PSIC = 'a15-PhoneSubInfoController.java'
ICCREC = 'a15-IccRecords.java'
ISIM = 'a15-IsimUiccRecords.java'
SIM = 'a15-SIMRecords.java'
APITXT = 'a15-system-current.txt'

# Some A15 sources live in other pinned evidence dirs.
EXTRA = {
    TM: HERE.parent / 'work/shim-proof/platform-reference/a15-TelephonyManager.java',
    PSIC: HERE.parent / 'work/shim-proof/platform-chain-01/a15-PhoneSubInfoController.java',
    APITXT: HERE.parent / 'work/shim-proof/platform-chain-01/a15-system-current.txt',
}


def source(name):
    p = EXTRA.get(name, IN / name)
    if not p.exists():
        sys.exit(f'missing pinned input: {p}')
    return p.read_text()


# ---------------------------------------------------------------- insertions
# Each entry: (anchor, new_text, 'before'|'after')

AIDL_ADD = '''
    /**
     * Fetches the IMS Application Reference Identifier(IARI) based on the subscription.
     *
     * <p>Requires Permission: {@link
     * android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE}, or that the calling
     * app has carrier privileges (see {@link android.telephony.TelephonyManager#hasCarrierPrivileges}).
     *
     * @param subId subscriptionId
     * @param appType the uicc app type
     * @param callingPackage package name of the caller
     * @param callback result receiver to get the list of IARI strings. The result code is ignored.
     * @throws SecurityException if the caller does not have the required permission/privileges
     * @throws IllegalArgumentException in case if subscription is not valid
     * @throws UnsupportedOperationException in case if device does not have
     *             {@link android.content.pm.PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}.
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)")
    oneway void getUiccIari(int subId, int appType, String callingPackage,
        in ResultReceiver callback);
'''

TM_ADD = '''
    /**
     * The key to indicate the result of the ResultReceiver in the {@link #requestUiccIari}.
     * @hide
     */
    public static final String KEY_UICC_IARI_LIST = "uicc_iari_list";

    /**
     * The key to indicate the exception of the ResultReceiver in the {@link #requestUiccIari}.
     * @hide
     */
    public static final String KEY_UICC_IARI_EXCEPTION = "uicc_iari_exception";

    /**
     * Fetches the IMS Application Reference Identifier(IARI) that was loaded from the UICC.
     * If an error occurs internally during the process of fetching IARI,
     * an exception is passed through the onError callback.
     * <ul>
     *   <li>IllegalArgumentException : if the subscriptionId is not valid</li>
     *   <li>SecurityException : if the caller does not have the required permission</li>
     *   <li>UnsupportedOperationException : if the device does not have
     *   {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}</li>
     * </ul>
     *
     * <p>NOTE: unlike Android 17, this backported API is not gated behind the
     * {@code support_ims_registration_event_download} aconfig flag, which does
     * not exist on Android 15.
     *
     * @param executor executor to run the callback on.
     * @param callback callback object to which the result will be delivered.
     * @hide
     */
    @SystemApi
    @RequiresPermission(value = Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @RequiresFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)
    public void requestUiccIari(@NonNull Executor executor,
            @NonNull OutcomeReceiver<Set<String>, Exception> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        IPhoneSubInfo info = getSubscriberInfoService();
        if (info == null) {
            executor.execute(() -> callback.onError(
                    new RuntimeException("requestUiccIari : Subscriber Info is null")));
            return;
        }

        ResultReceiver wrappedCallback = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle result) {
                ParcelableException e = result.getParcelable(
                        KEY_UICC_IARI_EXCEPTION, android.os.ParcelableException.class);
                if (e != null) {
                    Throwable t = e.getCause();
                    if (t instanceof IllegalArgumentException || t instanceof SecurityException
                            || t instanceof UnsupportedOperationException) {
                        executor.execute(() -> callback.onError((Exception) t));
                    } else {
                        executor.execute(() -> callback.onError(
                                new RuntimeException("requestUiccIari : " + t.getMessage())));
                    }
                } else {
                    List<String> iaris = result.getStringArrayList(KEY_UICC_IARI_LIST);
                    Set<String> iariSet = (iaris != null) ? new HashSet<>(iaris) : new HashSet<>();
                    executor.execute(() -> callback.onResult(iariSet));
                }
            }
        };

        try {
            if (isApplicationOnUicc(APPTYPE_ISIM)) {
                info.getUiccIari(getSubId(), APPTYPE_ISIM, getOpPackageName(), wrappedCallback);
            } else if (isApplicationOnUicc(APPTYPE_USIM)) {
                info.getUiccIari(getSubId(), APPTYPE_USIM, getOpPackageName(), wrappedCallback);
            } else {
                executor.execute(() -> callback.onError(new RuntimeException(
                        "requestUiccIari : ISIM or USIM application is not exist in UICC")));
            }
        } catch (Exception ex) {
            executor.execute(() -> callback.onError(ex));
        }
    }

'''

PSIC_ADD = '''
    /**
     * Fetches the IMS Application Reference Identifier(IARI) based on the subscription.
     *
     * @param subId subscriptionId
     * @param appType the uicc app type
     * @param callingPackage package name of the caller
     * @param callback result receiver to get the list of IARI strings. The result code is ignored.
     */
    public void getUiccIari(int subId, int appType, String callingPackage,
            ResultReceiver callback) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            callbackUiccIari(callback, null,
                    new IllegalArgumentException("Invalid subscription: " + subId));
            return;
        }
        try {
            TelephonyPermissions
                    .enforceCallingOrSelfReadPrivilegedPhoneStatePermissionOrCarrierPrivilege(
                            mContext, subId, "getUiccIari");
            enforceTelephonyFeatureWithException(callingPackage,
                    PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, "getUiccIari");
        } catch (SecurityException | UnsupportedOperationException ex) {
            callbackUiccIari(callback, null, ex);
            return;
        }

        Phone phone = getPhone(subId);
        if (phone == null) {
            loge("getUiccIari(): phone is null");
            callbackUiccIari(callback, Collections.emptyList(), null);
            return;
        }
        UiccPort uiccPort = phone.getUiccPort();
        if (uiccPort == null || uiccPort.getUiccProfile() == null) {
            loge("getUiccIari(): uiccPort or uiccProfile is null");
            callbackUiccIari(callback, Collections.emptyList(), null);
            return;
        }
        UiccCardApplication uiccApp = uiccPort.getUiccProfile().getApplicationByType(appType);
        if (uiccApp == null) {
            loge("getUiccIari(): no app with specified apptype=" + appType);
            callbackUiccIari(callback, Collections.emptyList(), null);
            return;
        }
        String[] iaris = uiccApp.getIccRecords().getUiccIari();
        if (iaris == null) {
            loge("getUiccIari(): IARI list is null");
            callbackUiccIari(callback, Collections.emptyList(), null);
            return;
        }
        List<String> iariList = Arrays.stream(iaris)
                .filter(u -> u != null)
                .map(u -> u.trim())
                .filter(u -> u.length() > 0)
                .collect(Collectors.toList());
        callbackUiccIari(callback, iariList, null);
    }

    private void callbackUiccIari(ResultReceiver callback, List<String> iari, Exception ex) {
        Bundle result = new Bundle();
        if (ex != null) {
            result.putParcelable(TelephonyManager.KEY_UICC_IARI_EXCEPTION,
                    new ParcelableException(ex));
        } else {
            result.putStringArrayList(TelephonyManager.KEY_UICC_IARI_LIST,
                    (iari == null)
                            ? new ArrayList<>(Collections.emptyList()) : new ArrayList<>(iari));
        }
        callback.send(0, result);
    }

'''

ICCREC_ADD = '''
    /**
     * Returns the IMS Application Reference Identifier(IARI).
     * @return array of IARI or null if not present
     */
    public String[] getUiccIari() {
        return null;
    }
'''

ISIM_FIELD = '''
    private String[] mIsimIari;             // IMS Application Reference Identifier
'''

ISIM_LOAD = '''
        mFh.loadEFLinearFixedAll(EF_IARI, obtainMessage(
                IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimIariLoaded()));
        mRecordsToLoad++;
'''

ISIM_RESET = '''        mIsimIari = null;
'''

ISIM_METHODS = '''
    @VisibleForTesting
    public EfIsimIariLoaded getIsimIariObject() {
        return new EfIsimIariLoaded();
    }

    private class EfIsimIariLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_IARI";
        }
        public void onRecordLoaded(AsyncResult ar) {
            ArrayList<byte[]> iariList = (ArrayList<byte[]>) ar.result;
            if (iariList != null && iariList.size() > 0) {
                if (DBG) log("EF_IARI record count: " + iariList.size());
                mIsimIari = new String[iariList.size()];
                int i = 0;
                for (byte[] data : iariList) {
                    String iari = isimTlvToString(data);
                    if (DUMP_RECORDS) log("EF_IARI[" + i + "]=" + iari);
                    mIsimIari[i++] = iari;
                }
            }
        }
    }

    /**
     * Returns the IMS Application Reference Identifier(IARI) that was loaded from the ISIM.
     * @return array of IARI or null if not loaded
     */
    public String[] getUiccIari() {
        return (mIsimIari != null) ? mIsimIari.clone() : null;
    }

'''

SIM_FIELD = '''
    // IARI TLV TAG from TS 31.102
    static final int TAG_IARI_TLV = 0x80;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private String[] mUsimIari;    // IMS Application Reference Identifier
'''

SIM_EVENT = '''    protected static final int EVENT_GET_IARI_DONE = 48 + SIM_RECORD_EVENT_BASE;
'''

SIM_CASE = '''                case EVENT_GET_IARI_DONE:
                    isRecordLoadResponse = true;
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        loge("Failed to read USIM EF_IARI field error=" + ar.exception);
                    } else {
                        List<byte[]> iariList = (ArrayList<byte[]>) ar.result;
                        if (iariList != null) {
                            parseEfIari(iariList);
                        }
                    }
                    break;

'''

SIM_LOAD = '''        mFh.loadEFLinearFixedAll(EF_IARI, obtainMessage(EVENT_GET_IARI_DONE));
        mRecordsToLoad++;

'''

SIM_RESET = '''        mUsimIari = null;
'''

SIM_METHODS = '''
    /**
     * convert a byte array of packed IARIs to an array of strings
     * Reference: 3GPP TS 31.102 Section 4.2.95 and TS 31.103 Section 4.2.16.
     */
    private void parseEfIari(List<byte[]> dataList) {
        final int count = dataList.size();
        mUsimIari = new String[count];
        for (int i = 0; i < count; i++) {
            byte[] data = dataList.get(i);
            SimTlv tlv = new SimTlv(data, 0, data.length);
            if (tlv.getTag() == TAG_IARI_TLV) {
                mUsimIari[i] = new String(tlv.getData(), StandardCharsets.UTF_8);
            } else {
                mUsimIari[i] = null;
            }
        }
        if (VDBG) logv("IARIs: " + Arrays.toString(mUsimIari));
    }

    /**
     * Returns the IMS Application Reference Identifier(IARI) that was loaded from the USIM.
     * @return array of IARI or null if not loaded
     */
    public String[] getUiccIari() {
        return (mUsimIari != null) ? mUsimIari.clone() : null;
    }
'''

PLAN = [
    (AIDL, '    String getSimServiceTable(int subId, int appType);\n}\n',
     AIDL_ADD, 'before'),
    (TM, '    /** UICC application type is unknown or not specified */\n',
     TM_ADD, 'before'),
    (PSIC,
     '    /** Below are utility methods that abstracts the flow that many public methods use:\n',
     PSIC_ADD, 'before'),
    (ICCREC, '''    public UsimServiceTable getUsimServiceTable() {
        return null;
    }
''', ICCREC_ADD, 'after'),
    (ISIM, '    private String auth_rsp;\n', ISIM_FIELD, 'before'),
    (ISIM, '''        mFh.loadEFTransparent(EF_SMSS,  obtainMessage(
                IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimSmssLoaded()));
        mRecordsToLoad++;
''', ISIM_LOAD, 'after'),
    (ISIM, '        mIsimImpi = null;\n', ISIM_RESET, 'after'),
    (ISIM, '''    @VisibleForTesting
    public EfIsimPsiSmscLoaded getPsiSmscObject() {
        return new EfIsimPsiSmscLoaded();
    }
''', ISIM_METHODS, 'before'),
    (SIM, '    // ***** Constants\n', SIM_FIELD, 'after'),
    (SIM, '    private static final int SIM_RECORD_EVENT_BASE = 0x00;\n', SIM_EVENT, 'after'),
    (SIM, '                case EVENT_GET_SMSS_RECORD_DONE:\n', SIM_CASE, 'before'),
    (SIM, '''        mFh.loadEFTransparent(EF_HPLMN_W_ACT, obtainMessage(EVENT_GET_HPLMN_W_ACT_DONE));
        mRecordsToLoad++;
''', SIM_LOAD, 'after'),
    (SIM, '        mImsi = null;\n', SIM_RESET, 'after'),
    (SIM, '''                + " TPMR=" + getSmssTpmrValue();
    }
''', SIM_METHODS, 'after'),
]


def apply_all(text, plan, name):
    """Apply one file's insertions, failing closed on any bad anchor."""
    applied = []
    for _, anchor, add, where in [p for p in plan if p[0] == name]:
        count = text.count(anchor)
        if count != 1:
            sys.exit(f'{name}: anchor matched {count} times (need exactly 1):\n'
                     f'    {anchor.strip()[:90]!r}')
        text = (text.replace(anchor, add + anchor) if where == 'before'
                else text.replace(anchor, anchor + add))
        applied.append({'anchor': anchor.strip()[:80], 'where': where})
    return text, applied


def main():
    attempt = Path(tempfile.mkdtemp(prefix='iari-backport-', dir=str(HERE.parent / 'work/shim-proof')))
    (attempt / 'patched').mkdir()
    report = {'attempt': str(attempt), 'dropped_a17_aconfig_gate': True,
              'files': {}, 'anchors_total': 0}

    for name in (AIDL, TM, PSIC, ICCREC, ISIM, SIM):
        text = source(name)
        before = hashlib.sha256(text.encode()).hexdigest()
        new, applied = apply_all(text, PLAN, name)
        after = hashlib.sha256(new.encode()).hexdigest()
        out = attempt / 'patched' / name
        out.write_text(new)
        diff = ''.join(difflib.unified_diff(
            text.splitlines(keepends=True), new.splitlines(keepends=True),
            fromfile=f'a/{name}', tofile=f'b/{name}'))
        (attempt / f'{name}.patch').write_text(diff)
        report['files'][name] = {
            'input_sha256': before, 'patched_sha256': after,
            'input_bytes': len(text), 'patched_bytes': len(new),
            'insertions': len(applied), 'anchors': applied,
        }
        report['anchors_total'] += len(applied)
        print(f'{name}: {len(applied)} anchored insertions '
              f'({len(text)} -> {len(new)} bytes)')

    # The API text file is reported, not auto-edited: metalava ordering and the
    # exact current.txt block are tree-dependent and must be regenerated by a
    # real metalava run rather than hand-guessed.
    report['api_txt_required_entries'] = [
        'method public void requestUiccIari(java.util.concurrent.Executor, '
        'android.util.OutcomeReceiver<java.util.Set<java.lang.String>, java.lang.Exception>);',
        'field public static final String KEY_UICC_IARI_EXCEPTION = "uicc_iari_exception";',
        'field public static final String KEY_UICC_IARI_LIST = "uicc_iari_list";',
    ]
    (attempt / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'\nattempt: {attempt}')
    print(f"anchors applied: {report['anchors_total']}")
    print('api_txt: NOT auto-edited (see report.api_txt_required_entries)')


if __name__ == '__main__':
    main()
