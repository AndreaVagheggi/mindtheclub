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

    /** add.xml: the "+" at the end of the reaction bar, which opens every emoji. */
    val Add: ImageVector by lazy {
        icon(
            "add", 960f,
            "M440,520L200,520L200,440L440,440L440,200L520,200L520,440L760,440L760,520L520,520L520,760L440,760L440,520Z"
        )
    }

    /** reply.xml: the toolbar action of a picked message. */
    val Reply: ImageVector by lazy {
        icon("reply", 24f, "M10,9V5l-7,7 7,7v-4.1c5,0 8.5,1.6 11,5.1 -1,-5 -4,-10 -11,-11z")
    }

    /** forward.xml: the toolbar action of a picked message. */
    val Forward: ImageVector by lazy {
        icon("forward", 24f, "M21,11l-6,-6v5H8c-2.76,0 -5,2.24 -5,5v4h2v-4c0,-1.65 1.35,-3 3,-3h7v5L21,11z")
    }

    /** close.xml: the cross that takes a quote away. */
    val Close: ImageVector by lazy {
        icon(
            "close", 18f,
            "M14.53,4.53l-1.06,-1.06L9,7.94 4.53,3.47 3.47,4.53 7.94,9l-4.47,4.47 1.06,1.06L9,10.06l4.47,4.47 1.06,-1.06L10.06,9z"
        )
    }

    /** add_contact.xml: the head of the question that adds a contact (new_peer_dialog.xml). */
    val AddContact: ImageVector by lazy {
        icon(
            "add_contact", 24f,
            "M15,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM6,10L6,7L4,7v3L1,10v2h3v3h2v-3h3v-2L6,10zM15,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"
        )
    }

    /** person2.xml: beside the name on a profile (peer_view.xml). */
    val Person: ImageVector by lazy {
        icon(
            "person2", 24f,
            "M12,4A4,4 0 0,1 16,8A4,4 0 0,1 12,12A4,4 0 0,1 8,8A4,4 0 0,1 12,4M12,14C16.42,14 20,15.79 20,18V20H4V18C4,15.79 7.58,14 12,14Z"
        )
    }

    /** bio.xml: beside the bio on a profile (peer_view.xml). */
    val Bio: ImageVector by lazy {
        icon(
            "bio", 24f,
            "M12,12.25c1.24,0 2.25,-1.01 2.25,-2.25S13.24,7.75 12,7.75 9.75,8.76 9.75,10s1.01,2.25 2.25,2.25zM16.5,16.25c0,-1.5 -3,-2.25 -4.5,-2.25s-4.5,0.75 -4.5,2.25L7.5,17h9v-0.75zM19,3L5,3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2zM19,19L5,19L5,5h14v14z"
        )
    }

    /** delete.xml: on the button that removes a pending contact (peer_view.xml). */
    val Delete: ImageVector by lazy {
        icon("delete", 24f, "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    }

    /** pending.xml: the three dots on the row of a contact being acquired (peer_item.xml). */
    val Pending: ImageVector by lazy {
        icon(
            "pending", 24f,
            "M12,2C6.48,2 2,6.48 2,12c0,5.52 4.48,10 10,10s10,-4.48 10,-10C22,6.48 17.52,2 12,2zM7,13.5c-0.83,0 -1.5,-0.67 -1.5,-1.5c0,-0.83 0.67,-1.5 1.5,-1.5s1.5,0.67 1.5,1.5C8.5,12.83 7.83,13.5 7,13.5zM12,13.5c-0.83,0 -1.5,-0.67 -1.5,-1.5c0,-0.83 0.67,-1.5 1.5,-1.5s1.5,0.67 1.5,1.5C13.5,12.83 12.83,13.5 12,13.5zM17,13.5c-0.83,0 -1.5,-0.67 -1.5,-1.5c0,-0.83 0.67,-1.5 1.5,-1.5s1.5,0.67 1.5,1.5C18.5,12.83 17.83,13.5 17,13.5z"
        )
    }

    /** microphone.xml: the voice message button, its recording bar, and the mark on a voice bubble. */
    val Microphone: ImageVector by lazy {
        icon(
            "microphone", 24f,
            "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"
        )
    }

    /** play.xml: the voice bubble's play button. */
    val Play52: ImageVector by lazy {
        icon("play", 960f, "M300,300 L300,660 L660,480 Z")
    }

    /** pause.xml: the voice bubble's pause button, two hollow bars. */
    val Pause52: ImageVector by lazy {
        icon(
            "pause", 960f,
            "M506,660L506,300L660,300L660,660L506,660M300,660L300,300L454,300L454,660L300,660" +
                    "M557,609L609,609L609,351L557,351L557,609M351,609L403,609L403,351L351,351L351,609"
        )
    }

    /** rewind.xml: "slide left to cancel" on the recording bar. */
    val Rewind: ImageVector by lazy {
        icon(
            "rewind", 960f,
            "M440,720L200,480L440,240L496,296L313,480L496,664L440,720ZM704,720L464,480L704,240L760,296L577,480L760,664L704,720Z"
        )
    }

    /** lock.xml: the top of the capsule above the microphone while recording. */
    val Lock: ImageVector by lazy {
        icon(
            "lock", 24f,
            "M18,8h-1L17,6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2L6,8c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2L20,10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8L8.9,8L8.9,6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z"
        )
    }

    /** up.xml: "slide up to lock" in the capsule. */
    val Up: ImageVector by lazy {
        icon(
            "up", 24f,
            "M6,17.59l1.41,1.41l4.59,-4.58l4.59,4.58l1.41,-1.41l-6,-6zM6,11l1.41,1.41l4.59,-4.58l4.59,4.58l1.41,-1.41l-6,-6z"
        )
    }

    /** copy.xml: Copy, on a picked text or link. */
    val Copy: ImageVector by lazy {
        icon(
            "copy", 24f,
            "M16,1L4,1c-1.1,0 -2,0.9 -2,2v14h2L4,3h12L16,1zM19,5L8,5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2L21,7c0,-1.1 -0.9,-2 -2,-2zM19,21L8,21L8,7h11v14z"
        )
    }

    /** share2.xml: Share, on a picked message. */
    val Share: ImageVector by lazy {
        icon(
            "share2", 24f,
            "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92 1.61,0 2.92,-1.31 2.92,-2.92s-1.31,-2.92 -2.92,-2.92z"
        )
    }

    /** group_placeholder.xml, its figure: a group without a picture. */
    val Group: ImageVector by lazy {
        icon(
            "group", 960f,
            "M40,800L40,688Q40,654 57.5,625.5Q75,597 104,582Q166,551 230,535.5Q294,520 360,520Q426,520 490,535.5Q554,551 616,582Q645,597 662.5,625.5Q680,654 680,688L680,800L40,800ZM760,800L760,680Q760,636 735.5,595.5Q711,555 666,526Q717,532 762,546.5Q807,561 846,582Q882,602 901,626.5Q920,651 920,680L920,800L760,800ZM360,480Q294,480 247,433Q200,386 200,320Q200,254 247,207Q294,160 360,160Q426,160 473,207Q520,254 520,320Q520,386 473,433Q426,480 360,480ZM760,320Q760,386 713,433Q666,480 600,480Q589,480 572,477.5Q555,475 544,472Q571,440 585.5,401Q600,362 600,320Q600,278 585.5,239Q571,200 544,168Q558,163 572,161.5Q586,160 600,160Q666,160 713,207Q760,254 760,320ZM120,720L600,720L600,688Q600,677 594.5,668Q589,659 580,654Q526,627 471,613.5Q416,600 360,600Q304,600 249,613.5Q194,627 140,654Q131,659 125.5,668Q120,677 120,688L120,720ZM360,400Q393,400 416.5,376.5Q440,353 440,320Q440,287 416.5,263.5Q393,240 360,240Q327,240 303.5,263.5Q280,287 280,320Q280,353 303.5,376.5Q327,400 360,400Z"
        )
    }

    /** group_add.xml: the button that makes a group of the picked contacts. */
    val GroupAdd: ImageVector by lazy {
        icon(
            "group_add", 960f,
            "M500,478Q529,446 544.5,405Q560,364 560,320Q560,276 544.5,235Q529,194 500,162Q560,170 600,215Q640,260 640,320Q640,380 600,425Q560,470 500,478ZM720,800L720,680Q720,644 704,611.5Q688,579 662,554Q713,572 756.5,600.5Q800,629 800,680L800,800L720,800ZM800,520L800,440L720,440L720,360L800,360L800,280L880,280L880,360L960,360L960,440L880,440L880,520L800,520ZM320,480Q254,480 207,433Q160,386 160,320Q160,254 207,207Q254,160 320,160Q386,160 433,207Q480,254 480,320Q480,386 433,433Q386,480 320,480ZM0,800L0,688Q0,654 17.5,625.5Q35,597 64,582Q126,551 190,535.5Q254,520 320,520Q386,520 450,535.5Q514,551 576,582Q605,597 622.5,625.5Q640,654 640,688L640,800L0,800ZM320,400Q353,400 376.5,376.5Q400,353 400,320Q400,287 376.5,263.5Q353,240 320,240Q287,240 263.5,263.5Q240,287 240,320Q240,353 263.5,376.5Q287,400 320,400ZM80,720L560,720L560,688Q560,677 554.5,668Q549,659 540,654Q486,627 431,613.5Q376,600 320,600Q264,600 209,613.5Q154,627 100,654Q91,659 85.5,668Q80,677 80,688L80,720Z"
        )
    }

    /** ic_edit.xml: the pencil on the group's picture. */
    val Edit: ImageVector by lazy {
        icon(
            "edit", 24f,
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z"
        )
    }

    /** The toolbar's back arrow. */
    val Back: ImageVector by lazy {
        icon("back", 24f, "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    }

    /** send.xml: sends a locked recording. */
    val Send: ImageVector by lazy {
        icon("send", 24f, "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z")
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
