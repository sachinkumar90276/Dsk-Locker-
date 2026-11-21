package com.example.data.remote

import android.app.Activity
import com.google.firebase.Firebase
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

sealed interface PhoneOtpStartResult {
    data class CodeSent(val verificationId: String, val resendToken: PhoneAuthProvider.ForceResendingToken) : PhoneOtpStartResult
    data class AutoVerified(val uid: String, val phoneNumber: String, val idToken: String) : PhoneOtpStartResult
    data class Error(val message: String) : PhoneOtpStartResult
}

data class FirebaseSessionUser(
    val uid: String,
    val phoneNumber: String,
    val idToken: String
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
            idToken = token
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
                "Firebase Phone Sign-In is disabled on project gen-lang-client-0114461808 (Error 17006). Enable Authentication → Sign-in method → Phone in Firebase Console (and add a Test Phone Number for emulator testing)."
            code == "ERROR_APP_NOT_AUTHORIZED" ||
                code == "ERROR_MISSING_CLIENT_IDENTIFIER" ||
                code == "ERROR_CAPTCHA_CHECK_FAILED" ||
                msg.contains("web operation was canceled", ignoreCase = true) ||
                msg.contains("Recaptcha", ignoreCase = true) ||
                msg.contains("siteKey", ignoreCase = true) ||
                msg.contains("Play Integrity", ignoreCase = true) ->
                "App verification (Play Integrity / reCAPTCHA Enterprise) failed. In Firebase Console → Authentication → Phone, add a Test Phone Number for emulator testing or configure reCAPTCHA Enterprise for Android."
            else -> msg.ifBlank { "Firebase Phone authentication error." }
        }
    }
}
