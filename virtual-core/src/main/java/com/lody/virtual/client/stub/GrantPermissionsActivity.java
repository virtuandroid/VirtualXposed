package com.lody.virtual.client.stub;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.NameNotFoundException;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Html;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import static com.lody.virtual.server.permission.Permission.Status;

import com.lody.virtual.R;
import com.lody.virtual.client.hook.proxies.am.ActivityManagerStub;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VBinder;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.server.permission.RuntimePermission;
import com.lody.virtual.server.permission.VPermissionManager;

import mirror.android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Alberto Lazari
 */
public class GrantPermissionsActivity extends Activity {

    private static final String TAG = GrantPermissionsActivity.class.getSimpleName();
    private static final String COARSE_LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION;
    private static final String FINE_LOCATION = Manifest.permission.ACCESS_FINE_LOCATION;

    public static final String ACTION_REQUEST_PERMISSIONS = PackageManager
        .ACTION_REQUEST_PERMISSIONS.get();
    public static final String EXTRA_PERMISSIONS_NAMES = PackageManager
        .EXTRA_REQUEST_PERMISSIONS_NAMES.get();
    public static final String EXTRA_RESULTS = PackageManager
        .EXTRA_REQUEST_PERMISSIONS_RESULTS.get();
    public static final String EXTRA_APP_NAME =
        "com.lody.virtual.client.stub.extra.REQUEST_PERMISSIONS_APP_NAME";
    public static final String EXTRA_APP_UID =
        "com.lody.virtual.client.stub.extra.REQUEST_PERMISSIONS_APP_UID";

    private final VPermissionManager permissionManager = VPermissionManager.get();
    private final Set<String> groupsToSkip = new HashSet<>();

    private String[] permissions;
    private int uid;
    private String appName;
    private int[] grantResults;
    private int requestNumber = 0;
    private boolean alertHostDenied;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS_NAMES);
        uid = intent.getIntExtra(EXTRA_APP_UID, 0);
        appName = intent.getStringExtra(EXTRA_APP_NAME);
        grantResults = new int[permissions.length];
        alertHostDenied = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(getString(R.string.alert_host_permission_denied_preference), true);
        Arrays.fill(grantResults, PERMISSION_DENIED);

        // When requesting multiple permissions
        if (permissions.length > 1) {
            Arrays.stream(permissions).forEach(permissionName -> {
                if (VPermissionManager.BACKGROUND_PERMISSIONS.contains(permissionName)) {
                    // Do not show dialog if requesting any background permission
                    setResultAndFinish(RESULT_CANCELED);
                }
            });
        }

        grantNextPermission();
    }

    /**
     * Try to request the permission of index @requestNumber.
     * Check that the permission is granted for host app, otherwise request that one too
     */
    private void grantNextPermission() {
        final String permissionName = permissions[requestNumber];
        final var group = permissionManager
            .getPermission(permissionName, uid, RuntimePermission.class)
            .getPermissionGroup();
        final var groupName = group != null ? group.getName() : null;
        if (groupName != null && (
                group.getStatus() == Status.DENIED || groupsToSkip.contains(groupName)
        )) {
            setGrantResult(permissionManager.checkPermission(groupName, uid));
            return;
        }
        if (VPermissionManager.AUTO_GRANT_MAP.containsKey(permissionName)) {
            final var weakPermission = permissionManager.getPermission(
                    VPermissionManager.AUTO_GRANT_MAP.get(permissionName), uid);

            if (weakPermission != null && !weakPermission.isGranted()) {
                setGrantResult(permissionManager.checkPermission(permissionName, uid));
                return;
            }
        }
        // Check host permission status
        if (checkSelfPermission(permissionName) == PERMISSION_DENIED) {
            if (!alertHostDenied) {
                setGrantResult(PERMISSION_DENIED);
                return;
            }
            final int requestCode = requestNumber;
            final Intent intent = new Intent(this, GrantHostPermissionActivity.class);
            intent.putExtra(GrantHostPermissionActivity.EXTRA_PERMISSION_NAME, permissionName);
            intent.putExtra(GrantHostPermissionActivity.EXTRA_PERMISSION_GROUP_NAME, groupName);
            intent.putExtra(GrantHostPermissionActivity.EXTRA_APP_UID, uid);
            intent.putExtra(GrantHostPermissionActivity.EXTRA_APP_NAME, appName);
            startActivityForResult(intent, requestCode);
            return;
        }
        grantPermission(permissionName);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != requestNumber || resultCode != RESULT_OK) {
            setResultAndFinish(RESULT_CANCELED);
            return;
        }
        final String permissionName = data.getStringExtra(
                GrantHostPermissionActivity.EXTRA_PERMISSION_NAME);
        final String groupName = data.getStringExtra(
                GrantHostPermissionActivity.EXTRA_PERMISSION_GROUP_NAME);
        final int grantResult = data.getIntExtra(
                GrantHostPermissionActivity.EXTRA_RESULT, PERMISSION_DENIED);

        if (grantResult == PERMISSION_GRANTED) {
            // Permission is now granted to host, can request
            grantPermission(permissionName);
        } else {
            if (groupName != null) {
                // Don't keep requesting permissions for the same group (the dialog is always the same)
                groupsToSkip.add(groupName);
            }
            // Do not proceed, permission is still denied to host
            setGrantResult(PERMISSION_DENIED);
        }
    }

    /**
     * Request the @permissionName, showing the request dialog, if necessary
     */
    private void grantPermission(final String permissionName) {
        final var permission = permissionManager.getPermission(permissionName, uid,
                RuntimePermission.class);
        final var group = permission.getPermissionGroup();
        if (!permission.needsRequestDialog()) {
            switch (permission.getStatus()) {
                case Status.ALWAYS_ASK, Status.UNREQUESTED -> {
                    if (group != null && group.isGranted()) {
                        permissionManager.allowPermission(permissionName, uid);
                    }
                }
            };
            setGrantResult(permission.isGranted() ? PERMISSION_GRANTED : PERMISSION_DENIED);
            return;
        }
        if (permission.isOverridden()) {
            // If not linked to group, show request dialog for the individual permission
            showPermissionRequestDialog(permission);
            return;
        }
        if (group != null && group.isGranted()) {
            // Group is already granted, grant permission too
            permissionManager.allowPermission(permissionName, uid);
            return;
        }
        // Show dialog for the permission group
        showGroupRequestDialog(permission);
    }

    /**
     * Create and show the request permission dialog for @permissionName
     */
    private void showPermissionRequestDialog(final RuntimePermission permission) {
        final var permissionName = permission.getName();
        var message = getString(R.string.permission_request_dialog_message,
                    appName, permission.getReadableName());
        if (permissionName.equals(COARSE_LOCATION) || permissionName.equals(FINE_LOCATION)) {
            final var locationType = switch (permissionName) {
                case COARSE_LOCATION -> "coarse";
                case FINE_LOCATION -> "fine";
                default -> "";
            };
            final int descriptionRes = getResources().getIdentifier(locationType +
                    "_location_permission_description", "string", getPackageName());
            message = getString(R.string.permission_group_request_dialog_message,
                    appName, getString(descriptionRes));
        }
        createRequestDialog(permission, message).show();
    }

    /**
     * Create and show the request permission dialog for permission @group
     */
    private void showGroupRequestDialog(final RuntimePermission permission) {
        final var group = permission.getPermissionGroup();
        final var groupName = group.getName();
        final var pm = getPackageManager();
        String message = null;
        // Get group info
        try {
            final var groupInfo = pm.getPermissionGroupInfo(groupName, 0);
            message = getString(R.string.permission_group_request_dialog_message,
                    appName, groupInfo.loadDescription(pm));
        } catch (NameNotFoundException e) {
            VLog.e(TAG, "Description for permission group %s not found", groupName);
            e.printStackTrace();
        }
        if (message == null) {
            message = getString(R.string.permission_request_dialog_message,
                    appName, group.getReadableName());
        }
        createRequestDialog(permission, message).show();
    }

    private Dialog createRequestDialog(final RuntimePermission permission, final String message) {
        final var permissionName = permission.getName();
        final var dialog = new Dialog(this);
        // Remove title bar from layout
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.permission_request_dialog);
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_background);

        final var icon = (ImageView) dialog.findViewById(R.id.dialog_permission_icon);
        final var messageView = (TextView) dialog.findViewById(R.id.dialog_message);
        final var allowButton = (Button) dialog.findViewById(R.id.dialog_button_allow);
        final var onlyOnceButton = (Button) dialog.findViewById(R.id.dialog_button_only_once);
        final var doNotAllowButton = (Button) dialog.findViewById(R.id.dialog_button_do_not_allow);

        icon.setImageResource(permission.getGroupIconRes());
        messageView.setText(Html.fromHtml(message));
        allowButton.setOnClickListener(view -> {
            permissionManager.allowPermission(permissionName, uid);
            dismissWith(PERMISSION_GRANTED, dialog);
        });
        onlyOnceButton.setOnClickListener(view -> {
            permissionManager.allowPermissionOnce(permissionName, uid);
            dismissWith(PERMISSION_GRANTED, dialog);
        });
        final var group = permission.getPermissionGroup();
        doNotAllowButton.setOnClickListener(view -> {
            permissionManager.doNotAllowPermission(permissionName, uid);
            if (group != null) {
                // Don't keep requesting permissions for the same group
                groupsToSkip.add(group.getName());
            }
            dismissWith(PERMISSION_DENIED, dialog);
        });
        dialog.setOnCancelListener(view -> {
            setResultAndFinish(RESULT_CANCELED);
        });

        final var hasBackgroundPermission = permission.hasBackgroundPermission();
        if (hasBackgroundPermission) {
            allowButton.setText(getString(R.string.permission_manage_while_using_app));
        }
        if (group != null && group.getName().equals(Manifest.permission_group.READ_MEDIA_VISUAL)) {
            allowButton.setText(getString(R.string.permission_manage_allow_all));
        }
        if (!hasBackgroundPermission || permissionName.equals(Manifest.permission.BODY_SENSORS)) {
            onlyOnceButton.setVisibility(TextView.GONE);
        }
        final var coarseLocation = permissionManager.getPermission(COARSE_LOCATION, uid,
                RuntimePermission.class);
        if (permissionName.equals(FINE_LOCATION) && coarseLocation != null && coarseLocation.isGranted()) {
            final var locationChangeMessage = getString(
                    R.string.location_change_request_dialog_message, appName);
            messageView.setText(Html.fromHtml(locationChangeMessage));
            if (coarseLocation.isGrantedOnce()) {
                allowButton.setVisibility(TextView.GONE);
            } else {
                allowButton.setText(getString(R.string.permission_manage_change_to_precise));
            }
            doNotAllowButton.setText(getString(R.string.permission_manage_keep_approximate));
        }
        if (VPermissionManager.BACKGROUND_PERMISSIONS.contains(permissionName)) {
            allowButton.setText(getString(R.string.permission_manage_allow_all_time));
        }

        return dialog;
    }

    /**
     * Dismiss the permission request @dialog and set the result
     */
    private void dismissWith(final int grantResult, final Dialog dialog) {
        dialog.dismiss();
        setGrantResult(grantResult);
    }

    /**
     * Set the request dialog result and continue the request loop, if more permissions are still
     * to be granted
     */
    private void setGrantResult(final int grantResult) {
        grantResults[requestNumber++] = grantResult;
        if (requestNumber < permissions.length) {
            grantNextPermission();
        } else {
            setResultAndFinish(RESULT_OK);
        }
    }

    /**
     * Set the activity result and finish it
     */
    private void setResultAndFinish(final int resultCode) {
        final Intent result = new Intent(ACTION_REQUEST_PERMISSIONS);
        result.putExtra(EXTRA_PERMISSIONS_NAMES, permissions);
        result.putExtra(EXTRA_RESULTS, grantResults);
        setResult(resultCode, result);
        finish();
    }
}
