package org.joan.ims;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts/rechecks the IMS driver on boot and package replacement. */
public class JoanBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        JoanTrace.init(app);
        String action = intent == null ? "null" : intent.getAction();
        JoanTrace.note("receiver " + action + "; starting driver");
        JoanDriver.start(app);
    }
}
