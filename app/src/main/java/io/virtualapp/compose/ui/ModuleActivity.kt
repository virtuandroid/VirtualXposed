package io.virtualapp.compose.ui

import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.font.FontFamily
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import androidx.compose.material3.Switch
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

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

data class ModuleInfo(
    val packageInfo: PackageInfo,
    val appName: String,
    val icon: Drawable?,
    val xposedMinVersion: String,
    val xposedDescription: String,
    val isEnabled: Boolean,
    val blockListHookScopes: Set<String>,
    val allowListHookScopes: Set<String>
) {
    // Shorthand
    val packageName = packageInfo.packageName
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
    val blocked = moduleInfo.blockListHookScopes
    val allowed = moduleInfo.allowListHookScopes

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

        var newAllowedScope by remember { mutableStateOf("") }
        ScopeManagementCard(
            title = "Allowed Hook Scopes",
            scopes = allowed,
            currentText = newAllowedScope,
            onTextChanged = { newAllowedScope = it },
            onAddScope = { scopeToAdd ->
                val updatedScopes = allowed + scopeToAdd
                onIntent.invoke(MainIntent.OnModuleAllow(updatedScopes, moduleInfo))
                newAllowedScope = ""
            },
            onRemoveScope = { scopeToRemove ->
                val updatedScopes = allowed - scopeToRemove
                onIntent.invoke(MainIntent.OnModuleAllow(updatedScopes, moduleInfo))
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        var newBlockedScope by remember { mutableStateOf("") }
        ScopeManagementCard(
            title = "Blocked Hook Scopes",
            scopes = blocked,
            currentText = newBlockedScope,
            onTextChanged = { newBlockedScope = it },
            onAddScope = { scopeToAdd ->
                val updatedScopes = blocked + scopeToAdd
                onIntent.invoke(MainIntent.OnModuleBlock(updatedScopes, moduleInfo))
                newBlockedScope = ""
            },
            onRemoveScope = { scopeToRemove ->
                val updatedScopes = blocked - scopeToRemove
                onIntent.invoke(MainIntent.OnModuleBlock(updatedScopes, moduleInfo))
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Module Details
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

// Maybe change to on/off checkboxes?
@Composable
private fun ScopeManagementCard(
    title: String,
    scopes: Set<String>,
    currentText: String,
    onTextChanged: (String) -> Unit,
    onAddScope: (String) -> Unit,
    onRemoveScope: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = currentText,
                onValueChange = onTextChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("java.lang.*") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (currentText.isNotBlank()) {
                                onAddScope(currentText.trim())
                            }
                        },
                        enabled = currentText.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Scope"
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            AnimatedContent(
                targetState = scopes.isNotEmpty(),
                label = "ScopesContentTransition"
            ) { hasScopes ->
                if (hasScopes) {
                    Column {
                        scopes.forEach { scope ->
                            key(scope) {
                                AnimatedVisibility(
                                    visible = true,
                                    enter = fadeIn() + scaleIn(initialScale = 0.8f),
                                    exit = fadeOut() + scaleOut(targetScale = 0.8f)
                                ) {
                                    InputChip(
                                        selected = false,
                                        onClick = { },
                                        label = { Text(scope) },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { onRemoveScope(scope) },
                                                modifier = Modifier.size(18.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Remove $scope"
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = "No scopes defined",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
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