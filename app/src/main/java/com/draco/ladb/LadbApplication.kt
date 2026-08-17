package com.draco.ladb

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle

class LadbApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerActivityLifecycleCallbacks(this)
        }
    }

    /**
     * Apply the system palette directly, as Material only does so for a list of vendors
     */
    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.theme.applyStyle(R.style.ThemeOverlay_LADB_DynamicColors, true)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
