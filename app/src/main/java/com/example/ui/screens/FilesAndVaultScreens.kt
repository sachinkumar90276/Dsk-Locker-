package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.*
import com.example.security.BiometricAvailability
import com.example.security.BiometricHelper
import com.example.storage.LocalFileManager
import com.example.ui.DskLockerViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesExplorerScreen(
    viewModel: DskLockerViewModel,
    initialCategoryId: String?,
    onOpenUploadPicker: (forcedCategoryId: String?, folderId: String?) -> Unit,
    onOpenScanner: (forcedCategoryId: String?, folderId: String?) -> Unit,
    onOpenFileViewer: (String) -> Unit,
    onOpenNotes: () -> Unit,
    onOpenLinks: (String) -> Unit,
    onOpenSecuritySettings: () -> Unit
) {
    val files by viewModel.activeFiles.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val customCats by viewModel.customCategories.collectAsState()
    val extractions by viewModel.extractions.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()
    val secretMpinConfigured by viewModel.secretMpinConfigured.collectAsState()

    var selectedCategory by remember(initialCategoryId) { mutableStateOf(initialCategoryId) }
    var selectedDocSubcategory by remember(selectedCategory) { mutableStateOf(DocumentSubcategory.ALL) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var duplicatesOnly by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf<BackupStatus?>(null) }
    var sortOption by remember { mutableStateOf(SortOption.NEWEST_FIRST) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    var showCreateCatDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var fileForActionSheet by remember { mutableStateOf<FileItemEntity?>(null) }
    var renameTarget by remember { mutableStateOf<FileItemEntity?>(null) }
    var moveOrCopyTarget by remember { mutableStateOf<Pair<FileItemEntity, Boolean>?>(null) } // Boolean = isCopy

    var pendingDeleteTargets by remember { mutableStateOf<List<FileItemEntity>>(emptyList()) }
    var folderPendingDelete by remember { mutableStateOf<FolderEntity?>(null) }

    val videoMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 15)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importFiles(
                uris = uris,
                isInVault = false,
                folderId = selectedFolderId,
                forcedCategoryId = DskCategories.CAT_VIDEOS,
                autoAnalyzeOcr = true
            )
        }
    }

    val videoDocPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importFiles(
                uris = uris,
                isInVault = false,
                folderId = selectedFolderId,
                forcedCategoryId = DskCategories.CAT_VIDEOS,
                autoAnalyzeOcr = true
            )
        }
    }

    val searchDateFmt = remember { SimpleDateFormat("dd MMM yyyy yyyy-MM-dd", Locale.getDefault()) }

    val extractionMap = remember(extractions) { extractions.associateBy { it.fileId } }
    val duplicateShaSet = remember(files) {
        files.filter { it.sha256.isNotBlank() }
            .groupingBy { it.sha256 }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    }

    val documentSubcategoryCounts = remember(files, extractionMap) {
        val docFiles = files.filter { DskCategories.matchesCategory(it.categoryId, DskCategories.CAT_DOCUMENTS) }
        DocumentSubcategory.entries.associateWith { sub ->
            if (sub == DocumentSubcategory.ALL) {
                docFiles.size
            } else {
                docFiles.count { DskCategories.resolveDocumentSubcategory(it, extractionMap[it.id]) == sub }
            }
        }
    }

    val filteredFiles = remember(
        files,
        selectedCategory,
        selectedDocSubcategory,
        selectedFolderId,
        searchQuery,
        favoritesOnly,
        duplicatesOnly,
        duplicateShaSet,
        statusFilter,
        sortOption,
        extractionMap,
        customCats
    ) {
        val q = searchQuery.trim().lowercase()
        val list = files.filter { file ->
            val catOk = when {
                selectedCategory == null -> true
                selectedCategory == DskCategories.CAT_DOCUMENTS ->
                    DskCategories.matchesDocumentSubcategory(file, extractionMap[file.id], selectedDocSubcategory)
                else -> DskCategories.matchesCategory(file.categoryId, selectedCategory)
            }
            val folderOk = selectedFolderId == null || file.folderId == selectedFolderId
            val favOk = !favoritesOnly || file.isFavorite
            val dupOk = !duplicatesOnly || file.sha256 in duplicateShaSet
            val statusOk = statusFilter == null || file.backupStatus == statusFilter
            val ext = extractionMap[file.id]
            val formattedDate = searchDateFmt.format(Date(file.createdAt)).lowercase()
            val catLabel = DskCategories.labelFor(file.categoryId, customCats).lowercase()
            val queryOk = q.isBlank() ||
                file.displayName.lowercase().contains(q) ||
                file.originalFilename.lowercase().contains(q) ||
                catLabel.contains(q) ||
                formattedDate.contains(q) ||
                (ext?.documentName?.lowercase()?.contains(q) == true) ||
                (ext?.documentType?.lowercase()?.contains(q) == true) ||
                (ext?.documentNumber?.lowercase()?.contains(q) == true) ||
                (ext?.ownerName?.lowercase()?.contains(q) == true) ||
                (ext?.fatherName?.lowercase()?.contains(q) == true) ||
                (ext?.motherName?.lowercase()?.contains(q) == true) ||
                (ext?.dateOfBirth?.lowercase()?.contains(q) == true) ||
                (ext?.issueDate?.lowercase()?.contains(q) == true) ||
                (ext?.expiryDate?.lowercase()?.contains(q) == true) ||
                (ext?.organizationName?.lowercase()?.contains(q) == true) ||
                (ext?.registrationNumber?.lowercase()?.contains(q) == true) ||
                (ext?.address?.lowercase()?.contains(q) == true) ||
                (ext?.mobileNumber?.lowercase()?.contains(q) == true) ||
                (ext?.rawText?.lowercase()?.contains(q) == true)
            catOk && folderOk && favOk && dupOk && statusOk && queryOk
        }
        when (sortOption) {
            SortOption.NEWEST_FIRST -> list.sortedByDescending { it.updatedAt }
            SortOption.OLDEST_FIRST -> list.sortedBy { it.updatedAt }
            SortOption.NAME_ASC -> list.sortedBy { it.displayName.lowercase() }
            SortOption.NAME_DESC -> list.sortedByDescending { it.displayName.lowercase() }
            SortOption.LARGEST_FIRST -> list.sortedByDescending { it.sizeBytes }
            SortOption.SMALLEST_FIRST -> list.sortedBy { it.sizeBytes }
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refreshAll() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("FILES & CATEGORIES", style = MaterialTheme.typography.headlineMedium, color = SilverWhite)
                    Row {
                        IconButton(onClick = { viewModel.refreshAll() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh Files", tint = ElectricBlue)
                        }
                        IconButton(onClick = { showCreateFolderDialog = true }) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "New Folder", tint = NeonViolet)
                        }
                        IconButton(onClick = {
                            selectionMode = !selectionMode
                            if (!selectionMode) selectedIds = emptySet()
                        }) {
                            Icon(
                                if (selectionMode) Icons.Filled.Close else Icons.Filled.Checklist,
                                contentDescription = "Bulk Select",
                                tint = SilverWhite
                            )
                        }
                    }
                }
            }

            // Search bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search filename, doc name, doc #, owner...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = ElectricBlue) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear Search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("files_search_input")
                )
            }

            // 6 Main Categories + Custom Categories + Add Custom Category
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = { selectedCategory = null },
                            label = { Text("All (${files.size})") }
                        )
                    }
                    items(DskCategories.builtInCategories) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat.id,
                            onClick = {
                                when (cat.id) {
                                    DskCategories.CAT_VIDEO_URLS -> onOpenLinks("VIDEO_URL")
                                    DskCategories.CAT_WEBSITE_LINKS -> onOpenLinks("WEBSITE_LINK")
                                    DskCategories.CAT_TEXT -> onOpenNotes()
                                    else -> selectedCategory = if (selectedCategory == cat.id) null else cat.id
                                }
                            },
                            leadingIcon = {
                                Icon(categoryIcon(cat.id), contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text(cat.title) }
                        )
                    }
                    items(customCats) { cc ->
                        FilterChip(
                            selected = selectedCategory == cc.id,
                            onClick = { selectedCategory = if (selectedCategory == cc.id) null else cc.id },
                            label = { Text(cc.name) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { showCreateCatDialog = true },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("New Category") },
                            modifier = Modifier.testTag("new_custom_category_button")
                        )
                    }
                }
            }

            // Documents Smart Subcategories Filter Bar
            if (selectedCategory == DskCategories.CAT_DOCUMENTS) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "DOCUMENTS SMART FILTERS",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricBlue
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(DocumentSubcategory.entries) { sub ->
                                val subCount = documentSubcategoryCounts[sub] ?: 0
                                FilterChip(
                                    selected = selectedDocSubcategory == sub,
                                    onClick = { selectedDocSubcategory = sub },
                                    label = {
                                        Text(
                                            text = if (subCount > 0) "${sub.label} ($subCount)" else sub.label,
                                            fontSize = 11.sp
                                        )
                                    },
                                    modifier = Modifier.testTag("doc_subcat_${sub.id}")
                                )
                            }
                        }
                    }
                }
            }

            // Dedicated Videos Category Action Banner (Upload Video: Photo Picker + MKV/AVI/MOV Browser)
            if (selectedCategory == DskCategories.CAT_VIDEOS) {
                item {
                    GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(14.dp)) {
                        Text("VIDEO VAULT UPLOAD", style = MaterialTheme.typography.labelLarge, color = ElectricBlue)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Upload actual video files (MP4, MOV, MKV, WEBM, AVI, 3GP) with thumbnail, duration, video frame OCR & MPIN Delete.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MetallicSilver
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    videoMediaPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(48.dp)
                                    .testTag("upload_video_button")
                            ) {
                                Icon(Icons.Filled.VideoCall, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Upload Video", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { videoDocPicker.launch(arrayOf("video/*")) },
                                border = BorderStroke(1.dp, NeonViolet),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("browse_video_files_button")
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = PlasmaMagenta, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Browse Files", color = SilverWhite, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Filter & Sort Strip
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = { favoritesOnly = !favoritesOnly },
                            leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null, tint = AmberPending, modifier = Modifier.size(16.dp)) },
                            label = { Text("Favorites") }
                        )
                    }
                    if (duplicateShaSet.isNotEmpty()) {
                        item {
                            FilterChip(
                                selected = duplicatesOnly,
                                onClick = { duplicatesOnly = !duplicatesOnly },
                                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = AmberPending, modifier = Modifier.size(16.dp)) },
                                label = { Text("Duplicates (${duplicateShaSet.size})") }
                            )
                        }
                    }
                    item {
                        AssistChip(
                            onClick = {
                                val all = SortOption.entries
                                sortOption = all[(all.indexOf(sortOption) + 1) % all.size]
                            },
                            leadingIcon = { Icon(Icons.Filled.Sort, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Sort: ${sortOption.label}") }
                        )
                    }
                    items(BackupStatus.entries) { st ->
                        FilterChip(
                            selected = statusFilter == st,
                            onClick = { statusFilter = if (statusFilter == st) null else st },
                            label = { Text(st.label, fontSize = 11.sp) }
                        )
                    }
                }
            }

            // Folders row
            if (folders.isNotEmpty()) {
                item {
                    Text("FOLDERS", style = MaterialTheme.typography.labelMedium, color = ElectricBlue)
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(folders, key = { it.id }) { folder ->
                            val active = selectedFolderId == folder.id
                            Surface(
                                color = if (active) ElectricBlue.copy(alpha = 0.2f) else MidnightPurple.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, if (active) ElectricBlue else NeonViolet.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { selectedFolderId = if (active) null else folder.id }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Filled.Folder, contentDescription = null, tint = NeonViolet, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(folder.name, style = MaterialTheme.typography.labelLarge, color = SilverWhite)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { folderPendingDelete = folder },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "Delete Folder", tint = MutedSlate, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bulk Selection Toolbar (Delete always requires MPIN!)
            if (selectionMode && selectedIds.isNotEmpty()) {
                item {
                    GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleMedium, color = ElectricBlue)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = {
                                    val targets = filteredFiles.filter { it.id in selectedIds }
                                    viewModel.runManualBackupNow(targets)
                                    selectionMode = false
                                    selectedIds = emptySet()
                                }) {
                                    Text("Backup", color = EmeraldSync)
                                }
                                TextButton(onClick = {
                                    val targets = filteredFiles.filter { it.id in selectedIds }
                                    viewModel.restoreCloudFiles(targets, overwrite = false)
                                    selectionMode = false
                                    selectedIds = emptySet()
                                }) {
                                    Text("Restore", color = ElectricBlue)
                                }
                                TextButton(onClick = {
                                    pendingDeleteTargets = filteredFiles.filter { it.id in selectedIds }
                                }) {
                                    Text("Delete (MPIN)", color = CrimsonAlert)
                                }
                            }
                        }
                    }
                }
            }

            // Upload + Scan Document + Quick Text Note Action Row
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onOpenUploadPicker(selectedCategory, selectedFolderId) },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("files_upload_button")
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Upload", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }
                    Button(
                        onClick = { onOpenScanner(selectedCategory, selectedFolderId) },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSync, contentColor = VoidBlack),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("files_scan_document_button")
                    ) {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Scan Doc", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onOpenNotes,
                        border = BorderStroke(1.dp, NeonViolet),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Notes, contentDescription = null, tint = PlasmaMagenta, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Notes", color = SilverWhite, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }

            if (filteredFiles.isEmpty()) {
                item {
                    DskEmptyStateCard(
                        title = "No files match this filter",
                        subtitle = "Tap Upload above or adjust category and filter chips.",
                        icon = Icons.Filled.FolderOpen,
                        glowColor = NeonViolet
                    )
                }
            } else {
                items(filteredFiles, key = { it.id }) { file ->
                    val ext = extractionMap[file.id]
                    val isVideoItem = DskCategories.matchesCategory(file.categoryId, DskCategories.CAT_VIDEOS) ||
                        file.mimeType.startsWith("video/", ignoreCase = true)
                    val docBadge = when {
                        !ext?.documentType.isNullOrBlank() -> ext?.documentType
                        !ext?.documentName.isNullOrBlank() -> ext?.documentName
                        else -> null
                    }
                    if (isVideoItem && !selectionMode) {
                        VideoItemRowCard(
                            file = file,
                            onOpen = { fileForActionSheet = file },
                            onFavorite = { viewModel.toggleFavorite(file) },
                            onDeleteRequest = { pendingDeleteTargets = listOf(file) }
                        )
                    } else {
                        FileItemRowCard(
                            file = file,
                            badgeLabel = docBadge,
                            isSelected = file.id in selectedIds,
                            onSelectToggle = if (selectionMode) {
                                {
                                    selectedIds = if (file.id in selectedIds) selectedIds - file.id else selectedIds + file.id
                                }
                            } else null,
                            onOpen = { fileForActionSheet = file },
                            onFavorite = { viewModel.toggleFavorite(file) },
                            onDeleteRequest = { pendingDeleteTargets = listOf(file) }
                        )
                    }
                }
            }
        }
    }

    // File Action Sheet (View, AI OCR, Rename, Move, Copy, Backup, Restore, Download, Delete)
    fileForActionSheet?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { fileForActionSheet = null },
            containerColor = DeepObsidian
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(target.displayName, style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                Text(
                    "Original: ${target.originalFilename} • ${LocalFileManager.formatBytes(target.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MetallicSilver
                )
                Spacer(modifier = Modifier.height(14.dp))

                SheetActionRow(Icons.Filled.Visibility, "View / Preview File", ElectricBlue) {
                    fileForActionSheet = null
                    onOpenFileViewer(target.id)
                }
                SheetActionRow(Icons.Filled.DocumentScanner, "AI Document Reader & OCR", PlasmaMagenta) {
                    fileForActionSheet = null
                    viewModel.runOcrOnFile(target)
                    onOpenFileViewer(target.id)
                }
                SheetActionRow(Icons.Filled.Download, "DOWNLOAD TO PHONE", EmeraldSync) {
                    fileForActionSheet = null
                    viewModel.downloadFileToPhone(target) {}
                }
                SheetActionRow(Icons.Filled.DriveFileRenameOutline, "Rename Display Name", SilverWhite) {
                    fileForActionSheet = null
                    renameTarget = target
                }
                SheetActionRow(Icons.Filled.DriveFileMove, "Move to Category / Folder", SilverWhite) {
                    fileForActionSheet = null
                    moveOrCopyTarget = target to false
                }
                SheetActionRow(Icons.Filled.FileCopy, "Copy to Category / Folder", SilverWhite) {
                    fileForActionSheet = null
                    moveOrCopyTarget = target to true
                }
                SheetActionRow(Icons.Filled.CloudUpload, "Backup to Cloud Now", ElectricBlue) {
                    fileForActionSheet = null
                    viewModel.runManualBackupNow(listOf(target))
                }
                if (!target.cloudPath.isNullOrBlank()) {
                    SheetActionRow(Icons.Filled.CloudDownload, "Restore from Cloud", CyberBlue) {
                        fileForActionSheet = null
                        viewModel.restoreCloudFiles(listOf(target), overwrite = true)
                    }
                }
                SheetActionRow(Icons.Filled.Delete, "Delete (Secret MPIN Protected)", CrimsonAlert) {
                    fileForActionSheet = null
                    pendingDeleteTargets = listOf(target)
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showCreateCatDialog) {
        var catName by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { showCreateCatDialog = false }) {
            GlassCard4D(contentPadding = PaddingValues(20.dp)) {
                Text("Create Custom Category", style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = catName,
                    onValueChange = { catName = it },
                    label = { Text("Category Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showCreateCatDialog = false }) { Text("Cancel") }
                    Button(onClick = {
                        viewModel.createCustomCategory(catName)
                        showCreateCatDialog = false
                    }) { Text("Add") }
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

    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.displayName) }
        Dialog(onDismissRequest = { renameTarget = null }) {
            GlassCard4D(contentPadding = PaddingValues(20.dp)) {
                Text("Rename File", style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Display Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
                    Button(onClick = {
                        viewModel.renameFile(target, newName)
                        renameTarget = null
                    }) { Text("Save") }
                }
            }
        }
    }

    moveOrCopyTarget?.let { (target, isCopy) ->
        var chosenCat by remember { mutableStateOf(target.categoryId) }
        var chosenFolder by remember { mutableStateOf(target.folderId) }
        Dialog(onDismissRequest = { moveOrCopyTarget = null }) {
            GlassCard4D(contentPadding = PaddingValues(20.dp)) {
                Text(if (isCopy) "Copy File" else "Move File", style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                Spacer(modifier = Modifier.height(10.dp))
                Text("Category", style = MaterialTheme.typography.labelMedium, color = ElectricBlue)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(DskCategories.builtInCategories.filter { !it.isLinkCategory && it.id != DskCategories.CAT_TEXT }) { c ->
                        FilterChip(selected = chosenCat == c.id, onClick = { chosenCat = c.id }, label = { Text(c.title) })
                    }
                    items(customCats) { cc ->
                        FilterChip(selected = chosenCat == cc.id, onClick = { chosenCat = cc.id }, label = { Text(cc.name) })
                    }
                }
                if (folders.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Folder", style = MaterialTheme.typography.labelMedium, color = NeonViolet)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(selected = chosenFolder == null, onClick = { chosenFolder = null }, label = { Text("Root") })
                        }
                        items(folders) { f ->
                            FilterChip(selected = chosenFolder == f.id, onClick = { chosenFolder = f.id }, label = { Text(f.name) })
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { moveOrCopyTarget = null }) { Text("Cancel") }
                    Button(onClick = {
                        viewModel.moveOrCopyFile(target, chosenCat, chosenFolder, isCopy)
                        moveOrCopyTarget = null
                    }) { Text(if (isCopy) "Copy" else "Move") }
                }
            }
        }
    }

    if (pendingDeleteTargets.isNotEmpty()) {
        SecureDeleteFlowDialog(
            itemName = pendingDeleteTargets.first().displayName,
            itemCount = pendingDeleteTargets.size,
            hasSecretMpin = secretMpinConfigured,
            secretMpinLength = viewModel.secretMpinLength(),
            lockoutSeconds = lockoutSeconds,
            hasCloudBackup = pendingDeleteTargets.any { !it.cloudPath.isNullOrBlank() },
            showDeleteModeSelector = pendingDeleteTargets.any { !it.cloudPath.isNullOrBlank() },
            verifySecretMpin = viewModel::verifySecretMpinForDelete,
            onOpenSecuritySettings = onOpenSecuritySettings,
            onDismiss = { pendingDeleteTargets = emptyList() },
            onConfirmDelete = { mode ->
                viewModel.executeBulkDeleteFiles(pendingDeleteTargets, mode)
                pendingDeleteTargets = emptyList()
                selectionMode = false
                selectedIds = emptySet()
            }
        )
    }

    folderPendingDelete?.let { folder ->
        SecureDeleteFlowDialog(
            itemName = "Folder \"${folder.name}\"",
            hasSecretMpin = secretMpinConfigured,
            secretMpinLength = viewModel.secretMpinLength(),
            lockoutSeconds = lockoutSeconds,
            verifySecretMpin = viewModel::verifySecretMpinForDelete,
            onOpenSecuritySettings = onOpenSecuritySettings,
            onDismiss = { folderPendingDelete = null },
            onConfirmDelete = {
                viewModel.deleteFolder(folder)
                if (selectedFolderId == folder.id) selectedFolderId = null
                folderPendingDelete = null
            }
        )
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp, horizontal = 8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = SilverWhite)
        }
}

@Composable
fun VideoItemRowCard(
    file: FileItemEntity,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val context = LocalContext.current
    val videoMeta by produceState<LocalFileManager.VideoPreviewMeta?>(initialValue = null, file.id, file.localPath) {
        value = LocalFileManager.getVideoPreviewMeta(context, file)
    }

    GlassCard4D(
        glowColor = ElectricBlue,
        onClick = onOpen,
        contentPadding = PaddingValues(12.dp),
        cornerRadius = 16.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(96.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(VoidBlack)
            ) {
                val thumb = videoMeta?.thumbnailFile
                if (thumb != null) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = "Video thumbnail for ${file.originalFilename}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = ElectricBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = VoidBlack.copy(alpha = 0.68f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Preview Video", tint = SilverWhite, modifier = Modifier.size(18.dp))
                    }
                }
                val durMs = videoMeta?.durationMs ?: 0L
                Surface(
                    color = VoidBlack.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                ) {
                    Text(
                        text = LocalFileManager.formatDuration(durMs),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontFamily = JetBrainsMonoFamily
                        ),
                        color = SilverWhite,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.originalFilename,
                    style = MaterialTheme.typography.titleMedium,
                    color = SilverWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (file.displayName != file.originalFilename) {
                    Text(
                        text = file.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricBlue,
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
                    BackupStatusBadge(status = file.backupStatus)
                }
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    imageVector = if (file.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "Favorite Video",
                    tint = if (file.isFavorite) AmberPending else MetallicSilver
                )
            }
            IconButton(onClick = onDeleteRequest) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "Delete Video with Secret MPIN",
                    tint = CrimsonAlert
                )
            }
        }
    }
}

@Composable
fun SecureVaultScreen(
    viewModel: DskLockerViewModel,
    onOpenVaultUpload: () -> Unit,
    onOpenVaultScanner: () -> Unit,
    onOpenFileViewer: (String) -> Unit,
    onOpenSecuritySettings: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val vaultUnlocked by viewModel.vaultUnlocked.collectAsState()
    val vaultFiles by viewModel.vaultFiles.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()
    val secretMpinConfigured by viewModel.secretMpinConfigured.collectAsState()
    val bioEnabled = remember { viewModel.isBiometricEnabled() }
    val bioAvail = remember { viewModel.biometricAvailability() }
    val pinLen = remember { viewModel.loginMpinLength() }

    var pin by remember { mutableStateOf("") }
    var gateError by remember { mutableStateOf<String?>(null) }
    var vaultFilter by remember { mutableStateOf("ALL") }
    var pendingDelete by remember { mutableStateOf<FileItemEntity?>(null) }

    if (!vaultUnlocked) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .padding(bottom = 96.dp)
        ) {
            GlassCard4D(glowColor = PlasmaMagenta, contentPadding = PaddingValues(18.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    DskLockerLogo(
                        size = 84.dp,
                        showTitles = false,
                        animateRotation = true
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("AES-256-GCM SECURE VAULT", style = MaterialTheme.typography.titleLarge, color = SilverWhite)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Hardware AndroidKeyStore encryption. Private files are encrypted before local & cloud storage.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MetallicSilver,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            MpinDots(length = pin.length, maxDigits = pinLen, isError = gateError != null)
            if (gateError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(gateError!!, color = CrimsonAlert, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(modifier = Modifier.height(16.dp))
            MpinKeypad(
                enabled = lockoutSeconds <= 0,
                showBiometricButton = bioEnabled && bioAvail is BiometricAvailability.Available,
                onBiometricClick = {
                    if (activity != null) {
                        BiometricHelper.authenticate(
                            activity = activity,
                            title = "Unlock Secure Vault",
                            subtitle = "Verify fingerprint or face to decrypt private vault",
                            onSuccess = { viewModel.unlockVaultWithBiometric() },
                            onFailure = { gateError = it }
                        )
                    }
                },
                onDigit = { d ->
                    gateError = null
                    if (pin.length < pinLen) {
                        val next = pin + d
                        pin = next
                        if (next.length == pinLen) {
                            if (!viewModel.unlockVaultWithMpin(next)) {
                                pin = ""
                                gateError = "Incorrect Vault MPIN."
                            }
                        }
                    }
                },
                onBackspace = {
                    if (pin.isNotEmpty()) pin = pin.dropLast(1)
                }
            )
        }
    } else {
        val visibleVault = remember(vaultFiles, vaultFilter) {
            when (vaultFilter) {
                "PHOTOS" -> vaultFiles.filter { it.categoryId == DskCategories.CAT_PHOTOS }
                "VIDEOS" -> vaultFiles.filter { it.categoryId == DskCategories.CAT_VIDEOS }
                "DOCS" -> vaultFiles.filter {
                    DskCategories.matchesCategory(it.categoryId, DskCategories.CAT_DOCUMENTS) ||
                        DskCategories.matchesCategory(it.categoryId, DskCategories.CAT_OFFICE_DOCS) ||
                        it.categoryId in setOf(
                        DskCategories.CAT_AADHAAR,
                        DskCategories.CAT_PAN,
                        DskCategories.CAT_DRIVING_LICENCE,
                        DskCategories.CAT_VOTER_ID,
                        DskCategories.CAT_PASSPORT
                    )
                }
                else -> vaultFiles
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text("SECURE VAULT", style = MaterialTheme.typography.headlineMedium, color = PlasmaMagenta)
                        Text("${vaultFiles.size} AES-256-GCM Encrypted Item(s)", style = MaterialTheme.typography.labelSmall, color = EmeraldSync)
                    }
                    Row {
                        IconButton(onClick = { viewModel.lockVault() }) {
                            Icon(Icons.Filled.Lock, contentDescription = "Lock Vault", tint = CrimsonAlert)
                        }
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to "All", "PHOTOS" to "Private Photos", "VIDEOS" to "Private Videos", "DOCS" to "Private Docs").forEach { (k, lbl) ->
                        item {
                            FilterChip(selected = vaultFilter == k, onClick = { vaultFilter = k }, label = { Text(lbl) })
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onOpenVaultUpload,
                        colors = ButtonDefaults.buttonColors(containerColor = PlasmaMagenta, contentColor = VoidBlack),
                        modifier = Modifier
                            .weight(1.3f)
                            .testTag("vault_encrypt_upload_button")
                    ) {
                        Icon(Icons.Filled.EnhancedEncryption, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Encrypt & Import", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onOpenVaultScanner,
                        border = BorderStroke(1.dp, EmeraldSync),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vault_scan_document_button")
                    ) {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null, tint = EmeraldSync, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan Doc", color = SilverWhite)
                    }
                }
            }
            if (visibleVault.isEmpty()) {
                item {
                    DskEmptyStateCard(
                        title = "Vault is Empty & Armed",
                        subtitle = "Files imported here are encrypted on-device with AndroidKeyStore AES-256-GCM.",
                        icon = Icons.Filled.Shield,
                        glowColor = PlasmaMagenta
                    )
                }
            } else {
                items(visibleVault, key = { it.id }) { file ->
                    FileItemRowCard(
                        file = file,
                        onOpen = { onOpenFileViewer(file.id) },
                        onFavorite = { viewModel.toggleFavorite(file) },
                        onDeleteRequest = { pendingDelete = file }
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        SecureDeleteFlowDialog(
            itemName = target.displayName,
            hasSecretMpin = secretMpinConfigured,
            secretMpinLength = viewModel.secretMpinLength(),
            lockoutSeconds = lockoutSeconds,
            hasCloudBackup = !target.cloudPath.isNullOrBlank(),
            showDeleteModeSelector = !target.cloudPath.isNullOrBlank(),
            verifySecretMpin = viewModel::verifySecretMpinForDelete,
            onOpenSecuritySettings = onOpenSecuritySettings,
            onDismiss = { pendingDelete = null },
            onConfirmDelete = { mode ->
                viewModel.executeDeleteFile(target, mode)
                pendingDelete = null
            }
        )
    }
}

@Composable
fun RecycleBinScreen(
    viewModel: DskLockerViewModel,
    onBack: () -> Unit,
    onOpenSecuritySettings: () -> Unit
) {
    val trashFiles by viewModel.trashFiles.collectAsState()
    val trashNotes by viewModel.trashNotes.collectAsState()
    val trashLinks by viewModel.trashLinks.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()
    val secretMpinConfigured by viewModel.secretMpinConfigured.collectAsState()

    var pendingPermanentFile by remember { mutableStateOf<FileItemEntity?>(null) }
    var pendingEmptyAll by remember { mutableStateOf(false) }

    val fmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SilverWhite)
                }
                Text("RECYCLE BIN", style = MaterialTheme.typography.headlineMedium, color = SilverWhite, modifier = Modifier.weight(1f))
                if (trashFiles.isNotEmpty() || trashNotes.isNotEmpty() || trashLinks.isNotEmpty()) {
                    TextButton(
                        onClick = { pendingEmptyAll = true },
                        modifier = Modifier.testTag("empty_trash_button")
                    ) {
                        Text("Empty Trash", color = CrimsonAlert)
                    }
                }
            }
        }
        item {
            GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(12.dp)) {
                Text(
                    "Note: Moving mobile files to Trash never deletes your Permanent Cloud Backups unless explicitly deleted with MPIN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MetallicSilver
                )
            }
        }
        if (trashFiles.isEmpty() && trashNotes.isEmpty() && trashLinks.isEmpty()) {
            item {
                DskEmptyStateCard(
                    title = "Recycle Bin is Empty",
                    subtitle = "Deleted items appear here safely and still require Secret MPIN to purge permanently.",
                    icon = Icons.Filled.DeleteSweep,
                    glowColor = CrimsonAlert
                )
            }
        }
        items(trashFiles, key = { it.id }) { file ->
            GlassCard4D(glowColor = CrimsonAlert, contentPadding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.displayName, style = MaterialTheme.typography.titleMedium, color = SilverWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Deleted: ${file.deletedAt?.let { fmt.format(Date(it)) } ?: "Recently"} • ${LocalFileManager.formatBytes(file.sizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MetallicSilver
                        )
                    }
                    IconButton(onClick = { viewModel.restoreFromTrash(file) }) {
                        Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Restore", tint = EmeraldSync)
                    }
                    IconButton(onClick = { pendingPermanentFile = file }) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = "Permanent Delete", tint = CrimsonAlert)
                    }
                }
            }
        }
        items(trashNotes, key = { it.id }) { note ->
            GlassCard4D(contentPadding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Note: ${note.title}", style = MaterialTheme.typography.titleMedium, color = SilverWhite)
                        Text(note.content.take(80), style = MaterialTheme.typography.bodyMedium, color = MetallicSilver, maxLines = 1)
                    }
                    IconButton(onClick = { viewModel.restoreNote(note) }) {
                        Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Restore Note", tint = EmeraldSync)
                    }
                }
            }
        }
        items(trashLinks, key = { it.id }) { link ->
            GlassCard4D(contentPadding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${link.linkType}: ${link.title}", style = MaterialTheme.typography.titleMedium, color = SilverWhite)
                        Text(link.url, style = MaterialTheme.typography.labelSmall, color = ElectricBlue, maxLines = 1)
                    }
                    IconButton(onClick = { viewModel.restoreLink(link) }) {
                        Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Restore Link", tint = EmeraldSync)
                    }
                }
            }
        }
    }

    pendingPermanentFile?.let { target ->
        SecureDeleteFlowDialog(
            itemName = target.displayName,
            hasSecretMpin = secretMpinConfigured,
            secretMpinLength = viewModel.secretMpinLength(),
            lockoutSeconds = lockoutSeconds,
            verifySecretMpin = viewModel::verifySecretMpinForDelete,
            onOpenSecuritySettings = onOpenSecuritySettings,
            onDismiss = { pendingPermanentFile = null },
            onConfirmDelete = {
                viewModel.permanentDeleteFromTrash(target)
                pendingPermanentFile = null
            }
        )
    }

    if (pendingEmptyAll) {
        SecureDeleteFlowDialog(
            itemName = "All Recycle Bin items",
            itemCount = trashFiles.size + trashNotes.size + trashLinks.size,
            hasSecretMpin = secretMpinConfigured,
            secretMpinLength = viewModel.secretMpinLength(),
            lockoutSeconds = lockoutSeconds,
            verifySecretMpin = viewModel::verifySecretMpinForDelete,
            onOpenSecuritySettings = onOpenSecuritySettings,
            onDismiss = { pendingEmptyAll = false },
            onConfirmDelete = {
                viewModel.emptyTrash()
                pendingEmptyAll = false
            }
        )
    }
}

@Composable
fun GlobalSearchScreen(
    viewModel: DskLockerViewModel,
    onBack: () -> Unit,
    onOpenFileViewer: (String) -> Unit,
    onOpenNotes: () -> Unit,
    onOpenLinks: (String) -> Unit
) {
    val files by viewModel.activeFiles.collectAsState()
    val extractions by viewModel.extractions.collectAsState()
    val notes by viewModel.activeNotes.collectAsState()
    val links by viewModel.activeLinks.collectAsState()

    var query by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SortOption.NEWEST_FIRST) }
    val extractionMap = remember(extractions) { extractions.associateBy { it.fileId } }

    val searchDateFmt = remember { SimpleDateFormat("dd MMM yyyy yyyy-MM-dd", Locale.getDefault()) }

    val matchedFiles = remember(files, extractions, query, sortOption) {
        val q = query.trim().lowercase()
        val res = if (q.isBlank()) files else files.filter { f ->
            val ex = extractionMap[f.id]
            val catLabel = DskCategories.labelFor(f.categoryId).lowercase()
            val formattedDate = searchDateFmt.format(Date(f.createdAt)).lowercase()
            f.displayName.lowercase().contains(q) ||
                f.originalFilename.lowercase().contains(q) ||
                f.categoryId.lowercase().contains(q) ||
                catLabel.contains(q) ||
                formattedDate.contains(q) ||
                f.backupStatus.label.lowercase().contains(q) ||
                (ex?.documentName?.lowercase()?.contains(q) == true) ||
                (ex?.documentType?.lowercase()?.contains(q) == true) ||
                (ex?.documentNumber?.lowercase()?.contains(q) == true) ||
                (ex?.ownerName?.lowercase()?.contains(q) == true) ||
                (ex?.fatherName?.lowercase()?.contains(q) == true) ||
                (ex?.motherName?.lowercase()?.contains(q) == true) ||
                (ex?.dateOfBirth?.lowercase()?.contains(q) == true) ||
                (ex?.issueDate?.lowercase()?.contains(q) == true) ||
                (ex?.expiryDate?.lowercase()?.contains(q) == true) ||
                (ex?.organizationName?.lowercase()?.contains(q) == true) ||
                (ex?.registrationNumber?.lowercase()?.contains(q) == true) ||
                (ex?.address?.lowercase()?.contains(q) == true) ||
                (ex?.mobileNumber?.lowercase()?.contains(q) == true) ||
                (ex?.rawText?.lowercase()?.contains(q) == true)
        }
        when (sortOption) {
            SortOption.NEWEST_FIRST -> res.sortedByDescending { it.updatedAt }
            SortOption.OLDEST_FIRST -> res.sortedBy { it.updatedAt }
            SortOption.NAME_ASC -> res.sortedBy { it.displayName.lowercase() }
            SortOption.NAME_DESC -> res.sortedByDescending { it.displayName.lowercase() }
            SortOption.LARGEST_FIRST -> res.sortedByDescending { it.sizeBytes }
            SortOption.SMALLEST_FIRST -> res.sortedBy { it.sizeBytes }
        }
    }

    val matchedNotes = remember(notes, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) notes else notes.filter {
            it.title.lowercase().contains(q) || it.content.lowercase().contains(q)
        }
    }

    val matchedLinks = remember(links, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) links else links.filter {
            it.title.lowercase().contains(q) || it.url.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SilverWhite)
                }
                Text("GLOBAL SMART SEARCH", style = MaterialTheme.typography.headlineMedium, color = SilverWhite)
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search files, doc #, owner name, notes, URLs...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = ElectricBlue) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("global_search_input")
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(SortOption.entries) { opt ->
                    FilterChip(
                        selected = sortOption == opt,
                        onClick = { sortOption = opt },
                        label = { Text(opt.label, fontSize = 11.sp) }
                    )
                }
            }
        }
        item {
            Text("FILES & DOCUMENTS (${matchedFiles.size})", style = MaterialTheme.typography.labelLarge, color = ElectricBlue)
        }
        items(matchedFiles, key = { it.id }) { file ->
            FileItemRowCard(
                file = file,
                onOpen = { onOpenFileViewer(file.id) },
                onFavorite = { viewModel.toggleFavorite(file) },
                onDeleteRequest = { onOpenFileViewer(file.id) }
            )
        }
        if (matchedNotes.isNotEmpty()) {
            item { Text("TEXT NOTES (${matchedNotes.size})", style = MaterialTheme.typography.labelLarge, color = PlasmaMagenta) }
            items(matchedNotes, key = { it.id }) { note ->
                GlassCard4D(onClick = onOpenNotes, contentPadding = PaddingValues(12.dp)) {
                    Text(note.title, style = MaterialTheme.typography.titleMedium, color = SilverWhite)
                    Text(note.content.take(90), style = MaterialTheme.typography.bodyMedium, color = MetallicSilver, maxLines = 2)
                }
            }
        }
        if (matchedLinks.isNotEmpty()) {
            item { Text("VIDEO & WEBSITE LINKS (${matchedLinks.size})", style = MaterialTheme.typography.labelLarge, color = EmeraldSync) }
            items(matchedLinks, key = { it.id }) { link ->
                GlassCard4D(onClick = { onOpenLinks(link.linkType) }, contentPadding = PaddingValues(12.dp)) {
                    Text(link.title, style = MaterialTheme.typography.titleMedium, color = SilverWhite)
                    Text(link.url, style = MaterialTheme.typography.labelSmall, color = ElectricBlue, maxLines = 1)
                }
            }
        }
    }
}
