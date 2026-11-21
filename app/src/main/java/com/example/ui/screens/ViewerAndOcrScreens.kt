package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.data.local.*
import com.example.storage.LocalFileManager
import com.example.ui.DskLockerViewModel
import com.example.ui.OcrUiState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.window.Dialog
import com.example.ui.components.BackupStatusBadge
import com.example.ui.components.GlassCard4D
import com.example.ui.components.SecureDeleteFlowDialog
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FileViewerScreen(
    viewModel: DskLockerViewModel,
    fileId: String,
    initialTab: Int = 0,
    onOpenScanner: () -> Unit = {},
    onBack: () -> Unit,
    onOpenSecuritySettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val activeFiles by viewModel.activeFiles.collectAsState()
    val vaultFiles by viewModel.vaultFiles.collectAsState()
    val customCats by viewModel.customCategories.collectAsState()
    val extractions by viewModel.extractions.collectAsState()
    val reminders by viewModel.reminders.collectAsState()
    val ocrState by viewModel.ocrState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()
    val secretMpinConfigured by viewModel.secretMpinConfigured.collectAsState()

    val fileItem = remember(activeFiles, vaultFiles, fileId) {
        (activeFiles + vaultFiles).firstOrNull { it.id == fileId }
    }
    val existingExtraction = remember(extractions, fileId) {
        extractions.firstOrNull { it.fileId == fileId }
    }
    val existingReminder = remember(reminders, fileId) {
        reminders.firstOrNull { it.fileId == fileId }
    }

    var resolvedFile by remember { mutableStateOf<File?>(null) }
    var resolving by remember { mutableStateOf(true) }
    var fullScreen by remember { mutableStateOf(false) }
    var selectedTab by remember(fileId, initialTab) { mutableIntStateOf(initialTab) } // 0 = Preview, 1 = Document Details & OCR
    var showMoveCategoryDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(fileItem?.mimeType ?: "*/*")
    ) { uri ->
        if (uri != null && fileItem != null) {
            viewModel.exportFileToSafUri(fileItem, uri)
        }
    }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(fileItem) {
        resolving = true
        resolvedFile = if (fileItem != null) {
            LocalFileManager.resolveReadableFile(context, fileItem)
        } else null
        resolving = false
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearOcrState()
        }
    }

    if (fileItem == null) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("File not found.", color = SilverWhite)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (fullScreen) 0.dp else 14.dp)
            .padding(bottom = if (fullScreen) 0.dp else 92.dp)
    ) {
        // Header bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SilverWhite)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileItem.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = SilverWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Original: ${fileItem.originalFilename} • ${LocalFileManager.formatBytes(fileItem.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MetallicSilver,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { showMoveCategoryDialog = true }) {
                Icon(Icons.Filled.DriveFileMove, contentDescription = "Move Category", tint = SilverWhite)
            }
            IconButton(onClick = { showDeleteConfirmDialog = true }) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete File", tint = CrimsonAlert)
            }
            IconButton(onClick = { fullScreen = !fullScreen }) {
                Icon(
                    if (fullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = ElectricBlue
                )
            }
            IconButton(
                enabled = resolvedFile != null,
                onClick = {
                    resolvedFile?.let { f ->
                        val intent = LocalFileManager.buildShareOrOpenIntent(context, f, fileItem.mimeType, isShare = true)
                        runCatching { context.startActivity(Intent.createChooser(intent, "Share via")) }
                    }
                }
            ) {
                Icon(Icons.Filled.Share, contentDescription = "Share File", tint = SilverWhite)
            }
        }

        if (!fullScreen) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Direct Viewer") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    leadingIcon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("AI OCR & Details") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_ai_ocr_details")
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { viewModel.downloadFileToPhone(fileItem) {} },
                    enabled = resolvedFile != null && downloadProgress == null,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldSync, contentColor = VoidBlack),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("download_to_phone_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("DOWNLOAD TO PHONE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { createDocLauncher.launch(fileItem.originalFilename) },
                    enabled = resolvedFile != null,
                    border = BorderStroke(1.dp, ElectricBlue)
                ) {
                    Icon(Icons.Filled.FolderCopy, contentDescription = "Choose Folder", tint = ElectricBlue, modifier = Modifier.size(16.dp))
                }
                BackupStatusBadge(status = fileItem.backupStatus)
            }
            if (downloadProgress != null) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress!! },
                    color = EmeraldSync,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (resolving) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(color = ElectricBlue)
            }
        } else if (resolvedFile == null) {
            GlassCard4D(contentPadding = PaddingValues(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.CloudQueue, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(44.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Stored in Cloud Only", style = MaterialTheme.typography.titleMedium, color = SilverWhite)
                    Text("Restore this file to phone storage to view, run AI OCR, or export.", style = MaterialTheme.typography.bodyMedium, color = MetallicSilver)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { viewModel.restoreCloudFiles(listOf(fileItem), overwrite = true) }) {
                        Text("Restore to Phone")
                    }
                }
            }
        } else if (selectedTab == 0) {
            DirectPreviewContent(
                file = resolvedFile!!,
                fileItem = fileItem,
                onOpenExternal = {
                    val intent = LocalFileManager.buildShareOrOpenIntent(context, resolvedFile!!, fileItem.mimeType, isShare = false)
                    runCatching { context.startActivity(Intent.createChooser(intent, "Open With Compatible App")) }
                }
            )
        } else {
            DocumentOcrDetailsPanel(
                fileItem = fileItem,
                extraction = existingExtraction,
                reminder = existingReminder,
                ocrState = ocrState,
                onRunOcr = { viewModel.runOcrOnFile(fileItem) },
                onScanNewDocument = onOpenScanner,
                onSaveVerified = { updated, applyRename ->
                    viewModel.saveVerifiedExtraction(fileItem, updated, applyRename)
                },
                onSaveReminder = { rem ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.saveExpiryReminder(rem)
                }
            )
        }
    }

    if (showMoveCategoryDialog) {
        var chosenCat by remember(fileItem.id) { mutableStateOf(fileItem.categoryId) }
        Dialog(onDismissRequest = { showMoveCategoryDialog = false }) {
            GlassCard4D(contentPadding = PaddingValues(20.dp)) {
                Text("Move to Smart Category", style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(DskCategories.builtInCategories.filter { !it.isLinkCategory && it.id != DskCategories.CAT_TEXT }) { cat ->
                        FilterChip(
                            selected = chosenCat == cat.id,
                            onClick = { chosenCat = cat.id },
                            label = { Text(cat.title) }
                        )
                    }
                    items(customCats) { cc ->
                        FilterChip(
                            selected = chosenCat == cc.id,
                            onClick = { chosenCat = cc.id },
                            label = { Text(cc.name) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showMoveCategoryDialog = false }) { Text("Cancel") }
                    Button(onClick = {
                        viewModel.moveOrCopyFile(fileItem, chosenCat, fileItem.folderId, isCopy = false)
                        showMoveCategoryDialog = false
                    }) { Text("Move") }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        SecureDeleteFlowDialog(
            itemName = fileItem.displayName,
            hasSecretMpin = secretMpinConfigured,
            secretMpinLength = viewModel.secretMpinLength(),
            lockoutSeconds = lockoutSeconds,
            hasCloudBackup = !fileItem.cloudPath.isNullOrBlank(),
            showDeleteModeSelector = !fileItem.cloudPath.isNullOrBlank(),
            verifySecretMpin = viewModel::verifySecretMpinForDelete,
            onOpenSecuritySettings = onOpenSecuritySettings,
            onDismiss = { showDeleteConfirmDialog = false },
            onConfirmDelete = { mode ->
                viewModel.executeDeleteFile(fileItem, mode)
                showDeleteConfirmDialog = false
                onBack()
            }
        )
    }
}

@Composable
private fun DirectPreviewContent(
    file: File,
    fileItem: FileItemEntity,
    onOpenExternal: () -> Unit
) {
    val ext = fileItem.originalFilename.substringAfterLast('.', "").lowercase()
    val mime = fileItem.mimeType.lowercase()

    when {
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "bmp") -> {
            ZoomableImageViewer(file = file)
        }
        mime == "application/pdf" || ext == "pdf" -> {
            PdfPagesViewer(file = file)
        }
        mime.startsWith("video/") || mime.startsWith("audio/") ||
            ext in setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v", "mp3", "wav", "m4a", "aac") -> {
            Media3PlayerView(uri = Uri.fromFile(file), isAudio = mime.startsWith("audio/") || ext in setOf("mp3", "wav", "m4a", "aac"))
        }
        mime.startsWith("text/") || ext in setOf("txt", "md", "json", "csv", "log", "docx", "xlsx", "html", "htm", "rtf", "xml") -> {
            TextAndDocumentReader(file = file, originalName = fileItem.originalFilename, mimeType = mime)
        }
        else -> {
            GlassCard4D(contentPadding = PaddingValues(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(categoryIcon(fileItem.categoryId), contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(56.dp))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(fileItem.displayName, style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                    Text(
                        "Type: ${fileItem.mimeType} • SHA-256: ${fileItem.sha256.take(16)}...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MetallicSilver
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onOpenExternal,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open With Compatible App", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImageViewer(file: File) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }

    GlassCard4D(contentPadding = PaddingValues(6.dp), modifier = Modifier.fillMaxSize()) {
        if (bitmap == null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(color = ElectricBlue)
            }
        } else {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Zoomable Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .transformable(state = transformState)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        }
    }
}

@Composable
private fun PdfPagesViewer(file: File) {
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(1) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
    }

    LaunchedEffect(file, pageIndex) {
        withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        pageCount = renderer.pageCount.coerceAtLeast(1)
                        val idx = pageIndex.coerceIn(0, pageCount - 1)
                        renderer.openPage(idx).use { page ->
                            val bmp = Bitmap.createBitmap(
                                (page.width * 2).coerceIn(600, 1400),
                                (page.height * 2).coerceIn(600, 2000),
                                Bitmap.Config.ARGB_8888
                            )
                            bmp.eraseColor(AndroidColor.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pageBitmap = bmp
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(enabled = pageIndex > 0, onClick = { pageIndex-- }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous Page", tint = ElectricBlue)
            }
            Text("PDF Page ${pageIndex + 1} / $pageCount (Pinch to Zoom)", style = MaterialTheme.typography.labelMedium, color = SilverWhite)
            IconButton(enabled = pageIndex < pageCount - 1, onClick = { pageIndex++ }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Next Page", tint = ElectricBlue)
            }
        }
        GlassCard4D(contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
            if (pageBitmap == null) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(color = ElectricBlue)
                }
            } else {
                Image(
                    bitmap = pageBitmap!!.asImageBitmap(),
                    contentDescription = "PDF Page",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(state = transformState)
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                )
            }
        }
    }
}

@Composable
fun Media3PlayerView(uri: Uri, isAudio: Boolean = false) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    GlassCard4D(contentPadding = PaddingValues(10.dp), modifier = Modifier.fillMaxWidth()) {
        if (isAudio) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Media3 Audio Player", style = MaterialTheme.typography.titleMedium, color = SilverWhite)
            }
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isAudio) 140.dp else 340.dp)
                .clip(RoundedCornerShape(14.dp))
        )
    }
}

@Composable
private fun TextAndDocumentReader(file: File, originalName: String, mimeType: String) {
    val lines by produceState<List<String>>(initialValue = emptyList(), file) {
        value = withContext(Dispatchers.IO) {
            LocalFileManager.readDocumentOrSpreadsheetPreview(file, originalName, mimeType)
        }
    }
    var search by remember { mutableStateOf("") }
    val filtered = remember(lines, search) {
        val q = search.trim().lowercase()
        if (q.isBlank()) lines else lines.filter { it.lowercase().contains(q) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Search inside document...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = ElectricBlue) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        GlassCard4D(contentPadding = PaddingValues(14.dp), modifier = Modifier.fillMaxSize()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = SilverWhite
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentOcrDetailsPanel(
    fileItem: FileItemEntity,
    extraction: DocumentExtractionEntity?,
    reminder: DocumentReminderEntity?,
    ocrState: OcrUiState,
    onRunOcr: () -> Unit,
    onScanNewDocument: () -> Unit,
    onSaveVerified: (DocumentExtractionEntity, Boolean) -> Unit,
    onSaveReminder: (DocumentReminderEntity) -> Unit
) {
    val base = extraction ?: DocumentExtractionEntity(fileId = fileItem.id)
    var docName by remember(base) { mutableStateOf(base.documentName.orEmpty()) }
    var docType by remember(base) { mutableStateOf(base.documentType.orEmpty()) }
    var docNumber by remember(base) { mutableStateOf(base.documentNumber.orEmpty()) }
    var ownerName by remember(base) { mutableStateOf(base.ownerName.orEmpty()) }
    var fatherName by remember(base) { mutableStateOf(base.fatherName.orEmpty()) }
    var motherName by remember(base) { mutableStateOf(base.motherName.orEmpty()) }
    var dob by remember(base) { mutableStateOf(base.dateOfBirth.orEmpty()) }
    var issueDate by remember(base) { mutableStateOf(base.issueDate.orEmpty()) }
    var expiryDate by remember(base) { mutableStateOf(base.expiryDate.orEmpty()) }
    var address by remember(base) { mutableStateOf(base.address.orEmpty()) }
    var mobileNumber by remember(base) { mutableStateOf(base.mobileNumber.orEmpty()) }
    var orgName by remember(base) { mutableStateOf(base.organizationName.orEmpty()) }
    var regNumber by remember(base) { mutableStateOf(base.registrationNumber.orEmpty()) }
    var applyRename by remember(fileItem.id) { mutableStateOf(false) }

    var r30 by remember(reminder) { mutableStateOf(reminder?.remind30Days ?: true) }
    var r15 by remember(reminder) { mutableStateOf(reminder?.remind15Days ?: true) }
    var r7 by remember(reminder) { mutableStateOf(reminder?.remind7Days ?: true) }
    var r0 by remember(reminder) { mutableStateOf(reminder?.remindOnDay ?: true) }
    var notifOn by remember(reminder) { mutableStateOf(reminder?.notificationsEnabled ?: true) }

    val suggestedName = remember(docName, docType, ownerName) {
        val baseLabel = docName.trim().ifBlank { docType.trim() }
            .takeIf { it.isNotBlank() && !it.equals("Unclassified Document", ignoreCase = true) }
        val cleanOwner = ownerName.trim().takeIf { it.isNotBlank() }
        when {
            baseLabel != null && cleanOwner != null -> "$baseLabel - $cleanOwner"
            baseLabel != null -> baseLabel
            else -> null
        }
    }
    val isUnclassified = docType.isBlank() || docType.equals("Unclassified Document", ignoreCase = true)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRunOcr,
                    enabled = ocrState !is OcrUiState.Analyzing,
                    colors = ButtonDefaults.buttonColors(containerColor = PlasmaMagenta, contentColor = VoidBlack),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("run_ai_ocr_button")
                ) {
                    if (ocrState is OcrUiState.Analyzing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = VoidBlack)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reading & Extracting Document...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Run AI Document Reader & OCR", fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = onScanNewDocument,
                    border = BorderStroke(1.dp, EmeraldSync.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("ocr_scan_camera_button")
                ) {
                    Icon(Icons.Filled.DocumentScanner, contentDescription = null, tint = EmeraldSync, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan Physical Document (Camera + ML Kit)", color = SilverWhite)
                }
            }
        }

        if (ocrState is OcrUiState.Error) {
            item {
                GlassCard4D(glowColor = CrimsonAlert, contentPadding = PaddingValues(12.dp)) {
                    Text(ocrState.message, color = CrimsonAlert, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            GlassCard4D(contentPadding = PaddingValues(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("DOCUMENT DETAILS", style = MaterialTheme.typography.titleLarge, color = ElectricBlue)
                    Surface(
                        color = if (isUnclassified) AmberPending.copy(alpha = 0.16f) else EmeraldSync.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, if (isUnclassified) AmberPending else EmeraldSync)
                    ) {
                        Text(
                            text = if (isUnclassified) "Unclassified Document" else docType,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isUnclassified) AmberPending else EmeraldSync,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Original file name preserved: \"${fileItem.originalFilename}\". Display Name updates only if enabled below.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MetallicSilver
                )
                if (!suggestedName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = ElectricBlue.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Suggested Display Name: $suggestedName",
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricBlue,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Original physical file stays \"${fileItem.originalFilename}\"",
                                style = MaterialTheme.typography.labelSmall,
                                color = MetallicSilver
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text("Quick Classify Document Type", style = MaterialTheme.typography.labelMedium, color = NeonViolet)
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(
                        listOf(
                            "Aadhaar Card",
                            "PAN Card",
                            "Passport",
                            "Driving Licence",
                            "Voter ID",
                            "Resume",
                            "Certificate",
                            "Bill / Invoice",
                            "Office Document"
                        )
                    ) { typeLabel ->
                        FilterChip(
                            selected = docType.equals(typeLabel, ignoreCase = true),
                            onClick = {
                                docType = typeLabel
                                if (docName.isBlank() || docName.equals("Unclassified Document", true)) {
                                    docName = typeLabel
                                }
                            },
                            label = { Text(typeLabel, fontSize = 11.sp) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                OcrField("Document Name", docName) { docName = it }
                OcrField("Document Type", docType) { docType = it }
                OcrField("Document Number", docNumber) { docNumber = it }
                OcrField("Owner Name", ownerName) { ownerName = it }
                OcrField("Father Name", fatherName) { fatherName = it }
                OcrField("Mother Name", motherName) { motherName = it }
                OcrField("Date of Birth", dob) { dob = it }
                OcrField("Issue Date (YYYY-MM-DD)", issueDate) { issueDate = it }
                OcrField("Expiry Date (YYYY-MM-DD)", expiryDate) { expiryDate = it }
                OcrField("Address", address) { address = it }
                OcrField("Mobile Number", mobileNumber) { mobileNumber = it }
                OcrField("Organization Name", orgName) { orgName = it }
                OcrField("Registration Number", regNumber) { regNumber = it }

                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = applyRename, onCheckedChange = { applyRename = it })
                    Text(
                        "Apply Suggested Display Name (keeps \"${fileItem.originalFilename}\")",
                        style = MaterialTheme.typography.bodySmall,
                        color = MetallicSilver
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        onSaveVerified(
                            base.copy(
                                documentName = docName.trim().takeIf { it.isNotBlank() },
                                documentType = docType.trim().takeIf { it.isNotBlank() },
                                documentNumber = docNumber.trim().takeIf { it.isNotBlank() },
                                ownerName = ownerName.trim().takeIf { it.isNotBlank() },
                                fatherName = fatherName.trim().takeIf { it.isNotBlank() },
                                motherName = motherName.trim().takeIf { it.isNotBlank() },
                                dateOfBirth = dob.trim().takeIf { it.isNotBlank() },
                                issueDate = issueDate.trim().takeIf { it.isNotBlank() },
                                expiryDate = expiryDate.trim().takeIf { it.isNotBlank() },
                                address = address.trim().takeIf { it.isNotBlank() },
                                mobileNumber = mobileNumber.trim().takeIf { it.isNotBlank() },
                                organizationName = orgName.trim().takeIf { it.isNotBlank() },
                                registrationNumber = regNumber.trim().takeIf { it.isNotBlank() }
                            ),
                            applyRename
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("verify_save_ocr_button")
                ) {
                    Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Verify & Save Document Details", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (expiryDate.isNotBlank()) {
            item {
                GlassCard4D(glowColor = AmberPending, contentPadding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("DOCUMENT EXPIRY REMINDERS", style = MaterialTheme.typography.titleMedium, color = AmberPending)
                        Switch(checked = notifOn, onCheckedChange = { notifOn = it })
                    }
                    Text("Expiry Date: $expiryDate", style = MaterialTheme.typography.labelMedium, color = SilverWhite)
                    Spacer(modifier = Modifier.height(8.dp))
                    ReminderCheck("30 Days Before", r30) { r30 = it }
                    ReminderCheck("15 Days Before", r15) { r15 = it }
                    ReminderCheck("7 Days Before", r7) { r7 = it }
                    ReminderCheck("On Expiry Date", r0) { r0 = it }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            onSaveReminder(
                                (reminder ?: DocumentReminderEntity(
                                    fileId = fileItem.id,
                                    documentName = docName.ifBlank { fileItem.displayName },
                                    expiryDateIso = expiryDate.trim()
                                )).copy(
                                    documentName = docName.ifBlank { fileItem.displayName },
                                    expiryDateIso = expiryDate.trim(),
                                    remind30Days = r30,
                                    remind15Days = r15,
                                    remind7Days = r7,
                                    remindOnDay = r0,
                                    notificationsEnabled = notifOn
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Reminder Schedule", color = AmberPending)
                    }
                }
            }
        }

        if (base.rawText.isNotBlank()) {
            item {
                GlassCard4D(contentPadding = PaddingValues(14.dp)) {
                    Text("RAW EXTRACTED OCR TEXT", style = MaterialTheme.typography.labelLarge, color = ElectricBlue)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(base.rawText, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily), color = MetallicSilver)
                }
            }
        }
    }
}

@Composable
private fun OcrField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = label != "Address",
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Composable
private fun ReminderCheck(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SilverWhite)
    }
}
