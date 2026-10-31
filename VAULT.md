# Vault Messages

A Jump Vault fork of [Fossify Messages](https://github.com/FossifyOrg/Messages) (GPL-3.0) with a built-in spam/political text filter.

## What it adds
- **Unknown-sender filter.** Texts (SMS and MMS) from numbers that aren't in your contacts, and that you've never texted, are checked against a keyword list as they arrive. Matches go to **Quarantine**: saved, marked read, hidden from the inbox, and never notified.
- **Sticky quarantine.** Once a sender is quarantined, their later texts stay quarantined until you move the thread to the inbox, save them as a contact, or reply.
- **Safety valve.** Anything that looks like a login/verification code is never quarantined.
- **Quarantine screen** (main menu > Quarantine): review, move to inbox, delete, toggle the filter, edit or reset the keyword list.

Code: `helpers/SpamFilter.kt`, `activities/QuarantineActivity.kt`, `adapters/QuarantineAdapter.kt`, `dialogs/SpamKeywordsDialog.kt`, plus hooks in `SmsReceiver`, `MmsReceiver`, `Messaging.kt` and `MainActivity`.

## Updates
Settings > Updates has **Check for updates** and **Install updates automatically** (on by default; checks when the app opens, at most every 6 hours). The app reads the latest GitHub release of this repo, downloads the APK and installs it. On Android 12+ the first self-update asks for one confirmation; later ones install silently.

## Builds
Every push to `main` builds a signed APK with GitHub Actions and publishes it as a release tagged `build-N` (versionCode 1000 + N). The signing key in `signing/vault-release.p12` is protected by a strong password kept in the `SIGNING_STORE_PASSWORD` repository secret; never commit that password.

Limitation: as with any non-Google texting app, there is no RCS while Vault Messages is the default texting app.
