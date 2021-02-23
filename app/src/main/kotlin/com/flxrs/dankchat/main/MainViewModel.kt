package com.flxrs.dankchat.main

import android.util.Log
import androidx.lifecycle.*
import com.flxrs.dankchat.chat.menu.EmoteItem
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
import com.flxrs.dankchat.utils.extensions.moveToFront
import com.flxrs.dankchat.utils.extensions.removeOAuthSuffix
import com.flxrs.dankchat.utils.extensions.timer
import com.flxrs.dankchat.utils.extensions.toEmoteItems
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject


@HiltViewModel
class MainViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val dataRepository: DataRepository,
    private val apiManager: ApiManager
) : ViewModel() {

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, Log.getStackTraceString(t))
        _errorEvent.postValue(t)
        val dataEvent = dataLoadingEvent.value
        val imageEvent = imageUploadedEvent.value

        if (dataEvent is DataLoadingState.Loading) {
            _dataLoadingEvent.postValue(DataLoadingState.Failed(t, dataEvent.parameters))
        } else if (imageEvent is ImageUploadState.Loading) {
            _imageUploadedEvent.postValue(ImageUploadState.Failed(t.message, imageEvent.mediaFile))
        }
    }
    private var fetchTimerJob: Job? = null
    private val channels: StateFlow<List<String>> = chatRepository.channels

    var started = false

    // TODO move shit to repo
    val activeChannel: StateFlow<String> = chatRepository.activeChannel
    val channelMentionCount = chatRepository.channelMentionCount.asLiveData(coroutineExceptionHandler)
    val shouldColorNotification: LiveData<Boolean> = liveData(coroutineExceptionHandler) {
        emit(false)

        combine(chatRepository.hasMentions, chatRepository.hasWhispers) { hasMentions, hasWhispers ->
            emit(hasMentions || hasWhispers)
        }
    }

    data class StreamData(val channel: String, val data: String)

    private val _errorEvent = SingleLiveEvent<Throwable>()
    private val _dataLoadingEvent = SingleLiveEvent<DataLoadingState>()
    private val _imageUploadedEvent = SingleLiveEvent<ImageUploadState>()
    private val streamInfoEnabled = MutableStateFlow(true)
    private val roomStateEnabled = MutableStateFlow(true)
    private val streamData = MutableStateFlow<List<StreamData>>(emptyList())
    private val currentSuggestionChannel = MutableStateFlow("")

    private val emotes = currentSuggestionChannel.flatMapLatest { dataRepository.getEmotes(it) }
    private val roomState = currentSuggestionChannel.flatMapLatest { chatRepository.getRoomState(it) }
    private val users = currentSuggestionChannel.flatMapLatest { chatRepository.getUsers(it) }
    private val supibotCommands = activeChannel.flatMapLatest { dataRepository.getSupibotCommands(it) }
    private val currentStreamInformation = combine(activeChannel, streamData) { activeChannel, streamData ->
        streamData.find { it.channel == activeChannel }?.data ?: ""
    }

    // TODO command repository
    private val emoteSuggestions = emotes.mapLatest { emotes ->
        emotes.distinctBy { it.code }
            .map { Suggestion.EmoteSuggestion(it) }
    }
    private val userSuggestions = users.mapLatest { users ->
        users.snapshot().keys.map { Suggestion.UserSuggestion(it) }
    }
    private val supibotCommandSuggestions = supibotCommands.mapLatest { commands ->
        commands.map { Suggestion.CommandSuggestion("$$it") }
    }

    val errorEvent: LiveData<Throwable>
        get() = _errorEvent
    val dataLoadingEvent: LiveData<DataLoadingState>
        get() = _dataLoadingEvent
    val imageUploadedEvent: LiveData<ImageUploadState>
        get() = _imageUploadedEvent

    val inputEnabled = MutableStateFlow(true)
    val appbarEnabled = MutableStateFlow(true)
    val whisperTabSelected = MutableStateFlow(false)
    val mentionSheetOpen = MutableStateFlow(false)

    val shouldShowViewPager = channels.mapLatest { it.isNotEmpty() }
    val shouldShowInput = combine(inputEnabled, shouldShowViewPager) { inputEnabled, shouldShowViewPager ->
        inputEnabled && shouldShowViewPager
    }

    // TODO
    val showUploadProgress = MediatorLiveData<Boolean>().apply {
        addSource(_imageUploadedEvent) { value = it is ImageUploadState.Loading || _dataLoadingEvent.value is DataLoadingState.Loading }
        addSource(_dataLoadingEvent) { value = it is DataLoadingState.Loading || _imageUploadedEvent.value is ImageUploadState.Loading }
    }

    val connectionState = activeChannel
        .flatMapLatest { chatRepository.getConnectionState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), SystemMessageType.DISCONNECTED)

    val canType = combine(connectionState, mentionSheetOpen, whisperTabSelected) { connectionState, mentionSheetOpen, whisperTabSelected ->
        val connected = connectionState == SystemMessageType.CONNECTED
        (!mentionSheetOpen && connected) || (whisperTabSelected && connected)
    }

    val currentBottomText: Flow<String> = combine(roomState, currentStreamInformation, mentionSheetOpen) { roomState, currentStreamInformation, _ ->
        val roomStateText = roomState.toString()
        val streamInfoText = currentStreamInformation

        val stateNotBlank = roomStateText.isNotBlank()
        val streamNotBlank = streamInfoText.isNotBlank()

        when {
            stateNotBlank && streamNotBlank -> "$roomStateText - $streamInfoText"
            stateNotBlank -> roomStateText
            streamNotBlank -> streamInfoText
            else -> ""
        }
    }

    val shouldShowBottomText: Flow<Boolean> =
        combine(roomStateEnabled, streamInfoEnabled, mentionSheetOpen, currentBottomText) { roomStateEnabled, streamInfoEnabled, mentionSheetOpen, bottomText ->
            (roomStateEnabled || streamInfoEnabled) && !mentionSheetOpen && bottomText.isNotBlank()
        }

    val shouldShowFullscreenHelper: Flow<Boolean> =
        combine(shouldShowInput, shouldShowBottomText, currentBottomText, shouldShowViewPager) { shouldShowInput, shouldShowBottomText, bottomText, shouldShowViewPager ->
            !shouldShowInput && shouldShowBottomText && bottomText.isNotBlank() && shouldShowViewPager
        }

    val shouldShowEmoteMenuIcon: Flow<Boolean> =
        combine(canType, mentionSheetOpen) { canType, mentionSheetOpen ->
            canType && !mentionSheetOpen
        }

    val suggestions: Flow<List<Suggestion>> =
        combine(emoteSuggestions, userSuggestions, supibotCommandSuggestions) { emoteSuggestions, userSuggestions, supibotCommandSuggestions ->
            userSuggestions + supibotCommandSuggestions + emoteSuggestions
        }

    val emoteItems: Flow<List<List<EmoteItem>>> = emotes.map { emotes ->
        val groupedByType = emotes.groupBy {
            when (it.emoteType) {
                is EmoteType.ChannelTwitchEmote -> EmoteMenuTab.SUBS
                is EmoteType.ChannelFFZEmote, is EmoteType.ChannelBTTVEmote -> EmoteMenuTab.CHANNEL
                else -> EmoteMenuTab.GLOBAL
            }
        }
        mutableListOf(
            groupedByType[EmoteMenuTab.SUBS].moveToFront(activeChannel.value).toEmoteItems(),
            groupedByType[EmoteMenuTab.CHANNEL].toEmoteItems(),
            groupedByType[EmoteMenuTab.GLOBAL].toEmoteItems()
        )
    }.flowOn(Dispatchers.Default)

    fun loadData(dataLoadingParameters: DataLoadingState.Parameters) = loadData(
        oAuth = dataLoadingParameters.oAuth,
        id = dataLoadingParameters.id,
        name = dataLoadingParameters.name,
        loadTwitchData = dataLoadingParameters.loadTwitchData,
        loadHistory = dataLoadingParameters.loadHistory,
        loadSupibot = dataLoadingParameters.loadSupibot
    )

    fun loadData(
        oAuth: String,
        id: String,
        name: String,
        channelList: List<String> = channels.value,
        loadTwitchData: Boolean,
        loadHistory: Boolean,
        loadSupibot: Boolean,
        scrollBackLength: Int? = null
    ) {
        scrollBackLength?.let { chatRepository.scrollbackLength = it }

        viewModelScope.launch {
            _dataLoadingEvent.postValue(
                DataLoadingState.Loading(DataLoadingState.Parameters(oAuth, id, name, channelList, loadTwitchData = loadTwitchData, loadHistory = loadHistory, loadSupibot = loadSupibot))
            )

            supervisorScope {
                loadInitialData(oAuth.removeOAuthSuffix, id, channelList, loadTwitchData, loadSupibot).joinAll()

                // depends on previously loaded data
                dataRepository.setEmotesForSuggestions("w") // global emote suggestions for whisper tab
                channelList.map {
                    dataRepository.setEmotesForSuggestions(it)
                    launch(coroutineExceptionHandler) { chatRepository.loadChatters(it) }
                    launch(coroutineExceptionHandler) { chatRepository.loadRecentMessages(it, loadHistory) }
                }.joinAll()

                _dataLoadingEvent.postValue(DataLoadingState.Finished)
            }
        }
    }

    fun getLastMessage() = chatRepository.getLastMessage()

    fun setSupibotSuggestions(enabled: Boolean) = viewModelScope.launch(coroutineExceptionHandler) {
        when {
            enabled -> dataRepository.loadSupibotCommands()
            else -> dataRepository.clearSupibotCommands()
        }
    }

    fun getChannels() = chatRepository.channels.value

    fun setActiveChannel(channel: String) {
        chatRepository.setActiveChannel(channel)
        currentSuggestionChannel.value = channel
    }

    fun setSuggestionChannel(channel: String) {
        currentSuggestionChannel.value = channel
    }

    fun setStreamInfoEnabled(enabled: Boolean) {
        streamInfoEnabled.value = enabled
    }

    fun setRoomStateEnabled(enabled: Boolean) {
        roomStateEnabled.value = enabled
    }

    fun setScrollbackLength(scrollBackLength: Int) {
        chatRepository.scrollbackLength = scrollBackLength
    }

    fun setMentionSheetOpen(enabled: Boolean) {
        mentionSheetOpen.value = enabled
        if (enabled) when (whisperTabSelected.value) {
            true -> chatRepository.clearMentionCount("w")
            else -> chatRepository.clearMentionCounts()
        }
    }

    fun setWhisperTabSelected(open: Boolean) {
        whisperTabSelected.value = open
        if (mentionSheetOpen.value) {
            when {
                open -> chatRepository.clearMentionCount("w")
                else -> chatRepository.clearMentionCounts()
            }
        }
    }

    fun clear(channel: String) = chatRepository.clear(channel)

    fun clearMentionCount(channel: String) = chatRepository.clearMentionCount(channel)

    fun reconnect() = chatRepository.reconnect(false)
    fun joinChannel(channel: String): List<String> = chatRepository.joinChannel(channel)
    fun partChannel(): List<String> = chatRepository.partActiveChannel()
    fun trySendMessage(message: String) {
        if (mentionSheetOpen.value && whisperTabSelected.value && !message.startsWith("/w ")) {
            return
        }

        chatRepository.sendMessage(message)
    }


    fun closeAndReconnect(name: String, oAuth: String, userId: String, loadTwitchData: Boolean = false) {
        chatRepository.closeAndReconnect(name, oAuth)

        if (loadTwitchData && oAuth.isNotBlank()) loadData(
            oAuth = oAuth,
            id = userId,
            name = name,
            channelList = channels.value,
            loadTwitchData = true,
            loadHistory = false,
            loadSupibot = false
        )
    }

    fun reloadEmotes(channel: String, oAuth: String, id: String) = viewModelScope.launch {
        _dataLoadingEvent.postValue(
            DataLoadingState.Loading(
                DataLoadingState.Parameters(
                    oAuth = oAuth,
                    id = id,
                    channels = listOf(channel),
                    isReloadEmotes = true
                )
            )
        )

        supervisorScope {
            val fixedOAuth = oAuth.removeOAuthSuffix
            listOf(
                launch(coroutineExceptionHandler) { dataRepository.loadChannelData(channel, fixedOAuth, forceReload = true) },
                launch(coroutineExceptionHandler) { dataRepository.loadTwitchEmotes(fixedOAuth, id) },
                launch(coroutineExceptionHandler) { dataRepository.loadDankChatBadges() },
            ).joinAll()
            dataRepository.setEmotesForSuggestions(channel)

            _dataLoadingEvent.postValue(DataLoadingState.Reloaded)
        }
    }

    fun uploadMedia(file: File) {
        viewModelScope.launch(coroutineExceptionHandler) {
            _imageUploadedEvent.postValue(ImageUploadState.Loading(file))
            val url = dataRepository.uploadMedia(file)
            val state = url?.let {
                file.delete()
                ImageUploadState.Finished(it)
            } ?: ImageUploadState.Failed(null, file)
            _imageUploadedEvent.postValue(state)
        }
    }

    fun setMentionEntries(stringSet: Set<String>?) = viewModelScope.launch(coroutineExceptionHandler) { chatRepository.setMentionEntries(stringSet) }
    fun setBlacklistEntries(stringSet: Set<String>?) = viewModelScope.launch(coroutineExceptionHandler) { chatRepository.setBlacklistEntries(stringSet) }

    suspend fun fetchStreamData(oAuth: String, stringBuilder: (viewers: Int) -> String) = withContext(coroutineExceptionHandler) {
        fetchTimerJob?.cancel()
        val channels = channels.value
        val fixedOAuth = oAuth.removeOAuthSuffix

        fetchTimerJob = timer(STREAM_REFRESH_RATE) {
            val data = channels.map { channel ->
                async {
                    apiManager.getStream(fixedOAuth, channel)?.let {
                        StreamData(channel = channel, data = stringBuilder(it.viewers))
                    }
                }
            }
            streamData.value = data.awaitAll().filterNotNull()
        }
    }

    fun clearIgnores() = chatRepository.clearIgnores()

    private fun CoroutineScope.loadInitialData(oAuth: String, id: String, channelList: List<String>, loadTwitchData: Boolean, loadSupibot: Boolean): List<Job> {
        return listOf(
            launch(coroutineExceptionHandler) { dataRepository.loadDankChatBadges() },
            launch(coroutineExceptionHandler) { dataRepository.loadGlobalBadges() },
            launch(coroutineExceptionHandler) { if (loadTwitchData) dataRepository.loadTwitchEmotes(oAuth, id) },
            launch(coroutineExceptionHandler) { if (loadSupibot) dataRepository.loadSupibotCommands() },
            launch(coroutineExceptionHandler) { chatRepository.loadIgnores(oAuth, id) }
        ) + channelList.map {
            launch(coroutineExceptionHandler) { dataRepository.loadChannelData(it, oAuth) }
        }
    }

    companion object {
        private val TAG = MainViewModel::class.java.simpleName
        private const val STREAM_REFRESH_RATE = 30_000L
    }
}