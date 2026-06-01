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
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.viewModel.PeerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeletePeerDialog : DialogFragment() {

    private lateinit var peer: Peer
    private lateinit var viewModel: PeerViewModel
    private var listener: DeletePeerListener? = null

    companion object {
        private const val ARG_USER_ID = "userId"
        private const val ARG_NAME = "name"
        private const val ARG_BIO = "bio"
        private const val ARG_PIC = "picture"

        fun newInstance(userId: String, name: String?, bio: String?, picture: String?): DeletePeerDialog {
            val args = Bundle().apply {
                putString(ARG_USER_ID, userId)
                putString(ARG_NAME, name)
                putString(ARG_BIO, bio)
                putString(ARG_PIC, picture)
            }
            return DeletePeerDialog().apply {
                arguments = args
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val userId = arguments?.getString(ARG_USER_ID)
        val name = arguments?.getString(ARG_NAME)
        val bio = arguments?.getString(ARG_BIO)
        val picture = arguments?.getString(ARG_PIC)

        peer = Peer(0,userId!!, name!!, "", bio, picture, "", "")
        viewModel = getPeerViewModel()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let { fragmentActivity ->

            val builder = AlertDialog.Builder(fragmentActivity)
            val inflater = requireActivity().layoutInflater
            val view = inflater.inflate(R.layout.delete_peer_dialog, null, false)
            builder.setView(view)

            val nameView = view.findViewById<TextView>(R.id.name)
            val bioView = view.findViewById<TextView>(R.id.bio)
            val pictureView = view.findViewById<ImageView>(R.id.peerPic)

            nameView.text = peer.name
            bioView.text = peer.bio
            pictureView.setImageURI(peer.picture?.toUri())

            builder.setPositiveButton("Yes") { dialog, _ ->
                val callback = listener

                requireActivity().lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        viewModel.deletePeerByUserId(peer.userId)

                        if(peer.userId.startsWith("group")){
                            debugLine("DeletePeerDialog", "Deleting group: ${peer.name}")
                            viewModel.deleteGroup(peer.userId)
                        }
                    }
                    callback?.onPeerDeleted()
                }
            }

            builder.setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }

            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? DeletePeerListener
    }
    override fun onDetach() {
        super.onDetach()
        listener = null
    }
    interface DeletePeerListener {
        fun onPeerDeleted()
    }
}

