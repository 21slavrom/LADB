package com.draco.ladb

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

class LadbApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setThemeOverlay(R.style.ThemeOverlay_LADB_DynamicColors)
                .build()
        )
    }
}
