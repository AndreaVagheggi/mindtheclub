package com.bolimot.mindtheclub.sharing

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.chat.SendGif
import com.bolimot.mindtheclub.chat.SendImage
import com.bolimot.mindtheclub.chat.SendVideo
import com.bolimot.mindtheclub.functions.getPeerRepository
import com.bolimot.mindtheclub.sending.sendObject
import com.bolimot.mindtheclub.sending.sendWeb
import com.bolimot.mindtheclub.tools.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun incomingSharedContent(type: String?, uri: String?, text: String?, selectedPeers: List<String>, context: Context){
    when(type){
        Type.AUDIO -> {
            if(!uri.isNullOrEmpty()) {
                if (context is ComponentActivity) {
                    context.lifecycleScope.launch(Dispatchers.IO) {
                        sendObject(
                            selectedPeers,
                            uri,
                            "",
                            "",
                            "",
                            context.lifecycleScope,
                            null,
                            Type.AUDIO
                        )
                    }
                }
            }
        }
        Type.IMAGE, Type.STICKER -> {
            if(!uri.isNullOrEmpty()) {
                val intent = Intent(context, SendImage::class.java).apply {
                    putExtra("imagePath", uri)
                    putExtra("userId", selectedPeers.joinToString(","))
                }
                context.startActivity(intent)
            }
        }
        Type.VIDEO -> {
            if(!uri.isNullOrEmpty()) {
                val intent = Intent(context, SendVideo::class.java).apply {
                    putExtra("imagePath", uri)
                    putExtra("userId", selectedPeers.joinToString(","))
                }
                context.startActivity(intent)
            }
        }
        Type.WEB, Type.TEXT-> {
            if(!text.isNullOrEmpty()) {
                if(selectedPeers.count() > 1) {
                    WebPreviewCache.clear()  // won't need cache for multi-peer path
                    CoroutineScope(Dispatchers.IO).launch {
                        sendWeb(selectedPeers, text)
                    }
                } else {
                    CoroutineScope(Dispatchers.IO).launch {
                        val toUserId = selectedPeers.firstOrNull() ?: return@launch
                        val peerRepository = getPeerRepository(context)
                        val peer = peerRepository.getPeer(toUserId) ?: return@launch
                        val intent = Intent(context, ChatScreen::class.java).apply {
                            putExtra("userId", peer.userId)
                            putExtra("name", peer.name)
                            putExtra("bio", peer.bio)
                            putExtra("picture", peer.picture)
                            putExtra("text", text)

                        }
                        context.startActivity(intent)
                    }
                }
            }
        }
        Type.GIF -> {
            if(!uri.isNullOrEmpty()) {
                val intent = Intent(context, SendGif::class.java).apply {
                    putExtra("gifPath", uri)
                    putExtra("userId", selectedPeers.joinToString(","))
                }
                context.startActivity(intent)
            }
        }
    }
}