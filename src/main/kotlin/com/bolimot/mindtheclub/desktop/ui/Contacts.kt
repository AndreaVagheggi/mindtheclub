package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * A contact's profile and the question that adds a contact, as the phone has them.
 *
 *   - ProfileDialog is PeerView (peer_view.xml): the name at the top, the picture large and
 *     round, then the name and the bio, each beside its icon. Opened from a shared contact's
 *     View Profile it shows nothing else, as PeerView does with "fromChat"; opened from the row
 *     of a contact being acquired it also offers "Remove pending contact".
 *   - NewPeerDialog is NewPeerDialog (new_peer_dialog.xml): "Add new contact", the name in bold
 *     with the default picture beside it, the bio, a line, the question, No and Yes.
 *
 * The phone's PeerView for a contact already in the list also has Delete and Block; neither
 * is on the PC yet.
 */

/** PeerView. */
@Composable
internal fun ProfileDialog(profile: ProfileView, onRemovePending: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        properties = StaysOpen,
        title = { Text(profile.name) },
        text = {
            Column(modifier = Modifier.width(360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(18.dp))
                Avatar(profile.picture, 210.dp)
                Spacer(Modifier.height(32.dp))
                ProfileLine(AppIcons.Person, profile.name)
                Spacer(Modifier.height(32.dp))
                ProfileLine(AppIcons.Bio, profile.bio)
                if (profile.pending) {
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onRemovePending,
                        colors = ButtonDefaults.buttonColors(containerColor = MTC_OPAQUE)
                    ) {
                        Icon(AppIcons.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(Strings.REMOVE_PENDING, color = Color.White)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(Strings.CLOSE) }
        }
    )
}

/** One line of a profile: its icon, then its text (peer_view.xml, nameView and name). */
@Composable
private fun ProfileLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color.Black)
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.Black, modifier = Modifier.padding(4.dp))
    }
}

/** NewPeerDialog: Yes hands the contact to AcquireContactWorker, No closes. */
@Composable
internal fun NewPeerDialog(contact: ContactToAdd, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        properties = StaysOpen,
        text = {
            Column(modifier = Modifier.width(320.dp).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.AddContact, contentDescription = null, tint = Color.Black)
                    Spacer(Modifier.width(4.dp))
                    Text(Strings.ADD_NEW_CONTACT, color = Color.Black)
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(contact.name, color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    // The phone's dialog shows its default picture here, not the contact's.
                    Avatar(null, 64.dp)
                }
                val bio = contact.bio ?: ""
                if (bio.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text(bio, color = Color.Black)
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = GREY, modifier = Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(12.dp))
                Text(Strings.ADD_MESSAGE, color = Color.Black)
            }
        },
        confirmButton = {
            TextButton(onClick = onYes) { Text(Strings.YES) }
        },
        dismissButton = {
            TextButton(onClick = onNo) { Text(Strings.NO) }
        }
    )
}

/** @color/mtc_opaque: the phone's bottom bar and floating buttons. */
private val MTC_OPAQUE = Color(0xFF607D9F)

/** @color/grey: the line of new_peer_dialog.xml and the pending dots of peer_item.xml. */
internal val GREY = Color(0xFFBDBDBD)
