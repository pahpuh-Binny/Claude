package io.github.pahpuhbinny.usbcapture;

import android.app.Application;
import android.content.ComponentCallbacks2;

import io.github.pahpuhbinny.usbcapture.log.AppLog;
import io.github.pahpuhbinny.usbcapture.log.CrashHandler;
import io.github.pahpuhbinny.usbcapture.log.MainThreadWatchdog;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        CrashHandler.install(this);
        AppLog.i("App", "=== Session start ===\n" + CrashHandler.deviceSummary());
        CrashHandler.collectPreviousRunReports(this);
        MainThreadWatchdog.start("app start");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            AppLog.w("App", "System memory is low (trim level " + level + ")");
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        AppLog.w("App", "System reported low memory");
    }
}
