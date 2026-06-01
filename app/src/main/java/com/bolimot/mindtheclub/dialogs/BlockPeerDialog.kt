package com.bolimot.mindtheclub.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import com.bolimot.mindtheclub.functions.getPeerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockPeerDialog : DialogFragment() {

    private var listener: BlockPeerListener? = null

    companion object {
        private const val ARG_USER_ID = "userId"
        private const val ARG_NAME = "name"
        private const val ARG_PICTURE = "picture"

        fun newInstance(userId: String, name: String?, picture: String?): BlockPeerDialog {
            val args = Bundle().apply {
                putString(ARG_USER_ID, userId)
                putString(ARG_NAME, name)
                putString(ARG_PICTURE, picture)
            }
            return BlockPeerDialog().apply { arguments = args }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val userId = arguments?.getString(ARG_USER_ID) ?: ""
        val name = arguments?.getString(ARG_NAME) ?: ""
        val picture = arguments?.getString(ARG_PICTURE)

        val builder = AlertDialog.Builder(requireActivity())
        val view = requireActivity().layoutInflater.inflate(R.layout.block_peer_dialog, null, false)
        builder.setView(view)

        view.findViewById<TextView>(R.id.name).text = name
        picture?.let {
            view.findViewById<ImageView>(R.id.peerPic).setImageURI(it.toUri())
        }

        builder.setPositiveButton("Yes") { _, _ ->
            val callback = listener
            requireActivity().lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    getBlockedUserRepository(requireContext()).blockUser(userId, name)
                    getPeerViewModel().blockPeer(userId)
                }
                callback?.onPeerBlocked()
            }
        }

        builder.setNegativeButton("No") { dialog, _ ->
            dialog.dismiss()
        }

        return builder.create()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? BlockPeerListener
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    interface BlockPeerListener {
        fun onPeerBlocked()
    }
}
