package com.quantchat.integration

import android.content.Intent

sealed interface LaunchAction {
    data object None : LaunchAction
    data class PrefillSharedPrompt(val text: String, val sourceLabel: String) : LaunchAction
    data object OpenModelsTab : LaunchAction
}

object SystemIntegrationBridge {
    const val ACTION_OPEN_MODELS = "com.aistudio.quantchat.ACTION_OPEN_MODELS"

    fun parseLaunchIntent(intent: Intent?): LaunchAction {
        if (intent == null) return LaunchAction.None
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (sharedText.isNotEmpty()) {
                    LaunchAction.PrefillSharedPrompt(sharedText, "Android Share")
                } else {
                    LaunchAction.None
                }
            }

            Intent.ACTION_PROCESS_TEXT -> {
                val processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
                if (processText.isNotEmpty()) {
                    LaunchAction.PrefillSharedPrompt(processText, "Text Selection")
                } else {
                    LaunchAction.None
                }
            }

            ACTION_OPEN_MODELS -> LaunchAction.OpenModelsTab

            else -> LaunchAction.None
        }
    }
}
