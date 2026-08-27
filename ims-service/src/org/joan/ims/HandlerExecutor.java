package org.joan.ims;

import java.util.concurrent.Executor;

/** Main-thread executor without importing the app-class annotation. */
final class HandlerExecutor implements Executor {
    private final android.os.Handler h = new android.os.Handler(
            android.os.Looper.getMainLooper());

    @Override
    public void execute(Runnable r) {
        h.post(r);
    }
}
