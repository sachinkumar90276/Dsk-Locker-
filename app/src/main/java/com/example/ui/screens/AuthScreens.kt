package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.security.BiometricAvailability
import com.example.security.BiometricHelper
import com.example.ui.DskLockerViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun CreateMpinScreen(
    viewModel: DskLockerViewModel,
    isForgotMpin: Boolean = false
) {
    val error by viewModel.authError.collectAsState()
    var selectedLength by remember(isForgotMpin) { mutableIntStateOf(6) }
    var firstPin by remember(isForgotMpin) { mutableStateOf("") }
    var confirmPin by remember(isForgotMpin) { mutableStateOf("") }
    var isConfirmStep by remember(isForgotMpin) { mutableStateOf(false) }

    val pinsMatch = firstPin.length == selectedLength && confirmPin.length == selectedLength && firstPin == confirmPin
    val pinsMismatch = firstPin.length == selectedLength && confirmPin.length == selectedLength && firstPin != confirmPin

    FourDAnimatedBackground {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                DskLockerLogo(size = 52.dp, showTitles = false, animateRotation = true)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    MetallicBrandText(
                        text = "DSK LOCKER",
                        style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 1.6.sp)
                    )
                    Text(
                        text = if (isForgotMpin) "RESET LOGIN MPIN" else "SET LOGIN MPIN",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = ElectricBlue,
                            letterSpacing = 1.2.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (isForgotMpin) "Reset Your ${selectedLength}-Digit MPIN" else "Set Your ${selectedLength}-Digit MPIN",
                style = MaterialTheme.typography.headlineMedium,
                color = SilverWhite,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose 4-Digit or 6-Digit MPIN to unlock your private vault.",
                style = MaterialTheme.typography.bodySmall,
                color = MetallicSilver,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(4, 6).forEach { len ->
                    FilterChip(
                        selected = selectedLength == len,
                        onClick = {
                            if (selectedLength != len) {
                                selectedLength = len
                                firstPin = ""
                                confirmPin = ""
                                isConfirmStep = false
                                viewModel.clearAuthError()
                            }
                        },
                        leadingIcon = if (selectedLength == len) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        label = { Text("$len Digits", fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.testTag("mpin_length_${len}_chip")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            GlassCard4D(
                glowColor = if (pinsMatch) EmeraldSync else ElectricBlue,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
            ) {
                Surface(
                    onClick = {
                        isConfirmStep = false
                        viewModel.clearAuthError()
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (!isConfirmStep) ElectricBlue.copy(alpha = 0.14f) else Color.Transparent,
                    border = if (!isConfirmStep) BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.5f)) else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp)
                    ) {
                        Text(
                            text = if (!isConfirmStep) "STEP 1: ENTER ${selectedLength}-DIGIT MPIN" else "1. NEW ${selectedLength}-DIGIT MPIN",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (!isConfirmStep) ElectricBlue else MetallicSilver
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        MpinDots(length = firstPin.length, maxDigits = selectedLength, isError = false)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    onClick = {
                        if (firstPin.length == selectedLength) {
                            isConfirmStep = true
                            viewModel.clearAuthError()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isConfirmStep) NeonViolet.copy(alpha = 0.16f) else Color.Transparent,
                    border = if (isConfirmStep) BorderStroke(1.dp, NeonViolet.copy(alpha = 0.55f)) else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp)
                    ) {
                        Text(
                            text = if (isConfirmStep) "STEP 2: CONFIRM ${selectedLength}-DIGIT MPIN" else "2. CONFIRM MPIN",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isConfirmStep) PlasmaMagenta else MetallicSilver
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        MpinDots(
                            length = confirmPin.length,
                            maxDigits = selectedLength,
                            isError = error != null || pinsMismatch
                        )
                    }
                }
            }

            if (error != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(error!!, color = CrimsonAlert, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            } else if (pinsMismatch) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("MPIN entries do not match. Tap Backspace or Start Over.", color = CrimsonAlert, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            } else if (pinsMatch) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("MPIN matched! Tap \"Set ${selectedLength}-Digit MPIN\" below to save.", color = EmeraldSync, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(12.dp))

            MpinKeypad(
                buttonSize = 58.dp,
                verticalSpacing = 10.dp,
                onDigit = { digit ->
                    viewModel.clearAuthError()
                    if (!isConfirmStep) {
                        if (firstPin.length < selectedLength) {
                            firstPin += digit
                            if (firstPin.length == selectedLength) {
                                isConfirmStep = true
                            }
                        }
                    } else {
                        if (confirmPin.length < selectedLength) {
                            confirmPin += digit
                        }
                    }
                },
                onBackspace = {
                    viewModel.clearAuthError()
                    if (isConfirmStep) {
                        if (confirmPin.isNotEmpty()) confirmPin = confirmPin.dropLast(1)
                        else isConfirmStep = false
                    } else if (firstPin.isNotEmpty()) {
                        firstPin = firstPin.dropLast(1)
                    }
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    if (firstPin.length < selectedLength) {
                        viewModel.createMpin(firstPin, confirmPin)
                    } else if (confirmPin.length < selectedLength) {
                        isConfirmStep = true
                        viewModel.createMpin(firstPin, confirmPin)
                    } else {
                        viewModel.createMpin(firstPin, confirmPin)
                    }
                },
                enabled = firstPin.length == selectedLength && confirmPin.length == selectedLength,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (pinsMatch) EmeraldSync else ElectricBlue,
                    contentColor = VoidBlack
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("set_mpin_submit_button")
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isForgotMpin) "SET NEW ${selectedLength}-DIGIT MPIN" else "SET ${selectedLength}-DIGIT MPIN",
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (firstPin.isNotEmpty() || confirmPin.isNotEmpty()) {
                    TextButton(onClick = {
                        firstPin = ""
                        confirmPin = ""
                        isConfirmStep = false
                        viewModel.clearAuthError()
                    }) {
                        Text("Start Over", color = ElectricBlue)
                    }
                }
                if (isForgotMpin) {
                    TextButton(
                        onClick = { viewModel.cancelForgotMpin() },
                        modifier = Modifier.testTag("cancel_forgot_mpin_button")
                    ) {
                        Text("Back to MPIN Login", color = MetallicSilver)
                    }
                }
            }
        }
    }
}

@Composable
fun BiometricSetupScreen(viewModel: DskLockerViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val availability = remember { viewModel.biometricAvailability() }
    var feedback by remember { mutableStateOf<String?>(null) }

    FourDAnimatedBackground {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp)
        ) {
            DskLockerLogo(size = 80.dp)
            Spacer(modifier = Modifier.height(24.dp))
            GlassCard4D(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
                contentPadding = PaddingValues(22.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.Filled.Fingerprint,
                        contentDescription = "Biometric Enrollment",
                        tint = ElectricBlue,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Enable Biometric Unlock",
                        style = MaterialTheme.typography.headlineMedium,
                        color = SilverWhite,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Bind your Android Fingerprint or Face Authentication to DSK LOCKER with MPIN fallback.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MetallicSilver,
                        textAlign = TextAlign.Center
                    )
                    if (availability is BiometricAvailability.Unavailable) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = availability.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AmberPending,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (feedback != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(feedback!!, color = CrimsonAlert, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (activity == null) {
                                feedback = "FragmentActivity unavailable for BiometricPrompt."
                            } else {
                                BiometricHelper.authenticate(
                                    activity = activity,
                                    title = "Enroll DSK LOCKER Biometric",
                                    subtitle = "Verify fingerprint or face to bind this device",
                                    onSuccess = { viewModel.completeBiometricSetup(true) },
                                    onFailure = { err -> feedback = err }
                                )
                            }
                        },
                        enabled = availability is BiometricAvailability.Available,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("enroll_biometric_button")
                    ) {
                        Text("Verify & Enroll Biometric", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(
                        onClick = { viewModel.completeBiometricSetup(false) },
                        modifier = Modifier.testTag("skip_biometric_button")
                    ) {
                        Text("Skip for Now (Use MPIN)", color = MetallicSilver)
                    }
                }
            }
        }
    }
}

@Composable
fun MpinLoginScreen(viewModel: DskLockerViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val error by viewModel.authError.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()
    val biometricEnabled = remember { viewModel.isBiometricEnabled() }
    val bioAvail = remember { viewModel.biometricAvailability() }
    val pinLen = remember { viewModel.loginMpinLength() }

    var pin by remember { mutableStateOf("") }
    var bioMessage by remember { mutableStateOf<String?>(null) }
    var showForgotConfirm by remember { mutableStateOf(false) }

    val triggerBiometric = {
        if (activity != null && bioAvail is BiometricAvailability.Available) {
            BiometricHelper.authenticate(
                activity = activity,
                title = "DSK LOCKER Biometric Unlock",
                subtitle = "Authenticate to unlock your private universe",
                onSuccess = { viewModel.onBiometricLoginSuccess() },
                onFailure = { msg -> bioMessage = msg }
            )
        } else if (bioAvail is BiometricAvailability.Unavailable) {
            bioMessage = bioAvail.reason
        }
    }

    FourDAnimatedBackground {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            DskLockerLogo(size = 64.dp, animateRotation = true)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Welcome Back!",
                style = MaterialTheme.typography.headlineMedium,
                color = SilverWhite
            )
            Text(
                text = "Enter Your ${pinLen}-Digit MPIN",
                style = MaterialTheme.typography.bodyLarge,
                color = MetallicSilver
            )
            Spacer(modifier = Modifier.height(14.dp))

            MpinDots(length = pin.length, maxDigits = pinLen, isError = error != null || lockoutSeconds > 0)

            if (lockoutSeconds > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Security Lockout: Retry in ${lockoutSeconds}s",
                    style = MaterialTheme.typography.labelLarge,
                    color = CrimsonAlert
                )
            } else if (error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.labelMedium,
                    color = CrimsonAlert,
                    textAlign = TextAlign.Center
                )
            } else if (bioMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = bioMessage!!,
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberPending,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            MpinKeypad(
                enabled = lockoutSeconds <= 0,
                buttonSize = 62.dp,
                verticalSpacing = 10.dp,
                showBiometricButton = biometricEnabled && bioAvail is BiometricAvailability.Available,
                onBiometricClick = triggerBiometric,
                onDigit = { d ->
                    viewModel.clearAuthError()
                    bioMessage = null
                    if (pin.length < pinLen) {
                        val next = pin + d
                        pin = next
                        if (next.length == pinLen) {
                            viewModel.verifyMpinLogin(next)
                            pin = ""
                        }
                    }
                },
                onBackspace = {
                    if (pin.isNotEmpty()) {
                        pin = pin.dropLast(1)
                        viewModel.clearAuthError()
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.verifyMpinLogin(pin)
                    pin = ""
                },
                enabled = lockoutSeconds <= 0 && pin.length == pinLen,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("mpin_unlock_button")
            ) {
                Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("UNLOCK", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { showForgotConfirm = true },
                border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("set_reset_mpin_button")
            ) {
                Icon(Icons.Filled.Password, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Set / Reset Login MPIN", color = ElectricBlue, fontWeight = FontWeight.SemiBold)
            }

            if (biometricEnabled && bioAvail is BiometricAvailability.Available) {
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    onClick = triggerBiometric,
                    modifier = Modifier.testTag("biometric_unlock_button")
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = NeonViolet, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Use Biometric Unlock", color = PlasmaMagenta)
                }
            }
        }
    }

    if (showForgotConfirm) {
        AlertDialog(
            onDismissRequest = { showForgotConfirm = false },
            title = { Text("Reset Login MPIN?") },
            text = { Text("Create a new 4-digit or 6-digit Login MPIN for DSK LOCKER. All your existing files, vault items, and notes remain preserved.") },
            confirmButton = {
                Button(
                    onClick = {
                        showForgotConfirm = false
                        viewModel.startForgotMpin()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack)
                ) {
                    Text("Set New MPIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgotConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun PostAuthSplashScreen(
    viewModel: DskLockerViewModel,
    onSplashFinished: () -> Unit
) {
    val motion = LocalDskMotion.current
    var progress by remember { mutableFloatStateOf(0.15f) }
    var stepLabel by remember { mutableStateOf("MPIN verified...") }

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "splashProgress"
    )

    LaunchedEffect(Unit) {
        viewModel.preloadWorkspaceForUnlock { step, frac ->
            stepLabel = step
            progress = frac
        }
        delay(if (motion.isAnimated) 520L else 140L)
        onSplashFinished()
    }

    FourDAnimatedBackground(
        showWatermark = true,
        modifier = Modifier
            .fillMaxSize()
            .testTag("post_auth_splash")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp)
        ) {
            DskLockerLogo(
                size = 132.dp,
                showTitles = true,
                animateRotation = true,
                subtitleOverride = "YOUR PRIVATE DIGITAL VAULT"
            )
            Spacer(modifier = Modifier.height(28.dp))
            GlassCard4D(
                glowColor = if (animatedProgress >= 0.99f) EmeraldSync else ElectricBlue,
                contentPadding = PaddingValues(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (animatedProgress >= 0.99f) "PRIVATE VAULT READY" else "UNLOCKING YOUR PRIVATE VAULT",
                        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp),
                        color = if (animatedProgress >= 0.99f) EmeraldSync else ElectricBlue,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { animatedProgress.coerceIn(0.06f, 1f) },
                        color = if (animatedProgress >= 0.99f) EmeraldSync else ElectricBlue,
                        trackColor = RoyalIndigo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "$stepLabel • ${(animatedProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MetallicSilver,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

