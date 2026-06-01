package com.bolimot.mindtheclub.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import com.bolimot.mindtheclub.functions.getPeerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UnblockPeerDialog : DialogFragment() {

    private var listener: UnblockPeerListener? = null

    companion object {
        private const val ARG_USER_ID = "userId"
        private const val ARG_NAME = "name"

        fun newInstance(userId: String, name: String): UnblockPeerDialog {
            val args = Bundle().apply {
                putString(ARG_USER_ID, userId)
                putString(ARG_NAME, name)
            }
            return UnblockPeerDialog().apply { arguments = args }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val userId = arguments?.getString(ARG_USER_ID) ?: ""
        val name = arguments?.getString(ARG_NAME) ?: ""

        val builder = AlertDialog.Builder(requireActivity())
        val view = requireActivity().layoutInflater.inflate(R.layout.unblock_peer_dialog, null, false)
        builder.setView(view)

        view.findViewById<TextView>(R.id.name).text = name

        builder.setPositiveButton("Yes") { _, _ ->
            val callback = listener
            requireActivity().lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    getBlockedUserRepository(requireContext()).unblockUser(userId)
                    getPeerViewModel().unblockPeer(userId)
                }
                callback?.onPeerUnblocked()
            }
        }

        builder.setNegativeButton("No") { dialog, _ ->
            dialog.dismiss()
        }

        return builder.create()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? UnblockPeerListener
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    interface UnblockPeerListener {
        fun onPeerUnblocked()
    }
}
