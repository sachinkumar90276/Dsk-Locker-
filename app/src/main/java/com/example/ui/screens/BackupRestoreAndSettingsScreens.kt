package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.*
import com.example.data.remote.SqlSchemaViewerData
import com.example.security.BiometricAvailability
import com.example.security.BiometricHelper
import com.example.storage.LocalFileManager
import com.example.ui.DskLockerViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupRestoreScreen(
    viewModel: DskLockerViewModel,
    onBack: (() -> Unit)? = null
) {
    val activeFiles by viewModel.activeFiles.collectAsState()
    val vaultFiles by viewModel.vaultFiles.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val settingsState by viewModel.settings.collectAsState()
    val history by viewModel.backupHistory.collectAsState()
    val restoreProgress by viewModel.restoreProgress.collectAsState()
    val settings = settingsState ?: UserSettingsEntity()

    val allFiles = activeFiles + vaultFiles
    val cloudBackedFiles = remember(allFiles) { allFiles.filter { !it.cloudPath.isNullOrBlank() } }
    val failedFiles = remember(allFiles) { allFiles.filter { it.backupStatus == BackupStatus.BACKUP_FAILED } }
    val pendingFiles = remember(allFiles) {
        allFiles.filter {
            it.backupStatus == BackupStatus.MOBILE_ONLY ||
                it.backupStatus == BackupStatus.BACKUP_PENDING ||
                it.backupStatus == BackupStatus.BACKUP_FAILED
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Auto & Manual Backup, 1 = Restore System, 2 = History
    var selectedRestoreIds by remember { mutableStateOf(setOf<String>()) }
    var restoreCategoryFilter by remember { mutableStateOf<String?>(null) }
    var overwriteConfirmTargets by remember { mutableStateOf<List<FileItemEntity>?>(null) }
    val fmt = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SilverWhite)
                    }
                }
                Column {
                    Text("CLOUD BACKUP & RESTORE", style = MaterialTheme.typography.headlineMedium, color = SilverWhite)
                    Text(
                        "${cloudBackedFiles.size} in Cloud • ${pendingFiles.size} Pending • ${failedFiles.size} Failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricBlue
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(selected = selectedTab == 0, onClick = { selectedTab = 0 }, label = { Text("Backup Engine") }, modifier = Modifier.weight(1f))
                FilterChip(selected = selectedTab == 1, onClick = { selectedTab = 1 }, label = { Text("Restore") }, modifier = Modifier.weight(1f))
                FilterChip(selected = selectedTab == 2, onClick = { selectedTab = 2 }, label = { Text("History") }, modifier = Modifier.weight(1f))
            }
        }

        if (restoreProgress != null) {
            item {
                GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(14.dp)) {
                    Text("Restoring ${restoreProgress!!.first}", style = MaterialTheme.typography.labelLarge, color = ElectricBlue)
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { restoreProgress!!.second }, color = ElectricBlue, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        when (selectedTab) {
            0 -> {
                item {
                    GlassCard4D(contentPadding = PaddingValues(16.dp)) {
                        Text("AUTO BACKUP ENGINE", style = MaterialTheme.typography.titleMedium, color = ElectricBlue)
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingSwitchRow("Auto Backup", "Verify SHA-256 & upload in background", settings.autoBackupEnabled) { v ->
                            viewModel.updateSettings { it.copy(autoBackupEnabled = v) }
                        }
                        SettingSwitchRow("Wi-Fi Only", if (settings.wifiOnly) "Wi-Fi Only enabled" else "Mobile Data allowed", settings.wifiOnly) { v ->
                            viewModel.updateSettings { it.copy(wifiOnly = v) }
                        }
                        SettingSwitchRow("Photos Backup", "Auto-backup Photos category", settings.backupPhotos) { v ->
                            viewModel.updateSettings { it.copy(backupPhotos = v) }
                        }
                        SettingSwitchRow("Videos Backup", "Auto-backup Videos category", settings.backupVideos) { v ->
                            viewModel.updateSettings { it.copy(backupVideos = v) }
                        }
                        SettingSwitchRow("Documents Backup", "PDF, DOCX, Excel & Personal Records", settings.backupDocuments) { v ->
                            viewModel.updateSettings { it.copy(backupDocuments = v) }
                        }
                    }
                }

                if (folders.isNotEmpty()) {
                    item {
                        GlassCard4D(contentPadding = PaddingValues(16.dp)) {
                            Text("FOLDER BACKUP SELECTION", style = MaterialTheme.typography.titleMedium, color = NeonViolet)
                            Spacer(modifier = Modifier.height(8.dp))
                            folders.forEach { folder ->
                                SettingSwitchRow(folder.name, "Include folder in auto backup", folder.isAutoBackupEnabled) {
                                    viewModel.toggleFolderAutoBackup(folder)
                                }
                            }
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { viewModel.runManualBackupNow() },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("manual_backup_now_button")
                        ) {
                            Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Backup Now", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.runManualBackupNow(failedFiles) },
                            enabled = failedFiles.isNotEmpty(),
                            border = BorderStroke(1.dp, AmberPending),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Replay, contentDescription = null, tint = AmberPending, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Failed (${failedFiles.size})", color = AmberPending)
                        }
                    }
                }
            }

            1 -> {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = restoreCategoryFilter == null, onClick = { restoreCategoryFilter = null }, label = { Text("All Cloud") })
                        }
                        items(DskCategories.builtInCategories.filter { !it.isLinkCategory && it.id != DskCategories.CAT_TEXT }) { cat ->
                            FilterChip(
                                selected = restoreCategoryFilter == cat.id,
                                onClick = { restoreCategoryFilter = if (restoreCategoryFilter == cat.id) null else cat.id },
                                label = { Text(cat.title) }
                            )
                        }
                    }
                }

                val candidates = cloudBackedFiles.filter {
                    restoreCategoryFilter == null || DskCategories.matchesCategory(it.categoryId, restoreCategoryFilter)
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val targets = if (selectedRestoreIds.isNotEmpty()) {
                                    candidates.filter { it.id in selectedRestoreIds }
                                } else candidates
                                if (targets.any { f -> f.localPath != null && File(f.localPath).exists() }) {
                                    overwriteConfirmTargets = targets
                                } else {
                                    viewModel.restoreCloudFiles(targets, overwrite = false)
                                }
                            },
                            enabled = candidates.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("restore_selected_or_all_button")
                        ) {
                            Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (selectedRestoreIds.isNotEmpty()) "Restore Selected (${selectedRestoreIds.size})" else "Restore All (${candidates.size})")
                        }
                    }
                }

                if (candidates.isEmpty()) {
                    item {
                        DskEmptyStateCard(
                            title = "No Cloud Backups Found",
                            subtitle = "Back up files to Firebase Storage to restore them here.",
                            icon = Icons.Filled.CloudOff,
                            glowColor = ElectricBlue
                        )
                    }
                } else {
                    items(candidates, key = { it.id }) { file ->
                        val selected = file.id in selectedRestoreIds
                        GlassCard4D(
                            glowColor = if (selected) ElectricBlue else NeonViolet,
                            onClick = {
                                selectedRestoreIds = if (selected) selectedRestoreIds - file.id else selectedRestoreIds + file.id
                            },
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = selected, onCheckedChange = {
                                    selectedRestoreIds = if (selected) selectedRestoreIds - file.id else selectedRestoreIds + file.id
                                })
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.displayName, style = MaterialTheme.typography.titleMedium, color = SilverWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    BackupStatusBadge(status = file.backupStatus)
                                }
                                TextButton(onClick = {
                                    if (file.localPath != null && File(file.localPath).exists()) {
                                        overwriteConfirmTargets = listOf(file)
                                    } else {
                                        viewModel.restoreCloudFiles(listOf(file), overwrite = false)
                                    }
                                }) {
                                    Text("Restore", color = ElectricBlue)
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                if (history.isEmpty()) {
                    item {
                        GlassCard4D(contentPadding = PaddingValues(24.dp)) {
                            Text("No backup or restore events recorded yet.", style = MaterialTheme.typography.bodyMedium, color = MetallicSilver)
                        }
                    }
                } else {
                    items(history, key = { it.id }) { entry ->
                        GlassCard4D(
                            glowColor = if (entry.status == "SUCCESS") EmeraldSync else CrimsonAlert,
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.fileName, style = MaterialTheme.typography.titleMedium, color = SilverWhite, maxLines = 1)
                                    Text("${entry.action} • ${fmt.format(Date(entry.timestamp))}", style = MaterialTheme.typography.labelSmall, color = MetallicSilver)
                                    if (entry.message.isNotBlank()) {
                                        Text(entry.message, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp), color = MutedSlate)
                                    }
                                }
                                Text(
                                    entry.status,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (entry.status == "SUCCESS") EmeraldSync else CrimsonAlert
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    overwriteConfirmTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { overwriteConfirmTargets = null },
            title = { Text("Overwrite Existing Local Files?") },
            text = { Text("Some selected files already exist on phone storage. Confirm whether to overwrite them or skip existing files.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.restoreCloudFiles(targets, overwrite = true)
                    overwriteConfirmTargets = null
                }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.restoreCloudFiles(targets, overwrite = false)
                    overwriteConfirmTargets = null
                }) { Text("Skip Existing") }
            }
        )
    }
}

@Composable
fun ProfileSettingsScreen(
    viewModel: DskLockerViewModel,
    onOpenBackupRestore: () -> Unit,
    onOpenRecycleBin: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenLinks: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val clipboard = LocalClipboardManager.current

    val profileState by viewModel.profile.collectAsState()
    val settingsState by viewModel.settings.collectAsState()
    val securityLogs by viewModel.securityLogs.collectAsState()
    val storageStats by viewModel.storageStats.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()
    val secretMpinConfigured by viewModel.secretMpinConfigured.collectAsState()
    val deleteProtectionEnabled by viewModel.deleteProtectionEnabled.collectAsState()

    val profile = profileState ?: OwnerProfileEntity()
    val settings = settingsState ?: UserSettingsEntity()
    val bioAvail = remember { viewModel.biometricAvailability() }
    var bioEnabled by remember { mutableStateOf(viewModel.isBiometricEnabled()) }

    var showEditProfile by remember { mutableStateOf(false) }
    var showChangeMpinVerify by remember { mutableStateOf(false) }
    var showChangeMpinNew by remember { mutableStateOf(false) }
    var showSecretMpinVerifyCurrent by remember { mutableStateOf(false) }
    var showSecretMpinRecoveryGate by remember { mutableStateOf(false) }
    var showSecretMpinNew by remember { mutableStateOf(false) }
    var showRemoveBioMpin by remember { mutableStateOf(false) }
    var showSecurityLogs by remember { mutableStateOf(false) }
    var showSqlDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showHelpPrivacyDialog by remember { mutableStateOf<String?>(null) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.updateProfile(profile.ownerName, uri)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Profile Header Card
        item {
            GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .clickable {
                                avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                    ) {
                        val localAvatar = profile.avatarLocalPath?.let { File(it) }?.takeIf { it.exists() }
                        if (localAvatar != null) {
                            AsyncImage(
                                model = localAvatar,
                                contentDescription = "Owner Profile Picture",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                DskLockerLogo(
                                    size = 54.dp,
                                    showTitles = false,
                                    animateRotation = true
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.ownerName, style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                        Text(
                            profile.phoneNumber.ifBlank { viewModel.ownerPhoneDisplay() },
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricBlue
                        )
                        Text("Authorized Single Owner • MPIN Lock Active", style = MaterialTheme.typography.labelSmall, color = EmeraldSync)
                        Text(
                            "Vault ID: ${viewModel.firebaseUidDisplay().take(18)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = MutedSlate
                        )
                    }
                    IconButton(onClick = { showEditProfile = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit Profile", tint = ElectricBlue)
                    }
                }
            }
        }

        // Security & Privacy Section
        item {
            GlassCard4D(glowColor = NeonViolet, contentPadding = PaddingValues(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("SECURITY & PRIVACY", style = MaterialTheme.typography.labelLarge, color = NeonViolet)
                    Surface(
                        color = if (secretMpinConfigured) EmeraldSync.copy(alpha = 0.16f) else AmberPending.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, if (secretMpinConfigured) EmeraldSync else AmberPending)
                    ) {
                        Text(
                            text = if (secretMpinConfigured) "DELETE SHIELD ACTIVE" else "SECRET MPIN NOT SET",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            color = if (secretMpinConfigured) EmeraldSync else AmberPending,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Security Status: Login MPIN (${viewModel.loginMpinLength()}-Digit) • Secret MPIN (${if (secretMpinConfigured) "${viewModel.secretMpinLength()}-Digit Active" else "Not Set"})",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricBlue
                )
                Spacer(modifier = Modifier.height(8.dp))
                ActionRow(
                    Icons.Filled.Password,
                    if (viewModel.hasMpin()) "Set / Change Login MPIN" else "Set Login MPIN",
                    "Supports 4-digit or 6-digit PBKDF2 MPIN for app unlock"
                ) {
                    if (viewModel.hasMpin()) {
                        showChangeMpinVerify = true
                    } else {
                        showChangeMpinNew = true
                    }
                }
                ActionRow(
                    Icons.Filled.Shield,
                    if (secretMpinConfigured) "Change Secret MPIN (Delete Security)" else "Set Secret MPIN (Delete Security)",
                    if (secretMpinConfigured) "Separate ${viewModel.secretMpinLength()}-digit PIN required on every delete" else "Set a dedicated PIN (different from Login MPIN) for delete actions"
                ) {
                    if (secretMpinConfigured) {
                        showSecretMpinVerifyCurrent = true
                    } else {
                        showSecretMpinNew = true
                    }
                }
                if (secretMpinConfigured) {
                    ActionRow(
                        Icons.Filled.LockReset,
                        "Forgot Secret MPIN Recovery",
                        "Verify Login MPIN or Fingerprint to reset your Secret MPIN"
                    ) {
                        showSecretMpinRecoveryGate = true
                    }
                }
                SettingSwitchRow(
                    title = "Enable Delete Protection",
                    subtitle = "Mandatory Secret MPIN + Confirmation on every Delete (cannot be bypassed)",
                    checked = deleteProtectionEnabled
                ) { desired ->
                    viewModel.setDeleteProtectionEnabled(desired)
                }
                SettingSwitchRow(
                    title = "Biometric Unlock",
                    subtitle = if (bioAvail is BiometricAvailability.Available) "Fingerprint / FacePrompt active" else (bioAvail as BiometricAvailability.Unavailable).reason,
                    checked = bioEnabled
                ) { desired ->
                    if (desired) {
                        if (activity != null && bioAvail is BiometricAvailability.Available) {
                            BiometricHelper.authenticate(
                                activity = activity,
                                title = "Enroll Biometric Unlock",
                                subtitle = "Authenticate to enable biometric unlock",
                                onSuccess = {
                                    viewModel.toggleBiometricSetting(true)
                                    bioEnabled = true
                                },
                                onFailure = { /* keep disabled */ }
                            )
                        }
                    } else {
                        showRemoveBioMpin = true
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("Automatic Lock Timeout", style = MaterialTheme.typography.bodyMedium, color = SilverWhite)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf(0 to "Immediate", 60 to "1 min", 120 to "2 min", 300 to "5 min", 900 to "15 min").forEach { (sec, lbl) ->
                        item {
                            FilterChip(
                                selected = settings.autoLockSeconds == sec,
                                onClick = { viewModel.updateSettings { it.copy(autoLockSeconds = sec) } },
                                label = { Text(lbl, fontSize = 11.sp) }
                            )
                        }
                    }
                }
                ActionRow(Icons.Filled.History, "Security Logs", "${securityLogs.size} recorded events") {
                    showSecurityLogs = true
                }
            }
        }

        // Appearance & 4D Reduced Motion
        item {
            GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(16.dp)) {
                Text("APPEARANCE & 4D ENGINE", style = MaterialTheme.typography.labelLarge, color = ElectricBlue)
                Spacer(modifier = Modifier.height(8.dp))
                SettingSwitchRow("Dark Mode", "Midnight obsidian 4D surface", settings.darkMode) { v ->
                    viewModel.updateSettings { it.copy(darkMode = v) }
                }
                SettingSwitchRow("4D Animations", "Holographic nebula & rotating sphere", settings.animations4DEnabled) { v ->
                    viewModel.updateSettings { it.copy(animations4DEnabled = v) }
                }
                SettingSwitchRow("Reduced Motion Mode", "Battery & performance optimization", settings.reducedMotion) { v ->
                    viewModel.updateSettings { it.copy(reducedMotion = v) }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("Theme Accent Preset", style = MaterialTheme.typography.bodyMedium, color = SilverWhite)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    items(AccentPreset.entries) { preset ->
                        FilterChip(
                            selected = settings.accentPreset == preset.name,
                            onClick = { viewModel.updateSettings { it.copy(accentPreset = preset.name) } },
                            label = { Text(preset.label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // Storage, Backup, Managers & Firebase + SQL Backend
        item {
            GlassCard4D(contentPadding = PaddingValues(16.dp)) {
                Text("STORAGE & MANAGERS", style = MaterialTheme.typography.labelLarge, color = EmeraldSync)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "DSK Local: ${LocalFileManager.formatBytes(storageStats.lockerLocalBytes)} • Device Free: ${LocalFileManager.formatBytes(storageStats.deviceAvailableBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MetallicSilver
                )
                Spacer(modifier = Modifier.height(6.dp))
                ActionRow(Icons.Filled.Backup, "Backup & Restore Center", "Auto backup, history & restore") { onOpenBackupRestore() }
                ActionRow(Icons.Filled.Notes, "Text Notes Manager", "Create & search private notes") { onOpenNotes() }
                ActionRow(Icons.Filled.VideoLibrary, "Video & Website Links", "Saved YouTube & web bookmarks") { onOpenLinks("VIDEO_URL") }
                ActionRow(Icons.Filled.DeleteSweep, "Recycle Bin", "Restore or purge deleted files") { onOpenRecycleBin() }
                ActionRow(
                    Icons.Filled.Storage,
                    "SQL Database (18 Tables) & Rules",
                    if (viewModel.isSqlBackendConfigured()) "Firebase ID Token + PostgreSQL Connected" else "Configure BACKEND_API_URL + run schema.sql"
                ) {
                    showSqlDialog = true
                }
                HorizontalDivider(color = CrimsonAlert.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 8.dp))
                OutlinedButton(
                    onClick = { showLogoutConfirm = true },
                    border = BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("logout_button")
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = CrimsonAlert, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Lock App (MPIN Login)", color = CrimsonAlert, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Other: Notifications, Privacy, Help, About DSK LOCKER
        item {
            GlassCard4D(glowColor = PlasmaMagenta, contentPadding = PaddingValues(16.dp)) {
                Text("OTHER & ABOUT", style = MaterialTheme.typography.labelLarge, color = PlasmaMagenta)
                Spacer(modifier = Modifier.height(8.dp))
                SettingSwitchRow("Expiry & Backup Notifications", "30/15/7/0 day document expiry alerts", settings.notificationsEnabled) { v ->
                    viewModel.updateSettings { it.copy(notificationsEnabled = v) }
                }
                ActionRow(Icons.Filled.PrivacyTip, "Privacy & Zero-Trust Policy", "Single-owner RLS & AndroidKeyStore AES-256") {
                    showHelpPrivacyDialog = "PRIVACY"
                }
                ActionRow(Icons.Filled.HelpOutline, "Help & Quick Guide", "MPIN lock, Camera + ML Kit scanner, AI OCR & backup") {
                    showHelpPrivacyDialog = "HELP"
                }
                HorizontalDivider(color = NeonViolet.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 10.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    DskLockerLogo(
                        size = 64.dp,
                        showTitles = true,
                        animateRotation = true,
                        subtitleOverride = "YOUR PRIVATE DIGITAL VAULT"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "DSK LOCKER Ultimate 4D Personal Edition • Version 1.0 • Built by DSK OFFICIAL",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        color = MetallicSilver
                    )
                }
            }
        }
    }

    if (showEditProfile) {
        var nameInput by remember { mutableStateOf(profile.ownerName) }
        Dialog(onDismissRequest = { showEditProfile = false }) {
            GlassCard4D(contentPadding = PaddingValues(20.dp)) {
                Text("Edit Owner Profile", style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text("Owner Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Change Profile Picture")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showEditProfile = false }) { Text("Cancel") }
                    Button(onClick = {
                        viewModel.updateProfile(nameInput)
                        showEditProfile = false
                    }) { Text("Save") }
                }
            }
        }
    }

    if (showChangeMpinVerify) {
        MpinVerificationDialog(
            title = "Verify Current Login MPIN",
            subtitle = "Enter your existing ${viewModel.loginMpinLength()}-digit Login MPIN or tap Set New MPIN",
            verifyMpin = viewModel::verifyMpinForAction,
            lockoutSeconds = lockoutSeconds,
            maxDigits = viewModel.loginMpinLength(),
            onDismiss = { showChangeMpinVerify = false },
            onVerified = {
                showChangeMpinVerify = false
                showChangeMpinNew = true
            },
            onForgotMpin = {
                showChangeMpinVerify = false
                viewModel.startForgotMpin()
            }
        )
    }

    if (showChangeMpinNew) {
        var pinLen by remember { mutableIntStateOf(viewModel.loginMpinLength()) }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var confirmStep by remember { mutableStateOf(false) }
        var changeErr by remember { mutableStateOf<String?>(null) }
        val activeLength = if (confirmStep) confirmPin.length else newPin.length
        val matched = newPin.length == pinLen && confirmPin.length == pinLen && newPin == confirmPin
        val mismatched = newPin.length == pinLen && confirmPin.length == pinLen && newPin != confirmPin
        Dialog(onDismissRequest = { showChangeMpinNew = false }) {
            GlassCard4D(contentPadding = PaddingValues(20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (confirmStep) "Confirm ${pinLen}-Digit Login MPIN" else "Set ${pinLen}-Digit Login MPIN",
                        style = MaterialTheme.typography.titleLarge,
                        color = SilverWhite
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(4, 6).forEach { len ->
                            FilterChip(
                                selected = pinLen == len,
                                onClick = {
                                    pinLen = len
                                    newPin = ""
                                    confirmPin = ""
                                    confirmStep = false
                                    changeErr = null
                                },
                                label = { Text("$len Digits", fontSize = 11.sp) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    MpinDots(length = activeLength, maxDigits = pinLen, isError = mismatched || changeErr != null)
                    if (changeErr != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(changeErr!!, color = CrimsonAlert, style = MaterialTheme.typography.labelMedium)
                    } else if (mismatched) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("MPIN entries do not match", color = CrimsonAlert, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    MpinKeypad(
                        buttonSize = 56.dp,
                        verticalSpacing = 10.dp,
                        onDigit = { d ->
                            changeErr = null
                            if (!confirmStep) {
                                if (newPin.length < pinLen) {
                                    newPin += d
                                    if (newPin.length == pinLen) confirmStep = true
                                }
                            } else if (confirmPin.length < pinLen) {
                                confirmPin += d
                            }
                        },
                        onBackspace = {
                            changeErr = null
                            if (confirmStep) {
                                if (confirmPin.isNotEmpty()) confirmPin = confirmPin.dropLast(1)
                                else confirmStep = false
                            } else if (newPin.isNotEmpty()) {
                                newPin = newPin.dropLast(1)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val res = viewModel.changeMpin(newPin)
                            if (res.isSuccess) {
                                showChangeMpinNew = false
                            } else {
                                changeErr = res.exceptionOrNull()?.localizedMessage ?: "Invalid MPIN."
                            }
                        },
                        enabled = matched,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSync, contentColor = VoidBlack),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text("Set ${pinLen}-Digit Login MPIN", fontWeight = FontWeight.Bold)
                    }
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (confirmStep) {
                            TextButton(onClick = {
                                newPin = ""
                                confirmPin = ""
                                confirmStep = false
                                changeErr = null
                            }) { Text("Start Over", color = ElectricBlue) }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }
                        TextButton(onClick = { showChangeMpinNew = false }) { Text("Cancel") }
                    }
                }
            }
        }
    }

    if (showSecretMpinVerifyCurrent) {
        MpinVerificationDialog(
            title = "Verify Current Secret MPIN",
            subtitle = "Enter your current ${viewModel.secretMpinLength()}-digit Secret MPIN or recover below",
            verifyMpin = viewModel::verifySecretMpinForDelete,
            lockoutSeconds = lockoutSeconds,
            maxDigits = viewModel.secretMpinLength(),
            onDismiss = { showSecretMpinVerifyCurrent = false },
            onVerified = {
                showSecretMpinVerifyCurrent = false
                showSecretMpinNew = true
            },
            onForgotMpin = {
                showSecretMpinVerifyCurrent = false
                showSecretMpinRecoveryGate = true
            }
        )
    }

    if (showSecretMpinRecoveryGate) {
        MpinVerificationDialog(
            title = "Secret MPIN Recovery",
            subtitle = "Verify your Login MPIN to reset Secret MPIN",
            verifyMpin = viewModel::verifyMpinForAction,
            lockoutSeconds = lockoutSeconds,
            maxDigits = viewModel.loginMpinLength(),
            onDismiss = { showSecretMpinRecoveryGate = false },
            onVerified = {
                showSecretMpinRecoveryGate = false
                showSecretMpinNew = true
            }
        )
    }

    if (showSecretMpinNew) {
        var secretLen by remember { mutableIntStateOf(6) }
        var newSecretPin by remember { mutableStateOf("") }
        var confirmSecretPin by remember { mutableStateOf("") }
        var confirmStep by remember { mutableStateOf(false) }
        var secretErr by remember { mutableStateOf<String?>(null) }
        val activeLen = if (confirmStep) confirmSecretPin.length else newSecretPin.length
        val matched = newSecretPin.length == secretLen && confirmSecretPin.length == secretLen && newSecretPin == confirmSecretPin
        val mismatched = newSecretPin.length == secretLen && confirmSecretPin.length == secretLen && newSecretPin != confirmSecretPin

        Dialog(onDismissRequest = { showSecretMpinNew = false }) {
            GlassCard4D(glowColor = CrimsonAlert, contentPadding = PaddingValues(20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (confirmStep) "Confirm Secret MPIN" else if (secretMpinConfigured) "Change Secret MPIN" else "Set Secret MPIN",
                        style = MaterialTheme.typography.titleLarge,
                        color = SilverWhite
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Must be different from your Login MPIN.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberPending
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(4, 6).forEach { len ->
                            FilterChip(
                                selected = secretLen == len,
                                onClick = {
                                    secretLen = len
                                    newSecretPin = ""
                                    confirmSecretPin = ""
                                    confirmStep = false
                                    secretErr = null
                                },
                                label = { Text("$len Digits", fontSize = 11.sp) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    MpinDots(length = activeLen, maxDigits = secretLen, isError = mismatched || secretErr != null)
                    if (secretErr != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(secretErr!!, color = CrimsonAlert, style = MaterialTheme.typography.labelMedium)
                    } else if (mismatched) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Secret MPIN entries do not match", color = CrimsonAlert, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    MpinKeypad(
                        buttonSize = 56.dp,
                        verticalSpacing = 10.dp,
                        onDigit = { d ->
                            secretErr = null
                            if (!confirmStep) {
                                if (newSecretPin.length < secretLen) {
                                    newSecretPin += d
                                    if (newSecretPin.length == secretLen) confirmStep = true
                                }
                            } else if (confirmSecretPin.length < secretLen) {
                                confirmSecretPin += d
                            }
                        },
                        onBackspace = {
                            secretErr = null
                            if (confirmStep) {
                                if (confirmSecretPin.isNotEmpty()) confirmSecretPin = confirmSecretPin.dropLast(1)
                                else confirmStep = false
                            } else if (newSecretPin.isNotEmpty()) {
                                newSecretPin = newSecretPin.dropLast(1)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val res = viewModel.setOrChangeSecretMpin(newSecretPin, confirmSecretPin)
                            if (res.isSuccess) {
                                showSecretMpinNew = false
                            } else {
                                secretErr = res.exceptionOrNull()?.localizedMessage ?: "Failed to save Secret MPIN."
                                confirmSecretPin = ""
                                confirmStep = false
                                newSecretPin = ""
                            }
                        },
                        enabled = matched,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSync, contentColor = VoidBlack),
                        modifier = Modifier.fillMaxWidth().height(46.dp).testTag("save_secret_mpin_button")
                    ) {
                        Text("Save ${secretLen}-Digit Secret MPIN", fontWeight = FontWeight.Bold)
                    }
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = {
                            newSecretPin = ""
                            confirmSecretPin = ""
                            confirmStep = false
                            secretErr = null
                        }) { Text("Start Over", color = ElectricBlue) }
                        TextButton(onClick = { showSecretMpinNew = false }) { Text("Cancel") }
                    }
                }
            }
        }
    }

    if (showRemoveBioMpin) {
        MpinVerificationDialog(
            title = "Disable Biometric Unlock",
            subtitle = "Enter Login MPIN to remove biometric binding",
            verifyMpin = viewModel::verifyMpinForAction,
            lockoutSeconds = lockoutSeconds,
            maxDigits = viewModel.loginMpinLength(),
            onDismiss = { showRemoveBioMpin = false },
            onVerified = {
                viewModel.toggleBiometricSetting(false)
                bioEnabled = false
                showRemoveBioMpin = false
            }
        )
    }

    if (showSecurityLogs) {
        val fmt = remember { SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault()) }
        Dialog(onDismissRequest = { showSecurityLogs = false }) {
            GlassCard4D(contentPadding = PaddingValues(18.dp), modifier = Modifier.heightIn(max = 460.dp)) {
                Text("SECURITY AUDIT LOGS", style = MaterialTheme.typography.titleLarge, color = ElectricBlue)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                    items(securityLogs, key = { it.id }) { log ->
                        Column {
                            Text(
                                "${log.eventType} (${if (log.success) "OK" else "DENIED"})",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (log.success) EmeraldSync else CrimsonAlert
                            )
                            Text(log.detail, style = MaterialTheme.typography.bodyMedium, color = SilverWhite)
                            Text(fmt.format(Date(log.timestamp)), style = MaterialTheme.typography.labelSmall, color = MutedSlate)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = { showSecurityLogs = false }, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }

    if (showSqlDialog) {
        Dialog(onDismissRequest = { showSqlDialog = false }) {
            GlassCard4D(contentPadding = PaddingValues(18.dp), modifier = Modifier.heightIn(max = 500.dp)) {
                Text("Firebase + 18-Table SQL Backend", style = MaterialTheme.typography.titleLarge, color = ElectricBlue)
                Text(
                    "Set BACKEND_API_URL in AI Studio Secrets, apply database/schema.sql & storage.rules, and deploy backend/.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MetallicSilver
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        Text(
                            SqlSchemaViewerData.sqlAndStorageRulesSummary,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = SilverWhite
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showSqlDialog = false }) { Text("Close") }
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(SqlSchemaViewerData.sqlAndStorageRulesSummary))
                        showSqlDialog = false
                    }) { Text("Copy Overview") }
                }
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Lock DSK LOCKER?") },
            text = { Text("Your vault will lock immediately and require your 6-digit MPIN on the next unlock. All local and cloud files remain safe.") },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirm = false
                        viewModel.logout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert, contentColor = SilverWhite)
                ) { Text("Lock App") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") }
            }
        )
    }

    showHelpPrivacyDialog?.let { kind ->
        AlertDialog(
            onDismissRequest = { showHelpPrivacyDialog = null },
            title = { Text(if (kind == "PRIVACY") "Privacy & Zero-Trust Security" else "Help & Features Guide") },
            text = {
                Text(
                    if (kind == "PRIVACY") {
                        "• MPIN is hashed with PBKDF2-HMAC-SHA256 + random salt and never stored in plain text.\n" +
                            "• Vault files are encrypted on-device using AndroidKeyStore AES-256-GCM before storage or Firebase upload.\n" +
                            "• SQL Backend verifies Firebase ID tokens on every request and locks access to your single Firebase UID.\n" +
                            "• Deleting a file from your phone never deletes Firebase Storage backups automatically."
                    } else {
                        "• Upload files via Dashboard or Files tab (supports multi-select & Photo Picker).\n" +
                            "• Open any PDF, DOCX, or Image and tap AI OCR & Details to extract official fields & set expiry reminders.\n" +
                            "• Every delete action requires MPIN verification.\n" +
                            "• Use Pull-to-Refresh or the header Refresh icon to reconcile local & cloud state safely."
                    }
                )
            },
            confirmButton = {
                Button(onClick = { showHelpPrivacyDialog = null }) { Text("Got It") }
            }
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SilverWhite)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp), color = MetallicSilver)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SilverWhite)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp), color = MetallicSilver)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MutedSlate)
    }
}
