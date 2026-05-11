package io.gomob.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.message.MessageRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@HiltViewModel
class AppWarmupViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
) : ViewModel() {
    private var messageWarmupJob: Job? = null

    fun warmAfterLogin() {
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
