package io.virtualapp.settings

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.android.launcher3.LauncherFiles
import com.google.android.apps.nexuslauncher.SettingsActivity
import com.lody.virtual.client.env.Constants
import com.virtualxposed.log.client.VLoggingClient
import io.virtualapp.VCommends
import io.virtualapp.abs.ui.VActivity
import io.virtualapp.gms.FakeGms
import java.io.IOException

class AdvancedSettingsActivity : VActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, AdvancedSettingsFragment())
                .commit()
        }
    }

    /**
     * This fragment shows the launcher preferences.
     */
    class AdvancedSettingsFragment : PreferenceFragmentCompat() {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            preferenceManager.setSharedPreferencesName(LauncherFiles.SHARED_PREFERENCES_KEY)
            addPreferencesFromResource(io.virtualapp.R.xml.advanced_settings_preferences)

            findPreference<Preference>(INSTALL_GMS_PREFERENCE)?.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    if (FakeGms.isAlreadyInstalled(activity)) {
                        FakeGms.uninstallGms(activity)
                    } else {
                        FakeGms.installGms(activity)
                    }
                    true
                }

            val fingerprint = findPreference<ListPreference>(FINGERPRINT_INCONSISTENT)
            fingerprint?.value = VLoggingClient.get().getFingerprintAction()?.toString()
            fingerprint?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, k ->
                    val key = (k as? Int) ?: (k as? String)?.toIntOrNull() ?: return@OnPreferenceChangeListener false
                    val success = VLoggingClient.get().setFingerprintAction(key)
                    return@OnPreferenceChangeListener success
                }

            setupFileFlagPreference(DISABLE_XPOSED, ".disable_xposed")
            setupFileFlagPreference(DISABLE_RESIDENT_NOTIFICATION, Constants.NO_NOTIFICATION_FLAG)
            setupFileFlagPreference(ALLOW_FAKE_SIGNATURE, Constants.FAKE_SIGNATURE_FLAG)

            setupComponentStatePreference(
                DISABLE_INSTALLER_PREFERENCE,
                "vxp.installer"
            ) { disable ->
                if (!disable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

            setupComponentStatePreference(ENABLE_LAUNCHER, "vxp.launcher") { enable ->
                if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

            // Desktop Settings
            findPreference<Preference>(DESKTOP_SETTINGS_PREFERENCE)?.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    startActivity(Intent(activity, SettingsActivity::class.java))
                    false
                }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                findPreference<Preference>(DISABLE_RESIDENT_NOTIFICATION)?.let {
                    preferenceScreen.removePreference(it)
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}

        override fun startActivity(intent: Intent?) {
            try {
                super.startActivity(intent)
            } catch (ignored: Throwable) {
                Toast.makeText(activity, "startActivity failed.", Toast.LENGTH_SHORT).show()
                ignored.printStackTrace()
            }
        }

        private fun setupFileFlagPreference(preferenceKey: String, fileName: String) {
            findPreference<SwitchPreference>(preferenceKey)?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    if (newValue !is Boolean) return@OnPreferenceChangeListener false

                    val flag = activity?.getFileStreamPath(fileName)
                        ?: return@OnPreferenceChangeListener false
                    if (newValue) {
                        try {
                            flag.createNewFile()
                        } catch (e: IOException) {
                            false
                        }
                    } else {
                        !flag.exists() || flag.delete()
                    }
                }
        }

        private fun setupComponentStatePreference(
            preferenceKey: String,
            componentName: String,
            stateResolver: (Boolean) -> Int
        ) {
            findPreference<SwitchPreference>(preferenceKey)?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    if (newValue !is Boolean) return@OnPreferenceChangeListener false
                    try {
                        val act = activity ?: return@OnPreferenceChangeListener false
                        val newState = stateResolver(newValue)
                        act.packageManager.setComponentEnabledSetting(
                            ComponentName(act.packageName, componentName),
                            newState,
                            PackageManager.DONT_KILL_APP
                        )
                        true
                    } catch (ignored: Throwable) {
                        false
                    }
                }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VCommends.REQUEST_SELECT_APP && resultCode == RESULT_OK) {
            finish()
        }
    }

    companion object {
        private const val INSTALL_GMS_PREFERENCE = "advance_settings_install_gms"
        private const val FILE_MANAGE = "settings_file_manage"
        private const val DISABLE_XPOSED = "advance_settings_disable_xposed"
        private const val FINGERPRINT_INCONSISTENT = "advance_settings_fingerprint_action"
        private const val DISABLE_RESIDENT_NOTIFICATION =
            "advance_settings_disable_resident_notification"
        private const val HIDE_SETTINGS_PREFERENCE = "advance_settings_hide_settings"
        private const val ALLOW_FAKE_SIGNATURE = "advance_settings_allow_fake_signature"
        private const val DISABLE_INSTALLER_PREFERENCE = "advance_settings_disable_installer"
        const val ENABLE_LAUNCHER: String = "advance_settings_enable_launcher"
        const val DIRECTLY_BACK_PREFERENCE: String = "advance_settings_directly_back"
        private const val DESKTOP_SETTINGS_PREFERENCE = "settings_desktop"
    }
}