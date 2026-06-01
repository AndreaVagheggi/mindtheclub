package com.bolimot.mindtheclub.fragments

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.VideoAdapter
import com.bolimot.mindtheclub.functions.createVideoItems
import com.google.android.material.floatingactionbutton.FloatingActionButton


class VideoCameraFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var sendButton: FloatingActionButton

    private var videoAdapter: VideoAdapter? = null
    private var multipleSelection: Boolean = false

    companion object {
        private const val ARG_MULTIPLE_SELECTION = "multipleSelection"

        fun newInstance(multipleSelection: Boolean): VideoCameraFragment {
            val fragment = VideoCameraFragment()
            val args = Bundle()
            args.putBoolean(ARG_MULTIPLE_SELECTION, multipleSelection)
            fragment.arguments = args
            return fragment
        }
    }

    interface OnImageSelectedListener {
        fun onImageSelected(uri: Uri)
        fun onMultipleImageSelected(uris: List<Uri>)
    }

    private var imageSelectedListener: OnImageSelectedListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        imageSelectedListener = context as? OnImageSelectedListener
    }

    override fun onDetach() {
        super.onDetach()
        imageSelectedListener = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            multipleSelection = it.getBoolean(ARG_MULTIPLE_SELECTION)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.media_fragment, container, false)

        recyclerView = view.findViewById(R.id.recyclerView)
        sendButton = view.findViewById(R.id.send)

        sendButton.setOnClickListener {
            videoAdapter?.getSelectedImageUris()?.let { selectedImages ->
                imageSelectedListener?.onMultipleImageSelected(selectedImages)
            }
        }

        val selection = "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME}=?"
        val selectionArgs = arrayOf("Camera")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_ADDED
        )

        val cursor = requireActivity().contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

        val items = cursor?.let { createVideoItems(it) }

        videoAdapter = items?.let {
            VideoAdapter(it, object : VideoAdapter.OnImageClickListener {
                override fun onImageClicked(imageUri: Uri) {
                    if (!multipleSelection) {
                        imageSelectedListener?.onImageSelected(imageUri)
                    } else {
                        videoAdapter?.toggleSelection(imageUri)
                        if(videoAdapter?.isAnyImageSelected()!!){
                            sendButton.visibility = View.VISIBLE
                        } else {
                            sendButton.visibility = View.GONE
                        }
                    }
                }
            }, multipleSelection)
        }

        val layoutManager = GridLayoutManager(activity, 3)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (videoAdapter?.getItemViewType(position)) {
                    VideoAdapter.TYPE_DATE_HEADER -> layoutManager.spanCount
                    VideoAdapter.TYPE_IMAGE -> 1
                    else -> -1
                }
            }
        }
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = videoAdapter

        cursor?.close()

        return view
    }
}




