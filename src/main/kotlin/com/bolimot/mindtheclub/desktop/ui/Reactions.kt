package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.bolimot.mindtheclub.database.message.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Reactions, as the phone has them (C:\mtc, chat/ChatScreen.kt showReactionOverlay,
 * chat/EmojiPickerBottomSheet.kt, chat/ReactionsBottomSheet.kt, layout popup_reaction_bar.xml).
 *
 *   - Picking a message the other person sent (the right mouse button on the PC, the long press
 *     on the phone) brings up a white bar with four emojis and a "+", at the right edge just
 *     above the message. The emoji already put on that message is marked: choosing it again
 *     takes it away. The "+" opens every emoji.
 *   - Under a bubble with reactions sits a small white pill: up to three emojis, and the number
 *     of people once there is more than one. A click on it lists who put what; the user's own
 *     line takes the user's reaction away.
 */

/** The four emojis of the bar, in the App's order: like, love, laugh, wink. */
internal val QUICK_REACTIONS = listOf(
    "\uD83D\uDC4D",       // thumbs up
    "\u2764\uFE0F",       // red heart
    "\uD83D\uDE02",       // tears of joy
    "\uD83D\uDE09"        // winking face
)

/**
 * Where the bar goes: ChatScreen.showReactionOverlay. Against the right edge of the window,
 * just above the picked message; when there is no room above, over the bottom of the message.
 */
private object AboveMessageAtRightEdge : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        var y = anchorBounds.top - popupContentSize.height
        if (y < 0) y = anchorBounds.bottom - popupContentSize.height
        if (y + popupContentSize.height > windowSize.height) y = windowSize.height - popupContentSize.height
        if (y < 0) y = 0
        val x = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * The reaction bar of [message], shown while it is picked. Placed inside the bubble's own box,
 * which is what it is positioned against. It takes no focus and does not close on a click
 * elsewhere: it goes when the message is let go of, which every action does.
 */
@Composable
internal fun ReactionBarPopup(state: AppState, message: Message, onMore: () -> Unit) {
    val mine by produceState<String?>(null, message.messageId) {
        value = state.myEmojiOn(message.messageId)
    }

    Popup(
        popupPositionProvider = AboveMessageAtRightEdge,
        onDismissRequest = {},
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .border(1.dp, Color(0x33000000), RoundedCornerShape(50))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (emoji in QUICK_REACTIONS) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        // circle_selected_reaction.xml: the emoji already on the message.
                        .background(if (emoji == mine) Color(0x1F000000) else Color.Transparent)
                        .clickable { state.react(message, emoji) }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(emoji, fontSize = 24.sp)
                }
            }
            Box(
                modifier = Modifier.padding(start = 4.dp).size(48.dp).clip(CircleShape).clickable(onClick = onMore),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/**
 * The pill under a bubble: its caption is Message.reaction, kept up to date by ReactionManager
 * (reaction_pill_background.xml: white, a thin border, round ends).
 */
@Composable
internal fun ReactionPill(caption: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White)
            .border(1.dp, Color(0x22000000), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 28.dp)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(caption, color = Color.Black, fontSize = 15.sp, maxLines = 1)
    }
}

/**
 * Every emoji, eight to a row: EmojiPickerBottomSheet with the App's list (tools/EmojiUtils.kt).
 * The PC leaves out the ones Windows cannot draw, which would otherwise show as empty boxes.
 */
@Composable
internal fun EmojiPickerDialog(onPick: (String) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        properties = StaysOpen,
        title = { Text(Strings.SELECT_AN_EMOJI) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.width(400.dp).heightIn(max = 400.dp)
            ) {
                items(Emojis.all) { emoji ->
                    Box(
                        modifier = Modifier.clip(CircleShape).clickable { onPick(emoji) }.padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, fontSize = 24.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onClose) { Text(Strings.CANCEL) }
        }
    )
}

/** The App's EmojiUtils.getAllEmojis, made once. */
private object Emojis {
    val all: List<String> by lazy {
        val ranges = listOf(
            0x1F600..0x1F64F, // Emoticons
            0x1F300..0x1F5FF, // Misc Symbols and Pictographs
            0x1F680..0x1F6FF, // Transport and Map Symbols
            0x1F900..0x1F9FF, // Supplemental Symbols and Pictographs
            0x1FA70..0x1FAFF, // Symbols and Pictographs Extended-A
            0x2600..0x26FF,   // Miscellaneous Symbols
            0x2700..0x27BF    // Dingbats
        )
        // The emoji font of Windows. When it is not there, nothing is filtered.
        val font = java.awt.Font("Segoe UI Emoji", java.awt.Font.PLAIN, 12)
        val canCheck = font.family == "Segoe UI Emoji"
        ranges.flatMap { it }
            .filter { Character.isValidCodePoint(it) && (!canCheck || font.canDisplay(it)) }
            .map { String(Character.toChars(it)) }
            .distinct()
    }
}

/**
 * Who reacted to a message, and with what: ReactionsBottomSheet. A tab for all of them and
 * one for each emoji, most used first; the user's own line says "Tap to remove" and does it.
 */
@Composable
internal fun ReactionsDialog(state: AppState, messageId: String, onClose: () -> Unit) {
    val rows by produceState<List<ReactionRow>?>(null, messageId) {
        value = withContext(Dispatchers.IO) { state.reactionRowsOf(messageId) }
    }
    var filter by remember(messageId) { mutableStateOf<String?>(null) }

    val current = rows
    // Nobody has reacted any more: nothing to show, as the phone closes its sheet.
    if (current != null && current.isEmpty()) {
        LaunchedEffect(messageId) { onClose() }
        return
    }

    // groupByEmoji: most popular first, ties to whoever reacted first (rows are oldest first).
    val groups = current.orEmpty()
        .withIndex()
        .groupBy { it.value.emoji }
        .map { (emoji, list) -> Triple(emoji, list.size, list.first().index) }
        .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })

    AlertDialog(
        onDismissRequest = onClose,
        properties = StaysOpen,
        title = { Text(Strings.REACTIONS, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.width(360.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        ReactionTab(Strings.reactionsAll(current?.size ?: 0), selected = filter == null) { filter = null }
                    }
                    items(groups, key = { it.first }) { (emoji, count, _) ->
                        ReactionTab("$emoji $count", selected = filter == emoji) { filter = emoji }
                    }
                }
                Spacer(Modifier.size(8.dp))
                val shown = current.orEmpty().filter { filter == null || it.emoji == filter }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(shown, key = { it.userId }) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .then(
                                    if (row.isMe) Modifier.clickable {
                                        state.removeMyReaction(messageId)
                                        onClose()
                                    } else Modifier
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(row.picture, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (row.isMe) {
                                    Text(Strings.TAP_TO_REMOVE, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(row.emoji, fontSize = 20.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onClose) { Text(Strings.CLOSE) }
        }
    )
}

/** One tab of the list of who reacted: "All 3", "<emoji> 2". The chosen one is underlined in colour. */
@Composable
private fun ReactionTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colour = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = colour, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Spacer(Modifier.size(4.dp))
        Box(
            modifier = Modifier
                .width(24.dp)
                .size(width = 24.dp, height = 2.dp)
                .background(if (selected) colour else Color.Transparent)
        )
    }
}
