package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The paperclip of the message box and the bar it opens, as the phone has them
 * (C:\mtc, res/layout/screen_chat.xml: the ImageButton "attach" and "controls_toolbar").
 *
 * On the phone the paperclip sits at the end of the message box. A tap slides a rounded bar up
 * from just above the box, with four choices side by side, each an icon over a word: Images,
 * Video, File, Contact. A second tap, a choice, or five seconds without one, slide it away.
 */

/** The App's own icons, drawn from the same paths as its res/drawable files of the same name. */
internal object AppIcons {

    /** attach.xml */
    val Attach: ImageVector by lazy {
        icon(
            "attach", 24f,
            "M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 2.5,-2.5s2.5,1.12 2.5,2.5v10.5c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V6H10v9.5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5c0,-2.21 -1.79,-4 -4,-4S7,2.79 7,5v12.5c0,3.04 2.46,5.5 5.5,5.5s5.5,-2.46 5.5,-5.5V6h-1.5z"
        )
    }

    /** image.xml */
    val Image: ImageVector by lazy {
        icon(
            "image", 24f,
            "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z"
        )
    }

    /** movie.xml */
    val Movie: ImageVector by lazy {
        icon(
            "movie", 24f,
            "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z"
        )
    }

    /** file.xml */
    val File: ImageVector by lazy {
        icon(
            "file", 24f,
            "M14,2L6,2c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2L18,22c1.1,0 2,-0.9 2,-2L20,8l-6,-6zM18,20L6,20L6,4h7v5h5v11zM8,15.01l1.41,1.41L11,14.84L11,19h2v-4.16l1.59,1.59L16,15.01 12.01,11z"
        )
    }

    /** file2.xml: the sheet of paper a file bubble is drawn with. */
    val FileSheet: ImageVector by lazy {
        icon(
            "file2", 960f,
            "M240,880Q207,880 183.5,856.5Q160,833 160,800L160,160Q160,127 183.5,103.5Q207,80 240,80L560,80L800,320L800,800Q800,833 776.5,856.5Q753,880 720,880L240,880ZM520,360L520,160L240,160Q240,160 240,160Q240,160 240,160L240,800Q240,800 240,800Q240,800 240,800L720,800Q720,800 720,800Q720,800 720,800L720,360L520,360ZM240,160L240,160L240,360L240,360L240,160L240,360L240,360L240,800Q240,800 240,800Q240,800 240,800L240,800Q240,800 240,800Q240,800 240,800L240,160Q240,160 240,160Q240,160 240,160Z"
        )
    }

    /** play.xml: the triangle in the middle of a video bubble. */
    val Play: ImageVector by lazy {
        icon("play", 960f, "M300,300 L300,660 L660,480 Z")
    }

    /** video.xml: the small camera in the corner of a video bubble. */
    val VideoCamera: ImageVector by lazy {
        icon(
            "video", 24f,
            "M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z"
        )
    }

    /** contact.xml */
    val Contact: ImageVector by lazy {
        icon(
            "contact", 960f,
            "M560,520L760,520L760,440L560,440L560,520ZM560,400L760,400L760,320L560,320L560,400ZM200,640L520,640L520,618Q520,573 476,546.5Q432,520 360,520Q288,520 244,546.5Q200,573 200,618L200,640ZM360,480Q393,480 416.5,456.5Q440,433 440,400Q440,367 416.5,343.5Q393,320 360,320Q327,320 303.5,343.5Q280,367 280,400Q280,433 303.5,456.5Q327,480 360,480ZM160,800Q127,800 103.5,776.5Q80,753 80,720L80,240Q80,207 103.5,183.5Q127,160 160,160L800,160Q833,160 856.5,183.5Q880,207 880,240L880,720Q880,753 856.5,776.5Q833,800 800,800L160,800ZM160,720L800,720Q800,720 800,720Q800,720 800,720L800,240Q800,240 800,240Q800,240 800,240L160,240Q160,240 160,240Q160,240 160,240L160,720Q160,720 160,720Q160,720 160,720ZM160,720Q160,720 160,720Q160,720 160,720L160,240Q160,240 160,240Q160,240 160,240L160,240Q160,240 160,240Q160,240 160,240L160,720Q160,720 160,720Q160,720 160,720Z"
        )
    }

    private fun icon(name: String, viewport: Float, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = viewport,
            viewportHeight = viewport
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.White)).build()
}

/** How long the bar stays open with no choice made: ChatScreen.TOOLBAR_HIDE_DELAY. */
internal const val ATTACH_BAR_HIDE_DELAY_MS = 5000L

/** The paperclip at the end of the message box. */
@Composable
internal fun AttachButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(AppIcons.Attach, contentDescription = Strings.ATTACH, tint = Color.Gray)
    }
}

/**
 * The bar the paperclip opens. Drawn over the bottom of the conversation, as on the phone, and
 * no wider than a phone would make it. Each choice reports itself and nothing more: what it
 * does is the caller's business.
 */
@Composable
internal fun AttachBar(
    visible: Boolean,
    onImages: () -> Unit,
    onVideo: () -> Unit,
    onFile: () -> Unit,
    onContact: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        // 300 ms up and in, 300 ms down and out, as showToolbarWithAnimation does.
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it },
        exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it },
        modifier = modifier
    ) {
        Surface(
            color = BAR_COLOR,
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 4.dp,
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().height(64.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AttachChoice(AppIcons.Image, Strings.IMAGES, onImages)
                AttachChoice(AppIcons.Movie, Strings.VIDEO, onVideo)
                AttachChoice(AppIcons.File, Strings.FILE, onFile)
                AttachChoice(AppIcons.Contact, Strings.CONTACT, onContact)
            }
        }
    }
}

@Composable
private fun RowScope.AttachChoice(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

/** toolbar_chat_background.xml */
private val BAR_COLOR = Color(0xFF607D9F)
