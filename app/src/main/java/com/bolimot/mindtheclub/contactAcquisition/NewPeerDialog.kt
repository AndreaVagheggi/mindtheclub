package com.bolimot.mindtheclub.contactAcquisition

import android.app.Dialog
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.works.AcquireContactWorker
import java.util.concurrent.TimeUnit

class NewPeerDialog : DialogFragment() {
    private val peer: Peer? by lazy {
        val args = arguments ?: return@lazy null
        val userId = args.getString(ARG_USER_ID) ?: return@lazy null
        val name = args.getString(ARG_NAME) ?: ""
        val bio = args.getString(ARG_BIO)

        Peer(0, userId, "", name, bio, null, Contact.NEW, "")
    }

    private val fingerprint: String? by lazy { arguments?.getString(ARG_FINGERPRINT) }

    private val finishOnAccept: Boolean by lazy { arguments?.getBoolean(ARG_FINISH_ON_ACCEPT, true) ?: true }

    private val resultKey: String? by lazy { arguments?.getString(ARG_RESULT_KEY) }

    companion object {
        private const val ARG_USER_ID = "userId"
        private const val ARG_NAME = "name"
        private const val ARG_BIO = "bio"
        private const val ARG_FINGERPRINT = "fingerprint"
        private const val ARG_FINISH_ON_ACCEPT = "finishOnAccept"
        private const val ARG_RESULT_KEY = "resultKey"

        // Opt-in fragment result: hosts that pass this key as resultKey are told when the user
        // accepted, so they can react (leave the chat for the contact list). Hosts that pass no
        // key are not notified at all.
        const val RESULT_CONTACT_ACCEPTED = "newPeerAccepted"

        fun newInstance(
            userId: String,
            name: String,
            bio: String?,
            fingerprint: String? = null,
            finishOnAccept: Boolean = true,
            resultKey: String? = null
        ): NewPeerDialog {
            return NewPeerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_USER_ID, userId)
                    putString(ARG_NAME, name)
                    putString(ARG_BIO, bio)
                    putString(ARG_FINGERPRINT, fingerprint)
                    putBoolean(ARG_FINISH_ON_ACCEPT, finishOnAccept)
                    putString(ARG_RESULT_KEY, resultKey)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val peer = peer
        if (peer == null) {
            dismiss()
            return super.onCreateDialog(savedInstanceState)
        }

        val fragmentActivity = requireActivity()
        val builder = AlertDialog.Builder(fragmentActivity)
        val inflater = fragmentActivity.layoutInflater

        val view = inflater.inflate(R.layout.new_peer_dialog, null, false)
        builder.setView(view)

        val nameView = view.findViewById<TextView>(R.id.name)
        val bioView = view.findViewById<TextView>(R.id.bio)

        nameView.text = peer.name
        bioView.text = peer.bio

        builder.setPositiveButton("Yes") { dialog, _ ->
            debugLine("NewPeerDialog", "Acquiring new peer")

            val inputData = workDataOf(
                "userId" to peer.userId,
                "name" to peer.name,
                "bio" to peer.bio,
                "fingerprint" to fingerprint,
            )

            val workRequest = OneTimeWorkRequestBuilder<AcquireContactWorker>()
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    10, TimeUnit.SECONDS
                ).build()

            WorkManager.getInstance(fragmentActivity.applicationContext)
                .enqueue(workRequest)

            // Notify the host before dismissing, while the fragment is still
            // attached to its manager.
            resultKey?.let { key ->
                parentFragmentManager.setFragmentResult(key, Bundle())
            }

            dialog.dismiss()

            // Share or deep link flow: the activity was opened just for this handoff, so
            // finishing returns the user where they came from. Deferred invite flow (install
            // referrer): the host is the main screen, and finishing would close the whole app
            // right when the profile exchange with the inviter is about to start, quindi si
            // resta aperti.
            if (finishOnAccept) {
                fragmentActivity.finish()
            }
        }
        builder.setNegativeButton("No") { dialog, _ ->
            dialog.dismiss()
        }

        return builder.create()
    }
}
