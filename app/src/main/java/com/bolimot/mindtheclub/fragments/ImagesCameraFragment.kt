package com.bolimot.mindtheclub.fragments

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.ImagesAdapter
import com.bolimot.mindtheclub.viewModel.ImagesViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton

class ImagesCameraFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var sendButton: FloatingActionButton

    private var imageAdapter: ImagesAdapter? = null
    private var multipleSelection: Boolean = false
    private val imagesViewModel: ImagesViewModel by viewModels()

    companion object {
        private const val ARG_MULTIPLE_SELECTION = "multipleSelection"

        fun newInstance(multipleSelection: Boolean): ImagesCameraFragment {
            val fragment = ImagesCameraFragment()
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

        val layoutManager = GridLayoutManager(activity, 3)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (imageAdapter?.getItemViewType(position)) {
                    ImagesAdapter.TYPE_DATE_HEADER -> layoutManager.spanCount
                    ImagesAdapter.TYPE_IMAGE -> 1
                    else -> 1
                }
            }
        }
        recyclerView.layoutManager = layoutManager

        sendButton.setOnClickListener {
            imageAdapter?.getSelectedImageUris()?.let { selectedImages ->
                imageSelectedListener?.onMultipleImageSelected(selectedImages)
            }
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val initialAdapter = ImagesAdapter(emptyList(), object : ImagesAdapter.OnImageClickListener {
            override fun onImageClicked(imageUri: Uri) {  }
        }, multipleSelection)

        recyclerView.adapter = initialAdapter

        imagesViewModel.images.observe(viewLifecycleOwner) { items ->
            imageAdapter = ImagesAdapter(items, object : ImagesAdapter.OnImageClickListener {
                override fun onImageClicked(imageUri: Uri) {
                    if (!multipleSelection) {
                        imageSelectedListener?.onImageSelected(imageUri)
                    } else {
                        imageAdapter?.toggleSelection(imageUri)
                        if (imageAdapter?.isAnyImageSelected() == true) {
                            sendButton.visibility = View.VISIBLE
                        } else {
                            sendButton.visibility = View.GONE
                        }
                    }
                }
            }, multipleSelection)

            recyclerView.adapter = imageAdapter
        }

        imagesViewModel.loadImages(isCameraRoll = true)
    }
}

