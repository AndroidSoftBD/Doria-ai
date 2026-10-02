package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.model.AssistantStatus
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.DeepViolet
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.NeonPink
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun DoriaScreen(viewModel: DoriaViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] == true
    }

    LaunchedEffect(Unit) {
        if (!hasAudioPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_CONTACTS
                )
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .widthIn(max = 600.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top App Bar
            TopBarSection(
                detectedLanguage = uiState.detectedLanguage,
                isSpeakerTesting = uiState.isSpeakerTesting,
                onTestSpeaker = { viewModel.runSpeakerTest() }
            )

            // Scrollable Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Status Pill
                StatusIndicatorPill(status = uiState.status)

                Spacer(modifier = Modifier.height(20.dp))

                // Central Dynamic Glowing Energy Sphere & Visualizer
                VisualizerOrb(
                    status = uiState.status,
                    amplitude = uiState.audioAmplitude,
                    onClick = {
                        if (!hasAudioPermission) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        } else {
                            viewModel.toggleMicrophone()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Live Dialog Subtitles & Cards
                SubtitleCardsSection(
                    userText = uiState.userTranscription,
                    assistantText = uiState.assistantResponse,
                    status = uiState.status
                )

                // Error Notice if any
                uiState.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(12.dp))
                    ErrorCard(
                        errorMessage = error,
                        onDismiss = { viewModel.dismissError() }
                    )
                }
            }

            // Bottom Section: Quick prompts & Mic Action Button
            BottomControlsSection(
                status = uiState.status,
                isMicActive = uiState.isMicActive,
                amplitude = uiState.audioAmplitude,
                hasPermission = hasAudioPermission,
                onMicClick = {
                    if (!hasAudioPermission) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    } else {
                        viewModel.toggleMicrophone()
                    }
                },
                onQuickPromptClick = { prompt ->
                    viewModel.sendQuery(prompt)
                }
            )
        }
    }
}

@Composable
fun TopBarSection(
    detectedLanguage: String,
    isSpeakerTesting: Boolean,
    onTestSpeaker: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "DORIA",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        color = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DeepViolet.copy(alpha = 0.6f))
                        .border(1.dp, NeonViolet.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "LIVE",
                        color = CyberCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = detectedLanguage,
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        // Speaker Diagnostic Test Button
        Button(
            onClick = onTestSpeaker,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSpeakerTesting) EmeraldMint else DarkSurfaceVariant,
                contentColor = if (isSpeakerTesting) Color.Black else TextSecondary
            ),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("test_speaker_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Test Speaker",
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isSpeakerTesting) "Testing 440Hz..." else "Test Speaker",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun StatusIndicatorPill(status: AssistantStatus) {
    val (statusText, statusColor) = when (status) {
        AssistantStatus.IDLE -> Pair("Tap mic to speak", TextMuted)
        AssistantStatus.CONNECTING -> Pair("Doria is thinking...", CyberCyan)
        AssistantStatus.LISTENING -> Pair("Listening to you...", EmeraldMint)
        AssistantStatus.SPEAKING -> Pair("Doria is speaking...", NeonPink)
        AssistantStatus.ERROR -> Pair("Connection notice", NeonPink)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                color = statusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun VisualizerOrb(
    status: AssistantStatus,
    amplitude: Float,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val dynamicScale by animateFloatAsState(
        targetValue = (1.0f + (amplitude * 0.4f)).coerceIn(0.9f, 1.45f),
        label = "amplitude_scale"
    )

    val effectiveScale = if (status == AssistantStatus.LISTENING || status == AssistantStatus.SPEAKING) {
        dynamicScale
    } else {
        pulseScale
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(240.dp)
            .clickable(onClick = onClick)
            .testTag("visualizer_orb")
    ) {
        // Outer glowing ripple rings
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = (size.minDimension / 2.6f) * effectiveScale

            val ringColor1 = when (status) {
                AssistantStatus.SPEAKING -> NeonPink.copy(alpha = 0.35f + (amplitude * 0.4f))
                AssistantStatus.LISTENING -> CyberCyan.copy(alpha = 0.35f + (amplitude * 0.4f))
                AssistantStatus.CONNECTING -> NeonViolet.copy(alpha = 0.5f)
                AssistantStatus.ERROR -> Color.Red.copy(alpha = 0.3f)
                AssistantStatus.IDLE -> DeepViolet.copy(alpha = 0.25f)
            }

            drawCircle(
                color = ringColor1,
                radius = baseRadius * 1.18f,
                center = center,
                style = Stroke(width = 2.5.dp.toPx())
            )

            drawCircle(
                color = ringColor1.copy(alpha = ringColor1.alpha * 0.5f),
                radius = baseRadius * 1.35f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // Inner glowing core sphere
        val sphereGradient = when (status) {
            AssistantStatus.SPEAKING -> Brush.radialGradient(
                colors = listOf(NeonPink, DeepViolet, DarkBackground)
            )
            AssistantStatus.LISTENING -> Brush.radialGradient(
                colors = listOf(CyberCyan, NeonViolet, DarkBackground)
            )
            AssistantStatus.CONNECTING -> Brush.radialGradient(
                colors = listOf(NeonViolet, DeepViolet, DarkBackground)
            )
            AssistantStatus.ERROR -> Brush.radialGradient(
                colors = listOf(Color.Red, DeepViolet, DarkBackground)
            )
            AssistantStatus.IDLE -> Brush.radialGradient(
                colors = listOf(DeepViolet, DarkSurfaceVariant, DarkBackground)
            )
        }

        Box(
            modifier = Modifier
                .size(150.dp)
                .scale(effectiveScale)
                .clip(CircleShape)
                .background(sphereGradient)
                .border(
                    width = 2.dp,
                    brush = Brush.sweepGradient(
                        colors = listOf(CyberCyan, NeonPink, NeonViolet, CyberCyan)
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (status) {
                    AssistantStatus.SPEAKING -> Icons.Default.RecordVoiceOver
                    AssistantStatus.LISTENING -> Icons.Default.Mic
                    AssistantStatus.CONNECTING -> Icons.Default.RecordVoiceOver
                    else -> Icons.Default.Mic
                },
                contentDescription = "Assistant State",
                tint = TextPrimary,
                modifier = Modifier.size(44.dp)
            )
        }
    }
}

@Composable
fun SubtitleCardsSection(
    userText: String,
    assistantText: String,
    status: AssistantStatus
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // User speech speech bubble
        AnimatedVisibility(
            visible = userText.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("user_transcript_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.8f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "You",
                        color = CyberCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp, top = 2.dp)
                    )
                    Text(
                        text = userText,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Assistant response card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("assistant_response_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Doria",
                        color = NeonPink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (status == AssistantStatus.SPEAKING) {
                        Text(
                            text = "Audible Voice Output",
                            color = EmeraldMint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = assistantText,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

@Composable
fun ErrorCard(errorMessage: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("error_card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1219)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = Color(0xFFF87171),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = errorMessage,
                    color = Color(0xFFFECACA),
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = Color(0xFFF87171),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun BottomControlsSection(
    status: AssistantStatus,
    isMicActive: Boolean,
    amplitude: Float,
    hasPermission: Boolean,
    onMicClick: () -> Unit,
    onQuickPromptClick: (String) -> Unit
) {
    val quickPrompts = listOf(
        "WhatsApp kholo",
        "Open YouTube",
        "Call Rahul",
        "How's your day, Doria?",
        "Hinglish mein baat karo",
        "Tell me a witty joke"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Quick Action Suggestion Chips
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(quickPrompts) { prompt ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = DarkSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
                    modifier = Modifier
                        .clickable { onQuickPromptClick(prompt) }
                        .testTag("quick_action_${prompt.replace(" ", "_")}")
                ) {
                    Text(
                        text = prompt,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Main Microphone / Power Button
        val buttonGradient = if (isMicActive) {
            Brush.linearGradient(listOf(EmeraldMint, CyberCyan))
        } else if (status == AssistantStatus.SPEAKING) {
            Brush.linearGradient(listOf(NeonPink, DeepViolet))
        } else {
            Brush.linearGradient(listOf(NeonViolet, DeepViolet))
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(buttonGradient)
                .clickable(onClick = onMicClick)
                .testTag("mic_button")
        ) {
            Icon(
                imageVector = if (isMicActive) Icons.Default.Mic else if (status == AssistantStatus.SPEAKING) Icons.Default.RecordVoiceOver else Icons.Default.Mic,
                contentDescription = if (isMicActive) "Stop Listening" else "Start Listening",
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isMicActive) "Tap to pause • Listening" else if (status == AssistantStatus.SPEAKING) "Tap to interrupt Doria" else "Tap to speak",
            color = TextMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}
