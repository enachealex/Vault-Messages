package org.fossify.messages.helpers

import android.content.Context
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.extensions.config

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
        uri: android.net.Uri,
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
