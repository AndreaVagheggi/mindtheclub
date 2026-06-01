package com.bolimot.mindtheclub.adapters

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.message.MessageRepository
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.isDifferentDay
import com.bolimot.mindtheclub.functions.isFileType
import com.bolimot.mindtheclub.functions.isGroupChat
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.viewHolders.chat.AudioViewHolder
import com.bolimot.mindtheclub.viewHolders.chat.DoublePictureViewHolder
import com.bolimot.mindtheclub.viewHolders.chat.FileViewHolder
import com.bolimot.mindtheclub.viewHolders.chat.MissedCallViewHolder
import com.bolimot.mindtheclub.viewHolders.chat.MultiPictureViewHolder
import com.bolimot.mindtheclub.viewHolders.chat.PictureViewHolder
import com.bolimot.mindtheclub.viewHolders.chat.ProfileViewHolder
import com.bolimot.mindtheclub.viewHolders.chat.ReceivingViewHolder
import com.bolimot.mindtheclub.viewHolders.chat.StickerViewHolder
import com.bolimot.mindtheclub.viewHolders.chat.TextImageViewHolder
import com.bolimot.mindtheclub.viewHolders.chat.TextViewHolder
import com.bolimot.mindtheclub.viewHolders.chat.VideoViewHolder
import com.bolimot.mindtheclub.viewHolders.chat.WebViewHolder
import com.bolimot.mindtheclub.viewHolders.groupChat.GroupAudioViewHolder
import com.bolimot.mindtheclub.viewHolders.groupChat.GroupDoublePictureViewHolder
import com.bolimot.mindtheclub.viewHolders.groupChat.GroupFileViewHolder
import com.bolimot.mindtheclub.viewHolders.groupChat.GroupMultiPictureViewHolder
import com.bolimot.mindtheclub.viewHolders.groupChat.GroupPictureViewHolder
import com.bolimot.mindtheclub.viewHolders.groupChat.GroupProfileViewHolder
import com.bolimot.mindtheclub.viewHolders.groupChat.GroupStickerViewHolder
import com.bolimot.mindtheclub.viewHolders.groupChat.GroupTextImageViewHolder
import com.bolimot.mindtheclub.viewHolders.groupChat.GroupTextViewHolder
import com.bolimot.mindtheclub.viewHolders.groupChat.GroupVideoViewHolder
import com.bolimot.mindtheclub.viewHolders.groupChat.GroupWebViewHolder
import kotlinx.coroutines.launch

class MessagesAdapter(private val listener: OnItemClickListener,
                      private val lifecycleOwner: LifecycleOwner,
                      private val repository: MessageRepository
) : PagingDataAdapter<Message, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    interface OnItemClickListener {
        fun onItemClick(message: Message)
        fun onItemLongClick(message: Message, anchorView: View, displayHeader: Boolean): Boolean
        fun isAnyMessageSelected(): Boolean
        fun onSwipeToReply(message: Message)
        fun onSwipeToDismissPlaceholder(message: Message)
        fun onViewProfileClick(message: Message)
        fun onAddContactClick(message: Message)
    }

    private var selectedMessages = mutableListOf<Array<String>>()

    override fun getItemViewType(position: Int): Int {
        try {
            val message = getItem(position) ?: return TYPE_UNKNOWN

            if (message.status == Status.RECEIVING) {
                return if (!isGroupChat(message)) TYPE_RECEIVING_IN else TYPE_RECEIVING_GROUP
            }

            val incomingMessage = message.toUserId == MySelf.userId()

            return when (message.type) {
                Type.IMAGE -> if (incomingMessage) {
                    if(!isGroupChat(message)) TYPE_IMAGE_IN else TYPE_IMAGE_GROUP
                } else TYPE_IMAGE_OUT

                Type.TEXT -> {
                    if(!isTextImage(message)) {
                        if (incomingMessage) {
                            if(!isGroupChat(message)) TYPE_TEXT_IN else TYPE_TEXT_GROUP
                        } else TYPE_TEXT_OUT
                    } else {
                        if (incomingMessage) {
                            if(!isGroupChat(message)) TYPE_TEXT_IMAGE_IN else TYPE_TEXT_IMAGE_GROUP
                        } else TYPE_TEXT_IMAGE_OUT
                    }
                }

                Type.VIDEO -> if (incomingMessage) {
                    if(!isGroupChat(message)) TYPE_VIDEO_IN else TYPE_VIDEO_GROUP
                } else TYPE_VIDEO_OUT

                Type.STICKER -> if (incomingMessage) {
                    if(!isGroupChat(message)) TYPE_STICKER_IN else TYPE_STICKER_GROUP
                } else TYPE_STICKER_OUT

                Type.GIF -> if (incomingMessage) {
                    if(!isGroupChat(message)) TYPE_GIF_IN else TYPE_GIF_GROUP
                } else TYPE_GIF_OUT

                Type.WEB -> if (incomingMessage) {
                    if(!isGroupChat(message)) TYPE_WEB_IN else TYPE_WEB_GROUP
                } else TYPE_WEB_OUT

                Type.AUDIO -> if (incomingMessage) {
                    if(!isGroupChat(message)) TYPE_AUDIO_IN else TYPE_AUDIO_GROUP
                } else TYPE_AUDIO_OUT

                Type.CONTACT -> if (incomingMessage) {
                    if(!isGroupChat(message)) TYPE_CONTACT_IN else TYPE_CONTACT_GROUP
                } else TYPE_CONTACT_OUT

                Type.MULTIPLE_IMAGES -> getMultipleType(incomingMessage, calculateGroupSize(message), message)

                Type.MISSED_CALL -> TYPE_MISSED_CALL

                else -> {
                    if(isFileType(message.type)) {
                        if (incomingMessage) {
                            if(!isGroupChat(message)) TYPE_FILE_IN else TYPE_FILE_GROUP
                        } else TYPE_FILE_OUT
                    } else {
                        TYPE_UNKNOWN
                    }
                }
            }
        } catch (e: Exception) {
            debugLine("getItemViewType", "Exception: ${e.message}")
            return TYPE_UNKNOWN
        }
    }

    private fun isTextImage(message: Message): Boolean{
        return !message.replyId.isNullOrEmpty() && message.uri.isNotEmpty()
    }

    private fun inflateView(parent: ViewGroup, layoutId: Int): View {
        return LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
    }

    private fun calculateGroupSize(message: Message): Int {
        val uriList = message.uri.split(",")
        return uriList.size
    }

    private fun getMultipleType(incomingMessage: Boolean, groupSize: Int, message: Message): Int {
        return if(incomingMessage){
            when (groupSize) {
                2 -> if(!isGroupChat(message)) TYPE_DOUBLE_IMAGES_IN else TYPE_DOUBLE_IMAGES_GROUP
                else -> if(!isGroupChat(message)) TYPE_MULTIPLE_IMAGES_IN else TYPE_MULTIPLE_IMAGES_GROUP
            }
        } else {
            when (groupSize) {
                2 -> TYPE_DOUBLE_IMAGES_OUT
                else -> TYPE_MULTIPLE_IMAGES_OUT
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_VIDEO_OUT -> VideoViewHolder(inflateView(parent, R.layout.chat_video_out), listener)
            TYPE_VIDEO_IN -> VideoViewHolder(inflateView(parent, R.layout.chat_video_in), listener)
            TYPE_TEXT_OUT -> TextViewHolder(inflateView(parent, R.layout.chat_text_out), listener)
            TYPE_TEXT_IN -> TextViewHolder(inflateView(parent, R.layout.chat_text_in), listener)
            TYPE_TEXT_IMAGE_OUT -> TextImageViewHolder(inflateView(parent, R.layout.chat_text_image_out), listener)
            TYPE_TEXT_IMAGE_IN -> TextImageViewHolder(inflateView(parent, R.layout.chat_text_image_in), listener)
            TYPE_IMAGE_OUT -> PictureViewHolder(inflateView(parent, R.layout.chat_image_out), listener)
            TYPE_IMAGE_IN -> PictureViewHolder(inflateView(parent, R.layout.chat_image_in), listener)
            TYPE_MULTIPLE_IMAGES_OUT -> MultiPictureViewHolder(inflateView(parent, R.layout.chat_multiple_images_out), listener)
            TYPE_MULTIPLE_IMAGES_IN -> MultiPictureViewHolder(inflateView(parent, R.layout.chat_multiple_images_in), listener)
            TYPE_DOUBLE_IMAGES_OUT -> DoublePictureViewHolder(inflateView(parent, R.layout.chat_double_images_out), listener)
            TYPE_DOUBLE_IMAGES_IN -> DoublePictureViewHolder(inflateView(parent, R.layout.chat_double_images_in), listener)
            TYPE_STICKER_OUT -> StickerViewHolder(inflateView(parent, R.layout.chat_sticker_out), listener)
            TYPE_STICKER_IN -> StickerViewHolder(inflateView(parent, R.layout.chat_sticker_in), listener)
            TYPE_GIF_OUT -> PictureViewHolder(inflateView(parent, R.layout.chat_image_out), listener)
            TYPE_GIF_IN -> PictureViewHolder(inflateView(parent, R.layout.chat_image_in), listener)
            TYPE_WEB_OUT -> WebViewHolder(inflateView(parent, R.layout.chat_web_out), listener)
            TYPE_WEB_IN -> WebViewHolder(inflateView(parent, R.layout.chat_web_in), listener)
            TYPE_AUDIO_OUT -> AudioViewHolder(inflateView(parent, R.layout.chat_audio_out), listener)
            TYPE_AUDIO_IN -> AudioViewHolder(inflateView(parent, R.layout.chat_audio_in), listener)
            TYPE_FILE_OUT -> FileViewHolder(inflateView(parent, R.layout.chat_file_out), listener)
            TYPE_FILE_IN -> FileViewHolder(inflateView(parent, R.layout.chat_file_in), listener)
            TYPE_CONTACT_OUT -> ProfileViewHolder(inflateView(parent, R.layout.chat_profile_out), listener)
            TYPE_CONTACT_IN -> ProfileViewHolder(inflateView(parent, R.layout.chat_profile_in), listener)
            TYPE_MISSED_CALL -> MissedCallViewHolder(inflateView(parent, R.layout.missed_call), listener)
            TYPE_VIDEO_GROUP -> GroupVideoViewHolder(inflateView(parent, R.layout.group_video_in), listener)
            TYPE_TEXT_GROUP -> GroupTextViewHolder(inflateView(parent, R.layout.group_text_in), listener)
            TYPE_TEXT_IMAGE_GROUP -> GroupTextImageViewHolder(inflateView(parent, R.layout.group_text_image_in), listener)
            TYPE_IMAGE_GROUP -> GroupPictureViewHolder(inflateView(parent, R.layout.group_image_in), listener)
            TYPE_MULTIPLE_IMAGES_GROUP -> GroupMultiPictureViewHolder(inflateView(parent, R.layout.group_multiple_images_in), listener)
            TYPE_DOUBLE_IMAGES_GROUP -> GroupDoublePictureViewHolder(inflateView(parent, R.layout.group_double_images_in), listener)
            TYPE_STICKER_GROUP -> GroupStickerViewHolder(inflateView(parent, R.layout.group_sticker_in), listener)
            TYPE_GIF_GROUP -> GroupPictureViewHolder(inflateView(parent, R.layout.group_image_in), listener)
            TYPE_WEB_GROUP -> GroupWebViewHolder(inflateView(parent, R.layout.group_web_in), listener)
            TYPE_AUDIO_GROUP -> GroupAudioViewHolder(inflateView(parent, R.layout.group_audio_in), listener)
            TYPE_FILE_GROUP -> GroupFileViewHolder(inflateView(parent, R.layout.group_file_in), listener)
            TYPE_CONTACT_GROUP -> GroupProfileViewHolder(inflateView(parent, R.layout.group_profile_in), listener)
            TYPE_RECEIVING_IN -> ReceivingViewHolder(inflateView(parent, R.layout.chat_receiving_in))
            TYPE_RECEIVING_GROUP -> ReceivingViewHolder(inflateView(parent, R.layout.chat_receiving_in))

            else -> return EmptyViewHolder(View(parent.context))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty()) {
            val resolvedPayloads = payloads.flatMap {
                when (it) {
                    is List<*> -> it.filterIsInstance<String>()
                    is String -> listOf(it)
                    else -> emptyList()
                }
            }.toSet()
            for (payload in resolvedPayloads) {
                when (payload) {
                    PAYLOAD_STATUS -> {
                        when (holder) {
                            is VideoViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is TextViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is TextImageViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is PictureViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is MultiPictureViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is DoublePictureViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is StickerViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is WebViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is AudioViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is FileViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is ProfileViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is GroupVideoViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is GroupTextViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is GroupTextImageViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is GroupPictureViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is GroupMultiPictureViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is GroupDoublePictureViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is GroupStickerViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is GroupWebViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is GroupAudioViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is GroupFileViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                            is GroupProfileViewHolder -> holder.updateStatus(getItem(position)?.status ?: "")
                        }
                    }
                    PAYLOAD_REACTION -> {
                        when (holder) {
                            is VideoViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is TextViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is TextImageViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is PictureViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is MultiPictureViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is DoublePictureViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is StickerViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is WebViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is AudioViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is FileViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is ProfileViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is GroupVideoViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is GroupTextViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is GroupTextImageViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is GroupPictureViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is GroupMultiPictureViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is GroupDoublePictureViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is GroupStickerViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is GroupWebViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is GroupAudioViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is GroupFileViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                            is GroupProfileViewHolder -> holder.updateReaction(getItem(position)?.reaction)
                        }
                    }
                    PAYLOAD_SELECTION -> {
                        val message = getItem(position) ?: return
                        val isSelected = selectedMessages.any { it[0] == message.messageId }
                        val selectedColor = ContextCompat.getColor(holder.itemView.context, R.color.mtc_transparent)
                        holder.itemView.findViewById<View>(R.id.selectable_area)
                            ?.setBackgroundColor(if (isSelected) selectedColor else Color.TRANSPARENT)
                    }
                }
            }
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val displayDateHeader: Boolean
        try {
            if (position < itemCount) {
                val message = getItem(position)
                if (message != null) {
                    if (position < itemCount - 1) {
                        val nextMessage = getItem(position + 1)
                        displayDateHeader = isDifferentDay(message.date, nextMessage?.date)
                    } else {
                        displayDateHeader = true
                    }

                    val showMemberHeader = if (position < itemCount - 1) {
                        val prevMessage = getItem(position + 1)
                        displayDateHeader || prevMessage?.originalSenderId != message.originalSenderId
                    } else {
                        true
                    }

                    val isSelected = selectedMessages.any { it[0] == message.messageId }

                    when (holder) {
                        is TextViewHolder -> holder.bind(message, displayDateHeader, isSelected, message.subType)
                        is TextImageViewHolder -> holder.bind(message, displayDateHeader, isSelected, message.subType)
                        is PictureViewHolder -> holder.bind(message, displayDateHeader, isSelected, message.subType)
                        is VideoViewHolder -> holder.bind(message, displayDateHeader, isSelected, message.subType)
                        is FileViewHolder -> holder.bind(message, displayDateHeader, isSelected, message.subType)
                        is MultiPictureViewHolder -> holder.bind(message, displayDateHeader, isSelected, message.subType)
                        is DoublePictureViewHolder -> holder.bind(message, displayDateHeader, isSelected, message.subType)
                        is StickerViewHolder -> holder.bind(message, displayDateHeader, isSelected)
                        is WebViewHolder -> holder.bind(message, displayDateHeader, isSelected, message.subType)
                        is MissedCallViewHolder -> holder.bind(message, displayDateHeader, isSelected)
                        is ProfileViewHolder -> holder.bind(message, displayDateHeader, isSelected, message.subType)
                        is ReceivingViewHolder -> holder.bind(message, displayDateHeader)

                        is GroupTextViewHolder -> {
                            lifecycleOwner.lifecycleScope.launch {
                                val peerName = repository.getPeerName(message.messageId)
                                    ?: holder.itemView.context.getString(R.string.member)
                                val peerPicture = repository.getPeerPicture(message.messageId)

                                holder.bind(message, displayDateHeader, isSelected, message.subType, peerName, peerPicture, showMemberHeader)
                            }
                        }
                        is GroupTextImageViewHolder -> {
                            lifecycleOwner.lifecycleScope.launch {
                                val peerName = repository.getPeerName(message.messageId)
                                    ?: holder.itemView.context.getString(R.string.member)
                                val peerPicture = repository.getPeerPicture(message.messageId)

                                holder.bind(message, displayDateHeader, isSelected, message.subType, peerName, peerPicture, showMemberHeader)
                            }
                        }
                        is GroupPictureViewHolder -> {
                            lifecycleOwner.lifecycleScope.launch {
                                val peerName = repository.getPeerName(message.messageId)
                                    ?: holder.itemView.context.getString(R.string.member)
                                val peerPicture = repository.getPeerPicture(message.messageId)

                                holder.bind(message, displayDateHeader, isSelected, message.subType, peerName, peerPicture, showMemberHeader)
                            }
                        }
                        is GroupVideoViewHolder -> {
                            lifecycleOwner.lifecycleScope.launch {
                                val peerName = repository.getPeerName(message.messageId)
                                    ?: holder.itemView.context.getString(R.string.member)
                                val peerPicture = repository.getPeerPicture(message.messageId)

                                holder.bind(message, displayDateHeader, isSelected, message.subType, peerName, peerPicture, showMemberHeader)
                            }
                        }
                        is GroupFileViewHolder -> {
                            lifecycleOwner.lifecycleScope.launch {
                                val peerName = repository.getPeerName(message.messageId)
                                    ?: holder.itemView.context.getString(R.string.member)
                                val peerPicture = repository.getPeerPicture(message.messageId)

                                holder.bind(message, displayDateHeader, isSelected, message.subType, peerName, peerPicture, showMemberHeader)
                            }
                        }
                        is GroupMultiPictureViewHolder -> {
                            lifecycleOwner.lifecycleScope.launch {
                                val peerName = repository.getPeerName(message.messageId)
                                    ?: holder.itemView.context.getString(R.string.member)
                                val peerPicture = repository.getPeerPicture(message.messageId)

                                holder.bind(message, displayDateHeader, isSelected, message.subType, peerName, peerPicture, showMemberHeader)
                            }
                        }
                        is GroupDoublePictureViewHolder -> {
                            lifecycleOwner.lifecycleScope.launch {
                                val peerName = repository.getPeerName(message.messageId)
                                    ?: holder.itemView.context.getString(R.string.member)
                                val peerPicture = repository.getPeerPicture(message.messageId)

                                holder.bind(message, displayDateHeader, isSelected, message.subType, peerName, peerPicture, showMemberHeader)
                            }
                        }
                        is GroupStickerViewHolder -> {
                            lifecycleOwner.lifecycleScope.launch {
                                val peerName = repository.getPeerName(message.messageId)
                                    ?: holder.itemView.context.getString(R.string.member)
                                val peerPicture = repository.getPeerPicture(message.messageId)

                                holder.bind(message, displayDateHeader, isSelected, peerName, peerPicture, showMemberHeader)
                            }
                        }
                        is GroupWebViewHolder -> {
                            lifecycleOwner.lifecycleScope.launch {
                                val peerName = repository.getPeerName(message.messageId)
                                    ?: holder.itemView.context.getString(R.string.member)
                                val peerPicture = repository.getPeerPicture(message.messageId)

                                holder.bind(message, displayDateHeader, isSelected, message.subType, peerName, peerPicture, showMemberHeader)
                            }
                        }
                        is GroupProfileViewHolder -> {
                            lifecycleOwner.lifecycleScope.launch {
                                val peerName = repository.getPeerName(message.messageId)
                                    ?: holder.itemView.context.getString(R.string.member)
                                val peerPicture = repository.getPeerPicture(message.messageId)

                                holder.bind(message, displayDateHeader, isSelected, message.subType, peerName, peerPicture, showMemberHeader)
                            }
                        }

                        is GroupAudioViewHolder -> {
                            lifecycleOwner.lifecycleScope.launch {
                                val peerName = repository.getPeerName(message.messageId)
                                    ?: holder.itemView.context.getString(R.string.member)
                                val peerPicture = repository.getPeerPicture(message.messageId)

                                holder.bind(
                                    message,
                                    displayDateHeader,
                                    isSelected,
                                    peerName,
                                    peerPicture
                                )
                            }
                        }

                        is AudioViewHolder -> {
                            lifecycleOwner.lifecycleScope.launch {

                                val picUri = repository.getProfilePic(message.messageId)
                                holder.bind(message, displayDateHeader, isSelected, picUri)
                            }
                        }
                    }
                } else {
                    debugLine("onBindViewHolder", "Message is null at position: $position")
                }
            } else {
                debugLine("onBindViewHolder", "Invalid position: $position")
            }
        } catch (e: Exception) {
            debugLine("onBindViewHolder", "Exception: ${e.message}")
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is TextViewHolder) {
            holder.onViewRecycled()
        }

        if (holder is GroupTextViewHolder) {
            holder.onViewRecycled()
        }

        if (holder is AudioViewHolder) {
            holder.releaseResources()
        }

        super.onViewRecycled(holder)
    }

    suspend fun getPositionByMessageId(messageId: String, remoteUserId: String, context: Context): Int {
        var index = snapshot().indexOfFirst { it?.messageId == messageId }
        if(index !=-1) return index

        val originalMessage = getMessageDao(context).getMessage(messageId)
        val originalMessageId = originalMessage?.messageId ?: return RecyclerView.NO_POSITION
        index = getMessageDao(context).getMessageGlobalPosition(MySelf.userId()!!, remoteUserId, originalMessageId)
        return if (index > 0 ) index else RecyclerView.NO_POSITION
    }

    fun updateMessageStatus(messageId: String, newStatus: String) {
        val index = snapshot().indexOfFirst { it?.messageId == messageId }
        if (index != -1) {
            getItem(index)?.status = newStatus
            notifyItemChanged(index, PAYLOAD_STATUS)
        }
    }

    fun updateMessageReaction(messageId: String, emoji: String){
        val index = snapshot().indexOfFirst { it?.messageId == messageId }
        if (index != -1) {
            getItem(index)?.reaction = emoji
            notifyItemChanged(index, PAYLOAD_REACTION)
        }
    }

    fun toggleSelection(messageId: String) {
        val position = snapshot().indexOfFirst { it?.messageId == messageId }
        if (position != -1) {
            val message = getItem(position)
            if (message != null) {
                val selectedMessage = arrayOf(messageId, message.type, message.uri)
                val existingIndex = selectedMessages.indexOfFirst { it[0] == messageId }
                if (existingIndex != -1) {
                    selectedMessages.removeAt(existingIndex)
                } else {
                    selectedMessages.add(selectedMessage)
                }
                notifyItemChanged(position, PAYLOAD_SELECTION)
            }
        }
    }

    fun isAnyMessageSelected(): Boolean {
        return selectedMessages.isNotEmpty()
    }

    fun clearSelectionStateOnly() {
        selectedMessages.clear()
    }

    fun removeSelection() {
        val currentItems = snapshot()

        val positionsToUpdate = getSelectedMessagesIds().mapNotNull { id ->
            currentItems.indexOfFirst { it?.messageId == id }.takeIf { it != -1 }
        }

        selectedMessages.clear()

        positionsToUpdate.forEach { position ->
            notifyItemChanged(position)
        }
    }

    fun refreshAdapter(){
        this.refresh()
    }

    fun getSelectedMessagesIds(): List<String> {
        return selectedMessages.map { it[0] }
    }

    fun getSelectedMessagesTypes(): List<String> {
        return selectedMessages.map { it[1] }
    }

    fun getSelectedMessagesUris(): List<String> {
        return selectedMessages.map { it[2] }
    }

    companion object {
        private const val TYPE_UNKNOWN = 0
        private const val TYPE_TEXT_IN = 1
        private const val TYPE_TEXT_OUT = 2
        private const val TYPE_IMAGE_IN = 3
        private const val TYPE_IMAGE_OUT = 4
        private const val TYPE_MULTIPLE_IMAGES_IN = 5
        private const val TYPE_MULTIPLE_IMAGES_OUT = 6
        private const val TYPE_DOUBLE_IMAGES_IN = 7
        private const val TYPE_DOUBLE_IMAGES_OUT = 8
        private const val TYPE_VIDEO_IN = 9
        private const val TYPE_VIDEO_OUT = 10
        private const val TYPE_STICKER_IN = 11
        private const val TYPE_STICKER_OUT = 12
        private const val TYPE_GIF_IN = 13
        private const val TYPE_GIF_OUT = 14
        private const val TYPE_WEB_IN = 15
        private const val TYPE_WEB_OUT = 16
        private const val TYPE_AUDIO_IN = 17
        private const val TYPE_AUDIO_OUT = 18
        private const val TYPE_FILE_IN = 19
        private const val TYPE_FILE_OUT = 20
        private const val TYPE_MISSED_CALL = 21
        private const val TYPE_CONTACT_IN = 22
        private const val TYPE_CONTACT_OUT = 23
        private const val TYPE_TEXT_IMAGE_IN = 24
        private const val TYPE_TEXT_IMAGE_OUT = 25
        private const val TYPE_TEXT_GROUP = 26
        private const val TYPE_IMAGE_GROUP = 27
        private const val TYPE_MULTIPLE_IMAGES_GROUP = 28
        private const val TYPE_DOUBLE_IMAGES_GROUP = 29
        private const val TYPE_VIDEO_GROUP = 30
        private const val TYPE_STICKER_GROUP = 31
        private const val TYPE_GIF_GROUP = 32
        private const val TYPE_WEB_GROUP = 33
        private const val TYPE_AUDIO_GROUP = 34
        private const val TYPE_FILE_GROUP = 35
        private const val TYPE_CONTACT_GROUP = 36
        private const val TYPE_TEXT_IMAGE_GROUP = 37
        private const val PAYLOAD_STATUS = "payload_status"
        private const val PAYLOAD_REACTION = "payload_reaction"
        private const val PAYLOAD_SELECTION = "payload_selection"
        private const val TYPE_RECEIVING_IN = 38
        private const val TYPE_RECEIVING_GROUP = 39

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean = oldItem.uid == newItem.uid
            override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean = oldItem == newItem

            override fun getChangePayload(oldItem: Message, newItem: Message): Any? {
                val changes = mutableListOf<String>()
                if (oldItem.status != newItem.status) changes.add(PAYLOAD_STATUS)
                if (oldItem.reaction != newItem.reaction) changes.add(PAYLOAD_REACTION)
                return changes.ifEmpty { super.getChangePayload(oldItem, newItem) }
            }
        }
    }

    class EmptyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    fun attachSwipeToReply(recyclerView: RecyclerView) {
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val position = viewHolder.bindingAdapterPosition

                if (position == RecyclerView.NO_POSITION) {
                    return makeMovementFlags(0, 0)
                }

                val message = getItem(position)

                return if (message?.type == Type.MISSED_CALL) {
                    makeMovementFlags(0, 0)
                } else {
                    val swipeFlags = ItemTouchHelper.RIGHT
                    makeMovementFlags(0, swipeFlags)
                }
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                return 0.5f
            }

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
                return defaultValue * 3f
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val maxSwipePx = recyclerView.context.resources.displayMetrics.density * 100f
                val effectiveDx = if (isCurrentlyActive) dX.coerceAtMost(maxSwipePx) else dX

                super.onChildDraw(c, recyclerView, viewHolder, effectiveDx, dY, actionState, isCurrentlyActive)

                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && effectiveDx > 0) {
                    val itemView = viewHolder.itemView

                    val paint = Paint()
                    paint.color = Color.TRANSPARENT
                    c.drawRect(
                        itemView.left.toFloat(),
                        itemView.top.toFloat(),
                        itemView.left + effectiveDx,
                        itemView.bottom.toFloat(),
                        paint
                    )

                    val position = viewHolder.bindingAdapterPosition
                    val message = if (position != RecyclerView.NO_POSITION) getItem(position) else null
                    val isReceiving = message?.status == Status.RECEIVING

                    val iconRes = if (isReceiving) R.drawable.ic_dismiss else R.drawable.reply_chat

                    val icon = ContextCompat.getDrawable(
                        recyclerView.context,
                        iconRes
                    ) ?: return

                    val iconWidth = icon.intrinsicWidth
                    val iconHeight = icon.intrinsicHeight
                    val iconMargin = 24

                    val iconTop = itemView.top + (itemView.height - iconHeight) / 2
                    val iconLeft = itemView.left + iconMargin
                    val iconRight = iconLeft + iconWidth
                    val iconBottom = iconTop + iconHeight

                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    icon.draw(c)
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (direction == ItemTouchHelper.RIGHT) {
                    val position = viewHolder.bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        val message = getItem(position)
                        if (message != null) {
                            if (message.status == Status.RECEIVING) {
                                listener.onSwipeToDismissPlaceholder(message)
                            } else {
                                listener.onSwipeToReply(message)
                            }
                        }
                        notifyItemChanged(position)
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.apply {
                    translationX = 0f
                    translationY = 0f
                    alpha = 1f
                }
            }

            override fun getAnimationDuration(
                recyclerView: RecyclerView,
                animationType: Int,
                animateDx: Float,
                animateDy: Float
            ): Long {
                return when (animationType) {
                    ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL -> 200L
                    else -> super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
                }
            }
        }

        ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView)
    }
}


