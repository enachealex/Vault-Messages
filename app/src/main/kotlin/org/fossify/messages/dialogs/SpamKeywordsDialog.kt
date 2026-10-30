package org.fossify.messages.dialogs

import android.app.Activity
import android.content.DialogInterface.BUTTON_POSITIVE
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.messages.R
import org.fossify.messages.databinding.DialogSpamKeywordsBinding
import org.fossify.messages.extensions.config

class SpamKeywordsDialog(private val activity: Activity) {

    init {
        val binding = DialogSpamKeywordsBinding.inflate(activity.layoutInflater).apply {
            spamKeywordsEditText.setText(activity.config.spamKeywords.sorted().joinToString("\n"))
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.edit_spam_keywords) { alertDialog ->
                    alertDialog.getButton(BUTTON_POSITIVE).setOnClickListener {
                        val keywords = binding.spamKeywordsEditText.text.toString()
                            .lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()
                        activity.config.spamKeywords = keywords
                        activity.toast(R.string.spam_keywords_saved)
                        alertDialog.dismiss()
                    }
                }
            }
    }
}
