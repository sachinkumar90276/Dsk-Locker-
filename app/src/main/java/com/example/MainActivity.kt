package com.example

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.local.UserSettingsEntity
import com.example.security.configureAppCheck
import com.example.ui.AuthStage
import com.example.ui.DskLockerViewModel
import com.example.ui.components.FourDAnimatedBackground
import com.example.ui.components.GlassCard4D
import com.example.ui.components.PremiumFlashOverlay
import com.example.ui.screens.*
import com.example.ui.theme.*
import kotlinx.serialization.Serializable

@Serializable data object RouteDashboard
@Serializable data class RouteFiles(val categoryId: String? = null)
@Serializable data object RouteVault
@Serializable data object RouteBackup
@Serializable data object RouteProfile
@Serializable data object RouteNotes
@Serializable data class RouteLinks(val linkType: String = "VIDEO_URL")
@Serializable data object RouteRecycleBin
@Serializable data object RouteSearch
@Serializable data class RouteFileViewer(val fileId: String, val initialTab: Int = 0)
@Serializable data class RouteDocumentScanner(
    val isInVault: Boolean = false,
    val folderId: String? = null,
    val forcedCategoryId: String? = null
)
@Serializable data class RouteDirectVideoUrl(val url: String, val title: String)

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mandatory Step 1: Configure App Check with launch intent before UI or ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            val viewModel: DskLockerViewModel = viewModel()
            val settingsState by viewModel.settings.collectAsState()
            val settings = settingsState ?: UserSettingsEntity()

            val accentPreset = remember(settings.accentPreset) {
                runCatching { AccentPreset.valueOf(settings.accentPreset) }
                    .getOrDefault(AccentPreset.ELECTRIC_COSMOS)
            }
            val motionConfig = remember(settings.animations4DEnabled, settings.reducedMotion) {
                DskMotionConfig(
                    animations4D = settings.animations4DEnabled,
                    reducedMotion = settings.reducedMotion
                )
            }

            // Observe lifecycle for Automatic Lock
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                        Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            DskLockerTheme(
                darkTheme = settings.darkMode,
                accentPreset = accentPreset,
                motionConfig = motionConfig
            ) {
                DskLockerRoot(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun DskLockerRoot(viewModel: DskLockerViewModel) {
    val authStage by viewModel.authStage.collectAsState()
    val isForgotMpin by viewModel.isForgotMpinFlow.collectAsState()

    AnimatedContent(
        targetState = authStage,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "authStageTransition"
    ) { stage ->
        when (stage) {
            AuthStage.CREATE_MPIN -> CreateMpinScreen(viewModel, isForgotMpin)
            AuthStage.BIOMETRIC_SETUP -> BiometricSetupScreen(viewModel)
            AuthStage.MPIN_LOGIN -> MpinLoginScreen(viewModel)
            AuthStage.POST_AUTH_SPLASH -> PostAuthSplashScreen(
                viewModel = viewModel,
                onSplashFinished = { viewModel.finishPostAuthSplash() }
            )
            AuthStage.UNLOCKED_APP -> UnlockedDskLockerScaffold(viewModel)
        }
    }
}

private data class NavTabItem(
    val label: String,
    val icon: ImageVector,
    val route: Any,
    val testTag: String
)

@Composable
private fun UnlockedDskLockerScaffold(viewModel: DskLockerViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }
    val banner by viewModel.statusBanner.collectAsState()
    val flashState by viewModel.flashState.collectAsState()

    var uploadDialogContext by remember { mutableStateOf<UploadRequest?>(null) }
    var autoOcrAfterUpload by remember { mutableStateOf(true) }

    LaunchedEffect(banner) {
        if (!banner.isNullOrBlank()) {
            snackbarHostState.showSnackbar(banner!!)
            viewModel.clearBanner()
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 25)
    ) { uris ->
        val ctx = uploadDialogContext
        uploadDialogContext = null
        if (uris.isNotEmpty()) {
            viewModel.importFiles(
                uris = uris,
                isInVault = ctx?.isInVault ?: false,
                folderId = ctx?.folderId,
                forcedCategoryId = ctx?.forcedCategoryId,
                autoAnalyzeOcr = autoOcrAfterUpload
            )
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val ctx = uploadDialogContext
        uploadDialogContext = null
        if (uris.isNotEmpty()) {
            viewModel.importFiles(
                uris = uris,
                isInVault = ctx?.isInVault ?: false,
                folderId = ctx?.folderId,
                forcedCategoryId = ctx?.forcedCategoryId,
                autoAnalyzeOcr = autoOcrAfterUpload
            )
        }
    }

    val navTabs = remember {
        listOf(
            NavTabItem("Home", Icons.Filled.Dashboard, RouteDashboard, "nav_home"),
            NavTabItem("Files", Icons.Filled.FolderCopy, RouteFiles(null), "nav_files"),
            NavTabItem("Vault", Icons.Filled.Shield, RouteVault, "nav_vault"),
            NavTabItem("Backup", Icons.Filled.CloudSync, RouteBackup, "nav_backup"),
            NavTabItem("Profile", Icons.Filled.Person, RouteProfile, "nav_profile")
        )
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val useRail = screenWidthDp >= 680

    val showPrimaryNav = currentDest?.let { dest ->
        dest.hasRoute<RouteDashboard>() ||
            dest.hasRoute<RouteFiles>() ||
            dest.hasRoute<RouteVault>() ||
            dest.hasRoute<RouteBackup>() ||
            dest.hasRoute<RouteProfile>()
    } ?: true

    FourDAnimatedBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (useRail && showPrimaryNav) {
                        NavigationRail(
                            containerColor = MidnightPurple.copy(alpha = 0.85f),
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Spacer(modifier = Modifier.height(16.dp))
                            navTabs.forEach { tab ->
                                val selected = isTabSelected(currentDest, tab.route)
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(tab.route) {
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label, fontSize = 11.sp) },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        NavHost(
                            navController = navController,
                            startDestination = RouteDashboard
                        ) {
                            composable<RouteDashboard> {
                                DashboardScreen(
                                    viewModel = viewModel,
                                    onOpenUploadPicker = { uploadDialogContext = UploadRequest() },
                                    onOpenScanner = { navController.navigate(RouteDocumentScanner()) },
                                    onOpenFilesTab = { cat -> navController.navigate(RouteFiles(cat)) },
                                    onOpenVaultTab = { navController.navigate(RouteVault) },
                                    onOpenBackupRestore = { navController.navigate(RouteBackup) },
                                    onOpenNotes = { navController.navigate(RouteNotes) },
                                    onOpenLinks = { type -> navController.navigate(RouteLinks(type)) },
                                    onOpenRecycleBin = { navController.navigate(RouteRecycleBin) },
                                    onOpenSearch = { navController.navigate(RouteSearch) },
                                    onOpenFileViewer = { fid -> navController.navigate(RouteFileViewer(fid)) },
                                    onOpenSecuritySettings = { navController.navigate(RouteProfile) { launchSingleTop = true } }
                                )
                            }
                            composable<RouteFiles> { entry ->
                                val args = entry.toRoute<RouteFiles>()
                                FilesExplorerScreen(
                                    viewModel = viewModel,
                                    initialCategoryId = args.categoryId,
                                    onOpenUploadPicker = { cat, folder ->
                                        uploadDialogContext = UploadRequest(forcedCategoryId = cat, folderId = folder)
                                    },
                                    onOpenScanner = { cat, folder ->
                                        navController.navigate(RouteDocumentScanner(isInVault = false, folderId = folder, forcedCategoryId = cat))
                                    },
                                    onOpenFileViewer = { fid -> navController.navigate(RouteFileViewer(fid)) },
                                    onOpenNotes = { navController.navigate(RouteNotes) },
                                    onOpenLinks = { type -> navController.navigate(RouteLinks(type)) },
                                    onOpenSecuritySettings = { navController.navigate(RouteProfile) { launchSingleTop = true } }
                                )
                            }
                            composable<RouteVault> {
                                SecureVaultScreen(
                                    viewModel = viewModel,
                                    onOpenVaultUpload = { uploadDialogContext = UploadRequest(isInVault = true) },
                                    onOpenVaultScanner = { navController.navigate(RouteDocumentScanner(isInVault = true)) },
                                    onOpenFileViewer = { fid -> navController.navigate(RouteFileViewer(fid)) },
                                    onOpenSecuritySettings = { navController.navigate(RouteProfile) { launchSingleTop = true } }
                                )
                            }
                            composable<RouteBackup> {
                                BackupRestoreScreen(viewModel = viewModel)
                            }
                            composable<RouteProfile> {
                                ProfileSettingsScreen(
                                    viewModel = viewModel,
                                    onOpenBackupRestore = { navController.navigate(RouteBackup) },
                                    onOpenRecycleBin = { navController.navigate(RouteRecycleBin) },
                                    onOpenNotes = { navController.navigate(RouteNotes) },
                                    onOpenLinks = { type -> navController.navigate(RouteLinks(type)) }
                                )
                            }
                            composable<RouteNotes> {
                                NotesManagerScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onOpenSecuritySettings = { navController.navigate(RouteProfile) { launchSingleTop = true } }
                                )
                            }
                            composable<RouteLinks> { entry ->
                                val args = entry.toRoute<RouteLinks>()
                                LinksManagerScreen(
                                    viewModel = viewModel,
                                    initialLinkType = args.linkType,
                                    onBack = { navController.popBackStack() },
                                    onPlayDirectVideoUrl = { url, title ->
                                        navController.navigate(RouteDirectVideoUrl(url, title))
                                    },
                                    onOpenSecuritySettings = { navController.navigate(RouteProfile) { launchSingleTop = true } }
                                )
                            }
                            composable<RouteRecycleBin> {
                                RecycleBinScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onOpenSecuritySettings = { navController.navigate(RouteProfile) { launchSingleTop = true } }
                                )
                            }
                            composable<RouteSearch> {
                                GlobalSearchScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onOpenFileViewer = { fid -> navController.navigate(RouteFileViewer(fid)) },
                                    onOpenNotes = { navController.navigate(RouteNotes) },
                                    onOpenLinks = { type -> navController.navigate(RouteLinks(type)) }
                                )
                            }
                            composable<RouteFileViewer> { entry ->
                                val args = entry.toRoute<RouteFileViewer>()
                                FileViewerScreen(
                                    viewModel = viewModel,
                                    fileId = args.fileId,
                                    initialTab = args.initialTab,
                                    onOpenScanner = { navController.navigate(RouteDocumentScanner()) },
                                    onBack = { navController.popBackStack() },
                                    onOpenSecuritySettings = { navController.navigate(RouteProfile) { launchSingleTop = true } }
                                )
                            }
                            composable<RouteDocumentScanner> { entry ->
                                val args = entry.toRoute<RouteDocumentScanner>()
                                DocumentScannerScreen(
                                    viewModel = viewModel,
                                    initialInVault = args.isInVault,
                                    folderId = args.folderId,
                                    forcedCategoryId = args.forcedCategoryId,
                                    onBack = { navController.popBackStack() },
                                    onScanImported = { importedFileId, openOcrTab ->
                                        navController.navigate(
                                            RouteFileViewer(
                                                fileId = importedFileId,
                                                initialTab = if (openOcrTab) 1 else 0
                                            )
                                        ) {
                                            popUpTo<RouteDocumentScanner> { inclusive = true }
                                        }
                                    }
                                )
                            }
                            composable<RouteDirectVideoUrl> { entry ->
                                val args = entry.toRoute<RouteDirectVideoUrl>()
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp)
                                        .navigationBarsPadding()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { navController.popBackStack() }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SilverWhite)
                                        }
                                        Text(args.title, style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Media3PlayerView(uri = Uri.parse(args.url))
                                }
                            }
                        }

                        // Premium Floating 4D Navigation Bar on Handheld
                        if (!useRail && showPrimaryNav) {
                            FloatingNavBar4D(
                                tabs = navTabs,
                                currentDest = currentDest,
                                onNavigate = { route ->
                                    navController.navigate(route) {
                                        popUpTo<RouteDashboard> { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    uploadDialogContext?.let { ctx ->
        Dialog(onDismissRequest = { uploadDialogContext = null }) {
            GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(20.dp)) {
                Text(
                    if (ctx.isInVault) "Encrypt & Import to Vault" else "Upload to DSK LOCKER",
                    style = MaterialTheme.typography.titleLarge,
                    color = SilverWhite
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Choose Photos & Videos, Documents & Files (PDF, DOCX, Excel, Audio, APK, ZIP), or Scan Physical Document.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MetallicSilver
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoOcrAfterUpload, onCheckedChange = { autoOcrAfterUpload = it })
                    Text("Automatic Content OCR & Smart Detection", style = MaterialTheme.typography.bodyMedium, color = SilverWhite)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("pick_photos_videos_button")
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("PHOTOS & VIDEOS", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { documentPicker.launch(arrayOf("*/*")) },
                    border = BorderStroke(1.dp, NeonViolet),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("pick_documents_button")
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = PlasmaMagenta, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("DOCUMENTS & FILES", color = SilverWhite, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        val target = uploadDialogContext
                        uploadDialogContext = null
                        navController.navigate(
                            RouteDocumentScanner(
                                isInVault = target?.isInVault ?: false,
                                folderId = target?.folderId,
                                forcedCategoryId = target?.forcedCategoryId
                            )
                        )
                    },
                    border = BorderStroke(1.dp, EmeraldSync),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("scan_document_dialog_button")
                ) {
                    Icon(Icons.Filled.DocumentScanner, contentDescription = null, tint = EmeraldSync, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SCAN PHYSICAL DOCUMENT", color = SilverWhite, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { uploadDialogContext = null },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Cancel", color = MetallicSilver)
                }
            }
        }
    }

    PremiumFlashOverlay(
        flashState = flashState,
        onDismiss = { viewModel.dismissFlashScreen() }
    )
}

private data class UploadRequest(
    val isInVault: Boolean = false,
    val forcedCategoryId: String? = null,
    val folderId: String? = null
)

@Composable
private fun FloatingNavBar4D(
    tabs: List<NavTabItem>,
    currentDest: androidx.navigation.NavDestination?,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MidnightPurple.copy(alpha = 0.92f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(listOf(ElectricBlue.copy(alpha = 0.55f), NeonViolet.copy(alpha = 0.45f)))
        ),
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 540.dp)
            .shadow(18.dp, RoundedCornerShape(28.dp), ambientColor = ElectricBlue.copy(alpha = 0.3f))
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 8.dp)
        ) {
            tabs.forEach { tab ->
                val selected = isTabSelected(currentDest, tab.route)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) ElectricBlue.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { onNavigate(tab.route) }
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag(tab.testTag)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) ElectricBlue else MetallicSilver,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (selected) ElectricBlue else MetallicSilver
                    )
                }
            }
        }
    }
}

private fun isTabSelected(dest: androidx.navigation.NavDestination?, route: Any): Boolean {
    if (dest == null) return false
    return when (route) {
        is RouteDashboard -> dest.hasRoute<RouteDashboard>()
        is RouteFiles -> dest.hasRoute<RouteFiles>()
        is RouteVault -> dest.hasRoute<RouteVault>()
        is RouteBackup -> dest.hasRoute<RouteBackup>()
        is RouteProfile -> dest.hasRoute<RouteProfile>()
        else -> false
    }
}
