package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.local.*
import com.example.ui.DskLockerViewModel
import com.example.ui.components.DskEmptyStateCard
import com.example.ui.components.GlassCard4D
import com.example.ui.components.SecureDeleteFlowDialog
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun NotesManagerScreen(
    viewModel: DskLockerViewModel,
    onBack: () -> Unit,
    onOpenSecuritySettings: () -> Unit
) {
    val notes by viewModel.activeNotes.collectAsState()
    val customCats by viewModel.customCategories.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()
    val secretMpinConfigured by viewModel.secretMpinConfigured.collectAsState()
    val clipboard = LocalClipboardManager.current

    var searchQuery by remember { mutableStateOf("") }
    var favOnly by remember { mutableStateOf(false) }
    var filterCat by remember { mutableStateOf<String?>(null) }
    var editingNote by remember { mutableStateOf<TextNoteEntity?>(null) }
    var showNewNote by remember { mutableStateOf(false) }
    var pendingDeleteNote by remember { mutableStateOf<TextNoteEntity?>(null) }
    var copiedMessage by remember { mutableStateOf<String?>(null) }

    var textName by remember { mutableStateOf("") }
    var textEntry by remember { mutableStateOf("") }

    val filtered = remember(notes, searchQuery, favOnly, filterCat) {
        val q = searchQuery.trim().lowercase()
        notes.filter { n ->
            (!favOnly || n.isFavorite) &&
                (filterCat == null || n.categoryId == filterCat) &&
                (q.isBlank() || n.title.lowercase().contains(q) || n.content.lowercase().contains(q))
        }
    }

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
                Text("TEXT", style = MaterialTheme.typography.headlineMedium, color = SilverWhite, modifier = Modifier.weight(1f))
                Button(
                    onClick = { showNewNote = true },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                    modifier = Modifier.testTag("add_note_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Modal")
                }
            }
        }
        // Dedicated Inline Text Entry Form
        item {
            GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(14.dp)) {
                Text("ADD TEXT ENTRY", style = MaterialTheme.typography.labelLarge, color = ElectricBlue)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = textName,
                    onValueChange = { textName = it },
                    label = { Text("Text Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("text_name_input")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = textEntry,
                    onValueChange = { textEntry = it },
                    label = { Text("Text Entry") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("text_entry_input")
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        viewModel.saveNote(
                            TextNoteEntity(
                                title = textName.trim(),
                                content = textEntry.trim(),
                                categoryId = DskCategories.CAT_TEXT
                            )
                        )
                        textName = ""
                        textEntry = ""
                    },
                    enabled = textName.isNotBlank() && textEntry.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_text_inline_button")
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SAVE TEXT", fontWeight = FontWeight.Bold)
                }
            }
        }
        if (copiedMessage != null) {
            item {
                Text(copiedMessage!!, color = EmeraldSync, style = MaterialTheme.typography.labelMedium)
            }
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search saved text by name or content...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = ElectricBlue) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = favOnly,
                        onClick = { favOnly = !favOnly },
                        leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null, tint = AmberPending, modifier = Modifier.size(16.dp)) },
                        label = { Text("Favorites") }
                    )
                }
                item {
                    FilterChip(selected = filterCat == null, onClick = { filterCat = null }, label = { Text("All") })
                }
                items(DskCategories.builtInCategories.filter { !it.isLinkCategory }) { cat ->
                    FilterChip(
                        selected = filterCat == cat.id,
                        onClick = { filterCat = if (filterCat == cat.id) null else cat.id },
                        label = { Text(cat.title) }
                    )
                }
                items(customCats) { cc ->
                    FilterChip(
                        selected = filterCat == cc.id,
                        onClick = { filterCat = if (filterCat == cc.id) null else cc.id },
                        label = { Text(cc.name) }
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                DskEmptyStateCard(
                    title = "No Text Notes Yet",
                    subtitle = "Store private notes, ID details, or recovery descriptions.",
                    icon = Icons.Filled.NoteAdd,
                    glowColor = ElectricBlue
                )
            }
        } else {
            items(filtered, key = { it.id }) { note ->
                GlassCard4D(contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(note.title, style = MaterialTheme.typography.titleMedium, color = SilverWhite)
                            Text(
                                DskCategories.labelFor(note.categoryId, customCats),
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricBlue
                            )
                        }
                        IconButton(onClick = { viewModel.saveNote(note.copy(isFavorite = !note.isFavorite)) }) {
                            Icon(
                                if (note.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "Favorite Note",
                                tint = if (note.isFavorite) AmberPending else MetallicSilver
                            )
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString("${note.title}\n\n${note.content}"))
                            copiedMessage = "Copied \"${note.title}\" to clipboard."
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy Text", tint = ElectricBlue)
                        }
                        IconButton(onClick = { editingNote = note }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit Note", tint = SilverWhite)
                        }
                        IconButton(onClick = { pendingDeleteNote = note }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete Note", tint = CrimsonAlert)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = note.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MetallicSilver,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (showNewNote || editingNote != null) {
        NoteEditorDialog(
            initial = editingNote,
            customCategories = customCats,
            onDismiss = {
                showNewNote = false
                editingNote = null
            },
            onSave = { saved ->
                viewModel.saveNote(saved)
                showNewNote = false
                editingNote = null
            }
        )
    }

    pendingDeleteNote?.let { target ->
        SecureDeleteFlowDialog(
            itemName = target.title,
            hasSecretMpin = secretMpinConfigured,
            secretMpinLength = viewModel.secretMpinLength(),
            lockoutSeconds = lockoutSeconds,
            verifySecretMpin = viewModel::verifySecretMpinForDelete,
            onOpenSecuritySettings = onOpenSecuritySettings,
            onDismiss = { pendingDeleteNote = null },
            onConfirmDelete = {
                viewModel.deleteNote(target)
                pendingDeleteNote = null
            }
        )
    }
}

@Composable
private fun NoteEditorDialog(
    initial: TextNoteEntity?,
    customCategories: List<CustomCategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (TextNoteEntity) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var content by remember { mutableStateOf(initial?.content.orEmpty()) }
    var categoryId by remember { mutableStateOf(initial?.categoryId ?: DskCategories.CAT_TEXT) }

    Dialog(onDismissRequest = onDismiss) {
        GlassCard4D(contentPadding = PaddingValues(18.dp)) {
            Text(if (initial == null) "Add Text Note" else "Edit Text Note", style = MaterialTheme.typography.titleLarge, color = SilverWhite)
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Note Title") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("note_title_input")
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("Full Description / Text") },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("note_content_input")
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(DskCategories.builtInCategories.filter { !it.isLinkCategory }) { c ->
                    FilterChip(selected = categoryId == c.id, onClick = { categoryId = c.id }, label = { Text(c.title, fontSize = 11.sp) })
                }
                items(customCategories) { cc ->
                    FilterChip(selected = categoryId == cc.id, onClick = { categoryId = cc.id }, label = { Text(cc.name, fontSize = 11.sp) })
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = {
                        val entity = (initial ?: TextNoteEntity(title = title, content = content)).copy(
                            title = title.trim(),
                            content = content.trim(),
                            categoryId = categoryId
                        )
                        onSave(entity)
                    },
                    enabled = title.isNotBlank() && content.isNotBlank(),
                    modifier = Modifier.testTag("save_note_button")
                ) {
                    Text("Save Text")
                }
            }
        }
    }
}

@Composable
fun LinksManagerScreen(
    viewModel: DskLockerViewModel,
    initialLinkType: String,
    onBack: () -> Unit,
    onPlayDirectVideoUrl: (String, String) -> Unit,
    onOpenSecuritySettings: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val links by viewModel.activeLinks.collectAsState()
    val customCats by viewModel.customCategories.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()
    val secretMpinConfigured by viewModel.secretMpinConfigured.collectAsState()

    val normalizedInitType = if (initialLinkType == "WEB_PAGE") "WEBSITE_LINK" else initialLinkType
    var activeType by remember(normalizedInitType) { mutableStateOf(normalizedInitType) }
    var searchQuery by remember { mutableStateOf("") }
    var favOnly by remember { mutableStateOf(false) }
    var editingLink by remember { mutableStateOf<SavedLinkEntity?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    var pendingDeleteLink by remember { mutableStateOf<SavedLinkEntity?>(null) }
    var copiedMsg by remember { mutableStateOf<String?>(null) }

    // Inline form states for Video URL (text-only) & Web URL
    var inlineVideoName by remember { mutableStateOf("") }
    var inlineVideoUrl by remember { mutableStateOf("") }
    var inlineWebName by remember { mutableStateOf("") }
    var inlineWebUrl by remember { mutableStateOf("") }
    var inlineWebDesc by remember { mutableStateOf("") }

    val filtered = remember(links, activeType, searchQuery, favOnly) {
        val q = searchQuery.trim().lowercase()
        links.filter { l ->
            val typeMatch = if (activeType == "VIDEO_URL") {
                l.linkType == "VIDEO_URL"
            } else {
                l.linkType == "WEBSITE_LINK" || l.linkType == "WEB_PAGE"
            }
            typeMatch &&
                (!favOnly || l.isFavorite) &&
                (q.isBlank() || l.title.lowercase().contains(q) || l.url.lowercase().contains(q) || l.description.lowercase().contains(q))
        }
    }

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
                Text(
                    if (activeType == "VIDEO_URL") "VIDEO URL" else "WEB URL",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SilverWhite,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = activeType == "VIDEO_URL",
                    onClick = { activeType = "VIDEO_URL" },
                    leadingIcon = { Icon(Icons.Filled.VideoLibrary, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Video URL") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_video_url")
                )
                FilterChip(
                    selected = activeType == "WEBSITE_LINK",
                    onClick = { activeType = "WEBSITE_LINK" },
                    leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Web URL") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_web_url")
                )
            }
        }
        // Dedicated Inline Entry Form (Text-only for Video URL, dedicated Name+URL+Desc for Web URL)
        item {
            if (activeType == "VIDEO_URL") {
                GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(14.dp)) {
                    Text("SAVE VIDEO URL (TEXT LINK ONLY)", style = MaterialTheme.typography.labelLarge, color = ElectricBlue)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inlineVideoName,
                        onValueChange = { inlineVideoName = it },
                        label = { Text("Video Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("video_url_name_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inlineVideoUrl,
                        onValueChange = { inlineVideoUrl = it },
                        label = { Text("Video URL (https://...)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("video_url_input")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val rawUrl = inlineVideoUrl.trim()
                            val normalized = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "https://$rawUrl"
                            val resolvedTitle = inlineVideoName.trim().ifBlank { normalized }
                            viewModel.saveLink(
                                SavedLinkEntity(
                                    linkType = "VIDEO_URL",
                                    title = resolvedTitle,
                                    url = normalized,
                                    categoryId = DskCategories.CAT_VIDEO_URLS
                                )
                            )
                            inlineVideoName = ""
                            inlineVideoUrl = ""
                        },
                        enabled = inlineVideoUrl.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_video_link_button")
                    ) {
                        Icon(Icons.Filled.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SAVE VIDEO LINK", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                GlassCard4D(glowColor = EmeraldSync, contentPadding = PaddingValues(14.dp)) {
                    Text("SAVE WEB URL", style = MaterialTheme.typography.labelLarge, color = EmeraldSync)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inlineWebName,
                        onValueChange = { inlineWebName = it },
                        label = { Text("Website Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("web_url_name_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inlineWebUrl,
                        onValueChange = { inlineWebUrl = it },
                        label = { Text("Website URL (https://...)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("web_url_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inlineWebDesc,
                        onValueChange = { inlineWebDesc = it },
                        label = { Text("Optional Description") },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("web_url_desc_input")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val rawUrl = inlineWebUrl.trim()
                            val normalized = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "https://$rawUrl"
                            val resolvedTitle = inlineWebName.trim().ifBlank { normalized }
                            viewModel.saveLink(
                                SavedLinkEntity(
                                    linkType = "WEBSITE_LINK",
                                    title = resolvedTitle,
                                    url = normalized,
                                    description = inlineWebDesc.trim(),
                                    categoryId = DskCategories.CAT_WEBSITE_LINKS
                                ),
                                extractPageText = true
                            )
                            inlineWebName = ""
                            inlineWebUrl = ""
                            inlineWebDesc = ""
                        },
                        enabled = inlineWebUrl.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSync, contentColor = VoidBlack),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_web_url_button")
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SAVE WEB URL", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (copiedMsg != null) {
            item { Text(copiedMsg!!, color = EmeraldSync, style = MaterialTheme.typography.labelMedium) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search saved URLs, names & page text...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = ElectricBlue) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = favOnly,
                    onClick = { favOnly = !favOnly },
                    label = { Icon(Icons.Filled.Star, contentDescription = "Favorites", tint = AmberPending) }
                )
            }
        }

        if (filtered.isEmpty()) {
            item {
                DskEmptyStateCard(
                    title = if (activeType == "VIDEO_URL") "No Saved Video URLs" else "No Saved Web URLs",
                    subtitle = if (activeType == "VIDEO_URL") {
                        "Use the Video Name + Video URL form above to save text links securely."
                    } else {
                        "Save website URLs above to auto-fetch page titles and searchable text."
                    },
                    icon = if (activeType == "VIDEO_URL") Icons.Filled.VideoLibrary else Icons.Filled.Language,
                    glowColor = if (activeType == "VIDEO_URL") ElectricBlue else EmeraldSync
                )
            }
        } else {
            items(filtered, key = { it.id }) { link ->
                GlassCard4D(contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        if (!link.thumbnailUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = link.thumbnailUrl,
                                contentDescription = link.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(link.title, style = MaterialTheme.typography.titleMedium, color = SilverWhite, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(link.url, style = MaterialTheme.typography.labelSmall, color = ElectricBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (link.description.isNotBlank()) {
                                Text(link.description, style = MaterialTheme.typography.bodyMedium, color = MetallicSilver, maxLines = 2)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            val normalized = if (link.url.startsWith("http")) link.url else "https://${link.url}"
                            if (link.linkType == "VIDEO_URL" && (normalized.endsWith(".mp4", true) || normalized.endsWith(".m3u8", true))) {
                                onPlayDirectVideoUrl(normalized, link.title)
                            } else {
                                runCatching {
                                    viewModel.suppressAutoLockForExternalActivity()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
                                }
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (link.linkType == "VIDEO_URL") "Open Video" else "Open Website")
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(link.url))
                            copiedMsg = "Copied URL to clipboard."
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy Link", tint = ElectricBlue)
                        }
                        IconButton(onClick = { viewModel.saveLink(link.copy(isFavorite = !link.isFavorite)) }) {
                            Icon(
                                if (link.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "Favorite",
                                tint = if (link.isFavorite) AmberPending else MetallicSilver
                            )
                        }
                        IconButton(onClick = { editingLink = link }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit Link", tint = SilverWhite)
                        }
                        IconButton(onClick = { pendingDeleteLink = link }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete with MPIN", tint = CrimsonAlert)
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog || editingLink != null) {
        LinkEditorDialog(
            linkType = activeType,
            initial = editingLink,
            customCategories = customCats,
            onFetchMetadata = { url -> viewModel.fetchLinkMetadata(url) },
            onDismiss = {
                showNewDialog = false
                editingLink = null
            },
            onSave = { saved ->
                viewModel.saveLink(saved)
                showNewDialog = false
                editingLink = null
            }
        )
    }

    pendingDeleteLink?.let { target ->
        SecureDeleteFlowDialog(
            itemName = target.title,
            hasSecretMpin = secretMpinConfigured,
            secretMpinLength = viewModel.secretMpinLength(),
            lockoutSeconds = lockoutSeconds,
            verifySecretMpin = viewModel::verifySecretMpinForDelete,
            onOpenSecuritySettings = onOpenSecuritySettings,
            onDismiss = { pendingDeleteLink = null },
            onConfirmDelete = {
                viewModel.deleteLink(target)
                pendingDeleteLink = null
            }
        )
    }
}

@Composable
private fun LinkEditorDialog(
    linkType: String,
    initial: SavedLinkEntity?,
    customCategories: List<CustomCategoryEntity>,
    onFetchMetadata: suspend (String) -> com.example.data.remote.FetchedLinkMetadata,
    onDismiss: () -> Unit,
    onSave: (SavedLinkEntity) -> Unit
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var thumbnailUrl by remember { mutableStateOf(initial?.thumbnailUrl.orEmpty()) }
    var categoryId by remember {
        mutableStateOf(
            initial?.categoryId ?: when (linkType) {
                "VIDEO_URL" -> DskCategories.CAT_VIDEO_URLS
                "WEB_PAGE" -> DskCategories.CAT_WEB_PAGES
                else -> DskCategories.CAT_WEBSITE_LINKS
            }
        )
    }
    var fetching by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        GlassCard4D(contentPadding = PaddingValues(18.dp)) {
            Text(
                if (initial == null) {
                    when (linkType) {
                        "VIDEO_URL" -> "Save Video Link"
                        "WEB_PAGE" -> "Save Web Page"
                        else -> "Save Website Link"
                    }
                } else "Edit Link",
                style = MaterialTheme.typography.titleLarge,
                color = SilverWhite
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL (https://...)") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        enabled = url.isNotBlank() && !fetching,
                        onClick = {
                            scope.launch {
                                fetching = true
                                val meta = onFetchMetadata(url.trim())
                                if (title.isBlank() && !meta.title.isNullOrBlank()) title = meta.title
                                if (description.isBlank()) {
                                    val combined = listOfNotNull(
                                        meta.description?.takeIf { it.isNotBlank() },
                                        meta.pageContent?.takeIf { it.isNotBlank() }?.take(500)
                                    ).joinToString("\n\n").trim()
                                    if (combined.isNotBlank()) description = combined
                                }
                                if (!meta.thumbnailUrl.isNullOrBlank()) thumbnailUrl = meta.thumbnailUrl
                                fetching = false
                            }
                        }
                    ) {
                        if (fetching) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = "Fetch Metadata", tint = ElectricBlue)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("link_url_input")
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title (auto-fetched or custom)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("link_title_input")
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = thumbnailUrl,
                onValueChange = { thumbnailUrl = it },
                label = { Text("Thumbnail URL (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(DskCategories.builtInCategories) { c ->
                    FilterChip(selected = categoryId == c.id, onClick = { categoryId = c.id }, label = { Text(c.title, fontSize = 11.sp) })
                }
                items(customCategories) { cc ->
                    FilterChip(selected = categoryId == cc.id, onClick = { categoryId = cc.id }, label = { Text(cc.name, fontSize = 11.sp) })
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = {
                        val normalizedUrl = if (url.startsWith("http://") || url.startsWith("https://")) url.trim() else "https://${url.trim()}"
                        val resolvedTitle = title.trim().ifBlank { normalizedUrl }
                        val saved = (initial ?: SavedLinkEntity(
                            linkType = linkType,
                            title = resolvedTitle,
                            url = normalizedUrl,
                            categoryId = categoryId
                        )).copy(
                            title = resolvedTitle,
                            url = normalizedUrl,
                            thumbnailUrl = thumbnailUrl.trim().takeIf { it.isNotBlank() },
                            description = description.trim(),
                            categoryId = categoryId
                        )
                        onSave(saved)
                    },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.testTag("save_link_button")
                ) {
                    Text("Save")
                }
            }
        }
    }
}
