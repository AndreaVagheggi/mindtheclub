package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.desktop.web.WebPreview
import com.bolimot.mindtheclub.desktop.web.WebsiteInfo
import com.bolimot.mindtheclub.tools.SubType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

/*
 * Links with a preview, as the phone has them.
 *
 *   - MessageWeb is the bubble (chat_web_in.xml, WebViewHolder): 230 wide, a light card with the
 *     site's picture, the page's title in bold on two lines at most and its description in
 *     italics on three, then the text with the link. A click opens the link in the PC's browser,
 *     as a tap opens it on the phone. A picture that does not load leaves title and description.
 *   - LinkInsert is the card above the message box while a link is being written (ChatScreen,
 *     showPlaceholder and updateUI): a grey square blinking while the page is read, then the
 *     same three things. Its cross gives the preview up and, as on the phone, empties the box.
 */

/** chat_web_in.xml: the width of the card. */
private val WEB_CARD_WIDTH = 230.dp

/** rounded_insert: the card inside the bubble. */
private val WEB_INSERT_COLOR = Color(0xFFE0E0E0)

/** The site's picture, loaded from the site and kept on disk, as a small copy for the window. */
@Composable
private fun rememberWebPicture(imageUrl: String?): ImageBitmap? {
    val picture by produceState<ImageBitmap?>(null, imageUrl) {
        value = withContext(Dispatchers.IO) { WebPreview.pictureOf(imageUrl)?.let { Thumbnails.of(it) } }
    }
    return picture
}

/** The bubble of a link. */
@Composable
internal fun MessageWeb(message: Message) {
    val picture = rememberWebPicture(message.replyId)
    val title = message.nameAttached.orEmpty()
    val description = message.textAttached.orEmpty()
    val link = remember(message.text) { WebPreview.extractUrl(message.text) }

    Column(
        modifier = Modifier
            .width(WEB_CARD_WIDTH)
            .clickable(enabled = link != null) { link?.let { openInBrowser(it) } }
    ) {
        if (picture != null || title.isNotEmpty() || description.isNotEmpty()) {
            Surface(color = WEB_INSERT_COLOR, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    picture?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                        )
                    }
                    if (title.isNotEmpty()) {
                        Text(
                            title,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (description.isNotEmpty()) {
                        Text(
                            description,
                            color = Color.Black,
                            fontStyle = FontStyle.Italic,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Text(message.text, fontSize = 16.sp, color = Color(0xFF1565C0))
        // WebViewHolder: "forwarded" under a forwarded link.
        if (message.subType == SubType.FORWARD) {
            Text(Strings.FORWARDED, fontSize = 11.sp, color = Color(0xFF607D9F))
        }
    }
}

/**
 * The preview of the link being written, above the message box. [info] is null while the page
 * is still being read.
 */
@Composable
internal fun LinkInsert(info: WebsiteInfo?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val picture = rememberWebPicture(info?.imageUrl)
    val blink by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse)
    )
    val loading = info == null
    val hasPicture = loading || picture != null

    Surface(color = WEB_INSERT_COLOR, shape = RoundedCornerShape(8.dp), modifier = modifier.width(250.dp)) {
        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp, end = if (hasPicture) 8.dp else 24.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    val title = info?.title.orEmpty()
                    val description = info?.description.orEmpty()
                    if (title.isNotEmpty()) {
                        Text(title, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (description.isNotEmpty()) {
                        Text(description, color = Color.Black, fontStyle = FontStyle.Italic, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (hasPicture) {
                    Box(modifier = Modifier.size(width = 40.dp, height = 48.dp)) {
                        if (picture != null) {
                            Image(bitmap = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            // showPlaceholder: a blurred square, blinking (startBlinkingAnimation).
                            Box(modifier = Modifier.fillMaxSize().alpha(blink).background(Color.Gray))
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(if (hasPicture) 20.dp else 24.dp)
                    .clip(CircleShape)
                    .background(if (hasPicture) Color(0xFFBDBDBD) else Color.Transparent)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.Close, contentDescription = Strings.CLOSE, tint = Color.Black, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** What a tap on a link does on the phone: the page, in the browser. */
private fun openInBrowser(link: String) {
    try {
        val address = if (link.startsWith("http://", true) || link.startsWith("https://", true)) link else "https://$link"
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(address))
    } catch (e: Exception) {
        // Not an address the browser takes: nothing to open.
    }
}
