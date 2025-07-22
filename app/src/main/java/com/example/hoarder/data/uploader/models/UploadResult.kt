package com.example.hoarder.data.uploader.models

data class UploadResult(
    val success: Boolean,
    val uploadedBytes: Long,
    val actualNetworkBytes: Long,
    val statusMessage: String,
    val errorMessage: String? = null,
    val compressionStats: String? = null,
    val jobId: String? = null
)