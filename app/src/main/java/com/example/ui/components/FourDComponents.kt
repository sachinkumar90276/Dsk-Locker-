package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.local.BackupStatus
import com.example.data.local.DeleteMode
import com.example.storage.LocalFileManager
import com.example.ui.FlashScreenState
import com.example.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun FourDAnimatedBackground(
    modifier: Modifier = Modifier,
    showWatermark: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val motion = LocalDskMotion.current
    val accent = LocalDskAccent.current
    val infiniteTransition = rememberInfiniteTransition(label = "bg4d")
    val phase by if (motion.isAnimated) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 20000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "nebulaPhase"
        )
    } else {
        remember { mutableFloatStateOf(0.8f) }
    }
    val watermarkPulse by if (motion.isAnimated) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 5200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "watermarkPulse"
        )
    } else {
        remember { mutableFloatStateOf(0.5f) }
    }

    val isDark = MaterialTheme.colorScheme.background == VoidBlack
    val deepNavy = Color(0xFF07122B)
    val deepBlack = Color(0xFF03050C)
    val cyanMist = Color(0xFF00E5FF)

    val particles = remember {
        listOf(
            Triple(0.12f, 0.16f, 2.6f),
            Triple(0.28f, 0.34f, 1.8f),
            Triple(0.45f, 0.12f, 3.0f),
            Triple(0.68f, 0.24f, 2.2f),
            Triple(0.86f, 0.15f, 2.5f),
            Triple(0.18f, 0.56f, 2.0f),
            Triple(0.36f, 0.72f, 3.2f),
            Triple(0.62f, 0.52f, 1.9f),
            Triple(0.82f, 0.64f, 2.8f),
            Triple(0.24f, 0.88f, 2.3f),
            Triple(0.54f, 0.84f, 2.7f),
            Triple(0.76f, 0.90f, 1.8f),
            Triple(0.08f, 0.78f, 2.4f),
            Triple(0.91f, 0.42f, 2.1f)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isDark) deepBlack else MaterialTheme.colorScheme.background)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx1 = w * (0.24f + 0.12f * cos(phase))
            val cy1 = h * (0.20f + 0.08f * sin(phase))
            val cx2 = w * (0.76f + 0.11f * sin(phase * 0.75f))
            val cy2 = h * (0.68f + 0.09f * cos(phase * 0.75f))

            if (isDark) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(deepBlack, deepNavy, Color(0xFF040814))
                    )
                )
            }

            // Soft cyan and blue radial vault atmosphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        cyanMist.copy(alpha = if (isDark) 0.14f + 0.05f * watermarkPulse else 0.08f),
                        accent.secondary.copy(alpha = if (isDark) 0.10f else 0.06f),
                        Color.Transparent
                    ),
                    center = Offset(cx1, cy1),
                    radius = w * 0.82f
                ),
                center = Offset(cx1, cy1),
                radius = w * 0.82f
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.primary.copy(alpha = if (isDark) 0.16f else 0.09f),
                        Color.Transparent
                    ),
                    center = Offset(cx2, cy2),
                    radius = w * 0.74f
                ),
                center = Offset(cx2, cy2),
                radius = w * 0.74f
            )

            // Smooth moving light particles
            particles.forEachIndexed { idx, (bx, by, rDp) ->
                val speedMul = if (idx % 2 == 0) 1f else -0.8f
                val px = ((bx + 0.045f * sin(phase * speedMul + idx)) * w).coerceIn(0f, w)
                val py = ((by + 0.04f * cos(phase * 0.85f + idx * 1.3f)) * h).coerceIn(0f, h)
                val particleAlpha = if (isDark) {
                    0.18f + 0.22f * ((sin(phase * 1.4f + idx) + 1f) * 0.5f)
                } else {
                    0.10f
                }
                val particleColor = if (idx % 3 == 0) cyanMist else accent.primary
                drawCircle(
                    color = particleColor.copy(alpha = particleAlpha),
                    radius = rDp.dp.toPx(),
                    center = Offset(px, py)
                )
                drawCircle(
                    color = particleColor.copy(alpha = particleAlpha * 0.28f),
                    radius = (rDp * 3.2f).dp.toPx(),
                    center = Offset(px, py)
                )
            }

            // Subtle holographic perspective grid
            val step = w / 8f
            val gridColor = accent.primary.copy(alpha = if (isDark) 0.032f else 0.04f)
            var x = 0f
            while (x <= w) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                x += step
            }
            var y = 0f
            while (y <= h) {
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                y += step
            }
        }

        // Subtle Animated DSK Locker Watermark Logo in the Background
        if (showWatermark) {
            val wmScale = if (motion.isAnimated) 0.98f + 0.045f * watermarkPulse else 1f
            val wmTilt = if (motion.isAnimated) 4.5f * sin(phase) else 0f
            val wmAlpha = if (isDark) 0.055f + 0.035f * watermarkPulse else 0.045f
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(260.dp)
                        .graphicsLayer {
                            alpha = wmAlpha
                            scaleX = wmScale
                            scaleY = wmScale
                            rotationY = wmTilt
                            cameraDistance = 16f * density
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(
                                    cyanMist.copy(alpha = 0.55f),
                                    accent.primary.copy(alpha = 0.25f),
                                    Color.Transparent
                                )
                            )
                        )
                    }
                    Image(
                        painter = painterResource(id = R.drawable.img_dsk_shield_logo),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(188.dp)
                            .clip(RoundedCornerShape(52.dp))
                    )
                }
                // Soft dark readability veil so text, categories & file cards always pop clearly
                if (isDark) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(deepBlack.copy(alpha = 0.26f))
                    )
                }
            }
        }

        content()
    }
}

@Composable
fun GlassCard4D(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    glowColor: Color = LocalDskAccent.current.primary,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val isDark = MaterialTheme.colorScheme.background == VoidBlack
    val fillBrush = if (isDark) {
        Brush.linearGradient(
            colors = listOf(
                RoyalIndigo.copy(alpha = 0.62f),
                MidnightPurple.copy(alpha = 0.78f),
                DeepObsidian.copy(alpha = 0.88f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.92f),
                Color(0xFFF0F3FF).copy(alpha = 0.85f)
            )
        )
    }
    val borderBrush = Brush.linearGradient(
        colors = listOf(
            glowColor.copy(alpha = 0.55f),
            NeonViolet.copy(alpha = 0.32f),
            SilverWhite.copy(alpha = 0.14f)
        )
    )

    val cardModifier = modifier
        .shadow(
            elevation = 14.dp,
            shape = shape,
            ambientColor = glowColor.copy(alpha = 0.25f),
            spotColor = NeonViolet.copy(alpha = 0.35f)
        )
        .clip(shape)
        .background(fillBrush)
        .border(width = 1.dp, brush = borderBrush, shape = shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)

    Box(modifier = cardModifier) {
        // Specular top sheen
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, SilverWhite.copy(alpha = 0.35f), Color.Transparent)
                    )
                )
        )
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun MetallicBrandText(
    text: String,
    style: TextStyle = MaterialTheme.typography.headlineLarge,
    modifier: Modifier = Modifier
) {
    val brush = Brush.linearGradient(
        colors = listOf(
            SilverWhite,
            MetallicSilver,
            ElectricBlue.copy(alpha = 0.9f),
            SilverWhite
        )
    )
    Text(
        text = text,
        style = style.copy(brush = brush),
        modifier = modifier
    )
}

@Composable
fun DskLockerLogo(
    size: Dp = 84.dp,
    showTitles: Boolean = true,
    animateRotation: Boolean = true,
    subtitleOverride: String? = null,
    modifier: Modifier = Modifier
) {
    val motion = LocalDskMotion.current
    val cyanGlow = Color(0xFF00E5FF)
    var revealed by remember { mutableStateOf(!motion.isAnimated) }
    LaunchedEffect(Unit) { revealed = true }
    val entranceAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = if (motion.isAnimated) 650 else 0, easing = FastOutSlowInEasing),
        label = "logoFadeIn"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "logoAnim")
    val spinY by if (animateRotation && motion.isAnimated) {
        infiniteTransition.animateFloat(
            initialValue = -6.5f,
            targetValue = 6.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(3600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "logoTiltY"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val tiltX by if (animateRotation && motion.isAnimated) {
        infiniteTransition.animateFloat(
            initialValue = -2.2f,
            targetValue = 2.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(4200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "logoTiltX"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val glowPulse by if (motion.isAnimated) {
        infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.88f,
            animationSpec = infiniteRepeatable(
                animation = tween(2800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowPulse"
        )
    } else {
        remember { mutableFloatStateOf(0.65f) }
    }
    val ringSweep by if (motion.isAnimated) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(7500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ringSweep"
        )
    } else {
        remember { mutableFloatStateOf(40f) }
    }
    val shimmerSweep by if (motion.isAnimated) {
        infiniteTransition.animateFloat(
            initialValue = -0.6f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(3600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "cyanShimmer"
        )
    } else {
        remember { mutableFloatStateOf(-1f) }
    }

    val shape = RoundedCornerShape(size * 0.28f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.graphicsLayer { alpha = entranceAlpha }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size + 18.dp)
                .graphicsLayer {
                    rotationY = spinY
                    rotationX = tiltX
                    cameraDistance = 14f * density
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Soft breathing aura
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            cyanGlow.copy(alpha = glowPulse * 0.36f),
                            ElectricBlue.copy(alpha = glowPulse * 0.28f),
                            NeonViolet.copy(alpha = glowPulse * 0.14f),
                            Color.Transparent
                        )
                    )
                )
                // Smooth cinematic orbital ring around shield
                val ringPad = 3.dp.toPx()
                val ringStroke = (size.value * 0.032f).coerceIn(1.2f, 3f).dp.toPx()
                rotate(degrees = ringSweep) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color.Transparent,
                                cyanGlow.copy(alpha = 0.72f),
                                ElectricBlue.copy(alpha = 0.45f),
                                Color.Transparent
                            )
                        ),
                        topLeft = Offset(ringPad, ringPad),
                        size = Size(this.size.width - ringPad * 2f, this.size.height - ringPad * 2f),
                        startAngle = 0f,
                        sweepAngle = 210f,
                        useCenter = false,
                        style = Stroke(width = ringStroke, cap = StrokeCap.Round)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(shape)
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                cyanGlow.copy(alpha = 0.85f),
                                ElectricBlue,
                                SilverWhite.copy(alpha = 0.7f)
                            )
                        ),
                        shape = shape
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_dsk_shield_logo),
                    contentDescription = "DSK LOCKER Animated Shield Logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Soft cyan light reflection sweep passing across the logo
                if (motion.isAnimated) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = this.size.width
                        val h = this.size.height
                        val startX = shimmerSweep * w - w * 0.35f
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    cyanGlow.copy(alpha = 0.22f),
                                    SilverWhite.copy(alpha = 0.16f),
                                    Color.Transparent
                                ),
                                start = Offset(startX, 0f),
                                end = Offset(startX + w * 0.55f, h)
                            )
                        )
                    }
                }
            }
        }
        if (showTitles) {
            Spacer(modifier = Modifier.height(10.dp))
            MetallicBrandText(
                text = "DSK LOCKER",
                style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = 2.sp)
            )
            Text(
                text = subtitleOverride ?: "YOUR PRIVATE DIGITAL VAULT",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = ElectricBlue,
                    letterSpacing = 1.6.sp
                )
            )
        }
    }
}

@Composable
fun DskEmptyStateCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    glowColor: Color = ElectricBlue,
    modifier: Modifier = Modifier
) {
    GlassCard4D(
        glowColor = glowColor,
        contentPadding = PaddingValues(24.dp),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                DskLockerLogo(size = 56.dp, showTitles = false, animateRotation = true)
                Surface(
                    shape = CircleShape,
                    color = MidnightPurple,
                    border = BorderStroke(1.dp, glowColor),
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = glowColor, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = SilverWhite, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MetallicSilver, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun InteractiveStorageSphere(
    mobileUsedBytes: Long,
    cloudUsedBytes: Long,
    deviceTotalBytes: Long,
    onTapSphere: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = LocalDskMotion.current
    val accent = LocalDskAccent.current
    val transition = rememberInfiniteTransition(label = "sphere")
    val orbitAngle by if (motion.isAnimated) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(10000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "orbit"
        )
    } else {
        remember { mutableFloatStateOf(35f) }
    }
    val wavePhase by if (motion.isAnimated) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(3600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val totalLocker = (mobileUsedBytes + cloudUsedBytes).coerceAtLeast(1L)
    val fillFraction = ((mobileUsedBytes.toFloat() / deviceTotalBytes.coerceAtLeast(1L).toFloat()) * 15f)
        .coerceIn(0.18f, 0.86f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(164.dp)
            .clip(CircleShape)
            .clickable(onClick = onTapSphere)
            .testTag("interactive_storage_sphere")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension * 0.36f
            val c = Offset(size.width / 2f, size.height / 2f)

            // Outer ambient glow
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        accent.primary.copy(alpha = 0.25f),
                        NeonViolet.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = c,
                    radius = size.minDimension * 0.5f
                ),
                center = c,
                radius = size.minDimension * 0.5f
            )

            // 3D tilted orbital rings
            rotate(degrees = orbitAngle, pivot = c) {
                drawOval(
                    brush = Brush.sweepGradient(
                        listOf(ElectricBlue, NeonViolet, Color.Transparent, ElectricBlue)
                    ),
                    topLeft = Offset(c.x - r * 1.28f, c.y - r * 0.52f),
                    size = Size(r * 2.56f, r * 1.04f),
                    style = Stroke(width = 2.5f)
                )
            }
            rotate(degrees = -orbitAngle * 0.7f + 60f, pivot = c) {
                drawOval(
                    brush = Brush.sweepGradient(
                        listOf(NeonViolet, SilverWhite, Color.Transparent, NeonViolet)
                    ),
                    topLeft = Offset(c.x - r * 1.22f, c.y - r * 0.45f),
                    size = Size(r * 2.44f, r * 0.90f),
                    style = Stroke(width = 2f)
                )
            }

            // Core glass sphere
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(RoyalIndigo.copy(alpha = 0.95f), VoidBlack.copy(alpha = 0.98f)),
                    center = Offset(c.x - r * 0.25f, c.y - r * 0.25f),
                    radius = r * 1.3f
                ),
                center = c,
                radius = r
            )

            // Holographic energy wave inside sphere
            val waveTop = c.y + r * (1f - 2f * fillFraction)
            val path = Path().apply {
                moveTo(c.x - r * 0.88f, c.y + r * 0.88f)
                val steps = 18
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val x = (c.x - r * 0.88f) + t * (r * 1.76f)
                    val y = waveTop + sin(wavePhase + t * 4f) * 5f
                    if (i == 0) lineTo(x, y) else lineTo(x, y)
                }
                lineTo(c.x + r * 0.88f, c.y + r * 0.88f)
                close()
            }
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    listOf(ElectricBlue.copy(alpha = 0.55f), NeonViolet.copy(alpha = 0.75f))
                )
            )

            // Sphere specular rim
            drawCircle(
                brush = Brush.linearGradient(listOf(SilverWhite.copy(alpha = 0.7f), ElectricBlue, NeonViolet)),
                center = c,
                radius = r,
                style = Stroke(width = 2.5f)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = LocalFileManager.formatBytes(totalLocker),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = SilverWhite
                )
            )
            Text(
                text = "LOCKER VAULT",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = ElectricBlue,
                    fontSize = 9.sp
                )
            )
        }
    }
}

@Composable
fun BackupStatusBadge(status: BackupStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        BackupStatus.MOBILE_AND_CLOUD -> EmeraldSync.copy(alpha = 0.18f) to EmeraldSync
        BackupStatus.CLOUD_ONLY -> ElectricBlue.copy(alpha = 0.18f) to ElectricBlue
        BackupStatus.MOBILE_ONLY -> MetallicSilver.copy(alpha = 0.16f) to MetallicSilver
        BackupStatus.BACKUP_PENDING -> AmberPending.copy(alpha = 0.18f) to AmberPending
        BackupStatus.BACKUP_FAILED -> CrimsonAlert.copy(alpha = 0.2f) to CrimsonAlert
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(50),
        border = BorderStroke(0.8.dp, fg.copy(alpha = 0.5f)),
        modifier = modifier
    ) {
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = fg,
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun MpinDots(length: Int, maxDigits: Int = 6, isError: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(maxDigits) { idx ->
            val filled = idx < length
            val borderColor = when {
                isError -> CrimsonAlert
                filled -> ElectricBlue
                else -> NeonViolet.copy(alpha = 0.5f)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (filled) ElectricBlue.copy(alpha = 0.25f) else Color.Transparent)
                    .border(1.5.dp, borderColor, CircleShape)
            ) {
                if (filled) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (isError) CrimsonAlert else SilverWhite)
                    )
                }
            }
        }
    }
}

@Composable
fun MpinKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    showBiometricButton: Boolean = false,
    onBiometricClick: () -> Unit = {},
    enabled: Boolean = true,
    buttonSize: Dp = 68.dp,
    verticalSpacing: Dp = 12.dp
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9")
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { digit ->
                    KeypadButton(
                        label = digit,
                        enabled = enabled,
                        size = buttonSize,
                        testTag = "keypad_digit_$digit",
                        onClick = { onDigit(digit) }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (showBiometricButton) {
                Surface(
                    shape = CircleShape,
                    color = ElectricBlue.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .size(buttonSize)
                        .clip(CircleShape)
                        .clickable(enabled = enabled, onClick = onBiometricClick)
                        .testTag("keypad_biometric_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Fingerprint,
                            contentDescription = "Biometric Unlock",
                            tint = ElectricBlue,
                            modifier = Modifier.size(buttonSize * 0.44f)
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.size(buttonSize))
            }

            KeypadButton(
                label = "0",
                enabled = enabled,
                size = buttonSize,
                testTag = "keypad_digit_0",
                onClick = { onDigit("0") }
            )

            Surface(
                shape = CircleShape,
                color = MidnightPurple.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, NeonViolet.copy(alpha = 0.4f)),
                modifier = Modifier
                    .size(buttonSize)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, onClick = onBackspace)
                    .testTag("keypad_backspace")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        tint = SilverWhite,
                        modifier = Modifier.size(buttonSize * 0.35f)
                    )
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(
    label: String,
    enabled: Boolean,
    size: Dp = 68.dp,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = RoyalIndigo.copy(alpha = 0.55f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(listOf(ElectricBlue.copy(alpha = 0.4f), NeonViolet.copy(alpha = 0.4f)))
        ),
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = if (size < 62.dp) 21.sp else 24.sp,
                    color = SilverWhite
                )
            )
        }
    }
}

@Composable
fun MpinVerificationDialog(
    title: String,
    subtitle: String,
    verifyMpin: (String) -> Boolean,
    lockoutSeconds: Long,
    maxDigits: Int = 6,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
    onForgotMpin: (() -> Unit)? = null
) {
    var pin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        GlassCard4D(
            glowColor = CrimsonAlert,
            contentPadding = PaddingValues(20.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = "MPIN Protection",
                    tint = ElectricBlue,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = SilverWhite,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MetallicSilver,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                MpinDots(length = pin.length, maxDigits = maxDigits, isError = errorMsg != null)
                if (lockoutSeconds > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Locked for ${lockoutSeconds}s due to failed attempts",
                        style = MaterialTheme.typography.labelMedium,
                        color = CrimsonAlert
                    )
                } else if (errorMsg != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMsg!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = CrimsonAlert,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                MpinKeypad(
                    enabled = lockoutSeconds <= 0,
                    buttonSize = 58.dp,
                    verticalSpacing = 10.dp,
                    onDigit = { d ->
                        if (pin.length < maxDigits) {
                            errorMsg = null
                            val next = pin + d
                            pin = next
                            if (next.length == maxDigits) {
                                if (verifyMpin(next)) {
                                    onVerified()
                                } else {
                                    pin = ""
                                    errorMsg = "Incorrect MPIN. Action blocked."
                                }
                            }
                        }
                    },
                    onBackspace = {
                        if (pin.isNotEmpty()) {
                            pin = pin.dropLast(1)
                            errorMsg = null
                        }
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = if (onForgotMpin != null) Arrangement.SpaceBetween else Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (onForgotMpin != null) {
                        TextButton(
                            onClick = onForgotMpin,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("mpin_dialog_forgot")
                        ) {
                            Text("Set New MPIN", color = ElectricBlue)
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("mpin_dialog_cancel")
                    ) {
                        Text("Cancel", color = MetallicSilver)
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteModeSelectionDialog(
    itemCount: Int,
    itemName: String,
    hasCloudBackup: Boolean,
    onDismiss: () -> Unit,
    onModeChosen: (DeleteMode) -> Unit
) {
    var selectedMode by remember { mutableStateOf(DeleteMode.MOBILE_ONLY) }

    Dialog(onDismissRequest = onDismiss) {
        GlassCard4D(
            glowColor = CrimsonAlert,
            contentPadding = PaddingValues(20.dp)
        ) {
            Text(
                text = if (itemCount > 1) "Delete $itemCount Selected Items" else "Delete \"$itemName\"",
                style = MaterialTheme.typography.titleLarge,
                color = SilverWhite
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Permanent Cloud Backup Protection: Mobile deletion never deletes cloud backups automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MetallicSilver
            )
            Spacer(modifier = Modifier.height(14.dp))

            DeleteMode.entries.forEach { mode ->
                val enabled = mode != DeleteMode.CLOUD_ONLY || hasCloudBackup
                val selected = selectedMode == mode
                Surface(
                    color = if (selected) CrimsonAlert.copy(alpha = 0.16f) else MidnightPurple.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.dp,
                        if (selected) CrimsonAlert else NeonViolet.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = enabled) { selectedMode = mode }
                        .testTag("delete_mode_${mode.name.lowercase()}")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = if (enabled) ({ selectedMode = mode }) else null,
                            colors = RadioButtonDefaults.colors(selectedColor = CrimsonAlert)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (enabled) SilverWhite else MutedSlate
                            )
                            Text(
                                text = mode.subtitle,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                                color = if (enabled) MetallicSilver else MutedSlate
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = MetallicSilver)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onModeChosen(selectedMode) },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert),
                    modifier = Modifier.testTag("proceed_delete_mpin_button")
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Verify Secret MPIN")
                }
            }
        }
    }
}

@Composable
fun SecureDeleteFlowDialog(
    itemName: String,
    itemCount: Int = 1,
    hasSecretMpin: Boolean,
    secretMpinLength: Int,
    lockoutSeconds: Long,
    hasCloudBackup: Boolean = false,
    showDeleteModeSelector: Boolean = false,
    verifySecretMpin: (String) -> Boolean,
    onOpenSecuritySettings: () -> Unit,
    onDismiss: () -> Unit,
    onConfirmDelete: (DeleteMode) -> Unit
) {
    if (!hasSecretMpin) {
        Dialog(onDismissRequest = onDismiss) {
            GlassCard4D(
                glowColor = CrimsonAlert,
                contentPadding = PaddingValues(22.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.GppMaybe,
                        contentDescription = "Secret MPIN Required",
                        tint = AmberPending,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "SECRET MPIN REQUIRED",
                        style = MaterialTheme.typography.titleLarge,
                        color = SilverWhite,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Set a separate Secret MPIN in Security & Privacy Settings before deleting any file, note, or link.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MetallicSilver,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            onDismiss()
                            onOpenSecuritySettings()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("go_to_set_secret_mpin_button")
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Set Secret MPIN in Settings", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = MetallicSilver)
                    }
                }
            }
        }
        return
    }

    var pin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf(DeleteMode.MOBILE_ONLY) }

    Dialog(onDismissRequest = onDismiss) {
        GlassCard4D(
            glowColor = CrimsonAlert,
            contentPadding = PaddingValues(20.dp)
        ) {
            if (!verified) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = "Secure Delete Verification",
                        tint = CrimsonAlert,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "SECURE DELETE VERIFICATION",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                        color = SilverWhite,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Enter your ${secretMpinLength}-digit Secret MPIN to delete \"$itemName\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MetallicSilver,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    MpinDots(length = pin.length, maxDigits = secretMpinLength, isError = errorMsg != null)
                    if (lockoutSeconds > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Locked for ${lockoutSeconds}s due to failed attempts",
                            style = MaterialTheme.typography.labelMedium,
                            color = CrimsonAlert
                        )
                    } else if (errorMsg != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMsg!!,
                            style = MaterialTheme.typography.labelMedium,
                            color = CrimsonAlert,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    MpinKeypad(
                        enabled = lockoutSeconds <= 0,
                        buttonSize = 56.dp,
                        verticalSpacing = 10.dp,
                        onDigit = { d ->
                            if (pin.length < secretMpinLength) {
                                errorMsg = null
                                pin += d
                            }
                        },
                        onBackspace = {
                            if (pin.isNotEmpty()) {
                                pin = pin.dropLast(1)
                                errorMsg = null
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (verifySecretMpin(pin)) {
                                verified = true
                                errorMsg = null
                            } else {
                                pin = ""
                                errorMsg = "Incorrect Secret MPIN. Deletion blocked — please retry."
                            }
                        },
                        enabled = lockoutSeconds <= 0 && pin.length == secretMpinLength,
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert, contentColor = SilverWhite),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("verify_secret_mpin_continue_button")
                    ) {
                        Text("VERIFY & CONTINUE", fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("secret_mpin_cancel_button")
                    ) {
                        Text("Cancel", color = MetallicSilver)
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteForever,
                        contentDescription = "Confirm Delete",
                        tint = CrimsonAlert,
                        modifier = Modifier.size(42.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Are you sure you want to delete this file?",
                        style = MaterialTheme.typography.titleLarge,
                        color = SilverWhite,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (itemCount > 1) "$itemCount selected items ($itemName)" else itemName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ElectricBlue,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (showDeleteModeSelector) {
                        Spacer(modifier = Modifier.height(12.dp))
                        DeleteMode.entries.forEach { mode ->
                            val enabled = mode != DeleteMode.CLOUD_ONLY || hasCloudBackup
                            val selected = selectedMode == mode
                            Surface(
                                color = if (selected) CrimsonAlert.copy(alpha = 0.16f) else MidnightPurple.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) CrimsonAlert else NeonViolet.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = enabled) { selectedMode = mode }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(10.dp)
                                ) {
                                    RadioButton(
                                        selected = selected,
                                        onClick = if (enabled) ({ selectedMode = mode }) else null,
                                        colors = RadioButtonDefaults.colors(selectedColor = CrimsonAlert)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            text = mode.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (enabled) SilverWhite else MutedSlate
                                        )
                                        Text(
                                            text = mode.subtitle,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                            color = if (enabled) MetallicSilver else MutedSlate
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            border = BorderStroke(1.dp, MetallicSilver.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("confirm_delete_cancel_button")
                        ) {
                            Text("CANCEL", color = SilverWhite, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = { onConfirmDelete(selectedMode) },
                            colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert, contentColor = SilverWhite),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("confirm_delete_execute_button")
                        ) {
                            Text("CONFIRM DELETE", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumFlashOverlay(
    flashState: FlashScreenState,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = flashState.isVisible,
        enter = fadeIn(animationSpec = tween(220)),
        exit = fadeOut(animationSpec = tween(260))
    ) {
        val accentColor = when {
            flashState.errorMessage != null -> CrimsonAlert
            flashState.isSuccess -> EmeraldSync
            else -> ElectricBlue
        }
        val animatedProgress by animateFloatAsState(
            targetValue = flashState.progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            label = "flashProgress"
        )
        val infiniteTransition = rememberInfiniteTransition(label = "flashGlow")
        val sweepDeg by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "flashSweep"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "flashPulse"
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MidnightPurple.copy(alpha = 0.98f),
                            DeepObsidian.copy(alpha = 0.98f),
                            VoidBlack
                        )
                    )
                )
                .clickable(enabled = false) {}
                .padding(24.dp)
                .testTag("premium_flash_overlay")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 380.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(164.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = pulseAlpha * 0.42f),
                                    NeonViolet.copy(alpha = pulseAlpha * 0.18f),
                                    Color.Transparent
                                )
                            )
                        )
                        val strokePx = 4.dp.toPx()
                        drawCircle(
                            color = RoyalIndigo.copy(alpha = 0.45f),
                            style = Stroke(width = strokePx)
                        )
                        if (flashState.errorMessage == null && !flashState.isSuccess) {
                            rotate(degrees = sweepDeg) {
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        listOf(Color.Transparent, ElectricBlue, NeonViolet, SilverWhite)
                                    ),
                                    startAngle = 0f,
                                    sweepAngle = 220f,
                                    useCenter = false,
                                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                                )
                            }
                        } else {
                            drawArc(
                                color = accentColor,
                                startAngle = -90f,
                                sweepAngle = 360f * if (flashState.isSuccess) 1f else animatedProgress.coerceAtLeast(0.1f),
                                useCenter = false,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                            )
                        }
                    }

                    DskLockerLogo(
                        size = 92.dp,
                        showTitles = false,
                        animateRotation = flashState.errorMessage == null
                    )
                    if (flashState.isSuccess) {
                        Surface(
                            shape = CircleShape,
                            color = EmeraldSync,
                            border = BorderStroke(2.dp, VoidBlack),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = (-10).dp, y = (-10).dp)
                                .size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Success",
                                    tint = VoidBlack,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    } else if (flashState.errorMessage != null) {
                        Surface(
                            shape = CircleShape,
                            color = CrimsonAlert,
                            border = BorderStroke(2.dp, VoidBlack),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = (-10).dp, y = (-10).dp)
                                .size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.WarningAmber,
                                    contentDescription = "Error",
                                    tint = SilverWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = flashState.title.ifBlank { "PROCESSING..." },
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    ),
                    color = accentColor,
                    textAlign = TextAlign.Center
                )

                if (flashState.subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = flashState.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SilverWhite,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (flashState.errorMessage == null) {
                    LinearProgressIndicator(
                        progress = { animatedProgress.coerceIn(0.05f, 1f) },
                        color = accentColor,
                        trackColor = RoyalIndigo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (flashState.stepLabel.isNotBlank()) {
                            "${flashState.stepLabel} • ${(animatedProgress * 100).toInt()}%"
                        } else {
                            "${(animatedProgress * 100).toInt()}%"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MetallicSilver,
                        textAlign = TextAlign.Center
                    )
                } else {
                    GlassCard4D(glowColor = CrimsonAlert, contentPadding = PaddingValues(14.dp)) {
                        Text(
                            text = flashState.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CrimsonAlert,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            border = BorderStroke(1.dp, MetallicSilver.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("Close", color = SilverWhite)
                        }
                        if (flashState.onRetry != null) {
                            Button(
                                onClick = { flashState.onRetry.invoke() },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = VoidBlack),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("flash_retry_button")
                            ) {
                                Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

