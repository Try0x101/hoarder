package com.example.hoarder.ui.main

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.service.ServiceCommander

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _lastJson = MutableLiveData<String?>()
    val lastJson: LiveData<String?> = _lastJson

    private val _uploadStatus = MutableLiveData<String?>()
    val uploadStatus: LiveData<String?> = _uploadStatus

    private val _uploadMessage = MutableLiveData<String?>()
    val uploadMessage: LiveData<String?> = _uploadMessage

    private val _totalUploadedBytes = MutableLiveData(0L)
    val totalUploadedBytes: LiveData<Long> = _totalUploadedBytes

    private val _totalActualNetworkBytes = MutableLiveData(0L)
    val totalActualNetworkBytes: LiveData<Long> = _totalActualNetworkBytes

    private val _bufferedDataSize = MutableLiveData(0L)
    val bufferedDataSize: LiveData<Long> = _bufferedDataSize

    private val _isUploadEnabled = MutableLiveData<Boolean>()
    val isUploadEnabled: LiveData<Boolean> = _isUploadEnabled

    private val _isBulkInProgress = MutableLiveData(false)
    val isBulkInProgress: LiveData<Boolean> = _isBulkInProgress

    private val lbm = LocalBroadcastManager.getInstance(application)
    private val receivers = mutableListOf<BroadcastReceiver>()

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            _lastJson.value = intent?.getStringExtra("jsonString")
        }
    }

    private val uploadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            _isUploadEnabled.value = Prefs(application).isDataUploadEnabled()
            _uploadStatus.value = intent?.getStringExtra("status")
            _uploadMessage.value = intent?.getStringExtra("message")
            _totalUploadedBytes.value = intent?.getLongExtra("totalUploadedBytes", 0L)
            _totalActualNetworkBytes.value = intent?.getLongExtra("totalActualNetworkBytes", 0L)
            _bufferedDataSize.value = intent?.getLongExtra("bufferedDataSize", 0L)
            _isBulkInProgress.value = intent?.getBooleanExtra("bulkInProgress", false)
        }
    }

    fun registerReceivers() {
        lbm.registerReceiver(dataReceiver, IntentFilter(ServiceCommander.ACTION_DATA_UPDATE))
        receivers.add(dataReceiver)
        lbm.registerReceiver(uploadReceiver, IntentFilter(ServiceCommander.ACTION_UPLOAD_STATUS))
        receivers.add(uploadReceiver)
    }

    override fun onCleared() {
        super.onCleared()
        receivers.forEach { lbm.unregisterReceiver(it) }
    }
}