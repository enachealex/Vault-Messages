package org.fossify.messages.helpers

import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.markThreadMessagesRead
import org.fossify.messages.models.Conversation

/**
 * Vault Messages spam filter.
 *
 * Texts from senders you don't know (not a saved contact, and you've never texted them)
 * are routed to Quarantine when they contain one of the filter keywords. Quarantined
 * messages are still saved, just hidden from the inbox, marked read and never notified.
 * Once a thread is quarantined, further texts from that sender stay quarantined until
 * you move it to the inbox, save the sender as a contact, or reply to them.
 */
object SpamFilter {

    val DEFAULT_KEYWORDS: Set<String> = setOf(
        // Political campaigns and fundraising
        "vote", "voting", "voter", "voters", "ballot", "ballots", "election", "midterm", "midterms",
        "polling place", "early voting", "absentee",
        "democrat", "democrats", "democratic party", "republican", "republicans", "gop", "dnc", "rnc",
        "pac", "super pac", "actblue", "winred",
        "campaign", "candidate", "congressman", "congresswoman", "congress", "senator", "senate race",
        "governor", "petition", "endorse", "endorsed", "rally",
        "donate", "donation", "chip in", "contribute", "contribution", "fundraising", "matching gift",
        "poll", "survey",
        // Common spam and scams
        "you've won", "you have won", "claim your", "gift card", "free gift", "prize",
        "unpaid toll", "toll balance", "toll services", "final notice", "redelivery",
        "package could not be delivered", "account suspended", "pre-approved", "preapproved",
        "loan offer", "debt relief", "crypto", "bitcoin", "investment opportunity",
        "work from home", "make money", "act now", "limited time offer"
    )

    // Never quarantine anything that looks like a login or verification code.
    private const val MAX_MMS_PER_THREAD = 50
    private val MMS_PART_URI: Uri = Uri.parse("content://mms/part")

    private val VERIFICATION_PHRASES = listOf(
        "verification code", "security code", "your code", "login code", "sign-in code",
        "one-time", "one time pass", "passcode", "otp", "do not share", "don't share"
    )

    fun shouldQuarantine(context: Context, address: String, threadId: Long, body: String): Boolean {
        val config = context.config
        if (!config.spamFilterEnabled || address.isBlank()) return false

        if (isKnownSender(context, address, threadId)) {
            if (threadId > 0) config.releaseThread(threadId)
            return false
        }

        if (matchesAny(body, VERIFICATION_PHRASES)) return false
        if (threadId > 0 && config.isThreadQuarantined(threadId)) return true

        if (matchesAny(body, config.spamKeywords)) {
            if (threadId > 0) config.quarantineThread(threadId)
            return true
        }

        return false
    }

    /**
     * Runs the filter over conversations already on the phone. One-to-one threads from unknown
     * senders with any received message matching a keyword are moved to quarantine (not deleted).
     * Returns how many conversations were quarantined.
     */
    fun scanExistingConversations(context: Context, conversations: List<Conversation>): Int {
        val config = context.config
        val keywords = config.spamKeywords
        var quarantinedCount = 0

        conversations.forEach { conversation ->
            val threadId = conversation.threadId
            if (threadId <= 0 || conversation.isGroupConversation || conversation.isScheduled) return@forEach
            if (conversation.phoneNumber.isBlank() || config.isThreadQuarantined(threadId)) return@forEach

            val isSpam = getReceivedTexts(context, threadId).any { text ->
                matchesAny(text, keywords) && !matchesAny(text, VERIFICATION_PHRASES)
            }
            if (!isSpam || isKnownSender(context, conversation.phoneNumber, threadId)) return@forEach

            config.quarantineThread(threadId)
            runCatching { context.markThreadMessagesRead(threadId) }
            quarantinedCount++
        }

        return quarantinedCount
    }

    private fun getReceivedTexts(context: Context, threadId: Long): List<String> {
        val id = threadId.toString()
        val texts = ArrayList<String>()

        queryStrings(
            context, Sms.CONTENT_URI, Sms.BODY,
            "${Sms.THREAD_ID} = ? AND ${Sms.TYPE} = ?", arrayOf(id, Sms.MESSAGE_TYPE_INBOX.toString())
        ).let { texts.addAll(it) }

        val mmsIds = queryStrings(
            context, Mms.CONTENT_URI, Mms._ID,
            "${Mms.THREAD_ID} = ? AND ${Mms.MESSAGE_BOX} = ?", arrayOf(id, Mms.MESSAGE_BOX_INBOX.toString())
        ).take(MAX_MMS_PER_THREAD)

        mmsIds.forEach { mmsId ->
            texts.addAll(
                queryStrings(context, MMS_PART_URI, "text", "mid = ? AND ct = ?", arrayOf(mmsId, "text/plain"))
            )
        }

        return texts
    }

    private fun queryStrings(
        context: Context,
        uri: Uri,
        column: String,
        selection: String,
        args: Array<String>
    ): List<String> {
        val values = ArrayList<String>()
        try {
            context.contentResolver.query(uri, arrayOf(column), selection, args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { values.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        return values
    }

    fun matchesAny(body: String, keywords: Collection<String>): Boolean {
        if (body.isBlank()) return false
        return keywords.any { keyword ->
            val trimmed = keyword.trim()
            trimmed.isNotEmpty() && wordRegex(trimmed).containsMatchIn(body)
        }
    }

    private fun wordRegex(keyword: String): Regex {
        // Match whole words/phrases only, so "vote" doesn't match "devoted".
        return Regex("(?<![\\p{L}\\p{N}])${Regex.escape(keyword)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    /** Known = saved contact, or you've sent them an SMS/MMS before. If contacts can't be read, assume known. */
    fun isKnownSender(context: Context, address: String, threadId: Long): Boolean {
        val cursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        val contactResult = try {
            SimpleContactsHelper(context).existsSync(address, cursor)
        } catch (_: Exception) {
            ContactLookupResult.Undetermined
        } finally {
            cursor?.close()
        }

        if (contactResult != ContactLookupResult.NotFound) return true
        return threadId > 0 && hasSentToThread(context, threadId)
    }

    private fun hasSentToThread(context: Context, threadId: Long): Boolean {
        val id = threadId.toString()
        return hasRows(
            context, Sms.CONTENT_URI, arrayOf(Sms._ID),
            "${Sms.THREAD_ID} = ? AND ${Sms.TYPE} = ?", arrayOf(id, Sms.MESSAGE_TYPE_SENT.toString())
        ) || hasRows(
            context, Mms.CONTENT_URI, arrayOf(Mms._ID),
            "${Mms.THREAD_ID} = ? AND ${Mms.MESSAGE_BOX} = ?", arrayOf(id, Mms.MESSAGE_BOX_SENT.toString())
        )
    }

    private fun hasRows(
        context: Context,
        uri: Uri,
        projection: Array<String>,
        selection: String,
        args: Array<String>
    ): Boolean {
        return try {
            context.contentResolver.query(uri, projection, selection, args, null)?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
