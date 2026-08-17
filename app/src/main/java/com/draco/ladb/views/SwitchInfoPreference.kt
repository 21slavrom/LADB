package com.draco.ladb.views

import android.content.Context
import android.util.AttributeSet
import androidx.preference.SwitchPreferenceCompat

class SwitchInfoPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwitchPreferenceCompat(context, attrs) {
    /**
     * Only the switch widget toggles; row clicks fall through to the fragment
     */
    override fun onClick() {}
}
