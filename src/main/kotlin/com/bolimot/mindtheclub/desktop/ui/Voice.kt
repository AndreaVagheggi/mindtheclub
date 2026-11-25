package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.desktop.media.AudioPlayback
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Voice messages, as the phone has them.
 *
 *   - MessageAudio is the bubble (chat_audio_in.xml, chat_audio_out.xml, AudioViewHolder): on
 *     the phone's blue card, the speaker's round picture with a small microphone over it, the
 *     play button, and a thin bar of how far it has played.
 *   - VoiceButton is the microphone at the end of the message box, shown while nothing is
 *     written (ChatScreen, micTouchListener). Held down for half a second it records; let go,
 *     it sends. While it is held, sliding up locks the recording, so that it goes on without the
 *     hand, and sliding left throws it away. A locked recording turns the button into Send.
 *   - RecordingBar takes the place of the message box while recording (recordingView): the red
 *     microphone blinking, the time, and "slide left to cancel"; once locked, the bin instead.
 *   - LockCapsule is the capsule above the microphone (locking_view): a lock and the arrows up.
 *
 * The PC has a mouse where the phone has a finger: holding is the mouse button held down, and
 * sliding is moving the mouse with the button down. The distances are the phone's, 150 pixels.
 */

/** ChatScreen.handleSlide: how far the finger slides, in pixels, to lock or to cancel. */
private const val SLIDE_OFFSET_PX = 150f

/** startRecordingRunnable is posted this long after the finger goes down. */
private const val HOLD_TO_RECORD_MS = 500L

/** @color/mtc_opaque: the card of a voice bubble, in and out alike. */
private val VOICE_CARD = Color(0xFF607D9F)

/** @color/mtc_accent: how far it has played. */
private val VOICE_PROGRESS = Color(0xFF03A9F4)

/** @color/red: the microphone of the recording bar. */
private val RECORDING_RED = Color(0xFFDD1717)

/** The bubble of a voice message. */
@Composable
internal fun MessageAudio(state: AppState, message: Message) {
    val file = remember(message.uri) { MediaFiles.fileOfUri(message.uri) }
    val picture by produceState<File?>(null, message.messageId) {
        value = withContext(Dispatchers.IO) { state.voicePictureOf(message) }
    }

    // One player per bubble, released when the bubble leaves the screen (onViewRecycled).
    val playback = remember(file) { file?.let { AudioPlayback(it) } }
    DisposableEffect(playback) {
        onDispose { playback?.release() }
    }

    var playing by remember(playback) { mutableStateOf(false) }
    var progress by remember(playback) { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    // AudioViewHolder.updateProgressBar, every half second there; a little more often here so
    // the bar moves smoothly on a large screen.
    LaunchedEffect(playing) {
        while (playing && playback != null) {
            if (playback.hasEnded()) {
                playing = false
                progress = 0f
            } else {
                progress = playback.progress()
            }
            delay(100)
        }
    }

    Surface(color = VOICE_CARD, shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Avatar(picture, 42.dp)
                    Icon(
                        AppIcons.Microphone,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp).size(18.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (playing) AppIcons.Pause52 else AppIcons.Play52,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(52.dp)
                        // selectableItemBackgroundBorderless: a round ripple.
                        .clip(CircleShape)
                        .clickable(enabled = playback != null) {
                            val player = playback ?: return@clickable
                            if (playing) {
                                player.pause()
                                playing = false
                            } else {
                                scope.launch {
                                    val started = withContext(Dispatchers.IO) { player.play() }
                                    if (started) playing = true else state.showWarning(Strings.VOICE_MESSAGE_UNPLAYABLE)
                                }
                            }
                        }
                )
                LinearProgressIndicator(
                    progress = { progress },
                    color = VOICE_PROGRESS,
                    trackColor = Color.White,
                    modifier = Modifier.width(135.dp).height(6.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            // A voice message that came with the phone's backup has no sound here: the backup
            // carries names, not bytes.
            if (file == null) {
                Text(Strings.MEDIA_NOT_AVAILABLE, color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

/**
 * The microphone at the end of the message box, or Send once a recording is locked. The same
 * button throughout, so that the mouse held down on it keeps being followed while the screen
 * around it changes.
 */
@Composable
internal fun VoiceButton(state: AppState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val active = state.recording && !state.recordingLocked

    Box(
        modifier = modifier
            .size(48.dp)
            // microphone_active: the grey disc behind the microphone while it records.
            .background(if (active) GREY else Color.Transparent, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()

                    // A locked recording: the button is Send, and a click sends it.
                    if (state.recording && state.recordingLocked) {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.id == down.id && it.pressed })
                        state.stopAndSendRecording()
                        return@awaitEachGesture
                    }

                    val starting = scope.launch {
                        delay(HOLD_TO_RECORD_MS)
                        state.startRecording()
                    }
                    var decided = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (state.recording && !state.recordingLocked && !decided) {
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (dy < -SLIDE_OFFSET_PX) {
                                state.lockRecording()
                                decided = true
                            } else if (dx < -SLIDE_OFFSET_PX) {
                                state.cancelRecording()
                                decided = true
                            }
                        }
                    }
                    starting.cancel()
                    // Let go while recording, neither locked nor cancelled: stop and send.
                    if (state.recording && !state.recordingLocked) state.stopAndSendRecording()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (state.recording && state.recordingLocked) AppIcons.Send else AppIcons.Microphone,
            contentDescription = if (state.recording && state.recordingLocked) Strings.SEND else Strings.MICROPHONE,
            tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** What stands in the place of the message box while a voice message is recorded. */
@Composable
internal fun RecordingBar(state: AppState, modifier: Modifier = Modifier) {
    val blink by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse)
    )

    // The Chronometer, "%s": minutes and seconds.
    var elapsed by remember { mutableStateOf("00:00") }
    LaunchedEffect(state.recordingStartedAt) {
        while (true) {
            val seconds = ((System.currentTimeMillis() - state.recordingStartedAt) / 1000).coerceAtLeast(0)
            elapsed = "%02d:%02d".format(seconds / 60, seconds % 60)
            delay(250)
        }
    }

    Surface(color = Color.White, shape = RoundedCornerShape(24.dp), modifier = modifier.height(52.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(24.dp))
            Icon(
                AppIcons.Microphone,
                contentDescription = null,
                tint = RECORDING_RED,
                // Blinks until the recording is locked (stopRecording clears the animation).
                modifier = Modifier.size(28.dp).alpha(if (state.recordingLocked) 1f else blink)
            )
            Spacer(Modifier.width(12.dp))
            Text(elapsed, color = Color.Black, fontSize = 16.sp)
            if (state.recordingLocked) {
                // cancelRecording: the bin of a locked recording.
                Box(
                    modifier = Modifier.size(48.dp).clickable { state.cancelRecording() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.Delete, contentDescription = Strings.CANCEL, tint = Color.Black)
                }
            }
            Spacer(Modifier.weight(1f))
            if (!state.recordingLocked) {
                // "Slide left to cancel": the bin and the arrows, which blink.
                Icon(AppIcons.Delete, contentDescription = null, tint = GREY)
                Spacer(Modifier.width(12.dp))
                Icon(AppIcons.Rewind, contentDescription = null, tint = GREY, modifier = Modifier.size(48.dp).alpha(blink))
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

/** The capsule above the microphone while recording, until it is locked: slide up to lock. */
@Composable
internal fun LockCapsule(modifier: Modifier = Modifier) {
    val blink by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse)
    )
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(21.dp),
        shadowElevation = 2.dp,
        modifier = modifier.size(width = 42.dp, height = 84.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            Icon(AppIcons.Lock, contentDescription = null, tint = GREY, modifier = Modifier.size(24.dp))
            Spacer(Modifier.weight(1f))
            Icon(AppIcons.Up, contentDescription = null, tint = GREY, modifier = Modifier.size(24.dp).alpha(blink))
            Spacer(Modifier.height(8.dp))
        }
    }
}
