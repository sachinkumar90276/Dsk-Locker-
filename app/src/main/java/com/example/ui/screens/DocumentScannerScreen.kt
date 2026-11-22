package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.local.DskCategories
import com.example.ui.DskLockerViewModel
import com.example.ui.components.GlassCard4D
import com.example.ui.theme.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.example.ai.ImagePreprocessor
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

private data class LiveDocumentDetection(
    val blockRects: List<RectF> = emptyList(),
    val unionRect: RectF? = null,
    val charCount: Int = 0,
    val blockCount: Int = 0,
    val previewSnippet: String = ""
)

@Composable
fun DocumentScannerScreen(
    viewModel: DskLockerViewModel,
    initialInVault: Boolean = false,
    folderId: String? = null,
    forcedCategoryId: String? = null,
    onBack: () -> Unit,
    onScanImported: (fileId: String, openOcrTab: Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraPermissionDeniedOnce by remember { mutableStateOf(false) }

    var saveToVault by remember(initialInVault) { mutableStateOf(initialInVault) }
    var runAiOcr by remember { mutableStateOf(true) }
    var torchEnabled by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var scannerMessage by remember { mutableStateOf<String?>(null) }

    var liveDetection by remember { mutableStateOf(LiveDocumentDetection()) }
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var capturedMlKitText by remember { mutableStateOf("") }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            cameraPermissionDeniedOnce = true
            scannerMessage = "Camera permission is required for live document scanning."
        } else {
            scannerMessage = null
        }
    }

    // Google Play Services ML Kit Document Scanner (auto edge crop + multi-page PDF/JPEG)
    val gmsScannerClient = remember {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(5)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
    }

    val gmsScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val uris = mutableListOf<Uri>()
            scanResult?.pdf?.uri?.let { uris.add(it) }
            if (uris.isEmpty()) {
                scanResult?.pages?.forEach { page -> uris.add(page.imageUri) }
            }
            if (uris.isNotEmpty()) {
                isImporting = true
                viewModel.importScannedDocuments(
                    uris = uris,
                    isInVault = saveToVault,
                    folderId = folderId,
                    forcedCategoryId = forcedCategoryId ?: DskCategories.CAT_DOCUMENTS,
                    autoAnalyzeOcr = runAiOcr
                ) { imported ->
                    isImporting = false
                    if (imported != null) {
                        onScanImported(imported.id, runAiOcr)
                    } else {
                        scannerMessage = "Failed to save scanned document."
                    }
                }
            }
        }
    }

    val launchGmsScanner: () -> Unit = {
        if (activity == null) {
            scannerMessage = "Activity unavailable for ML Kit Document Scanner."
        } else {
            gmsScannerClient.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    gmsScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                .addOnFailureListener { err ->
                    scannerMessage = err.localizedMessage
                        ?: "ML Kit Auto-Crop Scanner module unavailable on this device; use the live CameraX scanner below."
                }
        }
    }

    var enhanceContrast by remember { mutableStateOf(true) }
    var enhancedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Load bitmap + run local ML Kit OCR preview whenever a photo is captured
    LaunchedEffect(capturedFile) {
        val file = capturedFile
        if (file == null || !file.exists()) {
            capturedBitmap = null
            enhancedBitmap = null
            capturedMlKitText = ""
            return@LaunchedEffect
        }
        val (bmp, enhanced, text) = withContext(Dispatchers.IO) {
            val decoded = decodeOrientedBitmap(file, 1600)
            val enh = decoded?.let { enhanceScanBitmap(it) }
            val recognized = (enh ?: decoded)?.let { runLocalMlKitText(it) }.orEmpty()
            Triple(decoded, enh, recognized)
        }
        capturedBitmap = bmp
        enhancedBitmap = enhanced
        capturedMlKitText = text
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .navigationBarsPadding()
    ) {
        // Top Header Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                onClick = {
                    if (capturedFile != null) {
                        capturedFile?.delete()
                        capturedFile = null
                    } else {
                        onBack()
                    }
                },
                modifier = Modifier.testTag("scanner_back_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SilverWhite)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (capturedFile == null) "DOCUMENT SCANNER" else "SCAN PREVIEW",
                    style = MaterialTheme.typography.titleLarge,
                    color = SilverWhite
                )
                Text(
                    text = stringResource(R.string.scan_document_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricBlue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AssistChip(
                onClick = launchGmsScanner,
                leadingIcon = {
                    Icon(
                        Icons.Filled.AutoFixHigh,
                        contentDescription = null,
                        tint = PlasmaMagenta,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = { Text("Auto-Crop", fontSize = 11.sp, color = SilverWhite) },
                border = BorderStroke(1.dp, PlasmaMagenta.copy(alpha = 0.55f)),
                modifier = Modifier.testTag("gms_document_scanner_button")
            )
        }

        if (!scannerMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            GlassCard4D(glowColor = AmberPending, contentPadding = PaddingValues(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = AmberPending, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = scannerMessage!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = SilverWhite,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { scannerMessage = null }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MetallicSilver, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when {
            !hasCameraPermission -> {
                CameraPermissionRequestPane(
                    deniedOnce = cameraPermissionDeniedOnce,
                    onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    onLaunchGmsScanner = launchGmsScanner
                )
            }

            capturedFile != null -> {
                CapturedScanReviewPane(
                    bitmap = if (enhanceContrast) (enhancedBitmap ?: capturedBitmap) else capturedBitmap,
                    mlKitText = capturedMlKitText,
                    enhanceContrast = enhanceContrast,
                    saveToVault = saveToVault,
                    runAiOcr = runAiOcr,
                    isImporting = isImporting,
                    onToggleEnhance = { enhanceContrast = it },
                    onToggleVault = { saveToVault = it },
                    onToggleAiOcr = { runAiOcr = it },
                    onRetake = {
                        capturedFile?.delete()
                        capturedFile = null
                    },
                    onConfirmSave = { triggerOcr ->
                        val file = capturedFile ?: return@CapturedScanReviewPane
                        isImporting = true
                        val saveUri = if (enhanceContrast && enhancedBitmap != null) {
                            val enhancedFile = File(file.parentFile, "enhanced_${file.name}")
                            runCatching {
                                enhancedFile.outputStream().use { out ->
                                    enhancedBitmap!!.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                }
                                Uri.fromFile(enhancedFile)
                            }.getOrElse { Uri.fromFile(file) }
                        } else {
                            Uri.fromFile(file)
                        }
                        viewModel.importScannedDocuments(
                            uris = listOf(saveUri),
                            isInVault = saveToVault,
                            folderId = folderId,
                            forcedCategoryId = forcedCategoryId ?: DskCategories.CAT_DOCUMENTS,
                            autoAnalyzeOcr = triggerOcr
                        ) { imported ->
                            isImporting = false
                            saveUri.path?.let { p -> File(p).takeIf { it != file }?.delete() }
                            file.delete()
                            capturedFile = null
                            if (imported != null) {
                                onScanImported(imported.id, triggerOcr)
                            } else {
                                scannerMessage = "Could not import scanned document."
                            }
                        }
                    }
                )
            }

            else -> {
                LiveCameraMlKitViewfinder(
                    lifecycleOwner = lifecycleOwner,
                    torchEnabled = torchEnabled,
                    useFrontCamera = useFrontCamera,
                    liveDetection = liveDetection,
                    isCapturing = isCapturing,
                    isImporting = isImporting,
                    saveToVault = saveToVault,
                    onDetectionUpdated = { liveDetection = it },
                    onToggleTorch = { torchEnabled = !torchEnabled },
                    onToggleCamera = {
                        useFrontCamera = !useFrontCamera
                        if (useFrontCamera) torchEnabled = false
                    },
                    onToggleVault = { saveToVault = !saveToVault },
                    onCaptureRequested = { controller ->
                        if (isCapturing || isImporting) return@LiveCameraMlKitViewfinder
                        isCapturing = true
                        val scanDir = File(context.cacheDir, "scans").apply { mkdirs() }
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val outFile = File(scanDir, "DSK_Scan_$stamp.jpg")
                        val options = ImageCapture.OutputFileOptions.Builder(outFile).build()
                        controller.takePicture(
                            options,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    isCapturing = false
                                    capturedFile = outFile
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    isCapturing = false
                                    outFile.delete()
                                    scannerMessage = exception.localizedMessage
                                        ?: "Camera capture failed. Try again or use Auto-Crop."
                                }
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun CameraPermissionRequestPane(
    deniedOnce: Boolean,
    onRequestPermission: () -> Unit,
    onLaunchGmsScanner: () -> Unit
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        GlassCard4D(
            glowColor = ElectricBlue,
            contentPadding = PaddingValues(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = ElectricBlue.copy(alpha = 0.16f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.DocumentScanner,
                            contentDescription = null,
                            tint = ElectricBlue,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.camera_permission_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = SilverWhite
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (deniedOnce) {
                        "Camera permission was denied. Enable Camera access in Android Settings, or use the zero-permission ML Kit Auto-Crop Scanner."
                    } else {
                        stringResource(R.string.camera_permission_desc)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MetallicSilver
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics(mergeDescendants = true) {}
                        .testTag("grant_camera_permission_button")
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.grant_camera_permission), fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onLaunchGmsScanner,
                    border = BorderStroke(1.dp, PlasmaMagenta),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics(mergeDescendants = true) {}
                ) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = PlasmaMagenta, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.mlkit_smart_scanner), color = SilverWhite)
                }
            }
        }
    }
}

@Composable
private fun LiveCameraMlKitViewfinder(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    torchEnabled: Boolean,
    useFrontCamera: Boolean,
    liveDetection: LiveDocumentDetection,
    isCapturing: Boolean,
    isImporting: Boolean,
    saveToVault: Boolean,
    onDetectionUpdated: (LiveDocumentDetection) -> Unit,
    onToggleTorch: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleVault: () -> Unit,
    onCaptureRequested: (LifecycleCameraController) -> Unit
) {
    val context = LocalContext.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    val cameraController = remember(context) {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(
                CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS
            )
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(960, 1280),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        }
    }

    LaunchedEffect(useFrontCamera) {
        cameraController.cameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    LaunchedEffect(torchEnabled) {
        runCatching { cameraController.enableTorch(torchEnabled) }
    }

    DisposableEffect(lifecycleOwner, cameraController, textRecognizer) {
        val analyzer = MlKitAnalyzer(
            listOf(textRecognizer),
            CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
            mainExecutor
        ) { result ->
            val visionText: Text? = result.getValue(textRecognizer)
            if (visionText == null) {
                onDetectionUpdated(LiveDocumentDetection())
                return@MlKitAnalyzer
            }
            val blockBoxes = visionText.textBlocks.mapNotNull { block ->
                block.boundingBox?.let { RectF(it) }
            }
            val union = if (blockBoxes.isNotEmpty()) {
                RectF(blockBoxes.first()).apply {
                    blockBoxes.drop(1).forEach { union(it) }
                }
            } else null

            val cleanText = visionText.text.trim()
            onDetectionUpdated(
                LiveDocumentDetection(
                    blockRects = blockBoxes,
                    unionRect = union,
                    charCount = cleanText.length,
                    blockCount = blockBoxes.size,
                    previewSnippet = cleanText.replace('\n', ' ').take(96)
                )
            )
        }
        cameraController.setImageAnalysisAnalyzer(mainExecutor, analyzer)
        runCatching { cameraController.bindToLifecycle(lifecycleOwner) }

        onDispose {
            cameraController.clearImageAnalysisAnalyzer()
            cameraController.unbind()
            textRecognizer.close()
        }
    }

    val isDocumentLocked = liveDetection.charCount >= 18 && liveDetection.blockCount >= 1
    val hudColor = when {
        isDocumentLocked -> EmeraldSync
        liveDetection.blockCount > 0 -> ElectricBlue
        else -> NeonViolet
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Live Viewfinder + ML Kit Overlay
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .border(1.5.dp, hudColor.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
                .background(VoidBlack)
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        controller = cameraController
                    }
                },
                update = { previewView ->
                    if (previewView.controller !== cameraController) {
                        previewView.controller = cameraController
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ML Kit Text & Document Boundary HUD Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Guide reticle frame when no live document box detected yet
                val guideLeft = w * 0.08f
                val guideTop = h * 0.12f
                val guideRight = w * 0.92f
                val guideBottom = h * 0.88f

                if (liveDetection.unionRect == null) {
                    val cornerLen = 36.dp.toPx()
                    val strokeW = 3.dp.toPx()
                    val guideColor = ElectricBlue.copy(alpha = 0.65f)
                    drawCornerBrackets(
                        left = guideLeft,
                        top = guideTop,
                        right = guideRight,
                        bottom = guideBottom,
                        cornerLen = cornerLen,
                        strokeWidth = strokeW,
                        color = guideColor
                    )
                } else {
                    // Individual ML Kit detected text blocks
                    liveDetection.blockRects.forEach { r ->
                        drawRoundRect(
                            color = ElectricBlue.copy(alpha = 0.14f),
                            topLeft = Offset(r.left, r.top),
                            size = androidx.compose.ui.geometry.Size(r.width(), r.height()),
                            cornerRadius = CornerRadius(6.dp.toPx())
                        )
                        drawRoundRect(
                            color = hudColor.copy(alpha = 0.6f),
                            topLeft = Offset(r.left, r.top),
                            size = androidx.compose.ui.geometry.Size(r.width(), r.height()),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(width = 1.2.dp.toPx())
                        )
                    }

                    // Enclosing detected document bounds
                    val pad = 12.dp.toPx()
                    val docLeft = (liveDetection.unionRect.left - pad).coerceIn(8f, w - 40f)
                    val docTop = (liveDetection.unionRect.top - pad).coerceIn(8f, h - 40f)
                    val docRight = (liveDetection.unionRect.right + pad).coerceIn(docLeft + 24f, w - 8f)
                    val docBottom = (liveDetection.unionRect.bottom + pad).coerceIn(docTop + 24f, h - 8f)

                    drawRoundRect(
                        color = hudColor.copy(alpha = 0.08f),
                        topLeft = Offset(docLeft, docTop),
                        size = androidx.compose.ui.geometry.Size(docRight - docLeft, docBottom - docTop),
                        cornerRadius = CornerRadius(12.dp.toPx())
                    )
                    drawCornerBrackets(
                        left = docLeft,
                        top = docTop,
                        right = docRight,
                        bottom = docBottom,
                        cornerLen = 28.dp.toPx(),
                        strokeWidth = 3.5.dp.toPx(),
                        color = hudColor
                    )
                }
            }

            // Floating Top-Right Quick Controls (Torch, Switch Lens, Vault Toggle)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                FilterChip(
                    selected = saveToVault,
                    onClick = onToggleVault,
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Shield,
                            contentDescription = null,
                            tint = if (saveToVault) PlasmaMagenta else MetallicSilver,
                            modifier = Modifier.size(15.dp)
                        )
                    },
                    label = { Text(if (saveToVault) "Vault" else "Files", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = VoidBlack.copy(alpha = 0.72f),
                        selectedContainerColor = PlasmaMagenta.copy(alpha = 0.28f),
                        labelColor = SilverWhite,
                        selectedLabelColor = SilverWhite
                    )
                )
                if (!useFrontCamera) {
                    IconButton(
                        onClick = onToggleTorch,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(VoidBlack.copy(alpha = 0.7f))
                            .testTag("scanner_torch_button")
                    ) {
                        Icon(
                            imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = "Toggle Flash",
                            tint = if (torchEnabled) AmberPending else SilverWhite
                        )
                    }
                }
                IconButton(
                    onClick = onToggleCamera,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(VoidBlack.copy(alpha = 0.7f))
                        .testTag("scanner_switch_camera_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = ElectricBlue
                    )
                }
            }

            // Live ML Kit Detection Status Pill
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = VoidBlack.copy(alpha = 0.78f),
                border = BorderStroke(1.dp, hudColor.copy(alpha = 0.65f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isDocumentLocked) Icons.Filled.CheckCircle else Icons.Filled.CenterFocusStrong,
                            contentDescription = null,
                            tint = hudColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                isDocumentLocked -> "Document Locked • ${liveDetection.blockCount} blocks detected"
                                liveDetection.blockCount > 0 -> "Scanning text (${liveDetection.charCount} chars)..."
                                else -> "Align physical document inside viewfinder"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = hudColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (liveDetection.previewSnippet.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = liveDetection.previewSnippet,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = SilverWhite.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Shutter Controls Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Button(
                onClick = { onCaptureRequested(cameraController) },
                enabled = !isCapturing && !isImporting,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDocumentLocked) EmeraldSync else ElectricBlue,
                    contentColor = VoidBlack
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .height(54.dp)
                    .semantics(mergeDescendants = true) {}
                    .testTag("capture_document_button")
            ) {
                if (isCapturing || isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = VoidBlack
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        if (isCapturing) "Capturing Document..." else "Saving...",
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(Icons.Filled.Camera, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isDocumentLocked) "Capture Document for OCR" else "Capture Photo",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CapturedScanReviewPane(
    bitmap: Bitmap?,
    mlKitText: String,
    enhanceContrast: Boolean,
    saveToVault: Boolean,
    runAiOcr: Boolean,
    isImporting: Boolean,
    onToggleEnhance: (Boolean) -> Unit,
    onToggleVault: (Boolean) -> Unit,
    onToggleAiOcr: (Boolean) -> Unit,
    onRetake: () -> Unit,
    onConfirmSave: (triggerOcr: Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard4D(glowColor = ElectricBlue, contentPadding = PaddingValues(10.dp)) {
            if (bitmap == null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                ) {
                    CircularProgressIndicator(color = ElectricBlue)
                }
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Captured Document Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 340.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            }
        }

        GlassCard4D(
            glowColor = if (mlKitText.isNotBlank()) EmeraldSync else AmberPending,
            contentPadding = PaddingValues(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (mlKitText.isNotBlank()) Icons.Filled.DocumentScanner else Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = if (mlKitText.isNotBlank()) EmeraldSync else AmberPending,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (mlKitText.isNotBlank()) {
                        "ML Kit Text Detected (${mlKitText.length} chars)"
                    } else {
                        "Low / No Print Text Detected Locally"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = SilverWhite
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = mlKitText.ifBlank {
                    "You can still save and run AI Document Reader (Gemini multimodal vision) or retake in better lighting."
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MetallicSilver,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }

        GlassCard4D(contentPadding = PaddingValues(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(checked = enhanceContrast, onCheckedChange = onToggleEnhance)
                Text(
                    "Enhance scan clarity & contrast",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SilverWhite
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(checked = runAiOcr, onCheckedChange = onToggleAiOcr)
                Text(
                    "Open AI OCR Reader automatically after save",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SilverWhite
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(checked = saveToVault, onCheckedChange = onToggleVault)
                Text(
                    "Encrypt in AES-256-GCM Secure Vault",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SilverWhite
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = onRetake,
                enabled = !isImporting,
                border = BorderStroke(1.dp, NeonViolet),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("retake_scan_button")
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = SilverWhite, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Retake", color = SilverWhite)
            }

            Button(
                onClick = { onConfirmSave(runAiOcr) },
                enabled = !isImporting,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldSync, contentColor = VoidBlack),
                modifier = Modifier
                    .weight(1.6f)
                    .height(48.dp)
                    .semantics(mergeDescendants = true) {}
                    .testTag("confirm_scan_ocr_button")
            ) {
                if (isImporting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = VoidBlack)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Saving...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (runAiOcr) "Save & Run OCR" else "Save Scan",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCornerBrackets(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    cornerLen: Float,
    strokeWidth: Float,
    color: Color
) {
    val len = cornerLen.coerceAtMost((right - left) * 0.35f).coerceAtMost((bottom - top) * 0.35f)
    val path = Path().apply {
        // Top-Left
        moveTo(left, top + len)
        lineTo(left, top)
        lineTo(left + len, top)
        // Top-Right
        moveTo(right - len, top)
        lineTo(right, top)
        lineTo(right, top + len)
        // Bottom-Right
        moveTo(right, bottom - len)
        lineTo(right, bottom)
        lineTo(right - len, bottom)
        // Bottom-Left
        moveTo(left + len, bottom)
        lineTo(left, bottom)
        lineTo(left, bottom - len)
    }
    drawPath(path = path, color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
}

private suspend fun runLocalMlKitText(bitmap: Bitmap): String {
    val input = InputImage.fromBitmap(bitmap, 0)
    val latin = runSingleScanRecognizer(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS), input)
    val hindi = runSingleScanRecognizer(TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()), input)
    val merged = linkedSetOf<String>()
    latin.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { merged.add(it) }
    hindi.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
        if (line.any { it in '\u0900'..'\u097F' } || merged.none { it.equals(line, true) }) {
            merged.add(line)
        }
    }
    return merged.joinToString("\n")
}

private suspend fun runSingleScanRecognizer(
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    input: InputImage
): String = suspendCancellableCoroutine { cont ->
    recognizer.process(input)
        .addOnSuccessListener { result ->
            recognizer.close()
            cont.resume(result.text.trim())
        }
        .addOnFailureListener {
            recognizer.close()
            cont.resume("")
        }
}

private fun decodeOrientedBitmap(file: File, maxDim: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    var sample = 1
    while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
        sample *= 2
    }
    val raw = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null

    val rotation = runCatching {
        when (
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)

    if (rotation == 0f) return raw
    val matrix = Matrix().apply { postRotate(rotation) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
        if (it !== raw) raw.recycle()
    }
}

private fun enhanceScanBitmap(src: Bitmap): Bitmap = ImagePreprocessor.enhanceForOcr(src)
