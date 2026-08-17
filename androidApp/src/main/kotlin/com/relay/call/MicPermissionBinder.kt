package com.relay.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MicPermissionBinder(
    private val activity: ComponentActivity,
    private val permission: MicPermission
) {
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permission.onResult(granted) }

    init {
        activity.lifecycleScope.launch {
            permission.prompts.collect { ask() }
        }
    }

    private fun ask() {
        val granted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            permission.onResult(true)
            return
        }
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
