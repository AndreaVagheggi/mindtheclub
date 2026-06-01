package com.bolimot.mindtheclub.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.EmojiAdapter
import com.bolimot.mindtheclub.tools.EmojiUtils
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class EmojiPickerBottomSheet(
    private val onEmojiSelected: (String) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.bottom_sheet_emoji_picker, container, false)
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewEmojis)
        recyclerView.layoutManager = GridLayoutManager(context, 8)
        recyclerView.adapter = EmojiAdapter(EmojiUtils.getAllEmojis()) { emoji ->
            onEmojiSelected(emoji)
            dismiss()
        }
        return view
    }
}