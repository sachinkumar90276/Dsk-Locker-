package com.example.ui

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.DocumentOcrResult
import com.example.ai.ExtendedOcrMetadata
import com.example.data.DskLockerRepository
import com.example.data.local.*
import com.example.data.remote.BackendConfig
import com.example.data.remote.FetchedLinkMetadata
import com.example.security.BiometricAvailability
import com.example.security.BiometricHelper
import com.example.storage.DeviceStorageStats
import com.example.storage.LocalFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class AuthStage {
    CREATE_MPIN,
    BIOMETRIC_SETUP,
    MPIN_LOGIN,
    POST_AUTH_SPLASH,
    UNLOCKED_APP
}

sealed interface OcrUiState {
    data object Idle : OcrUiState
    data class Analyzing(
        val stageLabel: String = "Preparing document...",
        val progress: Float = 0.15f
    ) : OcrUiState
    data class Success(
        val extraction: DocumentExtractionEntity,
        val extended: ExtendedOcrMetadata,
        val suggestedName: String?,
        val notice: String? = null
    ) : OcrUiState
    data class Error(
        val message: String,
        val canRetry: Boolean = true
    ) : OcrUiState
}

data class FlashScreenState(
    val isVisible: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val stepLabel: String = "",
    val progress: Float = 0f,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null,
    val onRetry: (() -> Unit)? = null
)

class DskLockerViewModel(application: Application) : AndroidViewModel(application) {
    val repo = DskLockerRepository(application.applicationContext ?: application)

    private val _authStage = MutableStateFlow(
        runCatching {
            if (repo.prefs.hasMpin()) AuthStage.MPIN_LOGIN else AuthStage.CREATE_MPIN
        }.getOrDefault(AuthStage.CREATE_MPIN)
    )
    val authStage: StateFlow<AuthStage> = _authStage.asStateFlow()

    private val _isForgotMpinFlow = MutableStateFlow(false)
    val isForgotMpinFlow: StateFlow<Boolean> = _isForgotMpinFlow.asStateFlow()

    private val _authLoading = MutableStateFlow(false)
    val authLoading: StateFlow<Boolean> = _authLoading.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _lockoutSeconds = MutableStateFlow(
        runCatching { repo.prefs.remainingLockoutSeconds() }.getOrDefault(0L)
    )
    val lockoutSeconds: StateFlow<Long> = _lockoutSeconds.asStateFlow()

    private val _secretMpinConfigured = MutableStateFlow(
        runCatching { repo.prefs.hasSecretMpin() }.getOrDefault(false)
    )
    val secretMpinConfigured: StateFlow<Boolean> = _secretMpinConfigured.asStateFlow()

    private val _deleteProtectionEnabled = MutableStateFlow(
        runCatching { repo.prefs.deleteProtectionEnabled }.getOrDefault(true)
    )
    val deleteProtectionEnabled: StateFlow<Boolean> = _deleteProtectionEnabled.asStateFlow()

    private val _flashState = MutableStateFlow(FlashScreenState())
    val flashState: StateFlow<FlashScreenState> = _flashState.asStateFlow()

    private val _vaultUnlocked = MutableStateFlow(false)
    val vaultUnlocked: StateFlow<Boolean> = _vaultUnlocked.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _ocrState = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val ocrState: StateFlow<OcrUiState> = _ocrState.asStateFlow()

    private val _storageStats = MutableStateFlow(DeviceStorageStats(0L, 0L, 0L))
    val storageStats: StateFlow<DeviceStorageStats> = _storageStats.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _restoreProgress = MutableStateFlow<Pair<String, Float>?>(null)
    val restoreProgress: StateFlow<Pair<String, Float>?> = _restoreProgress.asStateFlow()

    // Reactive Database Flows
    val activeFiles = repo.activeFiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val vaultFiles = repo.vaultFiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trashFiles = repo.trashFiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val folders = repo.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val customCategories = repo.customCategories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val extractions = repo.documentExtractions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val reminders = repo.documentReminders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeNotes = repo.activeNotes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trashNotes = repo.trashNotes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeLinks = repo.activeLinks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trashLinks = repo.trashLinks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val backupHistory = repo.backupHistory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val securityLogs = repo.securityLogs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val profile = repo.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettingsEntity())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.ensureDefaultsInitialized() }
            _storageStats.value = runCatching { repo.getStorageStats() }
                .getOrDefault(DeviceStorageStats(0L, 0L, 0L))
            runCatching { startLockoutCountdownIfNeeded() }
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun clearAuthError() {
        _authError.value = null
    }

    fun clearOcrState() {
        _ocrState.value = OcrUiState.Idle
    }

    fun biometricAvailability(): BiometricAvailability =
        BiometricHelper.checkAvailability(getApplication())

    fun hasMpin(): Boolean = repo.prefs.hasMpin()

    fun loginMpinLength(): Int = repo.prefs.getMpinLength()

    fun hasSecretMpin(): Boolean = repo.prefs.hasSecretMpin()

    fun secretMpinLength(): Int = repo.prefs.getSecretMpinLength()

    fun dismissFlashScreen() {
        _flashState.value = FlashScreenState()
    }

    fun isBiometricEnabled(): Boolean = repo.prefs.biometricEnabled

    fun isFirebaseSessionActive(): Boolean = repo.hasFirebaseSession()

    fun isSqlBackendConfigured(): Boolean = BackendConfig.isSqlBackendConfigured

    fun firebaseUidDisplay(): String = repo.currentFirebaseUid() ?: "Local MPIN Vault"

    fun ownerPhoneDisplay(): String = repo.prefs.ownerPhone?.takeIf { it.isNotBlank() } ?: "MPIN Protected Owner"

    fun lastBackupMs(): Long = repo.prefs.lastBackupMs

    fun logout() {
        viewModelScope.launch {
            _vaultUnlocked.value = false
            LocalFileManager.wipeVaultPreviewCache(getApplication())
            repo.logSecurity("OWNER_LOCK_EXIT", "Owner locked DSK LOCKER back to MPIN login", true)
            _authError.value = null
            _authStage.value = if (repo.prefs.hasMpin()) AuthStage.MPIN_LOGIN else AuthStage.CREATE_MPIN
        }
    }

    fun createMpin(pin: String, confirmPin: String) {
        if ((pin.length != 4 && pin.length != 6) || !pin.all { it.isDigit() }) {
            _authError.value = "MPIN must be 4 or 6 digits."
            return
        }
        if (pin != confirmPin) {
            _authError.value = "MPIN entries do not match."
            return
        }
        viewModelScope.launch {
            repo.saveNewMpin(pin)
            _isForgotMpinFlow.value = false
            _authError.value = null
            val avail = biometricAvailability()
            if (avail is BiometricAvailability.Available && !repo.prefs.biometricEnabled) {
                _authStage.value = AuthStage.BIOMETRIC_SETUP
            } else {
                triggerPostAuthSplash()
            }
        }
    }

    fun completeBiometricSetup(enrolled: Boolean) {
        repo.prefs.biometricEnabled = enrolled
        viewModelScope.launch {
            if (enrolled) {
                repo.logSecurity("BIOMETRIC_ENROLLED", "Hardware biometric unlock enabled", true)
            }
            triggerPostAuthSplash()
        }
    }

    fun verifyMpinLogin(inputPin: String) {
        val rem = repo.prefs.remainingLockoutSeconds()
        if (rem > 0) {
            _lockoutSeconds.value = rem
            _authError.value = "Too many failed attempts. Wait ${rem}s."
            return
        }
        val expectedLen = repo.prefs.getMpinLength()
        if (inputPin.length != expectedLen && inputPin.length != 4 && inputPin.length != 6) {
            _authError.value = "Enter your ${expectedLen}-digit MPIN."
            return
        }
        if (repo.prefs.verifyMpin(inputPin)) {
            repo.prefs.resetFailedAttempts()
            _lockoutSeconds.value = 0
            _authError.value = null
            viewModelScope.launch { repo.logSecurity("MPIN_UNLOCK", "Owner unlocked DSK LOCKER via MPIN", true) }
            triggerPostAuthSplash()
        } else {
            val penalty = repo.prefs.recordFailedAttempt()
            viewModelScope.launch { repo.logSecurity("MPIN_FAILED", "Invalid MPIN login attempt", false) }
            if (penalty > 0) {
                startLockoutCountdownIfNeeded()
                _authError.value = "Account locked for ${penalty}s due to failed attempts."
            } else {
                val left = (5 - repo.prefs.failedAttempts).coerceAtLeast(1)
                _authError.value = "Incorrect MPIN. $left attempt(s) before temporary lock."
            }
        }
    }

    fun onBiometricLoginSuccess() {
        repo.prefs.resetFailedAttempts()
        _lockoutSeconds.value = 0
        _authError.value = null
        viewModelScope.launch { repo.logSecurity("BIOMETRIC_UNLOCK", "Owner unlocked DSK LOCKER via BiometricPrompt", true) }
        triggerPostAuthSplash()
    }

    fun startForgotMpin() {
        _isForgotMpinFlow.value = repo.prefs.hasMpin()
        _authError.value = null
        _authStage.value = AuthStage.CREATE_MPIN
    }

    fun cancelForgotMpin() {
        _isForgotMpinFlow.value = false
        _authError.value = null
        if (repo.prefs.hasMpin()) _authStage.value = AuthStage.MPIN_LOGIN
    }

    private fun triggerPostAuthSplash() {
        _authStage.value = AuthStage.POST_AUTH_SPLASH
    }

    suspend fun preloadWorkspaceForUnlock(onStep: (String, Float) -> Unit) {
        onStep("Verifying encrypted session...", 0.25f)
        runCatching { repo.ensureDefaultsInitialized() }
        onStep("Loading saved files & vaults...", 0.55f)
        _storageStats.value = runCatching { repo.getStorageStats() }
            .getOrDefault(DeviceStorageStats(0L, 0L, 0L))
        onStep("Loading vault categories...", 0.85f)
        runCatching { repo.refreshWorkspace() }
        onStep("Private vault ready", 1.0f)
    }

    fun finishPostAuthSplash() {
        _authStage.value = AuthStage.UNLOCKED_APP
        refreshAll(silent = true)
    }

    fun lockAppNow() {
        _vaultUnlocked.value = false
        LocalFileManager.wipeVaultPreviewCache(getApplication())
        if (repo.prefs.hasMpin()) {
            _authStage.value = AuthStage.MPIN_LOGIN
        }
    }

    fun onAppBackgrounded() {
        repo.prefs.lastBackgroundMs = System.currentTimeMillis()
        LocalFileManager.wipeVaultPreviewCache(getApplication())
        if ((settings.value?.autoLockSeconds ?: 120) == 0) {
            lockAppNow()
        }
    }

    fun onAppForegrounded() {
        val lastBg = repo.prefs.lastBackgroundMs
        val timeoutSec = settings.value?.autoLockSeconds ?: 120
        if (lastBg > 0 && timeoutSec > 0 && _authStage.value == AuthStage.UNLOCKED_APP) {
            val elapsedSec = (System.currentTimeMillis() - lastBg) / 1000L
            if (elapsedSec >= timeoutSec) {
                lockAppNow()
            }
        }
    }

    private fun startLockoutCountdownIfNeeded() {
        val rem = repo.prefs.remainingLockoutSeconds()
        if (rem <= 0) return
        _lockoutSeconds.value = rem
        viewModelScope.launch {
            while (true) {
                val left = repo.prefs.remainingLockoutSeconds()
                _lockoutSeconds.value = left
                if (left <= 0) break
                delay(1000L)
            }
        }
    }

    // --- Verify MPIN for Sensitive Operations (Delete, Change MPIN, Vault, Biometric removal) ---
    fun verifyMpinForAction(inputPin: String): Boolean {
        val rem = repo.prefs.remainingLockoutSeconds()
        if (rem > 0) {
            _lockoutSeconds.value = rem
            return false
        }
        val ok = repo.prefs.verifyMpin(inputPin)
        if (ok) {
            repo.prefs.resetFailedAttempts()
            _lockoutSeconds.value = 0
        } else {
            val penalty = repo.prefs.recordFailedAttempt()
            if (penalty > 0) startLockoutCountdownIfNeeded()
            viewModelScope.launch { repo.logSecurity("MPIN_ACTION_DENIED", "Wrong MPIN for protected action", false) }
        }
        return ok
    }

    // --- Separate Secret MPIN Verification & Setup (Mandatory Delete Security) ---
    fun setOrChangeSecretMpin(newPin: String, confirmPin: String): Result<Unit> {
        if ((newPin.length != 4 && newPin.length != 6) || !newPin.all { it.isDigit() }) {
            return Result.failure(IllegalArgumentException("Secret MPIN must be 4 or 6 digits."))
        }
        if (newPin != confirmPin) {
            return Result.failure(IllegalArgumentException("Secret MPIN entries do not match."))
        }
        if (repo.prefs.verifyMpin(newPin)) {
            return Result.failure(IllegalArgumentException("Secret MPIN must be different from your Login MPIN."))
        }
        repo.prefs.saveSecretMpin(newPin)
        _secretMpinConfigured.value = true
        _deleteProtectionEnabled.value = true
        viewModelScope.launch {
            repo.logSecurity("SECRET_MPIN_UPDATED", "Separate Delete Protection Secret MPIN configured securely", true)
            _statusBanner.value = "Secret MPIN saved. Delete Protection is active."
        }
        return Result.success(Unit)
    }

    fun verifySecretMpinForDelete(inputPin: String): Boolean {
        if (!repo.prefs.hasSecretMpin()) return false
        val rem = repo.prefs.remainingLockoutSeconds()
        if (rem > 0) {
            _lockoutSeconds.value = rem
            return false
        }
        val ok = repo.prefs.verifySecretMpin(inputPin)
        if (ok) {
            repo.prefs.resetFailedAttempts()
            _lockoutSeconds.value = 0
            viewModelScope.launch { repo.logSecurity("SECRET_MPIN_DELETE_OK", "Secret MPIN verified for secure file deletion", true) }
        } else {
            val penalty = repo.prefs.recordFailedAttempt()
            if (penalty > 0) startLockoutCountdownIfNeeded()
            viewModelScope.launch { repo.logSecurity("SECRET_MPIN_DELETE_DENIED", "Wrong Secret MPIN entered during delete verification", false) }
        }
        return ok
    }

    fun setDeleteProtectionEnabled(enabled: Boolean) {
        if (!enabled) {
            _deleteProtectionEnabled.value = true
            repo.prefs.deleteProtectionEnabled = true
            _statusBanner.value = "Delete Protection cannot be bypassed. Secret MPIN is always required to delete files."
        } else {
            repo.prefs.deleteProtectionEnabled = true
            _deleteProtectionEnabled.value = true
            _statusBanner.value = "Secret MPIN Delete Protection is active."
        }
    }

    fun unlockVaultWithMpin(inputPin: String): Boolean {
        val ok = verifyMpinForAction(inputPin)
        if (ok) {
            _vaultUnlocked.value = true
            viewModelScope.launch { repo.logSecurity("VAULT_UNLOCK", "Secure Vault unlocked via MPIN", true) }
        }
        return ok
    }

    fun unlockVaultWithBiometric() {
        _vaultUnlocked.value = true
        viewModelScope.launch { repo.logSecurity("VAULT_UNLOCK", "Secure Vault unlocked via Biometric", true) }
    }

    fun lockVault() {
        _vaultUnlocked.value = false
        LocalFileManager.wipeVaultPreviewCache(getApplication())
    }

    // --- File & Vault Operations (Direct Automatic OCR + Flash Screen) ---
    fun importFiles(
        uris: List<Uri>,
        isInVault: Boolean,
        folderId: String? = null,
        forcedCategoryId: String? = null,
        autoAnalyzeOcr: Boolean = true,
        onCompleteNavigate: ((FileItemEntity) -> Unit)? = null
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                _flashState.value = FlashScreenState(
                    isVisible = true,
                    title = "UPLOADING YOUR FILE",
                    subtitle = if (isInVault) "AES-256-GCM Encryption + Storage" else "Importing ${uris.size} file(s)",
                    stepLabel = "Preserving original file names...",
                    progress = 0.15f
                )
                val existingBySha = mutableListOf<String>()
                val imported = repo.importFiles(uris, isInVault, folderId, forcedCategoryId)
                if (imported.isEmpty()) {
                    throw IllegalStateException("Could not read or save selected file(s). Please retry.")
                }

                for (item in imported) {
                    val matches = repo.findDuplicateFiles(item.sha256).filter { it.id != item.id }
                    if (matches.isNotEmpty()) {
                        existingBySha.add(item.originalFilename)
                    }
                }

                val ocrTargets = imported.filter { repo.isOcrSupported(it) }
                val unsupportedTargets = imported.filter { !repo.isOcrSupported(it) }
                var ocrSuccessCount = 0
                var ocrPartialCount = 0
                var ocrFailedCount = 0
                var lastOcrFailureMsg: String? = null

                if (autoAnalyzeOcr && ocrTargets.isNotEmpty()) {
                    val allowCategoryPromotion = forcedCategoryId == null ||
                        forcedCategoryId == DskCategories.CAT_PHOTOS ||
                        forcedCategoryId == DskCategories.CAT_DOCUMENTS

                    for ((idx, item) in ocrTargets.withIndex()) {
                        _flashState.value = FlashScreenState(
                            isVisible = true,
                            title = "SCANNING DOCUMENT",
                            subtitle = item.originalFilename,
                            stepLabel = "Analyzing file (${idx + 1}/${ocrTargets.size})...",
                            progress = 0.35f + (0.42f * (idx.toFloat() / ocrTargets.size.coerceAtLeast(1)))
                        )
                        val ocrRes = repo.runDocumentOcr(
                            item = item,
                            autoDetectCategory = allowCategoryPromotion,
                            renameWithSuggested = (item.displayName == item.originalFilename),
                            onProgress = { stageTitle, frac ->
                                _flashState.value = _flashState.value.copy(
                                    title = stageTitle,
                                    stepLabel = item.originalFilename,
                                    progress = (0.35f + frac * 0.45f).coerceIn(0.20f, 0.85f)
                                )
                            }
                        )
                        when (ocrRes) {
                            is DocumentOcrResult.Success -> {
                                ocrSuccessCount++
                                if (idx == 0) {
                                    _ocrState.value = OcrUiState.Success(
                                        extraction = ocrRes.extraction,
                                        extended = ocrRes.extendedMetadata,
                                        suggestedName = ocrRes.suggestedDisplayName,
                                        notice = ocrRes.notice
                                    )
                                }
                            }
                            is DocumentOcrResult.PartialRawTextOnly -> {
                                ocrPartialCount++
                                if (idx == 0) _ocrState.value = OcrUiState.Error(ocrRes.errorMessage)
                            }
                            is DocumentOcrResult.Error -> {
                                ocrFailedCount++
                                lastOcrFailureMsg = ocrRes.message
                                if (idx == 0) _ocrState.value = OcrUiState.Error(ocrRes.message)
                            }
                        }
                    }
                }

                _flashState.value = FlashScreenState(
                    isVisible = true,
                    title = "SECURING YOUR FILE",
                    subtitle = "${imported.size} file(s)",
                    stepLabel = "Updating categories & search index...",
                    progress = 0.92f
                )
                _storageStats.value = repo.getStorageStats()

                val dupNotice = if (existingBySha.isNotEmpty()) {
                    " (Duplicate detected: ${existingBySha.first()})"
                } else ""

                val msg = buildString {
                    append(if (isInVault) "Encrypted & saved ${imported.size} file(s) in Vault." else "Saved ${imported.size} file(s) with original filename(s).")
                    if (autoAnalyzeOcr) {
                        when {
                            ocrSuccessCount > 0 && ocrFailedCount == 0 && unsupportedTargets.isEmpty() ->
                                append(" OCR extracted & indexed.")
                            ocrPartialCount > 0 && ocrSuccessCount == 0 ->
                                append(" Extracted text indexed for review.")
                            ocrFailedCount > 0 ->
                                append(" ${lastOcrFailureMsg ?: "OCR could not read text; file preserved safely."}")
                            unsupportedTargets.isNotEmpty() && ocrTargets.isEmpty() -> {
                                val exts = unsupportedTargets.map {
                                    it.originalFilename.substringAfterLast('.', "").uppercase().ifBlank { "BINARY" }
                                }.distinct().joinToString("/")
                                append(" Format ($exts) does not support text OCR — file saved safely.")
                            }
                        }
                    }
                    append(dupNotice)
                }

                _flashState.value = FlashScreenState(
                    isVisible = true,
                    title = "FILE SAVED SUCCESSFULLY",
                    subtitle = msg,
                    stepLabel = "Complete",
                    progress = 1f,
                    isSuccess = true
                )
                _statusBanner.value = msg
                delay(750L)
                _flashState.value = FlashScreenState()
                val latestPrimary = (if (isInVault) repo.vaultFiles.firstOrNull() else repo.activeFiles.firstOrNull())
                    ?.firstOrNull { it.id == imported.first().id }
                    ?: imported.first()
                onCompleteNavigate?.invoke(latestPrimary)
            }.onFailure { err ->
                _flashState.value = FlashScreenState(
                    isVisible = true,
                    title = "UPLOAD FAILED",
                    subtitle = "Existing files remain safe.",
                    stepLabel = "Error",
                    progress = 0f,
                    errorMessage = err.localizedMessage ?: "Failed to save file.",
                    onRetry = { importFiles(uris, isInVault, folderId, forcedCategoryId, autoAnalyzeOcr, onCompleteNavigate) }
                )
            }
        }
    }

    fun importScannedDocuments(
        uris: List<Uri>,
        isInVault: Boolean,
        folderId: String? = null,
        forcedCategoryId: String? = null,
        autoAnalyzeOcr: Boolean = true,
        onImported: (FileItemEntity?) -> Unit = {}
    ) {
        if (uris.isEmpty()) {
            onImported(null)
            return
        }
        viewModelScope.launch {
            runCatching {
                _flashState.value = FlashScreenState(
                    isVisible = true,
                    title = "UPLOADING YOUR FILE",
                    subtitle = "Saving ${uris.size} scanned page(s)",
                    stepLabel = "Preserving scanned original...",
                    progress = 0.2f
                )
                val targetCat = forcedCategoryId ?: DskCategories.CAT_DOCUMENTS
                val imported = repo.importFiles(
                    uris = uris,
                    isInVault = isInVault,
                    folderId = folderId,
                    forcedCategoryId = targetCat
                )
                val primary = imported.firstOrNull()
                    ?: throw IllegalStateException("Unable to save scanned document.")

                var ocrOutcomeNote = ""
                if (autoAnalyzeOcr) {
                    _flashState.value = FlashScreenState(
                        isVisible = true,
                        title = "SCANNING DOCUMENT",
                        subtitle = primary.originalFilename,
                        stepLabel = "Running OCR & detecting document type...",
                        progress = 0.55f
                    )
                    when (
                        val res = repo.runDocumentOcr(
                            item = primary,
                            autoDetectCategory = true,
                            renameWithSuggested = (primary.displayName == primary.originalFilename),
                            onProgress = { stageTitle, frac ->
                                _flashState.value = _flashState.value.copy(
                                    title = stageTitle,
                                    progress = (0.35f + frac * 0.45f).coerceIn(0.3f, 0.85f)
                                )
                            }
                        )
                    ) {
                        is DocumentOcrResult.Success -> {
                            _ocrState.value = OcrUiState.Success(
                                extraction = res.extraction,
                                extended = res.extendedMetadata,
                                suggestedName = res.suggestedDisplayName,
                                notice = res.notice
                            )
                            val docLabel = res.extraction.documentType ?: res.extraction.documentName ?: "Document"
                            ocrOutcomeNote = " Detected: $docLabel."
                        }
                        is DocumentOcrResult.PartialRawTextOnly -> {
                            _ocrState.value = OcrUiState.Error(res.errorMessage)
                            ocrOutcomeNote = " Text extracted for manual verification."
                        }
                        is DocumentOcrResult.Error -> {
                            _ocrState.value = OcrUiState.Error(res.message)
                            ocrOutcomeNote = " Scan saved safely; OCR could not extract text."
                        }
                    }
                }
                _flashState.value = FlashScreenState(
                    isVisible = true,
                    title = "SECURING YOUR FILE",
                    subtitle = primary.originalFilename,
                    stepLabel = "Finalizing search index...",
                    progress = 0.92f
                )
                _storageStats.value = repo.getStorageStats()
                val msg = (if (isInVault) "Scanned & encrypted ${imported.size} document(s) in Vault."
                else "Saved ${imported.size} scanned document(s).") + ocrOutcomeNote
                _flashState.value = FlashScreenState(
                    isVisible = true,
                    title = "FILE SAVED SUCCESSFULLY",
                    subtitle = msg,
                    stepLabel = "Complete",
                    progress = 1f,
                    isSuccess = true
                )
                _statusBanner.value = msg
                delay(700L)
                _flashState.value = FlashScreenState()
                onImported(primary)
            }.onFailure { err ->
                _flashState.value = FlashScreenState(
                    isVisible = true,
                    title = "SCAN SAVE FAILED",
                    subtitle = "Please retry saving.",
                    stepLabel = "Error",
                    errorMessage = err.localizedMessage ?: "Failed to save scan.",
                    onRetry = { importScannedDocuments(uris, isInVault, folderId, forcedCategoryId, autoAnalyzeOcr, onImported) }
                )
            }
        }
    }

    fun renameFile(file: FileItemEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            repo.renameFile(file, newName)
            _statusBanner.value = "Renamed to $newName"
        }
    }

    fun moveOrCopyFile(file: FileItemEntity, categoryId: String, folderId: String?, isCopy: Boolean) {
        viewModelScope.launch {
            repo.moveOrCopyFile(file, categoryId, folderId, isCopy)
            _storageStats.value = repo.getStorageStats()
            _statusBanner.value = if (isCopy) "File copied." else "File moved."
        }
    }

    fun toggleFavorite(file: FileItemEntity) {
        viewModelScope.launch { repo.toggleFileFavorite(file) }
    }

    fun executeDeleteFile(file: FileItemEntity, mode: DeleteMode) {
        viewModelScope.launch {
            repo.deleteFileWithMode(file, mode)
            _storageStats.value = repo.getStorageStats()
            _statusBanner.value = when (mode) {
                DeleteMode.MOBILE_ONLY -> "Removed mobile copy. Cloud backup preserved."
                DeleteMode.CLOUD_ONLY -> "Removed cloud copy. Mobile file kept."
                DeleteMode.BOTH -> "Moved to Recycle Bin & removed cloud copy."
            }
        }
    }

    fun executeBulkDeleteFiles(files: List<FileItemEntity>, mode: DeleteMode) {
        viewModelScope.launch {
            files.forEach { repo.deleteFileWithMode(it, mode) }
            _storageStats.value = repo.getStorageStats()
            _statusBanner.value = "Processed ${files.size} file(s) (${mode.label})."
        }
    }

    fun restoreFromTrash(file: FileItemEntity) {
        viewModelScope.launch {
            repo.restoreFromTrash(file)
            _statusBanner.value = "Restored ${file.displayName}"
        }
    }

    fun permanentDeleteFromTrash(file: FileItemEntity) {
        viewModelScope.launch {
            repo.permanentlyDeleteFile(file)
            _storageStats.value = repo.getStorageStats()
            _statusBanner.value = "Permanently deleted ${file.displayName}"
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            trashFiles.value.forEach { repo.permanentlyDeleteFile(it) }
            repo.emptyAllTrash()
            _storageStats.value = repo.getStorageStats()
            _statusBanner.value = "Recycle Bin emptied."
        }
    }

    fun downloadFileToPhone(file: FileItemEntity, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _downloadProgress.value = 0.05f
            val readable = LocalFileManager.resolveReadableFile(getApplication(), file)
            if (readable == null) {
                _downloadProgress.value = null
                _statusBanner.value = "File is Cloud Only. Restore to phone first."
                return@launch
            }
            val res = LocalFileManager.exportToPhoneDownloads(
                context = getApplication(),
                sourceFile = readable,
                fileName = file.originalFilename,
                mimeType = file.mimeType,
                onProgress = { pct -> _downloadProgress.value = pct }
            )
            _downloadProgress.value = null
            if (res.isSuccess) {
                repo.recordDownload(file)
                val dest = res.getOrThrow()
                _statusBanner.value = "Downloaded to $dest"
                onDone(dest)
            } else {
                _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Download failed."
            }
        }
    }

    fun exportFileToSafUri(file: FileItemEntity, targetUri: Uri) {
        viewModelScope.launch {
            _downloadProgress.value = 0.05f
            val readable = LocalFileManager.resolveReadableFile(getApplication(), file)
            if (readable == null) {
                _downloadProgress.value = null
                _statusBanner.value = "Local file missing. Restore first."
                return@launch
            }
            val res = LocalFileManager.exportToSafUri(getApplication(), readable, targetUri) { pct ->
                _downloadProgress.value = pct
            }
            _downloadProgress.value = null
            if (res.isSuccess) {
                repo.recordDownload(file)
                _statusBanner.value = "Saved ${file.originalFilename} to chosen folder."
            } else {
                _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Save failed."
            }
        }
    }

    // --- Backup & Restore ---
    fun runManualBackupNow(selected: List<FileItemEntity>? = null) {
        viewModelScope.launch {
            _isRefreshing.value = true
            val (ok, fail) = repo.runManualBackup(selected)
            _isRefreshing.value = false
            _statusBanner.value = if (!repo.hasFirebaseSession()) {
                "Cloud backup session unavailable. Local files & backup queue remain safe."
            } else {
                "Firebase Storage backup complete: $ok uploaded, $fail pending/failed."
            }
        }
    }

    fun restoreCloudFiles(files: List<FileItemEntity>, overwrite: Boolean) {
        viewModelScope.launch {
            _restoreProgress.value = "Preparing restore..." to 0f
            val res = repo.restoreFromCloud(files, overwrite) { name, pct ->
                _restoreProgress.value = name to pct
            }
            _restoreProgress.value = null
            _storageStats.value = repo.getStorageStats()
            if (res.isSuccess) {
                _statusBanner.value = "Restored ${res.getOrThrow()} file(s) with SHA-256 verification."
            } else {
                _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Restore failed."
            }
        }
    }

    // --- Refresh ---
    fun refreshAll(silent: Boolean = false) {
        viewModelScope.launch {
            _isRefreshing.value = true
            val res = repo.refreshWorkspace()
            _storageStats.value = repo.getStorageStats()
            _isRefreshing.value = false
            if (!silent) {
                _statusBanner.value = if (res.isSuccess) {
                    "Workspace synced & verified."
                } else {
                    res.exceptionOrNull()?.localizedMessage ?: "Refreshed local state."
                }
            }
        }
    }

    // --- AI Document OCR & Expiry Reminders ---
    fun loadExtendedMetadata(extraction: DocumentExtractionEntity): ExtendedOcrMetadata =
        repo.loadExtendedOcrMetadata(extraction)

    fun runOcrOnFile(file: FileItemEntity, showFlash: Boolean = true) {
        viewModelScope.launch {
            _ocrState.value = OcrUiState.Analyzing("Preparing document...", 0.10f)
            if (showFlash) {
                _flashState.value = FlashScreenState(
                    isVisible = true,
                    title = "SCANNING DOCUMENT",
                    subtitle = file.originalFilename,
                    stepLabel = "Enhancing clarity & extracting Hindi/English text...",
                    progress = 0.20f
                )
            }
            when (
                val res = repo.runDocumentOcr(
                    item = file,
                    autoDetectCategory = true,
                    renameWithSuggested = (file.displayName == file.originalFilename),
                    onProgress = { label, frac ->
                        _ocrState.value = OcrUiState.Analyzing(label, frac)
                        if (showFlash) {
                            _flashState.value = _flashState.value.copy(
                                title = label,
                                stepLabel = file.originalFilename,
                                progress = frac.coerceIn(0.15f, 0.94f)
                            )
                        }
                    }
                )
            ) {
                is DocumentOcrResult.Success -> {
                    _ocrState.value = OcrUiState.Success(
                        extraction = res.extraction,
                        extended = res.extendedMetadata,
                        suggestedName = res.suggestedDisplayName,
                        notice = res.notice
                    )
                    if (showFlash) {
                        _flashState.value = FlashScreenState(
                            isVisible = true,
                            title = "FILE SAVED SUCCESSFULLY",
                            subtitle = res.extraction.documentName ?: file.displayName,
                            stepLabel = res.notice ?: "Indexed & ready for verification",
                            progress = 1f,
                            isSuccess = true
                        )
                        delay(600L)
                        _flashState.value = FlashScreenState()
                    }
                }
                is DocumentOcrResult.PartialRawTextOnly -> {
                    _ocrState.value = OcrUiState.Error(res.errorMessage)
                    if (showFlash) {
                        _flashState.value = FlashScreenState(
                            isVisible = true,
                            title = "TEXT EXTRACTED & INDEXED",
                            subtitle = "Verify/edit fields in OCR Details",
                            stepLabel = "Raw OCR text saved",
                            progress = 1f,
                            isSuccess = true
                        )
                        delay(600L)
                        _flashState.value = FlashScreenState()
                    }
                }
                is DocumentOcrResult.Error -> {
                    _ocrState.value = OcrUiState.Error(res.message)
                    if (showFlash) {
                        _flashState.value = FlashScreenState(
                            isVisible = true,
                            title = "OCR NOTICE",
                            subtitle = file.originalFilename,
                            stepLabel = "Unable to extract text",
                            errorMessage = res.message,
                            onRetry = { runOcrOnFile(file, showFlash = true) }
                        )
                    }
                }
            }
        }
    }

    fun saveVerifiedExtraction(
        file: FileItemEntity,
        extraction: DocumentExtractionEntity,
        applySuggestedDisplayName: Boolean = false,
        extendedMetadata: ExtendedOcrMetadata? = null
    ) {
        viewModelScope.launch {
            _flashState.value = FlashScreenState(
                isVisible = true,
                title = "SECURING YOUR FILE",
                subtitle = file.originalFilename,
                stepLabel = "Saving verified information & search index...",
                progress = 0.65f
            )
            repo.saveVerifiedExtraction(file, extraction, applySuggestedDisplayName, extendedMetadata)
            val refreshedExt = extendedMetadata ?: repo.loadExtendedOcrMetadata(extraction)
            _ocrState.value = OcrUiState.Success(
                extraction = extraction.copy(isVerified = true),
                extended = refreshedExt.copy(needsReviewFields = emptySet(), stageSummary = "Verified by user"),
                suggestedName = null
            )
            _flashState.value = FlashScreenState(
                isVisible = true,
                title = "FILE SAVED SUCCESSFULLY",
                subtitle = "Verified metadata stored safely",
                stepLabel = "Complete",
                progress = 1f,
                isSuccess = true
            )
            _statusBanner.value = "Verified & saved document details."
            delay(550L)
            _flashState.value = FlashScreenState()
        }
    }

    fun saveExpiryReminder(reminder: DocumentReminderEntity) {
        viewModelScope.launch {
            repo.saveExpiryReminder(reminder)
            _statusBanner.value = "Expiry reminder schedule saved."
        }
    }

    // --- Folders, Categories, Notes, Links ---
    fun createFolder(name: String, categoryId: String, autoBackup: Boolean = true) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repo.createFolder(name, categoryId, autoBackup)
            _statusBanner.value = "Created folder \"$name\""
        }
    }

    fun toggleFolderAutoBackup(folder: FolderEntity) {
        viewModelScope.launch { repo.toggleFolderAutoBackup(folder) }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch {
            repo.deleteFolder(folder)
            _statusBanner.value = "Deleted folder \"${folder.name}\""
        }
    }

    fun createCustomCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repo.createCustomCategory(name)
            _statusBanner.value = "Added category \"$name\""
        }
    }

    fun saveNote(note: TextNoteEntity) {
        viewModelScope.launch {
            _flashState.value = FlashScreenState(
                isVisible = true,
                title = "UPLOADING YOUR FILE",
                subtitle = note.title,
                stepLabel = "Indexing text for instant search...",
                progress = 0.45f
            )
            repo.saveTextNote(note)
            _flashState.value = FlashScreenState(
                isVisible = true,
                title = "SECURING YOUR FILE",
                subtitle = note.title,
                stepLabel = "Securing entry in vault...",
                progress = 0.85f
            )
            _flashState.value = FlashScreenState(
                isVisible = true,
                title = "FILE SAVED SUCCESSFULLY",
                subtitle = note.title,
                stepLabel = "Text indexed & saved",
                progress = 1f,
                isSuccess = true
            )
            _statusBanner.value = "Saved text \"${note.title}\""
            delay(500L)
            _flashState.value = FlashScreenState()
        }
    }

    fun deleteNote(note: TextNoteEntity, permanent: Boolean = false) {
        viewModelScope.launch {
            repo.deleteTextNote(note, permanent)
            _statusBanner.value = if (permanent) "Permanently deleted text." else "Moved text to Recycle Bin."
        }
    }

    fun restoreNote(note: TextNoteEntity) {
        viewModelScope.launch { repo.saveTextNote(note.copy(isDeleted = false, deletedAt = null)) }
    }

    suspend fun fetchLinkMetadata(url: String): FetchedLinkMetadata = repo.fetchLinkMetadata(url)

    fun saveLink(link: SavedLinkEntity, extractPageText: Boolean = true) {
        viewModelScope.launch {
            runCatching {
                _flashState.value = FlashScreenState(
                    isVisible = true,
                    title = "UPLOADING YOUR FILE",
                    subtitle = link.title,
                    stepLabel = "Validating URL...",
                    progress = 0.25f
                )
                val enriched = if (extractPageText && (link.description.isBlank() || link.title == link.url)) {
                    _flashState.value = _flashState.value.copy(
                        title = "EXTRACTING INFORMATION",
                        stepLabel = "Fetching title & readable metadata...",
                        progress = 0.55f
                    )
                    val meta = runCatching { repo.fetchLinkMetadata(link.url) }.getOrNull()
                    if (meta != null) {
                        val extractedDesc = listOfNotNull(
                            meta.description?.takeIf { it.isNotBlank() },
                            meta.pageContent?.takeIf { it.isNotBlank() }?.take(800)
                        ).joinToString("\n\n").trim()
                        link.copy(
                            title = if (link.title == link.url && !meta.title.isNullOrBlank()) meta.title else link.title,
                            thumbnailUrl = link.thumbnailUrl ?: meta.thumbnailUrl,
                            description = link.description.ifBlank { extractedDesc }
                        )
                    } else link
                } else link

                _flashState.value = _flashState.value.copy(
                    title = "SECURING YOUR FILE",
                    stepLabel = "Saving link & updating search index...",
                    progress = 0.85f
                )
                repo.saveLink(enriched)
                _flashState.value = FlashScreenState(
                    isVisible = true,
                    title = "FILE SAVED SUCCESSFULLY",
                    subtitle = enriched.title,
                    stepLabel = "Complete",
                    progress = 1f,
                    isSuccess = true
                )
                _statusBanner.value = "Saved \"${enriched.title}\""
                delay(500L)
                _flashState.value = FlashScreenState()
            }.onFailure { err ->
                _flashState.value = FlashScreenState(
                    isVisible = true,
                    title = "SAVE FAILED",
                    subtitle = link.url,
                    errorMessage = err.localizedMessage ?: "Failed to save link.",
                    onRetry = { saveLink(link, extractPageText) }
                )
            }
        }
    }

    fun deleteLink(link: SavedLinkEntity, permanent: Boolean = false) {
        viewModelScope.launch {
            repo.deleteLink(link, permanent)
            _statusBanner.value = if (permanent) "Permanently deleted link." else "Moved link to Recycle Bin."
        }
    }

    fun restoreLink(link: SavedLinkEntity) {
        viewModelScope.launch { repo.saveLink(link.copy(isDeleted = false, deletedAt = null)) }
    }

    // --- Profile & Settings ---
    fun updateProfile(name: String, avatarUri: Uri? = null) {
        viewModelScope.launch {
            repo.updateProfile(name, avatarUri)
            _statusBanner.value = "Profile updated."
        }
    }

    fun updateSettings(transform: (UserSettingsEntity) -> UserSettingsEntity) {
        viewModelScope.launch { repo.updateSettings(transform) }
    }

    fun toggleBiometricSetting(enabled: Boolean) {
        repo.prefs.biometricEnabled = enabled
        viewModelScope.launch {
            repo.logSecurity(
                if (enabled) "BIOMETRIC_ENABLED" else "BIOMETRIC_REMOVED",
                if (enabled) "Biometric unlock enabled" else "Biometric unlock removed by owner",
                true
            )
            _statusBanner.value = if (enabled) "Biometric unlock enabled." else "Biometric unlock removed."
        }
    }

    fun changeMpin(newPin: String): Result<Unit> {
        if ((newPin.length != 4 && newPin.length != 6) || !newPin.all { it.isDigit() }) {
            return Result.failure(IllegalArgumentException("Login MPIN must be 4 or 6 digits."))
        }
        if (repo.prefs.hasSecretMpin() && repo.prefs.verifySecretMpin(newPin)) {
            return Result.failure(IllegalArgumentException("Login MPIN must be different from your Secret MPIN."))
        }
        viewModelScope.launch {
            repo.saveNewMpin(newPin)
            _statusBanner.value = "Login ${newPin.length}-digit MPIN updated successfully."
        }
        return Result.success(Unit)
    }
}
