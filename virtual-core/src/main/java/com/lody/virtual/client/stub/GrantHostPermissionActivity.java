package com.lody.virtual.client.stub;

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.Html;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions;
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;
import androidx.appcompat.app.AppCompatActivity;

import com.lody.virtual.R;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.server.permission.RuntimePermission;
import com.lody.virtual.server.permission.VPermissionManager;

import java.util.Map;

/**
 * @author Alberto Lazari
 */
public class GrantHostPermissionActivity extends AppCompatActivity {

    private static final String TAG = GrantHostPermissionActivity.class.getSimpleName();

    public static final Map<String, Integer> DEPRECATED_PERMISSIONS = Map.of(
        "android.permission.READ_EXTERNAL_STORAGE", 29,
        "android.permission.WRITE_EXTERNAL_STORAGE", 29
    );

    public static final String EXTRA_PERMISSION_NAME =
        "com.lody.virtual.client.stub.extra.REQUEST_HOST_PERMISSION_NAME";
    public static final String EXTRA_PERMISSION_GROUP_NAME =
        "com.lody.virtual.client.stub.extra.REQUEST_HOST_PERMISSION_GROUP_NAME";
    public static final String EXTRA_RESULT =
        "com.lody.virtual.client.stub.extra.REQUEST_HOST_PERMISSION_RESULT";
    public static final String EXTRA_APP_NAME =
        "com.lody.virtual.client.stub.extra.REQUEST_HOST_PERMISSION_APP_NAME";
    public static final String EXTRA_APP_UID =
        "com.lody.virtual.client.stub.extra.REQUEST_HOST_PERMISSION_APP_UID";

    private RuntimePermission permission;
    private String groupName;
    private String appName;
    private boolean isGranted = false;
    private ActivityResultLauncher requestPermissionLauncher;
    private ActivityResultLauncher requestPermissionGroupsLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        final var permissionName = intent.getStringExtra(EXTRA_PERMISSION_NAME);
        groupName = intent.getStringExtra(EXTRA_PERMISSION_GROUP_NAME);
        final int uid = intent.getIntExtra(EXTRA_APP_UID, 0);
        appName = intent.getStringExtra(EXTRA_APP_NAME);
        permission = VPermissionManager.get()
            .getAppPermissions(uid)
            .getPermission(permissionName, RuntimePermission.class);
        final String message = getString(R.string.host_permission_denied_dialog_message,
                appName, permission.getReadableName());

        requestPermissionLauncher = registerForActivityResult(new RequestPermission(), isGranted -> {
            this.isGranted = isGranted;
            onRequestPermissionsResult();
        });
        requestPermissionGroupsLauncher = registerForActivityResult(
                new RequestMultiplePermissions(), results -> {
                    setResultAndFinish(RESULT_OK);
                });

        final Integer maxSdk = DEPRECATED_PERMISSIONS.get(permissionName);
        if (maxSdk != null && maxSdk < Build.VERSION.SDK_INT) {
            // Just deny deprecated permissions, no need to notify the user
            setResultAndFinish(RESULT_OK);
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.host_permission_denied_dialog_title)
            .setMessage(Html.fromHtml(message))
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                dialog.dismiss();
                requestPermissionLauncher.launch(permissionName);
            })
            .setOnCancelListener(view -> {
                setResultAndFinish(RESULT_CANCELED);
            })
            .create()
            .show();
    }

    private void onRequestPermissionsResult() {
        if (isGranted) {
            // Ignore location permissions, they are supposed to be granted individually.
            if (groupName == null || groupName.equals(Manifest.permission_group.LOCATION)) {
                setResultAndFinish(RESULT_OK);
                return;
            }
            // Automatically request all permissions of the group, to prevent showing
            // the missing permission warning in the future.
            // Runtime permissions are granted only after being requested, even if one in the
            // group was already granted. In that case the dialog simply won't show up.
            final var groupPermissions = permission.getPermissionGroup()
                .getPermissions()
                .stream()
                .map(RuntimePermission::getName)
                .toArray(String[]::new);
            requestPermissionGroupsLauncher.launch(groupPermissions);
            setResultAndFinish(RESULT_OK);
        } else if (!shouldShowRequestPermissionRationale(permission.getName())) {
            // Permission could be permanently denied (or not set).
            // Inform the user and link to permissions settings
            final String message = getString(
                    R.string.host_permission_permanently_denied_dialog_message,
                    permission.getReadableName(), appName);
            new AlertDialog.Builder(this)
                .setTitle(R.string.host_permission_denied_dialog_title)
                .setMessage(Html.fromHtml(message))
                .setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
                    final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    final Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                    dialog.dismiss();
                    setResultAndFinish(RESULT_OK);
                })
                .setNegativeButton(android.R.string.ok, (dialog, which) -> {
                    dialog.dismiss();
                    setResultAndFinish(RESULT_OK);
                })
                .setOnCancelListener(view -> {
                    setResultAndFinish(RESULT_CANCELED);
                })
                .create()
                .show();
        } else {
            setResultAndFinish(RESULT_OK);
        }
    }

    /**
     * Set the activity result and finish it
     */
    private void setResultAndFinish(final int resultCode) {
        final Intent result = new Intent();
        result.putExtra(EXTRA_PERMISSION_NAME, permission.getName());
        result.putExtra(EXTRA_PERMISSION_GROUP_NAME, groupName);
        result.putExtra(EXTRA_RESULT, isGranted ? PERMISSION_GRANTED : PERMISSION_DENIED);
        setResult(resultCode, result);
        finish();
    }
}
