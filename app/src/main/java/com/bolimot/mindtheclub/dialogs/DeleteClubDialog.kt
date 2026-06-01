package com.bolimot.mindtheclub.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getClubViewModel
import com.bolimot.mindtheclub.viewModel.ClubViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeleteClubDialog : DialogFragment() {
    private lateinit var clubId: String
    private lateinit var viewModel: ClubViewModel
    private var listener: DeleteClubListener? = null
    private var name: String = ""

    companion object {
        private const val ARG_CLUB_ID = "clubId"
        private const val ARG_CLUB_NAME = "name"

        fun newInstance(clubId: String, name: String): DeleteClubDialog {
            val args = Bundle().apply {
                putString(ARG_CLUB_ID, clubId)
                putString(ARG_CLUB_NAME, name)
            }
            return DeleteClubDialog().apply {
                arguments = args
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clubId = arguments?.getString(ARG_CLUB_ID)!!

        viewModel = getClubViewModel()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let { fragmentActivity ->

            val builder = AlertDialog.Builder(fragmentActivity)
            val inflater = requireActivity().layoutInflater
            val view = inflater.inflate(R.layout.delete_club_dialog, null, false)

            val nameView = view.findViewById<TextView>(R.id.name)
            nameView.text = name

            builder.setView(view)

            builder.setPositiveButton("Yes") { dialog, _ ->
                // STEP 1: Capture the listener locally BEFORE the dialog dismisses/detaches
                // This reference will stay valid inside the coroutine even if 'listener' becomes null
                val callback = activity as? DeleteClubListener

                requireActivity().lifecycleScope.launch {
                    debugLine("DeleteClubDialog", "User has pressed yes")

                    val success = withContext(Dispatchers.IO) {
                        debugLine("DeleteClubDialog", "Deleting club")
                        viewModel.deleteClub(clubId)
                    }

                    debugLine("DeleteClubDialog", "Calling onClubDeleted")

                    // STEP 2: Use the local 'callback' variable, NOT the class property 'listener'
                    if (success) {
                        callback?.onClubDeleted()
                    }

                    // STEP 3: Ensure dialog is dismissed (if not already done by auto-dismiss)
                    if (this@DeleteClubDialog.isVisible) {
                        dialog.dismiss()
                    }
                }
            }

            builder.setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }

            val dialog = builder.create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(android.graphics.Color.RED)
            }

            dialog
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? DeleteClubListener
    }
    override fun onDetach() {
        super.onDetach()
        listener = null
    }
    interface DeleteClubListener {
        fun onClubDeleted()
    }
}

