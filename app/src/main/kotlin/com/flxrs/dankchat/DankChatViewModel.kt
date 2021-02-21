package com.flxrs.dankchat

import android.util.Log
import androidx.lifecycle.*
import com.flxrs.dankchat.chat.menu.EmoteMenuTab
import com.flxrs.dankchat.chat.suggestion.Suggestion
import com.flxrs.dankchat.service.ChatRepository
import com.flxrs.dankchat.service.DataRepository
import com.flxrs.dankchat.service.api.ApiManager
import com.flxrs.dankchat.service.state.DataLoadingState
import com.flxrs.dankchat.service.state.ImageUploadState
import com.flxrs.dankchat.service.twitch.connection.SystemMessageType
import com.flxrs.dankchat.service.twitch.emote.EmoteType
import com.flxrs.dankchat.utils.SingleLiveEvent
import com.flxrs.dankchat.utils.extensions.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DankChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    var started = false

    fun reconnect(onlyIfNecessary: Boolean) = chatRepository.reconnect(onlyIfNecessary)

    // TODO move to repo lmao
    fun connectAndJoinChannels(name: String, oAuth: String, channelList: List<String>? = channels.value, forceConnect: Boolean = false) {
        if (!chatRepository.startedConnection) {
            when {
                channelList.isNullOrEmpty() -> chatRepository.connect(name, oAuth, forceConnect)
                else -> channelList.forEachIndexed { i, channel ->
                    if (i == 0) chatRepository.connect(name, oAuth, forceConnect)
                    chatRepository.joinChannel(channel)
                }
            }
        }
    }


    companion object {
        private val TAG = DankChatViewModel::class.java.simpleName
    }
}