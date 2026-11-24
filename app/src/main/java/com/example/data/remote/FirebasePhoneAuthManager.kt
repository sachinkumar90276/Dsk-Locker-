package com.example.data.remote

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

private const val AUTH_TAG = "DskFirebaseAuth"

data class CountryDialCode(
    val isoCode: String,
    val countryName: String,
    val dialCode: String,
    val minDigits: Int = 7,
    val maxDigits: Int = 12
)

object AuthValidation {
    val supportedCountryCodes = listOf(
        CountryDialCode("IN", "India", "+91", 10, 10),
        CountryDialCode("US", "United States / Canada", "+1", 10, 10),
        CountryDialCode("GB", "United Kingdom", "+44", 10, 10),
        CountryDialCode("AE", "United Arab Emirates", "+971", 9, 9),
        CountryDialCode("SG", "Singapore", "+65", 8, 8),
        CountryDialCode("AU", "Australia", "+61", 9, 9),
        CountryDialCode("DE", "Germany", "+49", 10, 11),
        CountryDialCode("SA", "Saudi Arabia", "+966", 9, 9),
        CountryDialCode("MY", "Malaysia", "+60", 9, 10),
        CountryDialCode("JP", "Japan", "+81", 10, 10)
    )

    private val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val e164Regex = Regex("^\\+[1-9]\\d{7,14}$")

    fun isValidEmail(email: String): Boolean = emailRegex.matches(email.trim())

    fun normalizeAndValidatePhone(rawInput: String, selectedCountryCode: String = "+91"): Result<String> {
        val compact = rawInput.trim().replace(Regex("[\\s()-]"), "")
        if (compact.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter a valid mobile number."))
        }
        val normalized = if (compact.startsWith("+")) {
            "+" + compact.drop(1).filter { it.isDigit() }
        } else {
            val cc = if (selectedCountryCode.startsWith("+")) selectedCountryCode else "+$selectedCountryCode"
            val digits = compact.filter { it.isDigit() }.trimStart('0')
            "$cc$digits"
        }
        if (!e164Regex.matches(normalized)) {
            return Result.failure(IllegalArgumentException("Enter a valid mobile number with country code (e.g. $selectedCountryCode 9876543210)."))
        }
        val matchedCountry = supportedCountryCodes
            .sortedByDescending { it.dialCode.length }
            .firstOrNull { normalized.startsWith(it.dialCode) }
        if (matchedCountry != null) {
            val subscriberLen = normalized.removePrefix(matchedCountry.dialCode).length
            if (subscriberLen < matchedCountry.minDigits || subscriberLen > matchedCountry.maxDigits) {
                val expectedDesc = if (matchedCountry.minDigits == matchedCountry.maxDigits) {
                    "${matchedCountry.minDigits} digits"
                } else {
                    "${matchedCountry.minDigits}-${matchedCountry.maxDigits} digits"
                }
                return Result.failure(
                    IllegalArgumentException("Phone number for ${matchedCountry.countryName} (${matchedCountry.dialCode}) must be $expectedDesc.")
                )
            }
        }
        return Result.success(normalized)
    }
}

sealed interface PhoneOtpStartResult {
    data class CodeSent(val verificationId: String, val resendToken: PhoneAuthProvider.ForceResendingToken) : PhoneOtpStartResult
    data class AutoVerified(val uid: String, val phoneNumber: String, val idToken: String) : PhoneOtpStartResult
    data class Error(val message: String) : PhoneOtpStartResult
}

sealed interface GoogleSignInResult {
    data class Success(val session: FirebaseSessionUser) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data class Error(val message: String) : GoogleSignInResult
}

sealed interface EmailAuthResult {
    data class Success(
        val session: FirebaseSessionUser,
        val verificationEmailSent: Boolean = false,
        val isEmailVerified: Boolean = false
    ) : EmailAuthResult
    data class Error(val message: String) : EmailAuthResult
}

data class FirebaseSessionUser(
    val uid: String,
    val phoneNumber: String,
    val idToken: String,
    val email: String? = null,
    val displayName: String? = null,
    val isEmailVerified: Boolean = false
)

class FirebasePhoneAuthManager {
    private val auth: FirebaseAuth
        get() = Firebase.auth

    private fun isFirebaseReady(): Boolean =
        runCatching { FirebaseApp.getInstance(); true }.getOrDefault(false)

    fun currentUser(): com.google.firebase.auth.FirebaseUser? =
        if (!isFirebaseReady()) null else runCatching { auth.currentUser }.getOrNull()

    suspend fun getFreshIdToken(forceRefresh: Boolean = false): String? {
        val user = currentUser() ?: return null
        return runCatching { user.getIdToken(forceRefresh).await()?.token }.getOrNull()
    }

    fun signOut() {
        runCatching { auth.signOut() }
    }

    suspend fun signOutWithCredentialClear(context: Context) {
        withContext(NonCancellable) {
            try {
                withTimeoutOrNull(1500L) {
                    CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
                }
            } catch (e: Exception) {
                Log.w(AUTH_TAG, "Failed to clear Credential Manager state: ${e.message}")
            } finally {
                runCatching { auth.signOut() }
            }
        }
    }

    suspend fun attemptSilentGoogleSignIn(context: Context): FirebaseSessionUser? {
        if (!isFirebaseReady()) return null
        currentUser()?.let { existing ->
            val token = runCatching { existing.getIdToken(false).await()?.token }.getOrNull()
            if (!token.isNullOrBlank()) {
                return FirebaseSessionUser(
                    uid = existing.uid,
                    phoneNumber = existing.phoneNumber.orEmpty(),
                    idToken = token,
                    email = existing.email,
                    displayName = existing.displayName
                )
            }
        }
        val clientId = runCatching { context.getString(R.string.default_web_client_id) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val credentialManager = CredentialManager.create(context)
            val cred = credentialManager.getCredential(context, request).credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                val authResult = auth.signInWithCredential(
                    GoogleAuthProvider.getCredential(googleIdToken, null)
                ).await()
                val user = authResult.user ?: return null
                val firebaseToken = user.getIdToken(true).await()?.token ?: return null
                FirebaseSessionUser(
                    uid = user.uid,
                    phoneNumber = user.phoneNumber.orEmpty(),
                    idToken = firebaseToken,
                    email = user.email,
                    displayName = user.displayName
                )
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoCredentialException) {
            null
        } catch (e: Exception) {
            Log.w(AUTH_TAG, "Silent Google Sign-In unavailable: ${e.message}")
            null
        }
    }

    suspend fun signInWithGoogle(context: Context): GoogleSignInResult {
        if (!isFirebaseReady()) {
            return GoogleSignInResult.Error("Firebase is not initialized in this build. Verify google-services.json configuration.")
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            return GoogleSignInResult.Error("Google Sign-In configuration missing (default_web_client_id).")
        }
        if (clientId.isBlank()) {
            return GoogleSignInResult.Error("Google Sign-In Web Client ID is empty in google-services.json.")
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        return try {
            val credentialManager = CredentialManager.create(context)
            val activityContext = findActivity(context) ?: context
            val cred = credentialManager.getCredential(activityContext, request).credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                val authResult = auth.signInWithCredential(
                    GoogleAuthProvider.getCredential(googleIdToken, null)
                ).await()
                val user = authResult.user
                    ?: return GoogleSignInResult.Error("Firebase returned null user after Google Sign-In.")
                val firebaseToken = user.getIdToken(true).await()?.token
                    ?: return GoogleSignInResult.Error("Unable to obtain Firebase ID token after Google Sign-In.")
                GoogleSignInResult.Success(
                    FirebaseSessionUser(
                        uid = user.uid,
                        phoneNumber = user.phoneNumber.orEmpty(),
                        idToken = firebaseToken,
                        email = user.email,
                        displayName = user.displayName
                    )
                )
            } else {
                GoogleSignInResult.Error("Unexpected credential type returned by Google Sign-In.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(AUTH_TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            Log.w(AUTH_TAG, "No Google credentials found on device: ${e.message}", e)
            GoogleSignInResult.Error(
                "No Google account available on this device. Add a Google account in Android Settings and try again."
            )
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(AUTH_TAG, "Failed to parse Google ID token", e)
            GoogleSignInResult.Error("Invalid Google ID token response.")
        } catch (e: Exception) {
            Log.e(AUTH_TAG, "Google Sign-In failed", e)
            GoogleSignInResult.Error(e.localizedMessage ?: "Google Sign-In failed.")
        }
    }

    fun sendOrResendOtp(
        activity: Activity,
        phoneE164: String,
        resendToken: PhoneAuthProvider.ForceResendingToken? = null,
        onResult: (PhoneOtpStartResult) -> Unit
    ) {
        if (!isFirebaseReady()) {
            onResult(PhoneOtpStartResult.Error("Firebase is not initialized in this build. Verify google-services.json configuration."))
            return
        }
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                auth.signInWithCredential(credential)
                    .addOnSuccessListener { result ->
                        val user = result.user
                        if (user != null) {
                            user.getIdToken(true)
                                .addOnSuccessListener { tokenRes ->
                                    onResult(
                                        PhoneOtpStartResult.AutoVerified(
                                            uid = user.uid,
                                            phoneNumber = user.phoneNumber ?: phoneE164,
                                            idToken = tokenRes.token.orEmpty()
                                        )
                                    )
                                }
                                .addOnFailureListener { err ->
                                    onResult(PhoneOtpStartResult.Error(err.localizedMessage ?: "Failed to obtain ID token."))
                                }
                        } else {
                            onResult(PhoneOtpStartResult.Error("Firebase user missing after credential sign-in."))
                        }
                    }
                    .addOnFailureListener { err ->
                        onResult(PhoneOtpStartResult.Error(formatAuthError(err)))
                    }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                onResult(PhoneOtpStartResult.Error(formatAuthError(e)))
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                onResult(PhoneOtpStartResult.CodeSent(verificationId, token))
            }
        }

        auth.useAppLanguage()

        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneE164)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)

        if (resendToken != null) {
            builder.setForceResendingToken(resendToken)
        }

        PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }

    suspend fun verifyOtpCode(
        verificationId: String,
        smsCode: String,
        expectedPhoneE164: String
    ): Result<FirebaseSessionUser> = runCatching {
        check(isFirebaseReady()) { "Firebase is not initialized in this build." }
        val credential = PhoneAuthProvider.getCredential(verificationId, smsCode)
        val authResult = auth.signInWithCredential(credential).await()
        val user = authResult.user ?: throw IllegalStateException("Firebase Phone authentication returned null user.")
        val token = user.getIdToken(true).await()?.token
            ?: throw IllegalStateException("Unable to retrieve Firebase ID token.")
        FirebaseSessionUser(
            uid = user.uid,
            phoneNumber = user.phoneNumber ?: expectedPhoneE164,
            idToken = token,
            email = user.email,
            displayName = user.displayName
        )
    }.recoverCatching { throwable ->
        throw IllegalStateException(formatAuthError(throwable), throwable)
    }

    suspend fun registerWithEmailPassword(
        email: String,
        password: String,
        displayName: String? = null
    ): EmailAuthResult {
        if (!isFirebaseReady()) {
            return EmailAuthResult.Error("Firebase is not initialized in this build. Verify google-services.json configuration.")
        }
        val cleanEmail = email.trim()
        if (!AuthValidation.isValidEmail(cleanEmail)) {
            return EmailAuthResult.Error("Enter a valid email address.")
        }
        if (password.length < 6) {
            return EmailAuthResult.Error("Password must be at least 6 characters.")
        }
        return try {
            val authResult = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
            val user = authResult.user
                ?: return EmailAuthResult.Error("Firebase returned null user after registration.")
            val trimmedName = displayName?.trim().orEmpty()
            if (trimmedName.isNotBlank()) {
                runCatching {
                    val profileReq = UserProfileChangeRequest.Builder()
                        .setDisplayName(trimmedName)
                        .build()
                    user.updateProfile(profileReq).await()
                }
            }
            val verificationSent = runCatching {
                user.sendEmailVerification().await()
                true
            }.getOrDefault(false)
            val token = user.getIdToken(true).await()?.token
                ?: return EmailAuthResult.Error("Unable to retrieve Firebase ID token after registration.")
            EmailAuthResult.Success(
                session = FirebaseSessionUser(
                    uid = user.uid,
                    phoneNumber = user.phoneNumber.orEmpty(),
                    idToken = token,
                    email = user.email ?: cleanEmail,
                    displayName = user.displayName ?: trimmedName.ifBlank { null },
                    isEmailVerified = user.isEmailVerified
                ),
                verificationEmailSent = verificationSent,
                isEmailVerified = user.isEmailVerified
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(AUTH_TAG, "Email registration failed: ${e.message}")
            EmailAuthResult.Error(formatAuthError(e, isPhoneFlow = false))
        }
    }

    suspend fun signInWithEmailPassword(
        email: String,
        password: String
    ): EmailAuthResult {
        if (!isFirebaseReady()) {
            return EmailAuthResult.Error("Firebase is not initialized in this build. Verify google-services.json configuration.")
        }
        val cleanEmail = email.trim()
        if (!AuthValidation.isValidEmail(cleanEmail)) {
            return EmailAuthResult.Error("Enter a valid email address.")
        }
        if (password.isEmpty()) {
            return EmailAuthResult.Error("Enter your password.")
        }
        return try {
            val authResult = auth.signInWithEmailAndPassword(cleanEmail, password).await()
            val user = authResult.user
                ?: return EmailAuthResult.Error("Firebase returned null user after email sign-in.")
            val token = user.getIdToken(true).await()?.token
                ?: return EmailAuthResult.Error("Unable to retrieve Firebase ID token after email sign-in.")
            EmailAuthResult.Success(
                session = FirebaseSessionUser(
                    uid = user.uid,
                    phoneNumber = user.phoneNumber.orEmpty(),
                    idToken = token,
                    email = user.email ?: cleanEmail,
                    displayName = user.displayName,
                    isEmailVerified = user.isEmailVerified
                ),
                verificationEmailSent = false,
                isEmailVerified = user.isEmailVerified
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(AUTH_TAG, "Email sign-in failed: ${e.message}")
            EmailAuthResult.Error(formatAuthError(e, isPhoneFlow = false))
        }
    }

    suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        if (!isFirebaseReady()) {
            return Result.failure(IllegalStateException("Firebase is not initialized in this build."))
        }
        val cleanEmail = email.trim()
        if (!AuthValidation.isValidEmail(cleanEmail)) {
            return Result.failure(IllegalArgumentException("Enter a valid email address to reset your password."))
        }
        return try {
            auth.sendPasswordResetEmail(cleanEmail).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(AUTH_TAG, "Password reset failed: ${e.message}")
            Result.failure(IllegalStateException(formatAuthError(e, isPhoneFlow = false), e))
        }
    }

    suspend fun sendVerificationEmail(): Result<Unit> {
        val user = currentUser()
            ?: return Result.failure(IllegalStateException("No signed-in Firebase account found."))
        return try {
            user.sendEmailVerification().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(AUTH_TAG, "Failed to send email verification: ${e.message}")
            Result.failure(IllegalStateException(formatAuthError(e, isPhoneFlow = false), e))
        }
    }

    suspend fun reloadEmailVerificationStatus(): Result<Boolean> {
        val user = currentUser()
            ?: return Result.failure(IllegalStateException("No signed-in Firebase account found."))
        return try {
            user.reload().await()
            Result.success(auth.currentUser?.isEmailVerified ?: user.isEmailVerified)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(AUTH_TAG, "Failed to reload Firebase user verification: ${e.message}")
            Result.failure(IllegalStateException(formatAuthError(e, isPhoneFlow = false), e))
        }
    }

    fun currentUserEmailVerified(): Boolean? {
        val user = currentUser() ?: return null
        if (user.email.isNullOrBlank()) return null
        return user.isEmailVerified
    }

    fun formatAuthError(error: Throwable, isPhoneFlow: Boolean = true): String {
        val code = (error as? FirebaseAuthException)?.errorCode
        val msg = error.localizedMessage.orEmpty()
        return when {
            code == "ERROR_INVALID_PHONE_NUMBER" -> "Invalid mobile number format. Include country code (e.g. +91)."
            code == "ERROR_INVALID_VERIFICATION_CODE" -> "Incorrect 6-digit SMS OTP code."
            code == "ERROR_SESSION_EXPIRED" -> "OTP expired. Tap Resend OTP for a fresh SMS code."
            code == "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists. Sign in or reset your password."
            code == "ERROR_INVALID_EMAIL" -> "Invalid email address format."
            code == "ERROR_WEAK_PASSWORD" -> "Password is too weak. Use at least 6 characters."
            code == "ERROR_WRONG_PASSWORD" || code == "ERROR_INVALID_CREDENTIAL" ->
                "Incorrect email or password."
            code == "ERROR_USER_NOT_FOUND" -> "No Firebase account exists for this email address."
            code == "ERROR_USER_DISABLED" -> "This Firebase account has been disabled."
            code == "ERROR_TOO_MANY_REQUESTS" || code == "ERROR_QUOTA_EXCEEDED" ->
                "Too many authentication attempts. Wait briefly before retrying."
            code == "ERROR_OPERATION_NOT_ALLOWED" ||
                msg.contains("17006") ||
                msg.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) ->
                if (isPhoneFlow || msg.contains("17006")) {
                    "Phone OTP is disabled on this Firebase project (Error 17006). Enable Phone Auth in Firebase Console or use Google / Email Login."
                } else {
                    "Email/Password sign-in is disabled on this Firebase project. Enable Email/Password in Firebase Console."
                }
            code == "ERROR_APP_NOT_AUTHORIZED" ||
                code == "ERROR_MISSING_CLIENT_IDENTIFIER" ||
                code == "ERROR_CAPTCHA_CHECK_FAILED" ||
                msg.contains("web operation was canceled", ignoreCase = true) ||
                msg.contains("Recaptcha", ignoreCase = true) ||
                msg.contains("siteKey", ignoreCase = true) ||
                msg.contains("Play Integrity", ignoreCase = true) ->
                "Phone verification requires Play Integrity or reCAPTCHA verification. Use Google or Email Login instead."
            else -> msg.ifBlank { "Firebase authentication error." }
        }
    }

    private tailrec fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}
