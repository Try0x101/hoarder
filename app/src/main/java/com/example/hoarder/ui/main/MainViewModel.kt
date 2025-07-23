package com.example.hoarder.ui.main

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.ui.service.ServiceCommander
import com.example.hoarder.ui.state.ServiceStateRepository
import com.example.hoarder.ui.state.UploadState

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _lastJson = MutableLiveData<String?>()
    val lastJson: LiveData<String?> = _lastJson

    val uploadState: LiveData<UploadState> = ServiceStateRepository.uploadState.asLiveData()

    private val lbm = LocalBroadcastManager.getInstance(application)
    private val receivers = mutableListOf<BroadcastReceiver>()

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            _lastJson.value = intent?.getStringExtra("jsonString")
        }
    }

    fun registerReceivers() {
        lbm.registerReceiver(dataReceiver, IntentFilter(ServiceCommander.ACTION_DATA_UPDATE))
        receivers.add(dataReceiver)
    }

    override fun onCleared() {
        super.onCleared()
        receivers.forEach { lbm.unregisterReceiver(it) }
    }
}