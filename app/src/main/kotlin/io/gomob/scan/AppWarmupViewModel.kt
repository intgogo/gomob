package io.gomob.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.message.MessageRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AppWarmupViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
) : ViewModel() {
    private val _readyForShell = MutableStateFlow(false)
    val readyForShell: StateFlow<Boolean> = _readyForShell.asStateFlow()

    private var localWarmupJob: Job? = null
    private var messageWarmupJob: Job? = null

    fun prepareForShell() {
        if (_readyForShell.value || localWarmupJob?.isActive == true) return
        localWarmupJob = viewModelScope.launch {
            runCatching { messageRepository.warmRecentConversationSnapshots() }
            _readyForShell.value = true
            warmAfterShell()
        }
    }

    fun resetForLoggedOut() {
        localWarmupJob?.cancel()
        messageWarmupJob?.cancel()
        _readyForShell.value = false
        messageRepository.stopRealtimeSync()
    }

    private fun warmAfterShell() {
        if (messageWarmupJob?.isActive == true) return
        messageWarmupJob = viewModelScope.launch {
            messageRepository.startRealtimeSync()
            runCatching { messageRepository.refreshConversations() }
            runCatching { messageRepository.prewarmRecentConversationHistories() }
        }
    }

    override fun onCleared() {
        messageRepository.stopRealtimeSync()
        super.onCleared()
    }
}
