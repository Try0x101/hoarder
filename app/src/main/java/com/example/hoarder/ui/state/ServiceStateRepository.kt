package com.example.hoarder.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UploadState(
    val status: String? = "Initializing",
    val message: String? = "...",
    val totalUploadedBytes: Long = 0L,
    val totalActualNetworkBytes: Long = 0L,
    val bufferedDataSize: Long = 0L,
    val isBulkInProgress: Boolean = false
)

object ServiceStateRepository {
    private val _uploadState = MutableStateFlow(UploadState())
    val uploadState = _uploadState.asStateFlow()

    fun updateUploadState(newState: UploadState) {
        _uploadState.value = newState
    }
}