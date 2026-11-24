package com.example.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Notes
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import com.example.data.local.*
import com.example.data.remote.AuthValidation
import com.example.data.remote.SqlSchemaViewerData
import com.example.security.BiometricAvailability
import com.example.security.BiometricHelper
import com.example.storage.BackupFrequency
import com.example.storage.BackupScheduler
import com.example.storage.LocalFileManager
import com.example.ui.DskLockerViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class RestoreDisplayRow(
    val fileId: String,
    val displayName: String,
    val categoryId: String,
    val sizeBytes: Long,
    val isInVault: Boolean,
    val backupStatus: BackupStatus,
    val hasLocalCopy: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onSave: (hour: Int, minute: Int) -> Unit
) {
    val timeState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Backup Start Time", color = SilverWhite) },
        text = {
            TimePicker(state = timeState)
        },
        confirmButton = {
            Button(
                onClick = { onSave(timeState.hour, timeState.minute) },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                modifier = Modifier.testTag("backup_time_save_button")
            ) { Text("Save Time") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun BackupRestoreScreen(
    viewModel: DskLockerViewModel,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val activeFiles by viewModel.activeFiles.collectAsState()
    val vaultFiles by viewModel.vaultFiles.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val activeNotes by viewModel.activeNotes.collectAsState()
    val settingsState by viewModel.settings.collectAsState()
    val history by viewModel.backupHistory.collectAsState()
    val backupProgress by viewModel.backupProgress.collectAsState()
    val restoreProgress by viewModel.restoreProgress.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val cloudStatus by viewModel.cloudBackupStatus.collectAsState()
    val cloudRestorePreview by viewModel.cloudRestorePreview.collectAsState()
    val cloudHistory by viewModel.cloudHistory.collectAsState()
    val backendHealth by viewModel.backendHealth.collectAsState()
    val backendHealthError by viewModel.backendHealthError.collectAsState()
    val cloudAuthLoading by viewModel.cloudAuthLoading.collectAsState()
    val cloudAuthError by viewModel.cloudAuthError.collectAsState()
    val cloudAuthNotice by viewModel.cloudAuthNotice.collectAsState()
    val otpVerificationId by viewModel.otpVerificationId.collectAsState()
    val otpPendingPhone by viewModel.otpPendingPhone.collectAsState()
    val otpResendCooldownSeconds by viewModel.otpResendCooldownSeconds.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()

    val settings = settingsState ?: UserSettingsEntity()
    val configIssue = viewModel.cloudBackendConfigurationIssue()
    val hasCloudSession by viewModel.cloudSessionActive.collectAsState()
    val cloudEmailVerified by viewModel.cloudEmailVerified.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.attemptSilentCloudSignIn()
    }

    LaunchedEffect(hasCloudSession) {
        viewModel.refreshCloudBackupDashboard()
    }

    val allLocalFiles = activeFiles + vaultFiles
    val localMap = remember(allLocalFiles) { allLocalFiles.associateBy { it.id } }
    val remoteFiles = cloudRestorePreview?.files.orEmpty()

    val cloudBackedRows = remember(allLocalFiles, remoteFiles) {
        val merged = linkedMapOf<String, RestoreDisplayRow>()
        remoteFiles.forEach { rf ->
            val local = localMap[rf.fileId]
            val localExists = local?.localPath?.let { File(it).exists() } == true
            merged[rf.fileId] = RestoreDisplayRow(
                fileId = rf.fileId,
                displayName = rf.displayName,
                categoryId = rf.categoryId,
                sizeBytes = rf.sizeBytes,
                isInVault = rf.isInVault,
                backupStatus = if (localExists) BackupStatus.MOBILE_AND_CLOUD else BackupStatus.CLOUD_ONLY,
                hasLocalCopy = localExists
            )
        }
        allLocalFiles.filter { !it.cloudPath.isNullOrBlank() }.forEach { lf ->
            if (!merged.containsKey(lf.id)) {
                merged[lf.id] = RestoreDisplayRow(
                    fileId = lf.id,
                    displayName = lf.displayName,
                    categoryId = lf.categoryId,
                    sizeBytes = lf.sizeBytes,
                    isInVault = lf.isInVault,
                    backupStatus = lf.backupStatus,
                    hasLocalCopy = lf.localPath?.let { File(it).exists() } == true
                )
            }
        }
        merged.values.toList()
    }

    val failedFiles = remember(allLocalFiles) { allLocalFiles.filter { it.backupStatus == BackupStatus.BACKUP_FAILED } }
    val pendingFiles = remember(allLocalFiles) {
        allLocalFiles.filter {
            it.backupStatus == BackupStatus.MOBILE_ONLY ||
                it.backupStatus == BackupStatus.BACKUP_PENDING ||
                it.backupStatus == BackupStatus.BACKUP_FAILED
        }
    }
    val localBackedCount = remember(allLocalFiles) {
        allLocalFiles.count { !it.cloudPath.isNullOrBlank() || it.backupStatus == BackupStatus.MOBILE_AND_CLOUD }
    }
    val totalDocsCount = maxOf(allLocalFiles.size, cloudStatus?.backedUpFilesCount ?: 0)
    val backedUpDocsCount = cloudStatus?.backedUpFilesCount ?: maxOf(localBackedCount, cloudBackedRows.size)
    val totalBackupBytes = cloudStatus?.totalBackupSizeBytes ?: allLocalFiles
        .filter { !it.cloudPath.isNullOrBlank() }
        .sumOf { it.sizeBytes }
    val quotaLimitBytes = cloudStatus?.quotaLimitBytes ?: (450L * 1024L * 1024L)
    val lastBackupTs = cloudStatus?.lastBackupAt ?: viewModel.lastBackupMs()

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Backup Dashboard, 1 = Cloud Restore, 2 = History
    var selectedRestoreIds by remember { mutableStateOf(setOf<String>()) }
    var restoreCategoryFilter by remember { mutableStateOf<String?>(null) }
    var showCloudOtpDialog by remember { mutableStateOf(false) }
    var pendingRestoreIds by remember { mutableStateOf<List<String>?>(null) }
    var showRestoreMpinGate by remember { mutableStateOf(false) }
    var pendingVerifiedMpin by remember { mutableStateOf<String?>(null) }
    var overwriteConfirmIds by remember { mutableStateOf<List<String>?>(null) }
    var restoreMpinError by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    LaunchedEffect(hasCloudSession) {
        if (hasCloudSession && showCloudOtpDialog) {
            showCloudOtpDialog = false
        }
    }

    fun triggerRestoreFlow(targetIds: List<String>?) {
        val idsToRestore = targetIds
        val targetRows = if (idsToRestore == null) cloudBackedRows else cloudBackedRows.filter { it.fileId in idsToRestore }
        val requiresMpinKeyCheck = targetRows.any { it.isInVault } || (cloudStatus?.hasWrappedRecoveryKey == true)
        if (requiresMpinKeyCheck && viewModel.hasMpin()) {
            pendingRestoreIds = idsToRestore
            restoreMpinError = null
            showRestoreMpinGate = true
        } else if (targetRows.any { it.hasLocalCopy }) {
            pendingVerifiedMpin = null
            overwriteConfirmIds = idsToRestore ?: emptyList()
        } else {
            viewModel.restoreFullAccountBackup(fileIds = idsToRestore, overwrite = false, verifiedMpinForKeyRecovery = null)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SilverWhite)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("NEON CLOUD BACKUP", style = MaterialTheme.typography.headlineMedium, color = SilverWhite)
                    Text(
                        "${if (hasCloudSession) viewModel.ownerPhoneDisplay() else "Not Signed In"} • ${if (isOnline) "Online" else "Offline (Queued)"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOnline && hasCloudSession) EmeraldSync else AmberPending
                    )
                }
                OutlinedButton(
                    onClick = { showCloudOtpDialog = true },
                    border = BorderStroke(1.dp, if (hasCloudSession) EmeraldSync else ElectricBlue),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("cloud_account_connect_button")
                ) {
                    Icon(
                        if (hasCloudSession) Icons.Filled.VerifiedUser else Icons.Filled.PhoneAndroid,
                        contentDescription = null,
                        tint = if (hasCloudSession) EmeraldSync else ElectricBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (hasCloudSession) "Linked" else "Cloud Login",
                        fontSize = 11.sp,
                        color = if (hasCloudSession) EmeraldSync else ElectricBlue
                    )
                }
            }
        }

        if (hasCloudSession && cloudEmailVerified == false) {
            item {
                GlassCard4D(glowColor = AmberPending, contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.MarkEmailUnread, contentDescription = null, tint = AmberPending)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("EMAIL VERIFICATION PENDING", style = MaterialTheme.typography.labelLarge, color = AmberPending)
                            Text(
                                "Verify your email address via the link sent to your inbox, then tap Refresh.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SilverWhite
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    enabled = !cloudAuthLoading,
                                    onClick = { viewModel.sendCloudEmailVerification() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("Resend Email", fontSize = 11.sp) }
                                Button(
                                    enabled = !cloudAuthLoading,
                                    onClick = { viewModel.refreshCloudEmailVerification() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("Refresh Status", fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        }

        if (configIssue != null) {
            item {
                GlassCard4D(glowColor = CrimsonAlert, contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = CrimsonAlert)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("RENDER BACKEND CONFIGURATION REQUIRED", style = MaterialTheme.typography.labelLarge, color = CrimsonAlert)
                            Text(configIssue, style = MaterialTheme.typography.bodySmall, color = SilverWhite)
                        }
                    }
                }
            }
        }

        // Cloud Backup Dashboard Metrics Card
        item {
            GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("NEON POSTGRESQL VAULT SYNC", style = MaterialTheme.typography.labelLarge, color = ElectricBlue)
                        Text(
                            if (lastBackupTs > 0L) "Last backup: ${fmt.format(Date(lastBackupTs))}" else "Last backup: Never",
                            style = MaterialTheme.typography.labelSmall,
                            color = MetallicSilver
                        )
                        val liveHealthText = when {
                            backendHealth?.reachable == true ->
                                "Render API: Online (${backendHealth!!.status}) • ${backendHealth!!.baseUrl}"
                            !backendHealthError.isNullOrBlank() ->
                                "Render API: $backendHealthError"
                            else ->
                                "Render API: ${com.example.data.remote.BackendConfig.apiBaseUrl}"
                        }
                        Text(
                            text = liveHealthText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (backendHealth?.reachable == true) EmeraldSync else AmberPending,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { viewModel.checkLiveBackendHealth(showBanner = true) }
                        )
                    }
                    Surface(
                        color = if (settings.autoBackupEnabled) EmeraldSync.copy(alpha = 0.16f) else AmberPending.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, if (settings.autoBackupEnabled) EmeraldSync else AmberPending)
                    ) {
                        Text(
                            if (settings.autoBackupEnabled) "BACKUP ON" else "PAUSED",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = if (settings.autoBackupEnabled) EmeraldSync else AmberPending,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CloudMetricTile("Total Docs", "$totalDocsCount", SilverWhite, Modifier.weight(1f))
                    CloudMetricTile("Backed Up", "$backedUpDocsCount", EmeraldSync, Modifier.weight(1f))
                    CloudMetricTile("Pending", "${pendingFiles.size}", AmberPending, Modifier.weight(1f))
                    CloudMetricTile("Failed", "${failedFiles.size}", if (failedFiles.isNotEmpty()) CrimsonAlert else MetallicSilver, Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Cloud Size: ${LocalFileManager.formatBytes(totalBackupBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = SilverWhite
                    )
                    Text(
                        "Quota: ${LocalFileManager.formatBytes(quotaLimitBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MetallicSilver
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                val quotaFraction = if (quotaLimitBytes > 0L) (totalBackupBytes.toFloat() / quotaLimitBytes.toFloat()).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { quotaFraction },
                    color = if (quotaFraction > 0.85f) CrimsonAlert else ElectricBlue,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(selected = selectedTab == 0, onClick = { selectedTab = 0 }, label = { Text("Backup") }, modifier = Modifier.weight(1f))
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        viewModel.refreshCloudBackupDashboard()
                    },
                    label = { Text("Restore") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        viewModel.refreshCloudBackupDashboard()
                    },
                    label = { Text("History") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (backupProgress != null) {
            item {
                GlassCard4D(glowColor = EmeraldSync, contentPadding = PaddingValues(14.dp)) {
                    Text("Backing up ${backupProgress!!.first}", style = MaterialTheme.typography.labelLarge, color = EmeraldSync)
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { backupProgress!!.second }, color = EmeraldSync, modifier = Modifier.fillMaxWidth())
                }
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
                        Text("CLOUD BACKUP SETTINGS", style = MaterialTheme.typography.titleMedium, color = ElectricBlue)
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingSwitchRow("Cloud Backup Enabled", "Sync encrypted documents, OCR, notes & links to Neon", settings.autoBackupEnabled) { v ->
                            viewModel.updateSettings { it.copy(autoBackupEnabled = v) }
                        }
                        SettingSwitchRow("Wi-Fi Only", if (settings.wifiOnly) "Wi-Fi Only enabled" else "Mobile Data allowed", settings.wifiOnly) { v ->
                            viewModel.updateSettings { it.copy(wifiOnly = v) }
                        }
                        SettingSwitchRow(
                            "Charging Only",
                            if (settings.chargingOnly) "Auto-backup waits until charger is connected" else "Runs on battery or charger",
                            settings.chargingOnly
                        ) { v ->
                            viewModel.updateSettings { it.copy(chargingOnly = v) }
                        }
                        SettingSwitchRow("Photos Backup", "Auto-backup Photos & personal images", settings.backupPhotos) { v ->
                            viewModel.updateSettings { it.copy(backupPhotos = v) }
                        }
                        SettingSwitchRow("Videos Backup", "Auto-backup Videos category", settings.backupVideos) { v ->
                            viewModel.updateSettings { it.copy(backupVideos = v) }
                        }
                        SettingSwitchRow("Documents Backup", "PDF, DOCX, Excel, OCR & Reminders", settings.backupDocuments) { v ->
                            viewModel.updateSettings { it.copy(backupDocuments = v) }
                        }
                        HorizontalDivider(color = ElectricBlue.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 8.dp))
                        Text("Automatic Backup Schedule", style = MaterialTheme.typography.titleSmall, color = ElectricBlue)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            BackupFrequency.entries.forEach { freq ->
                                val selected = BackupFrequency.fromId(settings.backupFrequency) == freq
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.updateSettings { it.copy(backupFrequency = freq.id) } },
                                    label = { Text(freq.label, fontSize = 12.sp) },
                                    modifier = Modifier.testTag("backup_freq_${freq.id.lowercase()}")
                                )
                            }
                        }
                        var showBackupTimePicker by remember { mutableStateOf(false) }
                        Spacer(modifier = Modifier.height(6.dp))
                        ActionRow(
                            icon = Icons.Filled.Schedule,
                            title = "Preferred Backup Time",
                            subtitle = "${BackupFrequency.fromId(settings.backupFrequency).label} window starting ${BackupScheduler.formatBackupTime(settings.backupHour, settings.backupMinute)}"
                        ) {
                            showBackupTimePicker = true
                        }
                        if (showBackupTimePicker) {
                            BackupTimePickerDialog(
                                initialHour = settings.backupHour,
                                initialMinute = settings.backupMinute,
                                onDismiss = { showBackupTimePicker = false },
                                onSave = { h, m ->
                                    viewModel.updateSettings { it.copy(backupHour = h, backupMinute = m) }
                                    showBackupTimePicker = false
                                }
                            )
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
                                .height(48.dp)
                                .testTag("manual_backup_now_button")
                        ) {
                            Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("BACKUP NOW", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.runManualBackupNow(failedFiles) },
                            enabled = failedFiles.isNotEmpty(),
                            border = BorderStroke(1.dp, AmberPending),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Filled.Replay, contentDescription = null, tint = AmberPending, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Failed (${failedFiles.size})", color = AmberPending)
                        }
                    }
                }
            }

            1 -> {
                // Cloud Restore Summary Card
                item {
                    GlassCard4D(glowColor = NeonViolet, contentPadding = PaddingValues(16.dp)) {
                        Text("CLOUD RESTORE SUMMARY", style = MaterialTheme.typography.titleMedium, color = NeonViolet)
                        Spacer(modifier = Modifier.height(6.dp))
                        val remoteSnap = cloudRestorePreview?.snapshot
                        val sumFiles = cloudStatus?.backedUpFilesCount ?: cloudBackedRows.size
                        val sumPhotos = cloudStatus?.photosCount ?: cloudBackedRows.count { it.categoryId == DskCategories.CAT_PHOTOS }
                        val sumDocs = (cloudStatus?.documentsCount ?: 0) + (cloudStatus?.pdfsCount ?: 0)
                        val sumNotes = cloudStatus?.notesCount ?: (remoteSnap?.textNotes?.size ?: activeNotes.size)
                        val sumFolders = cloudStatus?.foldersCount ?: (remoteSnap?.folders?.size ?: folders.size)
                        Text(
                            "Backed-up Files: $sumFiles • Photos: $sumPhotos • Docs/PDFs: $sumDocs • Notes: $sumNotes • Folders: $sumFolders",
                            style = MaterialTheme.typography.labelMedium,
                            color = SilverWhite
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Backup Size: ${LocalFileManager.formatBytes(totalBackupBytes)} • ${if (lastBackupTs > 0L) "Last: ${fmt.format(Date(lastBackupTs))}" else "Not yet backed up"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MetallicSilver
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (cloudStatus?.hasWrappedRecoveryKey == true) {
                                "Vault Key Recovery: Wrapped Recovery Key protected by Render backend + MPIN"
                            } else {
                                "Vault Key Recovery: Local device key only (non-vault files always restore cleanly)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (cloudStatus?.hasWrappedRecoveryKey == true) EmeraldSync else AmberPending
                        )
                    }
                }

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

                val candidates = cloudBackedRows.filter {
                    restoreCategoryFilter == null || DskCategories.matchesCategory(it.categoryId, restoreCategoryFilter)
                }

                item {
                    Button(
                        onClick = {
                            val targets = if (selectedRestoreIds.isNotEmpty()) {
                                candidates.filter { it.fileId in selectedRestoreIds }.map { it.fileId }
                            } else {
                                null
                            }
                            triggerRestoreFlow(targets)
                        },
                        enabled = candidates.isNotEmpty() || (cloudStatus?.notesCount ?: 0) > 0 || (cloudStatus?.foldersCount ?: 0) > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("restore_selected_or_all_button")
                    ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (selectedRestoreIds.isNotEmpty()) {
                                "RESTORE SELECTED (${selectedRestoreIds.size})"
                            } else {
                                "RESTORE BACKUP (${candidates.size} Files)"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (candidates.isEmpty()) {
                    item {
                        DskEmptyStateCard(
                            title = if (hasCloudSession) "No Cloud Backups Found" else "Sign In To Restore",
                            subtitle = if (hasCloudSession) {
                                "Tap Backup Now in the Backup tab to back up your files & OCR metadata to Neon PostgreSQL."
                            } else {
                                "Tap Cloud Login above and Sign in with Google to fetch your Neon backups after reinstalling."
                            },
                            icon = Icons.Filled.CloudOff,
                            glowColor = ElectricBlue
                        )
                    }
                } else {
                    items(candidates, key = { it.fileId }) { row ->
                        val selected = row.fileId in selectedRestoreIds
                        GlassCard4D(
                            glowColor = if (selected) ElectricBlue else NeonViolet,
                            onClick = {
                                selectedRestoreIds = if (selected) selectedRestoreIds - row.fileId else selectedRestoreIds + row.fileId
                            },
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = selected, onCheckedChange = {
                                    selectedRestoreIds = if (selected) selectedRestoreIds - row.fileId else selectedRestoreIds + row.fileId
                                })
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(row.displayName, style = MaterialTheme.typography.titleMedium, color = SilverWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        BackupStatusBadge(status = row.backupStatus)
                                        Text(
                                            LocalFileManager.formatBytes(row.sizeBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MetallicSilver
                                        )
                                    }
                                }
                                TextButton(onClick = { triggerRestoreFlow(listOf(row.fileId)) }) {
                                    Text("Restore", color = ElectricBlue)
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                val remoteBackupLogs = cloudHistory?.backups.orEmpty()
                val remoteRestoreLogs = cloudHistory?.restores.orEmpty()
                if (remoteRestoreLogs.isNotEmpty()) {
                    item {
                        Text("CLOUD RESTORE HISTORY (NEON)", style = MaterialTheme.typography.labelLarge, color = NeonViolet)
                    }
                    items(remoteRestoreLogs, key = { "rr_${it.id}" }) { r ->
                        GlassCard4D(
                            glowColor = if (r.status == "COMPLETED") EmeraldSync else AmberPending,
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Account Restore (${r.verifiedFiles}/${r.totalFiles} files)", style = MaterialTheme.typography.titleMedium, color = SilverWhite)
                                    Text(fmt.format(Date(r.startedAt)), style = MaterialTheme.typography.labelSmall, color = MetallicSilver)
                                    if (r.message.isNotBlank()) {
                                        Text(r.message, style = MaterialTheme.typography.bodySmall, color = MutedSlate)
                                    }
                                }
                                Text(r.status, style = MaterialTheme.typography.labelLarge, color = if (r.status == "COMPLETED") EmeraldSync else AmberPending)
                            }
                        }
                    }
                }

                if (history.isEmpty() && remoteBackupLogs.isEmpty() && remoteRestoreLogs.isEmpty()) {
                    item {
                        GlassCard4D(contentPadding = PaddingValues(24.dp)) {
                            Text("No backup or restore events recorded yet.", style = MaterialTheme.typography.bodyMedium, color = MetallicSilver)
                        }
                    }
                } else {
                    if (remoteBackupLogs.isNotEmpty()) {
                        item {
                            Text("CLOUD BACKUP HISTORY (NEON)", style = MaterialTheme.typography.labelLarge, color = ElectricBlue)
                        }
                        items(remoteBackupLogs, key = { "rb_${it.id}" }) { entry ->
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

                    if (history.isNotEmpty()) {
                        item {
                            Text("DEVICE QUEUE & BACKUP LOGS", style = MaterialTheme.typography.labelLarge, color = MetallicSilver)
                        }
                        items(history, key = { "lh_${it.id}" }) { entry ->
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
    }

    if (showCloudOtpDialog) {
        val defaultCountry = AuthValidation.supportedCountryCodes.first()
        var selectedCountry by remember { mutableStateOf(defaultCountry) }
        var countryDropdownExpanded by remember { mutableStateOf(false) }
        var phoneDigitsInput by remember {
            mutableStateOf(
                otpPendingPhone?.removePrefix(defaultCountry.dialCode)
                    ?: viewModel.ownerPhoneDisplay()
                        .takeIf { it.startsWith("+") }
                        ?.removePrefix(defaultCountry.dialCode)
                        ?.filter { it.isDigit() }
                    ?: ""
            )
        }
        var otpCode by remember { mutableStateOf("") }
        // 0 = Google + Email, 1 = Phone OTP
        var selectedAuthTab by remember { mutableIntStateOf(if (otpVerificationId != null) 1 else 0) }
        // 0 = Sign In, 1 = Register, 2 = Reset Password
        var emailMode by remember { mutableIntStateOf(0) }
        var emailInput by remember { mutableStateOf("") }
        var passwordInput by remember { mutableStateOf("") }
        var confirmPasswordInput by remember { mutableStateOf("") }
        var ownerNameInput by remember { mutableStateOf("") }
        var showPassword by remember { mutableStateOf(false) }

        Dialog(onDismissRequest = {
            viewModel.cancelCloudOtp()
            showCloudOtpDialog = false
        }) {
            GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(20.dp)) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("CLOUD ACCOUNT LOGIN", style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        if (hasCloudSession) {
                            "Linked to ${viewModel.ownerPhoneDisplay()}. Encrypted backups and restore are active on Neon PostgreSQL."
                        } else {
                            "Connect with Google, Email & Password, or Phone OTP to sync encrypted backups."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MetallicSilver
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    if (hasCloudSession) {
                        if (cloudEmailVerified != null) {
                            Surface(
                                color = if (cloudEmailVerified == true) EmeraldSync.copy(alpha = 0.14f) else AmberPending.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (cloudEmailVerified == true) EmeraldSync.copy(alpha = 0.5f) else AmberPending.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        if (cloudEmailVerified == true) "Email Verified" else "Email Verification Pending",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (cloudEmailVerified == true) EmeraldSync else AmberPending
                                    )
                                    if (cloudEmailVerified == false) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                enabled = !cloudAuthLoading,
                                                onClick = { viewModel.sendCloudEmailVerification() }
                                            ) { Text("Send Link", fontSize = 11.sp) }
                                            Button(
                                                enabled = !cloudAuthLoading,
                                                onClick = { viewModel.refreshCloudEmailVerification() }
                                            ) { Text("Check Verified", fontSize = 11.sp) }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        OutlinedButton(
                            enabled = !cloudAuthLoading,
                            onClick = {
                                viewModel.signOutCloudAccount()
                                showCloudOtpDialog = false
                            },
                            border = BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("cloud_sign_out_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = CrimsonAlert, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign Out Cloud Session", color = CrimsonAlert, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            enabled = !cloudAuthLoading,
                            onClick = { viewModel.signInCloudWithGoogle(activity ?: context) },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("google_sign_in_button")
                        ) {
                            if (cloudAuthLoading && selectedAuthTab == 0 && emailInput.isBlank()) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = VoidBlack
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connecting Google...", fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Sign in with Google", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = selectedAuthTab == 0,
                                onClick = {
                                    selectedAuthTab = 0
                                    viewModel.clearCloudAuthMessages()
                                },
                                label = { Text("Email Login") },
                                leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("auth_mode_email_tab")
                            )
                            FilterChip(
                                selected = selectedAuthTab == 1,
                                onClick = {
                                    selectedAuthTab = 1
                                    viewModel.clearCloudAuthMessages()
                                },
                                label = { Text("Phone OTP") },
                                leadingIcon = { Icon(Icons.Filled.Sms, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("auth_mode_phone_tab")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (selectedAuthTab == 0) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilterChip(
                                    selected = emailMode == 0,
                                    onClick = { emailMode = 0; viewModel.clearCloudAuthMessages() },
                                    label = { Text("Sign In", fontSize = 11.sp) },
                                    modifier = Modifier.testTag("email_mode_signin")
                                )
                                FilterChip(
                                    selected = emailMode == 1,
                                    onClick = { emailMode = 1; viewModel.clearCloudAuthMessages() },
                                    label = { Text("Register", fontSize = 11.sp) },
                                    modifier = Modifier.testTag("email_mode_register")
                                )
                                FilterChip(
                                    selected = emailMode == 2,
                                    onClick = { emailMode = 2; viewModel.clearCloudAuthMessages() },
                                    label = { Text("Reset", fontSize = 11.sp) },
                                    modifier = Modifier.testTag("email_mode_reset")
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            if (emailMode == 1) {
                                OutlinedTextField(
                                    value = ownerNameInput,
                                    onValueChange = { ownerNameInput = it },
                                    label = { Text("Full Name (Optional)") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("email_auth_name_input")
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            OutlinedTextField(
                                value = emailInput,
                                onValueChange = { emailInput = it },
                                label = { Text("Email Address") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("email_auth_email_input")
                            )
                            if (emailMode != 2) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = passwordInput,
                                    onValueChange = { passwordInput = it },
                                    label = { Text("Password") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { showPassword = !showPassword }) {
                                            Icon(
                                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                                contentDescription = if (showPassword) "Hide password" else "Show password"
                                            )
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("email_auth_password_input")
                                )
                            }
                            if (emailMode == 1) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = confirmPasswordInput,
                                    onValueChange = { confirmPasswordInput = it },
                                    label = { Text("Confirm Password") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("email_auth_confirm_password_input")
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                enabled = !cloudAuthLoading,
                                onClick = {
                                    when (emailMode) {
                                        0 -> viewModel.signInCloudWithEmail(emailInput, passwordInput)
                                        1 -> viewModel.registerCloudWithEmail(
                                            email = emailInput,
                                            password = passwordInput,
                                            confirmPassword = confirmPasswordInput,
                                            displayName = ownerNameInput
                                        )
                                        else -> viewModel.sendCloudPasswordReset(emailInput)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("email_auth_submit_button")
                            ) {
                                if (cloudAuthLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = VoidBlack)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    when (emailMode) {
                                        0 -> "Sign In with Email"
                                        1 -> "Create Cloud Account"
                                        else -> "Send Password Reset Email"
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (emailMode == 0) {
                                TextButton(
                                    onClick = { emailMode = 2; viewModel.clearCloudAuthMessages() },
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .testTag("forgot_password_button")
                                ) {
                                    Text("Forgot Password?", fontSize = 12.sp, color = ElectricBlue)
                                }
                            }
                        } else {
                            if (otpVerificationId == null) {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { countryDropdownExpanded = true },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("phone_country_code_selector")
                                    ) {
                                        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "${selectedCountry.isoCode} (${selectedCountry.dialCode}) — ${selectedCountry.countryName}",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select country code")
                                    }
                                    DropdownMenu(
                                        expanded = countryDropdownExpanded,
                                        onDismissRequest = { countryDropdownExpanded = false }
                                    ) {
                                        AuthValidation.supportedCountryCodes.forEach { country ->
                                            DropdownMenuItem(
                                                text = { Text("${country.isoCode} (${country.dialCode}) ${country.countryName}") },
                                                onClick = {
                                                    selectedCountry = country
                                                    countryDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = phoneDigitsInput,
                                    onValueChange = { raw ->
                                        phoneDigitsInput = raw.filter { it.isDigit() || it == '+' }.take(15)
                                    },
                                    label = { Text("Mobile Number (${selectedCountry.dialCode})") },
                                    placeholder = { Text("9876543210") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("phone_number_input")
                                )
                            } else {
                                Text(
                                    "Enter the 6-digit OTP sent to ${otpPendingPhone.orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MetallicSilver
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = otpCode,
                                    onValueChange = { if (it.length <= 6) otpCode = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("6-Digit OTP") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("phone_otp_input")
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextButton(
                                        enabled = !cloudAuthLoading,
                                        onClick = { viewModel.cancelCloudOtp() },
                                        modifier = Modifier.testTag("change_phone_number_button")
                                    ) {
                                        Text("Change Number", fontSize = 12.sp)
                                    }
                                    TextButton(
                                        enabled = !cloudAuthLoading && activity != null && otpResendCooldownSeconds == 0,
                                        onClick = {
                                            val targetPhone = otpPendingPhone ?: phoneDigitsInput
                                            activity?.let {
                                                viewModel.sendCloudOtp(
                                                    activity = it,
                                                    rawPhoneInput = targetPhone,
                                                    countryCode = selectedCountry.dialCode,
                                                    isResend = true
                                                )
                                            }
                                        },
                                        modifier = Modifier.testTag("resend_otp_button")
                                    ) {
                                        Text(
                                            if (otpResendCooldownSeconds > 0) {
                                                "Resend OTP (${otpResendCooldownSeconds}s)"
                                            } else {
                                                "Resend OTP"
                                            },
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (cloudAuthNotice != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(cloudAuthNotice!!, style = MaterialTheme.typography.labelMedium, color = EmeraldSync)
                    }

                    if (cloudAuthError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(cloudAuthError!!, style = MaterialTheme.typography.labelMedium, color = CrimsonAlert)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            viewModel.cancelCloudOtp()
                            showCloudOtpDialog = false
                        }) { Text("Close") }
                        if (!hasCloudSession && selectedAuthTab == 1) {
                            Spacer(modifier = Modifier.width(8.dp))
                            if (otpVerificationId == null) {
                                Button(
                                    enabled = !cloudAuthLoading && activity != null,
                                    onClick = {
                                        activity?.let {
                                            viewModel.sendCloudOtp(
                                                activity = it,
                                                rawPhoneInput = phoneDigitsInput,
                                                countryCode = selectedCountry.dialCode,
                                                isResend = false
                                            )
                                        }
                                    },
                                    modifier = Modifier.testTag("send_otp_button")
                                ) {
                                    if (cloudAuthLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = VoidBlack)
                                    } else {
                                        Text("Send OTP")
                                    }
                                }
                            } else {
                                Button(
                                    enabled = !cloudAuthLoading && otpCode.length == 6,
                                    onClick = { viewModel.verifyCloudOtp(otpCode) },
                                    modifier = Modifier.testTag("verify_otp_button")
                                ) {
                                    if (cloudAuthLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = VoidBlack)
                                    } else {
                                        Text("Verify & Link")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRestoreMpinGate) {
        var pinInput by remember { mutableStateOf("") }
        var verifying by remember { mutableStateOf(false) }
        val maxPin = viewModel.loginMpinLength()
        Dialog(onDismissRequest = { if (!verifying) showRestoreMpinGate = false }) {
            GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("VERIFY MPIN FOR RESTORE", style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Enter your ${maxPin}-digit Login MPIN to authorize cloud restore & wrapped vault key recovery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MetallicSilver
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    MpinDots(length = pinInput.length, maxDigits = maxPin, isError = restoreMpinError != null)
                    if (lockoutSeconds > 0L) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Locked out for ${lockoutSeconds}s", color = CrimsonAlert, style = MaterialTheme.typography.labelMedium)
                    } else if (restoreMpinError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(restoreMpinError!!, color = CrimsonAlert, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    MpinKeypad(
                        buttonSize = 54.dp,
                        verticalSpacing = 8.dp,
                        onDigit = { d ->
                            if (!verifying && pinInput.length < maxPin) {
                                restoreMpinError = null
                                pinInput += d
                            }
                        },
                        onBackspace = {
                            if (!verifying && pinInput.isNotEmpty()) {
                                restoreMpinError = null
                                pinInput = pinInput.dropLast(1)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        enabled = !verifying && lockoutSeconds == 0L && pinInput.length == maxPin,
                        onClick = {
                            verifying = true
                            scope.launch {
                                val res = viewModel.verifyMpinForRestoreAndKeyRecovery(pinInput)
                                verifying = false
                                if (res.isSuccess) {
                                    showRestoreMpinGate = false
                                    pendingVerifiedMpin = pinInput
                                    val ids = pendingRestoreIds
                                    val targetRows = if (ids == null) cloudBackedRows else cloudBackedRows.filter { it.fileId in ids }
                                    if (targetRows.any { it.hasLocalCopy }) {
                                        overwriteConfirmIds = ids ?: emptyList()
                                    } else {
                                        viewModel.restoreFullAccountBackup(
                                            fileIds = ids,
                                            overwrite = false,
                                            verifiedMpinForKeyRecovery = pinInput
                                        )
                                    }
                                } else {
                                    restoreMpinError = res.exceptionOrNull()?.localizedMessage ?: "MPIN verification failed."
                                    pinInput = ""
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSync, contentColor = VoidBlack),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text(if (verifying) "Verifying..." else "Verify & Continue Restore", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { showRestoreMpinGate = false }, enabled = !verifying) {
                        Text("Cancel")
                    }
                }
            }
        }
    }

    overwriteConfirmIds?.let { ids ->
        val fileIdsOrAll = ids.takeIf { it.isNotEmpty() }
        AlertDialog(
            onDismissRequest = { overwriteConfirmIds = null },
            title = { Text("Overwrite Existing Local Files?") },
            text = { Text("Some selected files already exist on phone storage. Confirm whether to overwrite them or skip existing files.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.restoreFullAccountBackup(
                        fileIds = fileIdsOrAll,
                        overwrite = true,
                        verifiedMpinForKeyRecovery = pendingVerifiedMpin
                    )
                    overwriteConfirmIds = null
                }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.restoreFullAccountBackup(
                        fileIds = fileIdsOrAll,
                        overwrite = false,
                        verifiedMpinForKeyRecovery = pendingVerifiedMpin
                    )
                    overwriteConfirmIds = null
                }) { Text("Skip Existing") }
            }
        )
    }
}

@Composable
private fun CloudMetricTile(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = VoidBlack.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, valueColor.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MetallicSilver, maxLines = 1)
        }
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
    val bioEnabled by viewModel.biometricEnabledState.collectAsState()
    val loginMpinLength by viewModel.loginMpinLengthState.collectAsState()
    val bioAvail = remember(bioEnabled) { viewModel.biometricAvailability() }

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
        viewModel.clearExternalActivityLockSuppression()
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
                                viewModel.suppressAutoLockForExternalActivity()
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
                    text = "Security Status: Login MPIN (${loginMpinLength}-Digit) • Secret MPIN (${if (secretMpinConfigured) "${viewModel.secretMpinLength()}-Digit Active" else "Not Set"})",
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
                            viewModel.suppressAutoLockForExternalActivity()
                            BiometricHelper.authenticate(
                                activity = activity,
                                title = "Enroll Biometric Unlock",
                                subtitle = "Authenticate to enable biometric unlock",
                                onSuccess = {
                                    viewModel.clearExternalActivityLockSuppression()
                                    viewModel.toggleBiometricSetting(true)
                                },
                                onFailure = {
                                    viewModel.clearExternalActivityLockSuppression()
                                }
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
                ActionRow(Icons.AutoMirrored.Filled.Notes, "Text Notes Manager", "Create & search private notes") { onOpenNotes() }
                ActionRow(Icons.Filled.VideoLibrary, "Video & Website Links", "Saved YouTube & web bookmarks") { onOpenLinks("VIDEO_URL") }
                ActionRow(Icons.Filled.DeleteSweep, "Recycle Bin", "Restore or purge deleted files") { onOpenRecycleBin() }
                ActionRow(
                    Icons.Filled.Storage,
                    "Neon PostgreSQL & Render Backup",
                    if (viewModel.isSqlBackendConfigured()) "Render API + Neon PostgreSQL Connected" else (viewModel.cloudBackendConfigurationIssue() ?: "Configure BACKEND_API_URL + DATABASE_URL")
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
                ActionRow(Icons.AutoMirrored.Filled.HelpOutline, "Help & Quick Guide", "MPIN lock, Camera + ML Kit scanner, AI OCR & backup") {
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
                    onClick = {
                        viewModel.suppressAutoLockForExternalActivity()
                        avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
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
            subtitle = "Enter your existing ${loginMpinLength}-digit Login MPIN or tap Set New MPIN",
            verifyMpin = viewModel::verifyMpinForAction,
            lockoutSeconds = lockoutSeconds,
            maxDigits = loginMpinLength,
            onDismiss = { showChangeMpinVerify = false },
            onVerified = {
                showChangeMpinVerify = false
                showChangeMpinNew = true
            },
            onForgotMpin = {
                showChangeMpinVerify = false
                viewModel.logForgotMpinVerified("Unlocked Session")
                viewModel.startForgotMpin()
            }
        )
    }

    if (showChangeMpinNew) {
        var pinLen by remember { mutableIntStateOf(loginMpinLength) }
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
            maxDigits = loginMpinLength,
            onDismiss = { showRemoveBioMpin = false },
            onVerified = {
                viewModel.toggleBiometricSetting(false)
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
                Text("Neon PostgreSQL + Render Backup API", style = MaterialTheme.typography.titleLarge, color = ElectricBlue)
                Text(
                    "Live Render API: ${com.example.data.remote.BackendConfig.apiBaseUrl} • PostgreSQL storage is verified server-side via DATABASE_URL.",
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
                            "• Vault files are encrypted on-device with AES-256-GCM before Neon PostgreSQL backup.\n" +
                            "• Render API verifies Firebase ID tokens on every request and enforces per-user Neon isolation.\n" +
                            "• Deleting a file from your phone never deletes Neon Cloud Backup copies automatically."
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
