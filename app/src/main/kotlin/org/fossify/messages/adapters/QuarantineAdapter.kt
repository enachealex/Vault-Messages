package org.fossify.messages.adapters

import android.view.Menu
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.deleteConversation
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.models.Conversation

class QuarantineAdapter(
    activity: SimpleActivity, recyclerView: MyRecyclerView, onRefresh: () -> Unit, itemClick: (Any) -> Unit
) : BaseConversationsAdapter(activity, recyclerView, onRefresh, itemClick) {
    override fun getActionMenuId() = R.menu.cab_quarantine

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_release -> releaseConversations()
            R.id.cab_select_all -> selectAll()
        }
    }

    private fun getSelectedConversations(): List<Conversation> {
        return currentList.filter { selectedKeys.contains(it.hashCode()) }
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size
        val items = resources.getQuantityString(R.plurals.delete_conversations, itemsCnt, itemsCnt)

        val baseString = org.fossify.commons.R.string.deletion_confirmation
        val question = String.format(resources.getString(baseString), items)

        ConfirmationDialog(activity, question) {
            ensureBackgroundThread {
                val conversationsToRemove = getSelectedConversations()
                conversationsToRemove.forEach {
                    activity.deleteConversation(it.threadId)
                    activity.config.releaseThread(it.threadId)
                    activity.notificationManager.cancel(it.threadId.hashCode())
                }
                removeConversationsFromList(conversationsToRemove)
            }
        }
    }

    private fun releaseConversations() {
        val conversationsToRelease = getSelectedConversations()
        conversationsToRelease.forEach {
            activity.config.releaseThread(it.threadId)
        }
        removeConversationsFromList(conversationsToRelease)
    }

    private fun removeConversationsFromList(removedConversations: List<Conversation>) {
        val newList = try {
            currentList.toMutableList().apply { removeAll(removedConversations) }
        } catch (_: Exception) {
            currentList.toMutableList()
        }

        activity.runOnUiThread {
            if (newList.none { selectedKeys.contains(it.hashCode()) }) {
                refreshConversations()
                finishActMode()
            } else {
                submitList(newList)
                if (newList.isEmpty()) {
                    refreshConversations()
                }
            }
        }
    }
}
