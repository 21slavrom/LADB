package com.draco.ladb.fragments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.preference.*
import com.draco.ladb.R
import com.draco.ladb.utils.ADB
import com.draco.ladb.views.MainActivity
import com.google.android.material.snackbar.Snackbar
import kotlin.system.exitProcess

class HelpPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var adb: ADB

    override fun onAttach(context: Context) {
        super.onAttach(context)
        adb = ADB.getInstance(context)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.help, rootKey)

        findPreference<ListPreference>(getString(R.string.language_key))?.apply {
            value = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')

            setOnPreferenceChangeListener { _, newValue ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(newValue as String)
                )
                true
            }
        }
    }

    private fun restartApp() {
        val intent = Intent(requireContext(), MainActivity::class.java)
        startActivity(intent)
        requireActivity().finishAffinity()
        exitProcess(0)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            getString(R.string.unpair_key) -> {
                val context = requireContext()
                PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
                    putBoolean(context.getString(R.string.paired_key), false)
                }
                restartApp()
            }

            getString(R.string.restart_key) -> restartApp()
            getString(R.string.tutorial_key) -> openURL(getString(R.string.tutorial_url))

            getString(R.string.developer_key) -> openURL(getString(R.string.developer_url))
            getString(R.string.source_key) -> openURL(getString(R.string.source_url))

            else -> {
                if (preference !is DialogPreference) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(preference.title)
                        .setMessage(preference.summary)
                        .show()
                }
            }
        }

        return super.onPreferenceTreeClick(preference)
    }

    /**
     * Open a URL for the user
     */
    private fun openURL(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Snackbar.make(requireView(), getString(R.string.snackbar_intent_failed), Snackbar.LENGTH_SHORT).show()
        }
    }
}