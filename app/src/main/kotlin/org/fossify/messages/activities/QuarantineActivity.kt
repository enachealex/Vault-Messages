package org.fossify.messages.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.adapters.QuarantineAdapter
import org.fossify.messages.databinding.ActivityArchivedConversationsBinding
import org.fossify.messages.dialogs.SpamKeywordsDialog
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.deleteConversation
import org.fossify.messages.helpers.SpamFilter
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Events
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/** Lists conversations the spam filter routed away from the inbox. Reuses the archive screen layout. */
class QuarantineActivity : SimpleActivity() {
    private var bus: EventBus? = null
    private val binding by viewBinding(ActivityArchivedConversationsBinding::inflate)

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.archiveToolbar.title = getString(R.string.quarantine)
        setupOptionsMenu()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.conversationsList))
        setupMaterialScrollListener(
            scrollingView = binding.conversationsList,
            topAppBar = binding.archiveAppbar
        )

        bus = EventBus.getDefault()
        try {
            bus!!.register(this)
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.archiveAppbar, NavigationIcon.Arrow)
        binding.archiveToolbar.title = getString(R.string.quarantine)
        loadQuarantinedConversations()
    }

    override fun onDestroy() {
        super.onDestroy()
        bus?.unregister(this)
    }

    private fun setupOptionsMenu() {
        binding.archiveToolbar.inflateMenu(R.menu.quarantine_menu)
        binding.archiveToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.toggle_spam_filter -> {
                    config.spamFilterEnabled = !config.spamFilterEnabled
                    menuItem.isChecked = config.spamFilterEnabled
                }

                R.id.edit_spam_keywords -> SpamKeywordsDialog(this)
                R.id.reset_spam_keywords -> {
                    config.spamKeywords = SpamFilter.DEFAULT_KEYWORDS
                    toast(R.string.spam_keywords_saved)
                }

                R.id.empty_quarantine -> emptyQuarantine()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateOptionsMenu(conversations: List<Conversation>) {
        binding.archiveToolbar.menu.apply {
            findItem(R.id.toggle_spam_filter).isChecked = config.spamFilterEnabled
            findItem(R.id.empty_quarantine).isVisible = conversations.isNotEmpty()
        }
    }

    private fun getQuarantinedConversations(): ArrayList<Conversation> {
        val quarantined = config.quarantinedThreads
        if (quarantined.isEmpty()) return ArrayList()

        val all = try {
            conversationsDB.getNonArchived() + conversationsDB.getAllArchived()
        } catch (_: Exception) {
            emptyList()
        }

        return ArrayList(all.filter { quarantined.contains(it.threadId.toString()) })
    }

    private fun loadQuarantinedConversations() {
        ensureBackgroundThread {
            val conversations = getQuarantinedConversations()
            runOnUiThread {
                setupConversations(conversations)
            }
        }
    }

    private fun emptyQuarantine() {
        ConfirmationDialog(
            activity = this,
            message = getString(R.string.empty_quarantine_confirmation),
            positive = org.fossify.commons.R.string.yes,
            negative = org.fossify.commons.R.string.no
        ) {
            ensureBackgroundThread {
                getQuarantinedConversations().forEach {
                    deleteConversation(it.threadId)
                }
                config.quarantinedThreads = emptySet()
                runOnUiThread { loadQuarantinedConversations() }
            }
        }
    }

    private fun getOrCreateConversationsAdapter(): QuarantineAdapter {
        var currAdapter = binding.conversationsList.adapter
        if (currAdapter == null) {
            hideKeyboard()
            currAdapter = QuarantineAdapter(
                activity = this,
                recyclerView = binding.conversationsList,
                onRefresh = { notifyDatasetChanged() },
                itemClick = { handleConversationClick(it) }
            )

            binding.conversationsList.adapter = currAdapter
            if (areSystemAnimationsEnabled) {
                binding.conversationsList.scheduleLayoutAnimation()
            }
        }
        return currAdapter as QuarantineAdapter
    }

    private fun setupConversations(conversations: ArrayList<Conversation>) {
        val sortedConversations = ArrayList(conversations.sortedByDescending { it.date })

        showOrHidePlaceholder(conversations.isEmpty())
        updateOptionsMenu(conversations)

        try {
            getOrCreateConversationsAdapter().apply {
                updateConversations(sortedConversations)
            }
        } catch (_: Exception) {
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        binding.conversationsFastscroller.beGoneIf(show)
        binding.noConversationsPlaceholder.beVisibleIf(show)
        binding.noConversationsPlaceholder.setTextColor(getProperTextColor())
        binding.noConversationsPlaceholder.text = getString(R.string.no_quarantined_conversations)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun notifyDatasetChanged() {
        getOrCreateConversationsAdapter().notifyDataSetChanged()
    }

    private fun handleConversationClick(any: Any) {
        Intent(this, ThreadActivity::class.java).apply {
            val conversation = any as Conversation
            putExtra(THREAD_ID, conversation.threadId)
            putExtra(THREAD_TITLE, conversation.title)
            startActivity(this)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshConversations(@Suppress("unused") event: Events.RefreshConversations) {
        loadQuarantinedConversations()
    }
}
