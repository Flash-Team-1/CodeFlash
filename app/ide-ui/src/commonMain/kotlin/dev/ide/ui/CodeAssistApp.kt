package dev.ide.ui

import dev.ide.ui.components.BuildSuccessBats
import dev.ide.ui.components.HalloweenBuildFx
import dev.ide.ui.theme.LocalSeason
import dev.ide.ui.theme.Season
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.ide.ui.ext.AppKeys
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import dev.ide.ui.components.WindowTitleStrip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import dev.ide.ui.ads.BuildAdInterstitial
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.backend.AdHost
import dev.ide.ui.backend.RemoteFeatures
import dev.ide.ui.backend.UiAppUpdate
import dev.ide.ui.screens.AppUpdateSheet
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAccent
import dev.ide.ui.components.OnboardingSheet
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.import_gradle_failed
import dev.ide.ui.generated.resources.import_archive_empty
import dev.ide.ui.generated.resources.import_unrecognized
import dev.ide.ui.navigation.ScreenHost
import dev.ide.ui.platform.PlatformBackHandler
import dev.ide.ui.platform.PointerInputModeHost
import dev.ide.ui.platform.PlatformSystemBars
import dev.ide.ui.screens.ArchiveProjectDialog
import dev.ide.ui.screens.GradleImportModeDialog
import dev.ide.ui.screens.ImportSourceDialog
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.rememberJetBrainsMono
import dev.ide.ui.theme.rememberUiFont
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random
import kotlin.time.Clock

/** Where a put-off update is saved: the epoch-millis time it comes back. */
private const val UPDATE_SNOOZE_PREF = "update.snoozeUntil"
/** A put-off update comes back after a random wait between these two (15 minutes to 3 hours). */
private const val UPDATE_LATER_MIN_MS = 15 * 60_000L
private const val UPDATE_LATER_MAX_MS = 3 * 60 * 60_000L

/**
 * Root of the reusable IDE UI. Hosts pick the toolkit (Compose Desktop window, Android activity) and
 * supply an [IdeBackend] plus optional brand fonts and a [FileActions] bridge; the screens, theme,
 * navigation, and state are shared.
 *
 * The shell's own state and the flows that drive it live in [CodeAssistAppState]; this body is the theme,
 * the back gesture, the screen host ([AppNavGraph]), and the app-wide overlays. Screens transition with a
 * platform-differentiated feel ([ScreenHost]); the active project's UI state is re-keyed on
 * [IdeBackend.projectEpoch] so creating/opening a project rebuilds the tree and tabs. A first-launch
 * [OnboardingSheet] introduces the IDE over the picker.
 */
@Composable
fun CodeAssistApp(
    backend: IdeBackend,
    uiFont: FontFamily = rememberUiFont(),
    codeFont: FontFamily = rememberJetBrainsMono(),
    fileActions: FileActions = FileActions.None,
    /** Platform advertising bridge (AdMob on Android, [AdHost.None] on desktop). Ads render only through this. */
    adHost: AdHost = AdHost.None,
    composePreviewHost: ComposePreviewHost? = null,
    /** A `.caproj` path handed in from outside the app (Android "Open with"). When it changes to a
     *  non-null value, the import preview opens for it. Null on desktop / normal launch. */
    importPackagePath: String? = null,
    /** A store item id handed in from outside the app (a `codeassist://store/<id>` link). Opened when it
     *  changes to a non-null value. Null on desktop / normal launch. */
    openStoreItemId: String? = null,
    /** Called once [openStoreItemId] has been acted on, so the host can clear it. Without that the same
     *  id arriving twice would not re-fire, a link being tapped twice being the ordinary case. */
    onStoreItemIdHandled: () -> Unit = {},
    /** The target of a tapped tray notification (Android), acted on when it changes to a non-null value. */
    openNotificationTarget: dev.ide.ui.backend.UiNotificationTarget? = null,
    /** Called once [openNotificationTarget] has been acted on, so the host can clear it. */
    onNotificationTargetHandled: () -> Unit = {},
    /** Told whether the user has a project open ([CodeAssistAppState.projectOpen]), for host features that act
     *  on it from outside the composition (Android's "Open with" for a source file). */
    onProjectOpenChange: (Boolean) -> Unit = {},
) {
    // Register the UI facets of the enabled plugins, then load once. The backend reports exactly the plugins
    // whose engine half is enabled (see BuiltInPlugins' unified engine+UI declaration), so this shell code names
    // no specific plugin and a disabled plugin contributes nothing. Loaded eagerly (idempotent) so the tool-
    // window/action registries are populated before the editor composes — the top-bar toggles + side panes read
    // straight from ToolWindowRegistry.
    // noinspection RememberReturnType
    remember(Unit) {
        backend.uiPlugins().forEach { UiPluginHost.register(it) }
        // The shell's own tool window: the notification center, readable from inside a project.
        UiPluginHost.register(dev.ide.ui.screens.NotificationsUiPlugin)
        UiPluginHost.ensureLoaded()
    }

    val app = rememberCodeAssistAppState(backend, fileActions, adHost)

    // The active project changes (create/open) bump the epoch; re-key per-project state on it.
    val state = remember(backend, app.epoch) {
        IdeUiState(backend, composePreviewHost, initialGradleConvertPrompt = app.pendingGradleConvert)
    }
    // Clear the one-shot after it has been baked into the (re-created) state, so navigating back to a project
    // later never re-triggers the convert prompt.
    LaunchedEffect(state) { app.consumeGradleConvertPrompt() }
    // Cancel the state's async file-read scope when it's replaced (project/backend change) or leaves composition,
    // so a slow read for an abandoned project can't resolve against the new one.
    DisposableEffect(state) { onDispose { state.dispose() } }
    // Session restore/persistence, plugin editor events, and disk sync for the project on screen.
    LaunchedEffect(state) { state.runSessionEffects() }
    // Apply settings to the active project's live editor state whenever they change (or the project swaps).
    LaunchedEffect(state, app.settings) { state.applySettings(app.settings) }
    // A `.caproj` handed in from outside the app ("Open with"): read its preview and open the import screen.
    // Keyed on the path (the host makes each hand-off a distinct path) so it fires once per inbound package.
    LaunchedEffect(importPackagePath) { importPackagePath?.let { app.openImportPackage(it) } }
    // Feature switches from the server, then an update prompt only where the app can install one and only when it is newer.
    var availableUpdate by remember { mutableStateOf<UiAppUpdate?>(null) }
    // Whether the update sheet is up now. A non-mandatory update put off with "Later" (or dismissed) comes back
    // at a random time, saved so a relaunch keeps waiting for that time instead of showing on every launch.
    var updateVisible by remember { mutableStateOf(false) }
    var updateSnoozes by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        RemoteFeatures.replace(backend.store.myFeatures())
        val latest = backend.store.latestUpdate()
        if (fileActions.canInstallApk && latest != null && latest.versionCode > fileActions.appVersionCode) {
            availableUpdate = latest
        }
    }
    LaunchedEffect(availableUpdate, updateSnoozes) {
        val update = availableUpdate ?: return@LaunchedEffect
        if (!update.mandatory) {
            // The saved wait only applies to the build that was put off. A newer build shows right away.
            val saved = backend.settings.preference(UPDATE_SNOOZE_PREF)?.split(":")
            val snoozedBuild = saved?.getOrNull(0)
            val until = saved?.getOrNull(1)?.toLongOrNull() ?: 0L
            val wait = until - Clock.System.now().toEpochMilliseconds()
            if (snoozedBuild == update.versionCode.toString() && wait > 0) delay(wait)
        }
        updateVisible = true
    }
    // A store link handed in from outside the app. Cleared through the host once acted on, so tapping the
    // same link again is a fresh null -> id change and opens it again.
    LaunchedEffect(openStoreItemId) {
        openStoreItemId?.let { app.openStoreItemById(it); onStoreItemIdHandled() }
    }
    // A tapped tray notification, the same way: acted on once, then cleared through the host.
    LaunchedEffect(openNotificationTarget) {
        openNotificationTarget?.let { app.openNotificationTarget(it); onNotificationTargetHandled() }
    }
    LaunchedEffect(app.projectOpen) { onProjectOpenChange(app.projectOpen) }

    // Theme + accent + code font come from settings; the Settings screen (and the quick toggle) update them
    // live. "system" follows the OS dark-mode signal.
    val dark = isDarkTheme(app.settings, isSystemInDarkTheme())
    // Keep the system-bar icons legible against the app theme: dark icons in light mode, light icons in dark mode.
    // Reactive, so a theme toggle re-applies it (the host's one-time edge-to-edge setup can't follow the toggle).
    PlatformSystemBars(darkTheme = dark)
    val resolvedCodeFont = if (app.settings.codeFont == "monospace") FontFamily.Monospace else codeFont
    // The import notice is raised as a reason by the state holder and localized here.
    val importUnrecognizedMsg = stringResource(Res.string.import_unrecognized)
    val importGradleFailedMsg = stringResource(Res.string.import_gradle_failed)
    val importArchiveEmptyMsg = stringResource(Res.string.import_archive_empty)
    val importErrorMessage = when (val error = app.importError) {
        null -> null
        ImportError.Unrecognized -> importUnrecognizedMsg
        is ImportError.GradleFailed -> error.message.ifBlank { importGradleFailedMsg }
        is ImportError.ArchiveFailed -> error.message.ifBlank { importArchiveEmptyMsg }
    }

    CodeAssistTheme(
        dark = dark,
        accent = accentOf(app.settings),
        // A Custom accent seeds the whole expressive theme from the user's chosen color; a "Dynamic" accent
        // follows the wallpaper; any preset applies its fixed palette (overriding wallpaper dynamic color).
        seedColor = seedColorOf(app.settings),
        useDynamic = app.settings.accent == UiAccent.Dynamic,
        uiFont = uiFont,
        codeFont = resolvedCodeFont,
        editorColorScheme = app.editorColorScheme,
        // A plugin's app theme, while its plugin is loaded; once it is not, the id resolves to nothing and the
        // default accent applies, without the user's choice being forgotten.
        appTheme = app.settings.appTheme?.let { dev.ide.ui.theme.colors.ContributedAppThemes.byId(it) },
    ) {
        // Route the system back gesture through in-app navigation instead of letting it close the app (#997).
        // Registered above the editor's own overlay handler, so an open sheet/dialog is closed first (the
        // deeper handler wins); this one only fires for screen-level back.
        PlatformBackHandler(enabled = app.canNavigateBack, onBack = app::navigateBack)
        // The M3 background fills the whole window edge-to-edge (behind the system bars); content is
        // then inset by `safeDrawing`. On desktop these insets are empty, so this is a no-op there.
        // Read once per composition of the root: a session that crosses midnight into the season picks it up
        // on the next settings change or launch, which is soon enough for a decoration.
        val season = if (app.settings.seasonalTouches) Season.current() else null
        // Verified publishers see no ads. Check the account on launch and again after sign-in, so the ad state
        // follows verification (it is also kept in storage, so a restart before the check still hides ads).
        val signedInNow = backend.store.authState().collectAsState().value.signedIn
        LaunchedEffect(signedInNow) {
            if (!signedInNow) return@LaunchedEffect
            runCatching { backend.store.myProfile() }.getOrNull()?.let { app.adController.onVerificationKnown(it.verified) }
        }
        CompositionLocalProvider(
            LocalSeason provides season,
            LocalAds provides app.adController,
            LocalHostFileActions provides fileActions,
            LocalPluginNavigator provides app::openPluginScreen,
            dev.ide.ui.screens.LocalNotificationOpener provides { n -> app.openNotificationTarget(n) },
            LocalSettingsPageOpener provides { id -> app.openSettingsPageFrom(app.screen, id) },
            // A contributed panel can open a file the same way an action's OpenFile effect does, and lands the
            // user on the editor to see it. Guarded on a project being open, since a tab over nothing is not a
            // state the shell has (and `epoch > 0` would reopen a project the user closed behind Home).
            LocalPluginFileOpener provides { path, offset ->
                if (app.projectOpen) {
                    state.openAt(path, offset)
                    app.navigateTo(Screen.Editor)
                }
            },
        ) {
            // Occasional full-screen ad over a LONG build (Android only; inert on desktop / when ads are off).
            // Renders nothing — it just observes the build state and asks the host to present an interstitial.
            BuildAdInterstitial(backend, app.adController)
            // The update sheet sits inside the app theme, so it takes the active colors and follows theme changes.
            availableUpdate?.takeIf { updateVisible }?.let { update ->
                AppUpdateSheet(update, fileActions) {
                    // Later (or swiping the sheet away) on a non-mandatory update: save a random time to show it again.
                    val until = Clock.System.now().toEpochMilliseconds() +
                        Random.nextLong(UPDATE_LATER_MIN_MS, UPDATE_LATER_MAX_MS)
                    backend.settings.setPreference(UPDATE_SNOOZE_PREF, "${update.versionCode}:$until")
                    updateVisible = false
                    updateSnoozes++
                }
            }
            // Mouse or touch, from the last pointer event, for the parts of the UI that size and reveal by pointer.
            PointerInputModeHost {
                // App-wide shortcuts. At the root so they see a key on its way down (the switcher's modal keys,
                // double Shift) and, last, any key nothing inside consumed. On Android this also runs before
                // Compose's own Tab focus traversal, which would otherwise eat Ctrl+Tab.
                Surface(
                    Modifier.fillMaxSize().onPreviewKeyEvent(AppKeys::onPreviewKey).onKeyEvent(AppKeys::onKey),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        // Desktop with a custom title bar: the title strip for screens that draw none of their own.
                        WindowTitleStrip(title = "CodeFlash")
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AppNavGraph(
                                app = app,
                                state = state,
                                fileActions = fileActions,
                                codeFont = codeFont,
                                dark = dark,
                                modifier = Modifier.fillMaxSize(),
                            )
                            // Over everything in the content area, drawing only while a flock is in the air.
                            BuildSuccessBats(
                                backend = backend,
                                enabled = season == Season.Halloween,
                            )
                            // Boo on a failed build, candy when a program starts; nothing outside the season.
                            HalloweenBuildFx(backend = backend)
                        }
                    }
                    AppOverlays(
                        backend = backend,
                        state = state,
                        fileActions = fileActions,
                        onPicker = app.screen == Screen.Projects && app.homeTab == HomeTab.Projects,
                        showMigration = app.showMigration,
                        onBackup = app::backupAndShare,
                        onDismissMigration = app::dismissMigration,
                        showOnboarding = app.showOnboarding,
                        // Final CTA: send the user straight into the Create-Project flow.
                        onGetStarted = { app.createProject() },
                        onFinishOnboarding = app::dismissOnboarding,
                        showAnalytics = app.showAnalytics,
                        onAllowAnalytics = { app.setAnalyticsConsent(true) },
                        onDeclineAnalytics = { app.setAnalyticsConsent(false) },
                        importError = importErrorMessage,
                        onDismissImportError = app::dismissImportError,
                        importBusy = app.importBusy,
                        showLearnTourOffer = app.showLearnTourOffer,
                        onStartLearnTour = app::startLearnTour,
                        onDismissLearnTour = app::dismissLearnTourOffer,
                    )
                    ImportSourceDialog(
                        visible = app.showImportSourceChoice,
                        canPickFolder = fileActions.canPickDirectory,
                        canPickPackage = fileActions.canPickFile,
                        onFolder = app::chooseFolderImport,
                        onPackage = app::choosePackageImport,
                        onDismiss = app::dismissImportSourceChoice,
                    )
                    ArchiveProjectDialog(
                        projects = app.archiveChoices,
                        onPick = app::chooseArchiveProject,
                        onDismiss = app::dismissArchiveChoice,
                    )
                    GradleImportModeDialog(
                        visible = app.showImportModeChoice,
                        onCompat = { app.importGradleProject(convert = false) },
                        onConvert = { app.importGradleProject(convert = true) },
                        onDismiss = app::dismissImportModeChoice,
                    )
                }
            }
        }
    }
}
