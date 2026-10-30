# Vault Messages

A Jump Vault fork of [Fossify Messages](https://github.com/FossifyOrg/Messages) (GPL-3.0) with a built-in spam/political text filter.

## What it adds
- **Unknown-sender filter.** Texts (SMS and MMS) from numbers that aren't in your contacts, and that you've never texted, are checked against a keyword list as they arrive. Matches go to **Quarantine**: saved, marked read, hidden from the inbox, and never notified.
- **Sticky quarantine.** Once a sender is quarantined, their later texts stay quarantined until you move the thread to the inbox, save them as a contact, or reply.
- **Safety valve.** Anything that looks like a login/verification code is never quarantined.
- **Quarantine screen** (main menu > Quarantine): review, move to inbox, delete, toggle the filter, edit or reset the keyword list.

Code: `helpers/SpamFilter.kt`, `activities/QuarantineActivity.kt`, `adapters/QuarantineAdapter.kt`, `dialogs/SpamKeywordsDialog.kt`, plus hooks in `SmsReceiver`, `MmsReceiver`, `Messaging.kt` and `MainActivity`.

## Builds
Every push to `main` builds a signed APK with GitHub Actions and attaches it to a new GitHub Release. Open the latest release on your phone, download the `.apk`, and install. Updates install over the top because every build uses the same signing key in `signing/` (keep this repo private).

Limitation: as with any non-Google texting app, there is no RCS while Vault Messages is the default texting app.
