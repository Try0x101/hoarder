package com.example.hoarder.ui.managers

import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.example.hoarder.R

class SectionToggleManager(private val a: AppCompatActivity, private val updateJsonCallback: () -> Unit) {

    fun setup() {
        val dataCollectionHeader = a.findViewById<View>(R.id.dataCollectionHeader)
        val dataCollectionContent = a.findViewById<View>(R.id.dataCollectionContent)
        val dataCollectionArrow = a.findViewById<ImageView>(R.id.dataCollectionArrow)

        val precisionSettingsHeader = a.findViewById<View>(R.id.precisionSettingsHeader)
        val precisionSettingsContent = a.findViewById<View>(R.id.precisionSettingsContent)
        val precisionSettingsArrow = a.findViewById<ImageView>(R.id.precisionSettingsArrow)

        val powerSavingHeader = a.findViewById<View>(R.id.powerSavingHeader)
        val powerSavingContent = a.findViewById<View>(R.id.powerSavingContent)
        val powerSavingArrow = a.findViewById<ImageView>(R.id.powerSavingArrow)

        dataCollectionHeader.setOnClickListener {
            toggleVisibility(dataCollectionContent, dataCollectionArrow)
            if (dataCollectionContent.visibility == View.VISIBLE) {
                updateJsonCallback()
            }
        }
        precisionSettingsHeader.setOnClickListener { toggleVisibility(precisionSettingsContent, precisionSettingsArrow) }
        powerSavingHeader.setOnClickListener { toggleVisibility(powerSavingContent, powerSavingArrow) }
    }

    private fun toggleVisibility(content: View, arrow: ImageView) {
        val isVisible = content.visibility == View.VISIBLE
        content.visibility = if (isVisible) View.GONE else View.VISIBLE
        arrow.animate().rotation(if (isVisible) 0f else 180f).setDuration(300).start()
    }
}