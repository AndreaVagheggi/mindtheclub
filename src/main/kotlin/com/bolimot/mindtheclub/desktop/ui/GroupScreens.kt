package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File

/*
 * Making and running a group, as the phone does it:
 *   - PeersFragment: contacts picked in the list (the right mouse button is the PC's long
 *     press) and the round button at the bottom of the list, fab_add_group, which asks for the
 *     name (dialog_create_group); one group picked alone offers leaving or deleting it (AppTab,
 *     main_group_selected);
 *   - GroupMembersActivity and GroupMembersFragment (members_fragment.xml): the group's picture
 *     with its pencil and its name, editable by an admin, the members with their Admin badge,
 *     and the bottom bar: Add, Remove, Admin, Delete for an admin, Leave Group for a member.
 */

/** @color/mtc_opaque: the bottom bar and the round buttons. */
private val MTC_OPAQUE = Color(0xFF607D9F)

/** @color/mtc_transparent: a picked row. */
internal val PICKED = Color(0x7F607D8B)

/** group_placeholder.xml's background. */
private val GROUP_GREY = Color(0xFF607D8B)

/** A modifier that calls [onPick] on a right mouse button press. */
internal fun Modifier.onRightClick(key: Any, onPick: () -> Unit): Modifier = pointerInput(key) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) onPick()
        }
    }
}

/** A group's picture, or group_placeholder (the group figure on grey) when it has none. */
@Composable
internal fun GroupAvatar(picture: File?, size: Dp) {
    if (picture != null) {
        Avatar(picture, size)
    } else {
        Box(
            modifier = Modifier.size(size).background(GROUP_GREY, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Group, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.7f))
        }
    }
}

/** fab_add_group: the picked contacts become a group. */
@Composable
internal fun NewGroupButton(state: AppState, onAsk: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(56.dp).background(MTC_OPAQUE, CircleShape).clickable {
            val picked = state.pickedChats.filter { !it.startsWith("group") }
            if (picked.size < 2) state.showWarning(Strings.SELECT_GROUP) else onAsk()
        },
        contentAlignment = Alignment.Center
    ) {
        Icon(AppIcons.GroupAdd, contentDescription = Strings.NEW_GROUP, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

/** dialog_create_group: the name, Cancel, Create in red. An empty name says so. */
@Composable
internal fun NewGroupDialog(onCreate: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = StaysOpen,
        title = { Text(Strings.NEW_GROUP) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = false
                },
                label = { Text(Strings.GROUP_NAME) },
                singleLine = true,
                isError = error,
                supportingText = { if (error) Text(Strings.NAME_EMPTY) },
                modifier = Modifier.width(320.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) onCreate(trimmed) else error = true
            }) { Text(Strings.CREATE, color = Color.Red) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(Strings.CANCEL) }
        }
    )
}

/**
 * The top of the contact list while one group is picked alone (AppTab, main_group_selected):
 * Remove group for an admin, Leave Group for a member, each after the phone's question. Empty
 * until Firestore has said which, so an admin is never offered "leave".
 */
@Composable
internal fun PickedGroupBar(state: AppState) {
    val admin = state.pickedGroupAdmin ?: return
    var asking by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().background(MTC_OPAQUE).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { state.clearPicks() }) {
            Icon(AppIcons.Close, contentDescription = Strings.CLOSE, tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { asking = true }) {
            Text(if (admin) Strings.DELETE_GROUP else Strings.LEAVE_GROUP, color = Color.White)
        }
    }
    if (asking) {
        GroupExitDialog(admin, onConfirm = {
            asking = false
            state.exitPickedGroup()
        }, onCancel = { asking = false })
    }
}

/** groupActions.showDeleteGroupDialog and showLeaveGroupDialog. */
@Composable
private fun GroupExitDialog(admin: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = StaysOpen,
        title = { Text(if (admin) Strings.DELETE_GROUP else Strings.LEAVE_GROUP) },
        text = { Text(if (admin) Strings.DELETE_GROUP_CONFIRM else Strings.LEAVE_GROUP_ALERT, modifier = Modifier.width(380.dp)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(if (admin) Strings.DELETE else Strings.LEAVE) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(Strings.CANCEL) }
        }
    )
}

/**
 * GroupMembersActivity, in place of the conversation: back arrow and the group's name on top,
 * then the picture, the name, the members, and the bottom bar.
 */
@Composable
internal fun GroupScreenPane(state: AppState) {
    val screen = state.groupScreen ?: return
    var adding by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    var name by remember(screen.info.groupId, screen.info.name) { mutableStateOf(screen.info.name) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { state.closeGroupScreen() }) {
                Icon(AppIcons.Back, contentDescription = Strings.CLOSE, tint = Color.Black)
            }
            Spacer(Modifier.width(8.dp))
            Text(screen.info.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        GroupAvatar(screen.picture, 140.dp)
                        if (screen.isAdmin) {
                            Box(
                                modifier = Modifier.align(Alignment.BottomEnd).size(44.dp)
                                    .background(MTC_OPAQUE, CircleShape)
                                    .clickable { chooseFile(save = false, suggested = PICTURE_FILES)?.let { state.setGroupPicture(it) } },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(AppIcons.Edit, contentDescription = Strings.CHANGE_PICTURE, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(Strings.GROUP_NAME) },
                        singleLine = true,
                        enabled = screen.isAdmin,
                        // The phone saves on Done: here on Enter.
                        modifier = Modifier.width(360.dp).onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                state.renameGroup(name)
                                true
                            } else {
                                false
                            }
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        Strings.MEMBERS,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                }
            }
            items(screen.members, key = { it.userId }) { member ->
                val picked = member.userId in state.pickedMembers
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (picked) PICKED else Color.Transparent)
                        .onRightClick(member.userId) { state.togglePickMember(member.userId) }
                        .clickable {
                            if (state.pickedMembers.isNotEmpty()) state.togglePickMember(member.userId)
                            else state.showMemberProfile(member)
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(member.picture, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(member.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val bio = member.bio ?: ""
                        if (bio.isNotEmpty()) {
                            Text(bio, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (member.isAdmin) {
                        Text(
                            Strings.ADMIN_LABEL,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.background(MTC_OPAQUE, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // group_members_bottom_menu and group_leave_bottom_menu.
        Row(
            modifier = Modifier.fillMaxWidth().background(MTC_OPAQUE).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (screen.isAdmin) {
                TextButton(onClick = { adding = true }) { Text(Strings.ADD_MEMBER, color = Color.White) }
                TextButton(onClick = { state.removePickedMembers() }) { Text(Strings.REMOVE_MEMBER, color = Color.White) }
                TextButton(onClick = { state.promotePickedMembers() }) { Text(Strings.MAKE_ADMIN, color = Color.White) }
                TextButton(onClick = { exiting = true }) { Text(Strings.DELETE_GROUP_MENU, color = Color.White) }
            } else {
                TextButton(onClick = { exiting = true }) { Text(Strings.LEAVE_GROUP, color = Color.White) }
            }
        }
    }

    if (adding) {
        ContactPickerDialog(
            contacts = state.addableContacts(),
            title = Strings.SELECT_CONTACTS,
            onSend = { userIds ->
                adding = false
                state.addGroupMembers(userIds)
            },
            onClose = { adding = false }
        )
    }
    if (exiting) {
        GroupExitDialog(screen.isAdmin, onConfirm = {
            exiting = false
            state.exitOpenGroup()
        }, onCancel = { exiting = false })
    }
}
