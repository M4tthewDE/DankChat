package com.flxrs.dankchat.chat

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.flxrs.dankchat.service.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(savedStateHandle: SavedStateHandle, repository: ChatRepository) : ViewModel() {
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, Log.getStackTraceString(t))
    }
    private val channel = savedStateHandle.get<String>(ChatFragment.CHANNEL_ARG) ?: ""
    val chat: Flow<List<ChatItem>> = repository.getChat(channel)

    companion object {
        private val TAG = ChatViewModel::class.java.simpleName
    }
}