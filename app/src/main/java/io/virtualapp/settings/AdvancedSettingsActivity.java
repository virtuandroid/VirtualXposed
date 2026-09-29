package io.virtualapp.settings;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.widget.Toast;

import com.android.launcher3.LauncherFiles;
import com.lody.virtual.client.env.Constants;

import io.virtualapp.R;
import io.virtualapp.VCommends;
import io.virtualapp.abs.ui.VActivity;
import io.virtualapp.gms.FakeGms;

import java.io.File;
import java.io.IOException;

public class AdvancedSettingsActivity extends VActivity {

    private static final String INSTALL_GMS_PREFERENCE = "advance_settings_install_gms";
    private static final String FILE_MANAGE = "settings_file_manage";
    private static final String DISABLE_XPOSED = "advance_settings_disable_xposed";
    private static final String DISABLE_RESIDENT_NOTIFICATION = "advance_settings_disable_resident_notification";
    private static final String HIDE_SETTINGS_PREFERENCE = "advance_settings_hide_settings";
    private static final String ALLOW_FAKE_SIGNATURE = "advance_settings_allow_fake_signature";
    private static final String DISABLE_INSTALLER_PREFERENCE = "advance_settings_disable_installer";
    public static final String ENABLE_LAUNCHER = "advance_settings_enable_launcher";
    public static final String DIRECTLY_BACK_PREFERENCE = "advance_settings_directly_back";
    private static final String DESKTOP_SETTINGS_PREFERENCE = "settings_desktop";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            // Display the fragment as the main content.
            getFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, new AdvancedSettingsFragment())
                .commit();
        }
    }

    /**
     * This fragment shows the launcher preferences.
     */
    public static class AdvancedSettingsFragment extends PreferenceFragment {

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getPreferenceManager().setSharedPreferencesName(LauncherFiles.SHARED_PREFERENCES_KEY);
            addPreferencesFromResource(R.xml.advanced_settings_preferences);

            findPreference(INSTALL_GMS_PREFERENCE).setOnPreferenceClickListener(preference -> {
                final boolean alreadyInstalled = FakeGms.isAlreadyInstalled(getActivity());
                if (alreadyInstalled) {
                    FakeGms.uninstallGms(getActivity());
                } else {
                    FakeGms.installGms(getActivity());
                }
                return true;
            });

            final SwitchPreference disableXposed = (SwitchPreference)
                findPreference(DISABLE_XPOSED);
            disableXposed.setOnPreferenceChangeListener((preference, newValue) -> {
                if (!(newValue instanceof Boolean)) {
                    return false;
                }

                final boolean on = (boolean) newValue;

                // 文件不存在代表是保守模式
                final File disableXposedFile = getActivity().getFileStreamPath(".disable_xposed");
                if (on) {
                    boolean success;
                    try {
                        success = disableXposedFile.createNewFile();
                    } catch (IOException e) {
                        success = false;
                    }
                    return success;
                } else {
                    return !disableXposedFile.exists() || disableXposedFile.delete();
                }
            });

            final SwitchPreference disableResidentNotification = (SwitchPreference)
                findPreference(DISABLE_RESIDENT_NOTIFICATION);
            disableResidentNotification.setOnPreferenceChangeListener(((preference, newValue) -> {
                if (!(newValue instanceof Boolean)) {
                    return false;
                }

                final boolean on = (boolean) newValue;

                final File flag = getActivity().getFileStreamPath(Constants.NO_NOTIFICATION_FLAG);
                if (on) {
                    boolean success;
                    try {
                        success = flag.createNewFile();
                    } catch (IOException e) {
                        success = false;
                    }
                    return success;
                } else {
                    return !flag.exists() || flag.delete();
                }
            }));

            final SwitchPreference allowFakeSignature = (SwitchPreference)
                findPreference(ALLOW_FAKE_SIGNATURE);
            allowFakeSignature.setOnPreferenceChangeListener((preference, newValue) -> {
                if (!(newValue instanceof Boolean)) {
                    return false;
                }

                final boolean on = (boolean) newValue;
                final File flag = getActivity().getFileStreamPath(Constants.FAKE_SIGNATURE_FLAG);
                if (on) {
                    boolean success;
                    try {
                        success = flag.createNewFile();
                    } catch (IOException e) {
                        success = false;
                    }
                    return success;
                } else {
                    return !flag.exists() || flag.delete();
                }
            });

            final SwitchPreference disableInstaller = (SwitchPreference)
                findPreference(DISABLE_INSTALLER_PREFERENCE);
            disableInstaller.setOnPreferenceChangeListener((preference, newValue) -> {
                if (!(newValue instanceof Boolean)) {
                    return false;
                }
                try {
                    final boolean disable = (boolean) newValue;
                    final PackageManager packageManager = getActivity().getPackageManager();
                    packageManager.setComponentEnabledSetting(
                            new ComponentName(getActivity().getPackageName(), "vxp.installer"),
                            !disable
                                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP);
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            });

            final SwitchPreference enableLauncher = (SwitchPreference)
                findPreference(ENABLE_LAUNCHER);
            enableLauncher.setOnPreferenceChangeListener((preference, newValue) -> {
                if (!(newValue instanceof Boolean)) {
                    return false;
                }
                try {
                    final boolean enable = (boolean) newValue;
                    final PackageManager packageManager = getActivity().getPackageManager();
                    packageManager.setComponentEnabledSetting(
                            new ComponentName(getActivity().getPackageName(), "vxp.launcher"),
                            enable
                                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP);
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            });

            findPreference(DESKTOP_SETTINGS_PREFERENCE).setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(getActivity(),
                            com.google.android.apps.nexuslauncher.SettingsActivity.class));
                return false;
            });

            if (android.os.Build.VERSION.SDK_INT < 25) {
                // Android NR1 below do not need this.
                getPreferenceScreen().removePreference(disableResidentNotification);
            }
        }

        @Override
        public void startActivity(Intent intent) {
            try {
                super.startActivity(intent);
            } catch (Throwable ignored) {
                Toast.makeText(getActivity(), "startActivity failed.", Toast.LENGTH_SHORT).show();
                ignored.printStackTrace();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == VCommends.REQUEST_SELECT_APP) {
            if (resultCode == RESULT_OK) {
                finish();
            }
        }
    }
}
