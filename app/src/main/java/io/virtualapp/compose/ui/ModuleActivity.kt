package io.virtualapp.compose.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import io.virtualapp.compose.ui.ModuleViewModel.Companion.reindexScopes
import kotlinx.collections.immutable.PersistentList
import org.matrix.vector.ipc.HookScope

class ModuleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppsScreen(viewModel(factory = viewModelFactory {
                        initializer {
                            ModuleViewModel().also {
                                it.loadData(this@ModuleActivity.packageManager)
                            }
                        }
                    }))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    viewModel: ModuleViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val onIntent = viewModel::processIntent

    AnimatedContent(
        targetState = state.selectedModule,
        transitionSpec = {
            if (targetState != null) {
                (fadeIn(animationSpec = tween(150)) + slideInHorizontally { it / 2 }) togetherWith
                        (fadeOut(animationSpec = tween(150)) + slideOutHorizontally { -it / 2 })
            } else {
                (fadeIn(animationSpec = tween(150)) + slideInHorizontally { -it / 2 }) togetherWith
                        (fadeOut(animationSpec = tween(150)) + slideOutHorizontally { it / 2 })
            }
        },
        label = "IfElseTransition",
        contentKey = { it?.packageName }
    ) { currentModule ->
        if (currentModule != null) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(currentModule.appName) },
                        navigationIcon = {
                            IconButton(onClick = { onIntent.invoke(MainIntent.OnItemClicked(null)) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    )
                }
            ) { innerPadding ->
                XposedDetailScreen(
                    moduleInfo = currentModule,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    onIntent = onIntent,
                )
            }
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Xposed Modules") }
                    )
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    SettingsSearch(state.searchQuery, { newQuery ->
                        viewModel.processIntent(MainIntent.OnSearchQueryChanged(newQuery))
                    })
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        items(
                            items = state.modules,
                            key = { it.packageInfo.packageName }
                        ) { moduleInfo ->
                            XposedListItem(
                                moduleInfo = moduleInfo,
                                onClick = { onIntent(MainIntent.OnItemClicked(moduleInfo)) },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(durationMillis = 150),
                                    fadeOutSpec = tween(durationMillis = 100),
                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun XposedListItem(
    moduleInfo: ModuleInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = moduleInfo.isEnabled

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (enabled) 2.dp else 0.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .alpha(if (enabled) 1f else 0.38f), // Standard M3 disabled opacity for content
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (moduleInfo.icon != null) {
                Image(
                    painter = rememberDrawablePainter(moduleInfo.icon),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
            }

            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
            ) {
                Text(
                    text = moduleInfo.appName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = moduleInfo.packageInfo.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "App Version: v${moduleInfo.packageInfo.versionName ?: "N/A"} | Xposed Min API: ${moduleInfo.xposedMinVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun XposedDetailScreen(
    moduleInfo: ModuleInfo,
    onIntent: (MainIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val isEnabled = moduleInfo.isEnabled
    val pkg = moduleInfo.packageInfo
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (moduleInfo.icon != null) {
            Image(
                painter = rememberDrawablePainter(moduleInfo.icon),
                contentDescription = null,
                modifier = Modifier.size(80.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(text = moduleInfo.appName, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = pkg.packageName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isEnabled) "Module Enabled" else "Module Disabled",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isEnabled) "Disable to stop loading this module" else "Enable to load this module on app startup",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        onIntent.invoke(MainIntent.OnModuleEnable(enabled = checked, moduleInfo))
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        HookScopesSection(
            moduleInfo = moduleInfo,
            onIntent = onIntent
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Xposed Module Details",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))

                DebugInfoRow("Min Xposed Version", moduleInfo.xposedMinVersion)
                DebugInfoRow("Module Description", moduleInfo.xposedDescription)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Package Information",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkg.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    pkg.versionCode.toString()
                }
                DebugInfoRow("Version Name", pkg.versionName ?: "N/A")
                DebugInfoRow("Version Code", versionCode)
                DebugInfoRow(
                    "Target SDK",
                    pkg.applicationInfo?.targetSdkVersion?.toString() ?: "N/A"
                )
                DebugInfoRow("Min SDK", pkg.applicationInfo?.minSdkVersion?.toString() ?: "N/A")
                DebugInfoRow("APK Path", pkg.applicationInfo?.sourceDir ?: "N/A")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun HookScopesSection(
    moduleInfo: ModuleInfo,
    onIntent: (MainIntent) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    val scopes = moduleInfo.hookScopes

    fun onScopesUpdated(newScopes: List<HookScope>) {
        onIntent(MainIntent.OnModuleScope(newScopes, moduleInfo))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hook Scopes",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Restrict module package hooking. By default all hooks are allowed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Hook Scope",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (scopes.isEmpty()) {
                Column {
                    Button(onClick = {
                        onIntent.invoke(MainIntent.AddRecommendedScopes(moduleInfo))
                    }, modifier = Modifier.fillMaxSize()) {
                        Text(text = "Add recommended hook scopes")
                    }
                    Text(
                        text = "No hook scopes configured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Column {
                    LookaheadScope {
                        scopes.forEachIndexed { index, scope ->
                            key(scope.id) {
                                HookScopeItem(
                                    scope = scope,
                                    index = index,
                                    totalSize = scopes.size,
                                    onToggleActive = { active ->
                                        val updated = scopes.toMutableList().apply {
                                            this[index] = this[index].copy(active = active)
                                        }
                                        onScopesUpdated(updated)
                                    },
                                    onToggleAction = {
                                        val nextAction =
                                            if (scope.action == HookScope.ACTION_ALLOW) {
                                                HookScope.ACTION_BLOCK
                                            } else {
                                                HookScope.ACTION_ALLOW
                                            }
                                        val updated = scopes.toMutableList().apply {
                                            this[index] = this[index].copy(action = nextAction)
                                        }
                                        onScopesUpdated(updated)
                                    },
                                    onDelete = {
                                        val updated =
                                            scopes.toMutableList().apply { removeAt(index) }
                                        onScopesUpdated(updated.reindexScopes())
                                    },
                                    onMoveUp = {
                                        if (index > 0) {
                                            val updated = scopes.toMutableList()
                                            val item = updated.removeAt(index)
                                            updated.add(index - 1, item)
                                            onScopesUpdated(updated.reindexScopes())
                                        }
                                    },
                                    onMoveDown = {
                                        if (index < scopes.size - 1) {
                                            val updated = scopes.toMutableList()
                                            val item = updated.removeAt(index)
                                            updated.add(index + 1, item)
                                            onScopesUpdated(updated.reindexScopes())
                                        }
                                    },
                                    modifier = Modifier.animateBounds(lookaheadScope = this@LookaheadScope)
                                )
                                if (index < scopes.size - 1) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddScopeDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, pattern, action ->
                val newScope = HookScope(
                    name = name,
                    scope = pattern,
                    action = action,
                    active = true,
                    priority = scopes.size,
                )
                onScopesUpdated(scopes + newScope)
                showAddDialog = false
            }
        )
    }
}


@Composable
private fun HookScopeItem(
    scope: HookScope,
    index: Int,
    totalSize: Int,
    modifier: Modifier = Modifier,
    onToggleActive: (Boolean) -> Unit = {},
    onToggleAction: () -> Unit = {},
    onDelete: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
) {
    val isAllow = scope.action == HookScope.ACTION_ALLOW
    val isEnabled = scope.active

    val actionColor = when {
        !isEnabled -> MaterialTheme.colorScheme.outline
        isAllow -> Color(0xFF2E7D32)
        else -> Color(0xFFC62828)
    }

    val actionIcon = if (isAllow) Icons.Default.CheckCircle else Icons.Default.Block

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isEnabled) actionColor.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (scope.active) {
                IconButton(
                    onClick = onToggleAction,
                    enabled = isEnabled
                ) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = if (isAllow) "Allow Scope" else "Block Scope",
                        tint = actionColor
                    )
                }
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Delete Scope",
                    )
                }
            }


            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                if (scope.name.isNullOrBlank()) {
                    Text(
                        text = "No name",
                        style = MaterialTheme.typography.titleSmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Text(
                        text = scope.name ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    )
                }
            }

            Column {
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move Up",
                        tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant else Color.Transparent
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < totalSize - 1,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move Down",
                        tint = if (index < totalSize - 1) MaterialTheme.colorScheme.onSurfaceVariant else Color.Transparent
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Switch(
                checked = scope.active,
                onCheckedChange = onToggleActive,
                modifier = Modifier.scale(0.8f)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = scope.scope,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (isEnabled) actionColor else MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun AddScopeDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, scope: String, action: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var action by remember { mutableIntStateOf(HookScope.ACTION_ALLOW) }
    var patternError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Hook Scope") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule Name (Optional)") },
                    placeholder = { Text("e.g. Block Reflection") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        patternError = false
                    },
                    label = { Text("Scope Glob Pattern") },
                    placeholder = { Text("e.g. com.example.**") },
                    singleLine = true,
                    isError = patternError,
                    supportingText = {
                        if (patternError) {
                            Text("Scope pattern cannot be empty")
                        } else {
                            Text("Use * for shallow match\nUse ** for deep match")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Action",
                    style = MaterialTheme.typography.labelMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = action == HookScope.ACTION_ALLOW,
                        onClick = { action = HookScope.ACTION_ALLOW },
                        label = { Text("Allow") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )

                    FilterChip(
                        selected = action == HookScope.ACTION_BLOCK,
                        onClick = { action = HookScope.ACTION_BLOCK },
                        label = { Text("Block") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Block,
                                contentDescription = null,
                                tint = Color(0xFFC62828)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (pattern.isBlank()) {
                        patternError = true
                    } else {
                        onConfirm(name.trim(), pattern.trim(), action)
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DebugInfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun SettingsSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val animatedCornerRadius by animateDpAsState(
        targetValue = if (isFocused) 16.dp else 28.dp,
        animationSpec = tween(durationMillis = 250),
        label = "CornerRadiusAnimation"
    )

    val animatedContainerColor by animateColorAsState(
        targetValue = if (isFocused) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        },
        animationSpec = tween(durationMillis = 250),
        label = "ContainerColorAnimation"
    )

    TextField(
        value = query,
        onValueChange = onQueryChange,
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions.Default.copy(
            imeAction = ImeAction.Search,
        ),
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(text = "Search modules")
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search icon"
            )
        },
        trailingIcon = {
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search query"
                    )
                }
            }
        },
        shape = RoundedCornerShape(animatedCornerRadius),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = animatedContainerColor,
            unfocusedContainerColor = animatedContainerColor,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onBackground,
            unfocusedTrailingIconColor = MaterialTheme.colorScheme.onBackground,
            focusedTrailingIconColor = MaterialTheme.colorScheme.onBackground,
            focusedLeadingIconColor = MaterialTheme.colorScheme.onBackground,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor = MaterialTheme.colorScheme.onBackground,
        ),
    )

    val keyboardController = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) {
        onDispose {
            keyboardController?.hide()
        }
    }
}

@Composable
@Preview
private fun HookScopePreviewAllow() {
    HookScopeItem(
        HookScope(
            HookScope.ACTION_ALLOW,
            "java.lang.**", "Allow the Java Library", true, 0
        ),
        index = 0,
        totalSize = 10,
    ) {
    }
}

@Composable
@Preview
private fun HookScopePreviewDeny() {
    HookScopeItem(
        HookScope(
            HookScope.ACTION_BLOCK,
            "java.lang.**", "Deny the Java Library", true, 0
        ),
        index = 0,
        totalSize = 10,
    ) {
    }
}