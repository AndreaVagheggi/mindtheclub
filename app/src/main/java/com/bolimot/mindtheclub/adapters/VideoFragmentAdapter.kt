package com.bolimot.mindtheclub.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.bolimot.mindtheclub.fragments.VideoCameraFragment
import com.bolimot.mindtheclub.fragments.VideoGalleryFragment

class VideoFragmentAdapter(fragmentManager: FragmentManager,
                           lifecycle: Lifecycle,
                           private val itemCount: Int,
                           private val multipleSelection: Boolean) : FragmentStateAdapter(fragmentManager, lifecycle) {
    override fun getItemCount(): Int = itemCount

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> VideoGalleryFragment.newInstance(multipleSelection)
            1 -> VideoCameraFragment.newInstance(multipleSelection)
            else -> throw IllegalStateException("Invalid position: $position")
        }
    }
}