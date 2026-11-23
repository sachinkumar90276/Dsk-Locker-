package com.example.ui.screens

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.local.*
import com.example.storage.LocalFileManager
import com.example.ui.DskLockerViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DskLockerViewModel,
    onOpenUploadPicker: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenFilesTab: (String?) -> Unit,
    onOpenVaultTab: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenLinks: (String) -> Unit,
    onOpenRecycleBin: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFileViewer: (String) -> Unit,
    onOpenSecuritySettings: () -> Unit
) {
    val activeFiles by viewModel.activeFiles.collectAsState()
    val vaultFiles by viewModel.vaultFiles.collectAsState()
    val trashFiles by viewModel.trashFiles.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val notes by viewModel.activeNotes.collectAsState()
    val links by viewModel.activeLinks.collectAsState()
    val storageStats by viewModel.storageStats.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()
    val secretMpinConfigured by viewModel.secretMpinConfigured.collectAsState()

    val allFiles = activeFiles + vaultFiles
    val mobileStorageBytes = remember(allFiles, storageStats) {
        val localSum = allFiles.filter { it.localPath != null }.sumOf { it.sizeBytes }
        maxOf(localSum, storageStats.lockerLocalBytes)
    }
    val cloudStorageBytes = remember(allFiles) {
        allFiles.filter { it.cloudPath != null }.sumOf { it.sizeBytes }
    }
    val pendingBackups = remember(allFiles) {
        allFiles.count {
            it.backupStatus == BackupStatus.MOBILE_ONLY ||
                it.backupStatus == BackupStatus.BACKUP_PENDING ||
                it.backupStatus == BackupStatus.BACKUP_FAILED
        }
    }
    val backupStatusText = when {
        !viewModel.isFirebaseSessionActive() -> "LOCAL VAULT"
        pendingBackups == 0 && allFiles.isNotEmpty() -> "SYNCED"
        pendingBackups > 0 -> "$pendingBackups PENDING"
        else -> "IDLE"
    }
    val lastBackupDisplay = remember(viewModel.lastBackupMs(), activeFiles) {
        val ms = viewModel.lastBackupMs()
        if (ms <= 0L) "Not backed up yet"
        else SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(ms))
    }

    var showSphereBreakdown by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var filePendingDelete by remember { mutableStateOf<FileItemEntity?>(null) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refreshAll() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Branded Header with Refresh, Search, Trash, Lock
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        DskLockerLogo(
                            size = 42.dp,
                            showTitles = false,
                            animateRotation = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            MetallicBrandText(
                                text = "DSK LOCKER",
                                style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 1.2.sp)
                            )
                            Text(
                                text = if (viewModel.isFirebaseSessionActive()) "Firebase Cloud Connected" else "MPIN Secured Vault",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (viewModel.isFirebaseSessionActive()) EmeraldSync else ElectricBlue
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewModel.refreshAll() },
                            modifier = Modifier.testTag("header_refresh_button")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh Workspace", tint = ElectricBlue)
                        }
                        IconButton(
                            onClick = onOpenSearch,
                            modifier = Modifier.testTag("header_search_button")
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "Global Smart Search", tint = SilverWhite)
                        }
                        BadgedBox(
                            badge = {
                                if (trashFiles.isNotEmpty()) {
                                    Badge(containerColor = CrimsonAlert) {
                                        Text("${trashFiles.size}", fontSize = 9.sp)
                                    }
                                }
                            }
                        ) {
                            IconButton(
                                onClick = onOpenRecycleBin,
                                modifier = Modifier.testTag("header_trash_button")
                            ) {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = "Recycle Bin", tint = MetallicSilver)
                            }
                        }
                        IconButton(
                            onClick = { viewModel.lockAppNow() },
                            modifier = Modifier.testTag("header_lock_button")
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = "Lock App", tint = NeonViolet)
                        }
                    }
                }
            }

            // 4D Interactive Storage Sphere + Live Metrics Card
            item {
                GlassCard4D(
                    glowColor = ElectricBlue,
                    contentPadding = PaddingValues(18.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        InteractiveStorageSphere(
                            mobileUsedBytes = mobileStorageBytes,
                            cloudUsedBytes = cloudStorageBytes,
                            deviceTotalBytes = storageStats.deviceTotalBytes,
                            onTapSphere = { showSphereBreakdown = true }
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            MetricLine("MOBILE STORAGE", LocalFileManager.formatBytes(mobileStorageBytes), ElectricBlue)
                            MetricLine("CLOUD STORAGE", LocalFileManager.formatBytes(cloudStorageBytes), NeonViolet)
                            MetricLine("BACKUP STATUS", backupStatusText, EmeraldSync)
                            MetricLine("PENDING BACKUPS", "$pendingBackups item(s)", AmberPending)
                            MetricLine("LAST BACKUP", lastBackupDisplay, MetallicSilver)
                        }
                    }
                }
            }

            // Total Files & Folders + Notes & Links Counters
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StatTile(
                        title = "TOTAL FILES",
                        value = "${allFiles.size}",
                        icon = Icons.Filled.InsertDriveFile,
                        accent = ElectricBlue,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenFilesTab(null) }
                    )
                    StatTile(
                        title = "TOTAL FOLDERS",
                        value = "${folders.size}",
                        icon = Icons.Filled.Folder,
                        accent = NeonViolet,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenFilesTab(null) }
                    )
                    StatTile(
                        title = "TEXT & URLS",
                        value = "${notes.size + links.size}",
                        icon = Icons.Filled.AutoAwesome,
                        accent = EmeraldSync,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenNotes
                    )
                }
            }

            // 8 Quick Actions Grid
            item {
                Text(
                    text = "QUICK ACTIONS",
                    style = MaterialTheme.typography.labelLarge,
                    color = ElectricBlue,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                val quickActions = listOf(
                    QuickAction("Upload File", Icons.Filled.CloudUpload, ElectricBlue, "qa_upload", onOpenUploadPicker),
                    QuickAction("Backup Now", Icons.Filled.Backup, EmeraldSync, "qa_backup") { viewModel.runManualBackupNow() },
                    QuickAction("Restore Files", Icons.Filled.CloudDownload, CyberBlue, "qa_restore", onOpenBackupRestore),
                    QuickAction("Create Folder", Icons.Filled.CreateNewFolder, NeonViolet, "qa_folder") { showCreateFolderDialog = true },
                    QuickAction("Secure Vault", Icons.Filled.Shield, PlasmaMagenta, "qa_vault", onOpenVaultTab),
                    QuickAction("Add Text", Icons.Filled.NoteAdd, ElectricBlue, "qa_note", onOpenNotes),
                    QuickAction("Save Video URL", Icons.Filled.VideoLibrary, CrimsonAlert, "qa_video_url") { onOpenLinks("VIDEO_URL") },
                    QuickAction("Save Web Link", Icons.Filled.Language, EmeraldSync, "qa_web_link") { onOpenLinks("WEBSITE_LINK") }
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    quickActions.chunked(4).forEach { rowActions ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rowActions.forEach { qa ->
                                GlassCard4D(
                                    glowColor = qa.color,
                                    onClick = qa.onClick,
                                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 6.dp),
                                    cornerRadius = 16.dp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag(qa.tag)
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = qa.color.copy(alpha = 0.16f),
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(qa.icon, contentDescription = qa.label, tint = qa.color, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = qa.label,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = SilverWhite,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Camera + ML Kit Physical Document Scanner Spotlight Card
            item {
                GlassCard4D(
                    glowColor = EmeraldSync,
                    onClick = onOpenScanner,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    cornerRadius = 18.dp,
                    modifier = Modifier.testTag("dashboard_scan_document_card")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = EmeraldSync.copy(alpha = 0.16f),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.DocumentScanner,
                                    contentDescription = "Scan Document",
                                    tint = EmeraldSync,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SCAN PHYSICAL DOCUMENT",
                                style = MaterialTheme.typography.titleMedium,
                                color = SilverWhite,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Live Camera + ML Kit detection -> AI OCR Reader",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricBlue
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = EmeraldSync
                        )
                    }
                }
            }

            // Categories Horizontal Strip
            item {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("CATEGORIES", style = MaterialTheme.typography.labelLarge, color = ElectricBlue)
                    TextButton(onClick = { onOpenFilesTab(null) }) {
                        Text("View All", color = MetallicSilver)
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DskCategories.builtInCategories) { cat ->
                        val count = when (cat.id) {
                            DskCategories.CAT_VIDEO_URLS -> links.count { it.linkType == "VIDEO_URL" }
                            DskCategories.CAT_WEBSITE_LINKS -> links.count { it.linkType == "WEBSITE_LINK" || it.linkType == "WEB_PAGE" }
                            DskCategories.CAT_TEXT -> notes.size + activeFiles.count { it.categoryId == DskCategories.CAT_TEXT }
                            else -> activeFiles.count { DskCategories.matchesCategory(it.categoryId, cat.id) }
                        }
                        Surface(
                            color = MidnightPurple.copy(alpha = 0.78f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, NeonViolet.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .testTag("dashboard_cat_${cat.id}")
                                .clickable {
                                    when (cat.id) {
                                        DskCategories.CAT_VIDEO_URLS -> onOpenLinks("VIDEO_URL")
                                        DskCategories.CAT_WEBSITE_LINKS -> onOpenLinks("WEBSITE_LINK")
                                        DskCategories.CAT_TEXT -> onOpenNotes()
                                        else -> onOpenFilesTab(cat.id)
                                    }
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(categoryIcon(cat.id), contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(cat.title, style = MaterialTheme.typography.labelMedium, color = SilverWhite)
                                if (count > 0) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("($count)", style = MaterialTheme.typography.labelSmall, color = ElectricBlue)
                                }
                            }
                        }
                    }
                }
            }

            // Recent Files Section
            item {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("RECENT FILES", style = MaterialTheme.typography.labelLarge, color = ElectricBlue)
                    if (activeFiles.isNotEmpty()) {
                        Text(
                            text = "${activeFiles.size} stored",
                            style = MaterialTheme.typography.labelSmall,
                            color = MetallicSilver
                        )
                    }
                }
            }

            if (activeFiles.isEmpty()) {
                item {
                    GlassCard4D(contentPadding = PaddingValues(24.dp)) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudUpload,
                                contentDescription = null,
                                tint = ElectricBlue,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Your Private Universe is Ready",
                                style = MaterialTheme.typography.titleMedium,
                                color = SilverWhite
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap Upload File to import photos, PDFs, DOCX, Excel, APKs, or run AI Document OCR.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MetallicSilver
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = onOpenScanner,
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldSync, contentColor = VoidBlack)
                                ) {
                                    Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Scan Doc", fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = onOpenUploadPicker,
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack)
                                ) {
                                    Text("Upload File", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else {
                items(activeFiles.take(10), key = { it.id }) { file ->
                    FileItemRowCard(
                        file = file,
                        onOpen = { onOpenFileViewer(file.id) },
                        onFavorite = { viewModel.toggleFavorite(file) },
                        onDeleteRequest = { filePendingDelete = file }
                    )
                }
            }
        }
    }

    if (showSphereBreakdown) {
        Dialog(onDismissRequest = { showSphereBreakdown = false }) {
            GlassCard4D(contentPadding = PaddingValues(20.dp)) {
                Text("4D Storage Breakdown", style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                Spacer(modifier = Modifier.height(12.dp))
                MetricLine("DSK Locker Local Storage", LocalFileManager.formatBytes(mobileStorageBytes), ElectricBlue)
                Spacer(modifier = Modifier.height(6.dp))
                MetricLine("Neon Cloud Backup", LocalFileManager.formatBytes(cloudStorageBytes), NeonViolet)
                Spacer(modifier = Modifier.height(6.dp))
                MetricLine("Device Free Space", LocalFileManager.formatBytes(storageStats.deviceAvailableBytes), EmeraldSync)
                Spacer(modifier = Modifier.height(6.dp))
                MetricLine("Encrypted Vault Files", "${vaultFiles.size} item(s)", PlasmaMagenta)
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { showSphereBreakdown = false },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Close")
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { name, catId ->
                viewModel.createFolder(name, catId)
                showCreateFolderDialog = false
            }
        )
    }

    filePendingDelete?.let { target ->
        SecureDeleteFlowDialog(
            itemName = target.displayName,
            hasSecretMpin = secretMpinConfigured,
            secretMpinLength = viewModel.secretMpinLength(),
            lockoutSeconds = lockoutSeconds,
            hasCloudBackup = !target.cloudPath.isNullOrBlank(),
            showDeleteModeSelector = !target.cloudPath.isNullOrBlank(),
            verifySecretMpin = viewModel::verifySecretMpinForDelete,
            onOpenSecuritySettings = onOpenSecuritySettings,
            onDismiss = { filePendingDelete = null },
            onConfirmDelete = { mode ->
                viewModel.executeDeleteFile(target, mode)
                filePendingDelete = null
            }
        )
    }
}

private data class QuickAction(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val tag: String,
    val onClick: () -> Unit
)

@Composable
private fun MetricLine(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MutedSlate)
        Text(
            value,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatTile(
    title: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    GlassCard4D(
        glowColor = accent,
        onClick = onClick,
        contentPadding = PaddingValues(12.dp),
        cornerRadius = 16.dp,
        modifier = modifier
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
            color = SilverWhite
        )
        Text(
            title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MetallicSilver,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun FileItemRowCard(
    file: FileItemEntity,
    badgeLabel: String? = null,
    isSelected: Boolean = false,
    onSelectToggle: (() -> Unit)? = null,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    GlassCard4D(
        glowColor = if (isSelected) ElectricBlue else NeonViolet,
        onClick = onSelectToggle ?: onOpen,
        contentPadding = PaddingValues(12.dp),
        cornerRadius = 16.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (onSelectToggle != null) {
                Checkbox(checked = isSelected, onCheckedChange = { onSelectToggle() })
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ElectricBlue.copy(alpha = 0.14f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = categoryIcon(file.categoryId),
                            contentDescription = null,
                            tint = ElectricBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = SilverWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (file.displayName != file.originalFilename) {
                    Text(
                        text = "Original: ${file.originalFilename}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = LocalFileManager.formatBytes(file.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MetallicSilver
                    )
                    if (!badgeLabel.isNullOrBlank()) {
                        Surface(
                            color = if (badgeLabel.equals("Unclassified Document", true)) {
                                AmberPending.copy(alpha = 0.16f)
                            } else {
                                ElectricBlue.copy(alpha = 0.16f)
                            },
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = badgeLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = if (badgeLabel.equals("Unclassified Document", true)) AmberPending else ElectricBlue,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    BackupStatusBadge(status = file.backupStatus)
                }
            }

            IconButton(onClick = onFavorite) {
                Icon(
                    imageVector = if (file.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "Favorite",
                    tint = if (file.isFavorite) AmberPending else MetallicSilver
                )
            }
            IconButton(onClick = onDeleteRequest) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "Delete with MPIN",
                    tint = CrimsonAlert
                )
            }
        }
    }
}

@Composable
fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf(DskCategories.CAT_DOCUMENTS) }

    Dialog(onDismissRequest = onDismiss) {
        GlassCard4D(contentPadding = PaddingValues(20.dp)) {
            Text("Create Folder", style = MaterialTheme.typography.titleLarge, color = SilverWhite)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text("Assign Category", style = MaterialTheme.typography.labelMedium, color = ElectricBlue)
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(DskCategories.builtInCategories.filter { !it.isLinkCategory && it.id != DskCategories.CAT_TEXT }) { cat ->
                    FilterChip(
                        selected = selectedCat == cat.id,
                        onClick = { selectedCat = cat.id },
                        label = { Text(cat.title, fontSize = 11.sp) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { if (name.isNotBlank()) onCreate(name, selectedCat) },
                    enabled = name.isNotBlank()
                ) {
                    Text("Create")
                }
            }
        }
    }
}

fun categoryIcon(categoryId: String): ImageVector {
    return when (categoryId) {
        DskCategories.CAT_DOCUMENTS -> Icons.Filled.Description
        DskCategories.CAT_PDF, DskCategories.CAT_PERSONAL_RECORDS -> Icons.Filled.PictureAsPdf
        DskCategories.CAT_AADHAAR -> Icons.Filled.Fingerprint
        DskCategories.CAT_PAN -> Icons.Filled.CreditCard
        DskCategories.CAT_DRIVING_LICENCE -> Icons.Filled.DirectionsCar
        DskCategories.CAT_VOTER_ID -> Icons.Filled.HowToVote
        DskCategories.CAT_PASSPORT -> Icons.Filled.FlightTakeoff
        DskCategories.CAT_PHOTOS -> Icons.Filled.PhotoLibrary
        DskCategories.CAT_VIDEOS -> Icons.Filled.Videocam
        DskCategories.CAT_VIDEO_URLS -> Icons.Filled.VideoLibrary
        DskCategories.CAT_WEBSITE_LINKS -> Icons.Filled.Language
        DskCategories.CAT_WEB_PAGES -> Icons.Filled.Public
        DskCategories.CAT_TEXT -> Icons.Filled.TextSnippet
        DskCategories.CAT_OFFICE_DOCS, DskCategories.CAT_WORD, DskCategories.CAT_EXCEL -> Icons.Filled.Description
        DskCategories.CAT_AUDIO -> Icons.Filled.AudioFile
        DskCategories.CAT_APK -> Icons.Filled.Android
        DskCategories.CAT_ARCHIVE -> Icons.Filled.FolderZip
        else -> Icons.Filled.Folder
    }
}
