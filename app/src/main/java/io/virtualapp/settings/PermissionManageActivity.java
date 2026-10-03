package io.virtualapp.settings;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.PackageManager.PERMISSION_DENIED;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceViewHolder;

import static com.lody.virtual.server.permission.Permission.Status.*;

import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.server.permission.InstallPermission;
import com.lody.virtual.server.permission.RuntimePermission;
import com.lody.virtual.server.permission.Permission;
import com.lody.virtual.server.permission.PermissionGroup;
import com.lody.virtual.server.permission.VPermissionManager;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.virtualapp.R;
import io.virtualapp.abs.ui.VActivity;
import io.virtualapp.abs.ui.VUiKit;
import io.virtualapp.widgets.NonScrollListView;

/**
 * @author Alberto Lazari
 * @date 14/8/24
 */
public class PermissionManageActivity extends VActivity {
    private static final String TAG = PermissionManageActivity.class.getSimpleName();

    public static final String EXTRA_APP_NAME =
        "io.virtualapp.settings.extra.PERMISSION_MANAGE_APP_NAME";
    public static final String EXTRA_APP_UID =
        "io.virtualapp.settings.extra.PERMISSION_MANAGE_APP_UID";
    public static final String EXTRA_PERMISSIONS_TYPE =
        "io.virtualapp.settings.extra.PERMISSION_MANAGE_PERMISSIONS_TYPE";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final var extras = getIntent().getExtras();
        final String appName = extras.getString(EXTRA_APP_NAME);
        final int uid = extras.getInt(EXTRA_APP_UID);
        final var type = (Class<? extends Permission>) extras
            .getSerializable(EXTRA_PERMISSIONS_TYPE);

        setTitle(appName);

        getSupportFragmentManager()
            .beginTransaction()
            .replace(android.R.id.content, new PermissionsFragment(type, appName, uid))
            .commit();
    }

    public static class PermissionsFragment extends PreferenceFragmentCompat {

        private static final String ALLOWED_CATEGORY_KEY = "allowed";
        private static final String ASK_CATEGORY_KEY = "ask";
        private static final String DENIED_CATEGORY_KEY = "not_allowed";
        private static final String FOLLOW_GROUP_CATEGORY_KEY = "follow_group";

        private static final Map<Class<? extends Permission>, String> TYPE_TO_NAME_MAP = Map.of(
            InstallPermission.class, "install",
            RuntimePermission.class, "runtime"
        );

        private final VPermissionManager permissionManager = VPermissionManager.get();
        private final Class<? extends Permission> type;
        private final String appName;
        private final int uid;

        private Activity ctx;

        private Set<PreferenceCategory> categories;
        private PreferenceCategory allowedCategory;
        private PreferenceCategory askCategory;
        private PreferenceCategory deniedCategory;
        private PreferenceCategory groupCategory;

        public PermissionsFragment(final Class<? extends Permission> type, final String appName,
                final int uid) {

            this.type = type;
            this.appName = appName;
            this.uid = uid;
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.permissions_manage_preferences, rootKey);

            allowedCategory = (PreferenceCategory) findPreference(ALLOWED_CATEGORY_KEY);
            askCategory = (PreferenceCategory) findPreference(ASK_CATEGORY_KEY);
            deniedCategory = (PreferenceCategory) findPreference(DENIED_CATEGORY_KEY);
            groupCategory = (PreferenceCategory) findPreference(FOLLOW_GROUP_CATEGORY_KEY);

            categories = Set.of(allowedCategory, askCategory, deniedCategory, groupCategory);

            ctx = getActivity();

            // Sort permissions
            new TreeMap<>(permissionManager.getAppPermissions(uid).getAll(type))
                .forEach((name, permission) -> {
                    final var preference = createPermissionPreference(permission);
                    getCategoryFor(permission).addPreference(preference);
                });

            updateNoneTexts();

            final var overrideCategory = (PreferenceCategory)
                findPreference("override_permissions");
            if (!type.equals(PermissionGroup.class)) {
                ctx.setTitle("Override " + TYPE_TO_NAME_MAP.get(type) + " permissions");
                overrideCategory.setVisible(false);
                return;
            }

            // Set listener on permissions override settings
            TYPE_TO_NAME_MAP.keySet().forEach(overrideType -> {
                findPreference("override_" + TYPE_TO_NAME_MAP.get(overrideType))
                    .setOnPreferenceClickListener(preference -> {
                        final Intent intent = new Intent(ctx, PermissionManageActivity.class);
                        intent.putExtra(EXTRA_APP_NAME, appName);
                        intent.putExtra(EXTRA_APP_UID, uid);
                        intent.putExtra(EXTRA_PERMISSIONS_TYPE, overrideType);
                        startActivity(intent);
                        return true;
                    });
            });
            // Create an invisible preference to show the divider under last element
            final var phantomPreference = new Preference(ctx) {
                @Override
                public void onBindViewHolder(PreferenceViewHolder holder) {
                    super.onBindViewHolder(holder);
                    holder.setDividerAllowedAbove(true);
                    final var itemView = holder.itemView;
                    final var params = itemView.getLayoutParams();
                    params.height = 0;
                    itemView.setLayoutParams(params);
                }
            };
            overrideCategory.addPreference(phantomPreference);
        }

        private PreferenceCategory getCategoryFor(final Permission permission) {
            if (permission instanceof RuntimePermission runtimePermission
                    && !runtimePermission.isOverridden()) {
                return groupCategory;
            }
            return switch (permission.getStatus()) {
                case GRANTED -> allowedCategory;
                case ALWAYS_ASK -> askCategory;
                case UNREQUESTED -> deniedCategory;
                case DENIED -> deniedCategory;
            };
        }

        private void updateCategories() {
            categories.forEach(category -> {
                // Update permissions positions
                for (int i = 0; i < category.getPreferenceCount(); ++i) {
                    final var preference = category.getPreference(i);
                    if (preference instanceof PermissionPreference permissionPreference) {
                        final var permission = permissionManager.getPermission(
                                permissionPreference.getPermission().getName(), uid);
                        final var newCategory = getCategoryFor(permission);
                        if (category != newCategory) {
                            category.removePreference(permissionPreference);
                            // Iterating on category and the current item was removed.
                            // Keep index at the same position.
                            --i;
                            newCategory.addPreference(permissionPreference);
                        }
                    }
                }
            });
            updateNoneTexts();
        }

        private void updateNoneTexts() {
            categories.forEach(category -> {
                final var noneTextKey = "none_" + category.getKey() + "_text";
                switch (category.getKey()) {
                    case ALLOWED_CATEGORY_KEY, DENIED_CATEGORY_KEY -> {
                        final var count = category.getPreferenceCount();
                        final var noneText = findPreference(noneTextKey);
                        if (count == 1 && noneText != null) {
                            // The only preference is the none text, leave it that way
                            return;
                        }
                        if (count == 0) {
                            hideCategory(category, true);
                        } else {
                            category.setVisible(true);
                            if (noneText != null) {
                                category.removePreference(noneText);
                            }
                        }
                    }
                    default -> {
                        if (category.getPreferenceCount() == 0) {
                            hideCategory(category, false);
                        } else {
                            category.setVisible(true);
                        }
                    }
                };
            });
        }

        private void hideCategory(final PreferenceCategory category, final boolean showNoneText) {
            // Do not show "No permissions allowed/denied" for runtime permissions, because they
            // could all be following their group
            if (!showNoneText || type.equals(RuntimePermission.class)) {
                category.setVisible(false);
                return;
            }
            final var categoryKey = category.getKey();
            final var noneTextKey = "none_" + categoryKey + "_text";
            final int titleRes = getResources().getIdentifier(
                    "permission_manage_none_" + categoryKey, "string", ctx.getPackageName());
            final var noneText = new Preference(ctx) {
                @Override
                public void onBindViewHolder(PreferenceViewHolder holder) {
                    super.onBindViewHolder(holder);
                    holder.setDividerAllowedBelow(false);
                }
            };
            noneText.setKey(noneTextKey);
            noneText.setTitle(titleRes);
            noneText.setSelectable(false);
            noneText.setLayoutResource(R.layout.none_permissions);

            category.addPreference(noneText);
        }

        private PermissionPreference createPermissionPreference(final Permission permission) {
            final var name = permission.getName();
            final var readableName = permission.getReadableName();
            final var groupIconRes = permission instanceof RuntimePermission runtimePermission
                ? runtimePermission.getGroupIconRes()
                : PermissionGroup.getGroupIconRes(name);
            final var preference = new PermissionPreference(ctx, permission);
            preference.setKey("permission_manage_preference_" + name);
            preference.setTitle(readableName);
            preference.setSummary(name);
            preference.setLayoutResource(R.layout.item_permission_manage);
            preference.setIcon(groupIconRes);
            return preference;
        }

        private class PermissionPreference extends Preference {

            private final Permission permission;

            private PreferenceViewHolder holder;
            private ImageView iconView;

            public PermissionPreference(final Context ctx, final Permission permission) {
                super(ctx);
                this.permission = permission;
            }

            @Override
            public void onBindViewHolder(final PreferenceViewHolder holder) {
                super.onBindViewHolder(holder);
                this.holder = holder;

                holder.setDividerAllowedAbove(true);
                holder.setDividerAllowedBelow(true);

                if (iconView == null) {
                    iconView = (ImageView) holder.findViewById(android.R.id.icon);
                }
            }

            @Override
            protected void onClick() {
                super.onClick();
                showContextMenu();
            }

            public Permission getPermission() {
                return permission;
            }


            private void showContextMenu() {
                final PopupMenu popupMenu = new PopupMenu(ctx, holder.itemView);
                if (permission instanceof InstallPermission) {
                    popupMenu.inflate(R.menu.install_permission_manage_menu);
                } else if (permission instanceof RuntimePermission runtimePermission) {
                    popupMenu.inflate(R.menu.runtime_permission_manage_menu);
                    if (runtimePermission.hasPermissionGroup()) {
                        // Show "Follow group" menu action
                        popupMenu.getMenu()
                            .findItem(R.id.action_group)
                            .setVisible(true);
                    }
                } else if (permission instanceof PermissionGroup) {
                    popupMenu.inflate(R.menu.permission_group_manage_menu);
                }

                popupMenu.setOnMenuItemClickListener(item -> {
                    handleMenuChoice(item.getItemId());
                    return true;
                });
                try {
                    popupMenu.show();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }

            private void handleMenuChoice(final int choice) {
                if (choice == R.id.action_group) {
                    permissionManager.updatePermission(RuntimePermission.class,
                        permission.getName(), uid,
                        newPermission -> {
                            final var group = newPermission.getPermissionGroup();
                            newPermission.followGroup();
                            newPermission.setStatus(group.getStatus());
                        });
                    updateCategories();
                    return;
                }

                if (permission instanceof RuntimePermission runtimePermission) {
                    runtimePermission.override();
                }
                if (choice == R.id.action_allow) {
                    permissionManager.allowPermission(permission.getName(), uid);
                } else if (choice == R.id.action_ask) {
                    permissionManager.updatePermission(permission.getName(), uid,
                        newPermission -> {
                            newPermission.setStatus(ALWAYS_ASK);
                        });
                } else if (choice == R.id.action_do_not_allow) {
                    permissionManager.doNotAllowPermission(permission.getName(), uid);
                }
                updateCategories();
            }

        }
    }
}
