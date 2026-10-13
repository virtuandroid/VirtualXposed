package io.virtualapp.settings

import android.R
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceFragment
import android.widget.Toast
import com.android.launcher3.LauncherFiles
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.server.log.VLoggingManagerService
import io.virtualapp.VCommends
import io.virtualapp.abs.ui.VActivity
import io.virtualapp.compose.ui.AboutActivity
import io.virtualapp.compose.ui.ModuleActivity
import io.virtualapp.home.ListAppActivity
import io.virtualapp.utils.FileUtils
import java.io.File

/**
 * Settings activity for Launcher. Currently, implements the following setting: Allow rotation
 */
class SettingsActivity : VActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            // Display the fragment as the main content.
            fragmentManager
                .beginTransaction()
                .replace(R.id.content, SettingsFragment())
                .commit()
        }
    }

    /**
     * This fragment shows the launcher preferences.
     */
    class SettingsFragment : PreferenceFragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            preferenceManager.setSharedPreferencesName(LauncherFiles.SHARED_PREFERENCES_KEY)
            addPreferencesFromResource(io.virtualapp.R.xml.settings_preferences)

            findPreference(ADD_APP_PREFERENCE).setOnPreferenceClickListener { _: Preference? ->
                ListAppActivity.gotoListApp(activity)
                false
            }

            val moduleManage = findPreference(MODULE_MANAGE_PREFERENCE)
            moduleManage.setOnPreferenceClickListener { _: Preference? ->
                startActivity(Intent(activity, ModuleActivity::class.java))
                false
            }

            findPreference(ADVANCED_SETTINGS_PREFERENCE)
                .setOnPreferenceClickListener { _: Preference? ->
                    startActivity(Intent(activity, AdvancedSettingsActivity::class.java))
                    true
                }

            findPreference(PERMISSION_MANAGE_PREFERENCE)
                .setOnPreferenceClickListener { _: Preference? ->
                    startActivity(Intent(activity, PermissionsActivity::class.java))
                    true
                }

            findPreference(APP_MANAGE_PREFERENCE).setOnPreferenceClickListener { _: Preference? ->
                startActivity(Intent(activity, AppManageActivity::class.java))
                false
            }

            findPreference(TASK_MANAGE_PREFERENCE).setOnPreferenceClickListener { _: Preference? ->
                startActivity(Intent(activity, TaskManageActivity::class.java))
                false
            }

            findPreference(LOG_EXPORT_PREFERENCE).setOnPreferenceClickListener { _: Preference? ->
                val logDir = File(VLoggingManagerService.get().logDir)
                val latestLog = logDir.listFiles()?.toList()?.maxByOrNull { it.lastModified() }
                if (latestLog == null) {
                    Toast.makeText(context, "No log found!", Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener false
                }
                val result = FileUtils.saveFileToDownloads(activity, latestLog, "text/plain")
                if (result == null) {
                    Toast.makeText(context, "Unable to save log!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Saved file ${latestLog.name} to Downloads", Toast.LENGTH_LONG).show()
                }
                false
            }


            findPreference(ABOUT_PREFERENCE).setOnPreferenceClickListener { _: Preference? ->
                startActivity(Intent(activity, AboutActivity::class.java))
                false
            }

            findPreference(REBOOT_PREFERENCE).setOnPreferenceClickListener { _: Preference? ->
                val alertDialog = AlertDialog.Builder(activity)
                    .setTitle(io.virtualapp.R.string.settings_reboot_title)
                    .setMessage(getResources().getString(io.virtualapp.R.string.settings_reboot_content))
                    .setPositiveButton(
                        R.string.yes
                    ) { _: DialogInterface?, _: Int ->
                        VirtualCore.get().killAllApps()
                        Toast.makeText(
                            activity, io.virtualapp.R.string.reboot_tips_1,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton(R.string.no, null)
                    .create()
                try {
                    alertDialog.show()
                } catch (_: Throwable) {
                }
                false
            }

            val xposedEnabled = VirtualCore.get().isXposedEnabled
            if (!xposedEnabled) {
                preferenceScreen.removePreference(moduleManage)
            }
        }

        override fun startActivity(intent: Intent?) {
            try {
                super.startActivity(intent)
            } catch (ignored: Throwable) {
                Toast.makeText(activity, "startActivity failed.", Toast.LENGTH_SHORT).show()
                ignored.printStackTrace()
            }
        }

        companion object {
            private fun dismiss(dialog: ProgressDialog) {
                try {
                    dialog.dismiss()
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VCommends.REQUEST_SELECT_APP) {
            if (resultCode == RESULT_OK) {
                finish()
            }
        }
    }

    companion object {
        private const val ADD_APP_PREFERENCE = "settings_add_app"
        private const val MODULE_MANAGE_PREFERENCE = "settings_module_manage"
        private const val ADVANCED_SETTINGS_PREFERENCE = "settings_advanced"
        private const val PERMISSION_MANAGE_PREFERENCE = "settings_permission_manage"
        private const val APP_MANAGE_PREFERENCE = "settings_app_manage"
        private const val TASK_MANAGE_PREFERENCE = "settings_task_manage"
        private const val LOG_EXPORT_PREFERENCE = "settings_log_export"
        private const val ABOUT_PREFERENCE = "settings_about"
        private const val REBOOT_PREFERENCE = "settings_reboot"
    }
}
