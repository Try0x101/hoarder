package com.example.hoarder.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.main.MainViewModel
import com.example.hoarder.ui.managers.MainSwitchManager
import com.example.hoarder.ui.state.UploadState

class TelemetrySettingsActivity : AppCompatActivity(), MainSwitchManager.MainSwitchCallbacks {

    private lateinit var prefs: Prefs
    private lateinit var ui: UIHelper
    internal val viewModel: MainViewModel by viewModels()
    private var lastData: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telemetry_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        prefs = Prefs(this)
        ui = UIHelper(this, prefs, this) { newMode ->
            viewModel.onPowerModeChanged(newMode)
        }

        ui.setupUI()
        viewModel.registerReceivers()
        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.lastJson.observe(this) { json ->
            lastData = json
            ui.updateRawJson(json)
        }
        viewModel.uploadState.observe(this) { state ->
            updateFullUploadUI(state)
        }
    }

    private fun updateFullUploadUI(state: UploadState) {
        ui.updateUploadUI(prefs.isDataUploadEnabled(), state)
    }

    override fun onResume() {
        super.onResume()
        restoreServiceState()
        ui.updateRawJson(getLastData())
    }

    private fun restoreServiceState() {
        if (prefs.isDataCollectionEnabled()) startCollection()
        if (prefs.isDataUploadEnabled()) startUpload(prefs.getServerAddress())
    }

    fun onBatchingSettingsChanged(
        enabled: Boolean, recordCount: Int, byCount: Boolean,
        timeout: Int, byTimeout: Boolean, maxSize: Int, byMaxSize: Boolean, compLevel: Int
    ) = viewModel.onBatchingSettingsChanged(enabled, recordCount, byCount, timeout, byTimeout, maxSize, byMaxSize, compLevel)

    override fun startCollection() = viewModel.startCollection()
    override fun stopCollection() {
        ui.updateRawJson(null)
        lastData = null
        viewModel.stopCollection()
    }
    override fun startUpload(sa: String) = viewModel.startUpload(sa, getLastData())
    override fun stopUpload() = viewModel.stopUpload()
    fun sendBuffer() = viewModel.sendBuffer()

    fun getLastData(): String? = lastData

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}