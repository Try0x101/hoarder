package com.example.hoarder.ui.dialogs.settings

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.TelemetrySettingsActivity

class BatchSettingsDialogHandler(private val a: AppCompatActivity, private val p: Prefs) {

    fun show() {
        val builder = AlertDialog.Builder(a, R.style.AlertDialogTheme)
        val view = LayoutInflater.from(a).inflate(R.layout.dialog_batch_settings, null)

        val masterSwitch = view.findViewById<Switch>(R.id.masterBatchingSwitch)
        val optionsContainer = view.findViewById<LinearLayout>(R.id.advancedBatchingOptionsContainer)
        val triggerByCountSwitch = view.findViewById<Switch>(R.id.triggerByCountSwitch)
        val recordCountEditText = view.findViewById<EditText>(R.id.batchRecordCountEditText)
        val triggerByTimeoutSwitch = view.findViewById<Switch>(R.id.triggerByTimeoutSwitch)
        val timeoutEditText = view.findViewById<EditText>(R.id.batchTimeoutEditText)
        val triggerByMaxSizeSwitch = view.findViewById<Switch>(R.id.triggerByMaxSizeSwitch)
        val maxSizeEditText = view.findViewById<EditText>(R.id.batchMaxSizeEditText)
        val compressionLevelEditText = view.findViewById<EditText>(R.id.compressionLevelEditText)

        masterSwitch.isChecked = p.isBatchUploadEnabled()
        optionsContainer.visibility = if (masterSwitch.isChecked) View.VISIBLE else View.GONE
        triggerByCountSwitch.isChecked = p.isBatchTriggerByCountEnabled()
        recordCountEditText.setText(p.getBatchRecordCount().toString())
        recordCountEditText.isEnabled = triggerByCountSwitch.isChecked
        triggerByTimeoutSwitch.isChecked = p.isBatchTriggerByTimeoutEnabled()
        timeoutEditText.setText(p.getBatchTimeout().toString())
        timeoutEditText.isEnabled = triggerByTimeoutSwitch.isChecked
        triggerByMaxSizeSwitch.isChecked = p.isBatchTriggerByMaxSizeEnabled()
        maxSizeEditText.setText(p.getBatchMaxSizeKb().toString())
        maxSizeEditText.isEnabled = triggerByMaxSizeSwitch.isChecked
        compressionLevelEditText.setText(p.getCompressionLevel().toString())

        masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            optionsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        triggerByCountSwitch.setOnCheckedChangeListener { _, isChecked -> recordCountEditText.isEnabled = isChecked }
        triggerByTimeoutSwitch.setOnCheckedChangeListener { _, isChecked -> timeoutEditText.isEnabled = isChecked }
        triggerByMaxSizeSwitch.setOnCheckedChangeListener { _, isChecked -> maxSizeEditText.isEnabled = isChecked }

        builder.setView(view)
            .setTitle("Live Batching and Compression")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { dialog, _ ->
                p.setBatchUploadEnabled(masterSwitch.isChecked)
                p.setBatchTriggerByCountEnabled(triggerByCountSwitch.isChecked)
                p.setBatchRecordCount(recordCountEditText.text.toString().toIntOrNull() ?: p.getBatchRecordCount())
                p.setBatchTriggerByTimeoutEnabled(triggerByTimeoutSwitch.isChecked)
                p.setBatchTimeout(timeoutEditText.text.toString().toIntOrNull() ?: p.getBatchTimeout())
                p.setBatchTriggerByMaxSizeEnabled(triggerByMaxSizeSwitch.isChecked)
                p.setBatchMaxSizeKb(maxSizeEditText.text.toString().toIntOrNull() ?: p.getBatchMaxSizeKb())
                p.setCompressionLevel(compressionLevelEditText.text.toString().toIntOrNull()?.coerceIn(0, 9) ?: p.getCompressionLevel())

                if (a is TelemetrySettingsActivity) {
                    a.onBatchingSettingsChanged(
                        p.isBatchUploadEnabled(),
                        p.getBatchRecordCount(), p.isBatchTriggerByCountEnabled(),
                        p.getBatchTimeout(), p.isBatchTriggerByTimeoutEnabled(),
                        p.getBatchMaxSizeKb(), p.isBatchTriggerByMaxSizeEnabled(),
                        p.getCompressionLevel()
                    )
                }
                dialog.dismiss()
            }
            .show()
    }
}