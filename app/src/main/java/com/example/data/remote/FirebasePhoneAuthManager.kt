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
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

private const val AUTH_TAG = "DskFirebaseAuth"

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

data class FirebaseSessionUser(
    val uid: String,
    val phoneNumber: String,
    val idToken: String,
    val email: String? = null,
    val displayName: String? = null
)

class FirebasePhoneAuthManager {
    private val auth: FirebaseAuth
        get() = Firebase.auth

    fun currentUser(): com.google.firebase.auth.FirebaseUser? = runCatching { auth.currentUser }.getOrNull()

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

    private fun formatAuthError(error: Throwable): String {
        val code = (error as? FirebaseAuthException)?.errorCode
        val msg = error.localizedMessage.orEmpty()
        return when {
            code == "ERROR_INVALID_PHONE_NUMBER" -> "Invalid +91 mobile number format."
            code == "ERROR_INVALID_VERIFICATION_CODE" -> "Incorrect 6-digit SMS OTP code."
            code == "ERROR_SESSION_EXPIRED" -> "OTP expired. Tap Resend OTP for a fresh SMS code."
            code == "ERROR_TOO_MANY_REQUESTS" || code == "ERROR_QUOTA_EXCEEDED" ->
                "SMS OTP rate limit reached. Wait briefly before retrying."
            code == "ERROR_OPERATION_NOT_ALLOWED" ||
                msg.contains("17006") ||
                msg.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) ->
                "Phone OTP is disabled on this Firebase project (Error 17006). Use Sign in with Google instead."
            code == "ERROR_APP_NOT_AUTHORIZED" ||
                code == "ERROR_MISSING_CLIENT_IDENTIFIER" ||
                code == "ERROR_CAPTCHA_CHECK_FAILED" ||
                msg.contains("web operation was canceled", ignoreCase = true) ||
                msg.contains("Recaptcha", ignoreCase = true) ||
                msg.contains("siteKey", ignoreCase = true) ||
                msg.contains("Play Integrity", ignoreCase = true) ->
                "Phone verification requires Play Integrity or reCAPTCHA Enterprise. Use Sign in with Google instead."
            else -> msg.ifBlank { "Firebase Phone authentication error." }
        }
    }

    private tailrec fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}
