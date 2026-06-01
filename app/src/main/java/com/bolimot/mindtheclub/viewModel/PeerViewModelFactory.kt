package com.bolimot.mindtheclub.viewModel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bolimot.mindtheclub.database.club.ClubRepository
import com.bolimot.mindtheclub.database.peer.PeerRepository

class PeerViewModelFactory(
    private val application: Application,
    private val peerRepository: PeerRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PeerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PeerViewModel(application, peerRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class ClubViewModelFactory(
    private val application: Application,
    private val clubRepository: ClubRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ClubViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ClubViewModel(application, clubRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}