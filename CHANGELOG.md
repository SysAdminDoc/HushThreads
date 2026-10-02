# Changelog

Every HushThreads release, newest first.

## Unreleased

* **Threads:** Shared-link guidance now describes query-parameter removal without claiming that opaque share codes identify the account or are removed. The source comment no longer attributes a Threads rule to ClearURLs.

* **Tooling:** Local APK builds align native ZIP entries before signing and check the final signature and alignment with the existing key. Release receipts now record ELF segment alignment, ZIP alignment and native payload preservation separately from vendor incompatibilities.

* **Tooling:** Settings and recovery tests now cover Android 9 and Android 16, including large RTL text, entry and Back actions, content-URI imports with rollback, release-check consent and persisted crash recovery. Existing Android 11 coverage remains.

* **Tooling:** The build classpath uses Guava 33.7.2, which fixes the serialized-collection allocation advisory. Manager still supplies its own copy. The patch-source import guard now scans from the repository root and rejects static imports, missing sources and empty source directories.

* **Threads:** Repeated enabled, off and paused analytics checks on Android 16 and Threads 449 found no sustained worker CPU retry storm in the measured sessions. The guide records the limits; address interception stays unchanged.

* **Tooling:** The source ledger now records HushThreads in all five discovery indexes, groups verified forks and mirrors under their origins, and distinguishes candidates from catalogs. Build, reporting and source guidance now lives in README; licensing and mirror checks work without local guides.

* **Threads:** Source builds now declare stable arm64 Threads 448.0.0.54.85 alongside 449.0.0.54.82. All six patches pass unforced verification on both, and same-key updates preserve a signed-in session through settings and live-feed checks.

* **Threads:** Diagnostics count each removed ad post and each shared link that actually changes, separately from hook calls. Disabled, paused and failed operations add no outcome. Counts remain bounded and survive clear/undo without overflowing.

* **Tooling:** Concurrent builds now isolate split-bundle inputs before merging. Device builds use the same merge path, preserve the original archive and clean temporary inputs after success or failure.

* **Tooling:** APK verification checks selected feed, link, analytics and signature mutations against the stock build. It preserves called stock methods and ordered exception handling, including valid splits of long protected ranges. Missing hooks, discarded ad results and changed stock dependencies fail cleanly. Empty selections don't require omitted payloads.

* **Threads:** Link cleaning now rejects an unrelated constructor or a wide write that overwrites the response name. Casts keep tracking the same response object, including a cast through Object.

* **Tooling:** Device install and verifier scripts now check and renew an owned lease and verify the selected phone or emulator profile. The lease file stays exclusively open while each command runs. Signing conflicts preserve installed apps. Replacement uninstall requests are refused before building.
* **Threads:** Privacy settings and exported reports now list the matched and missing analytics address kinds. Partial coverage stays visible when the switch is off or HushThreads is paused. The guide describes the covered address paths rather than claiming every event log is blocked.
* **Tooling:** Settings tests initialize their context before accessing patch switches, so their order doesn't affect the result.

## 0.0.3 (2026-10-01)

* **Threads:** This release keeps the same 6 patches for Threads 449.0.0.54.82. Hide ads and Sanitize sharing links are stricter about where they patch, and the check that lets a same-key Instagram call into Threads no longer stops when Android can't answer a lookup.
* **Threads:** Hide ads now requires one distinct item getter with a matching cast and method declaration. Link cleaning follows the named response object's registers and requires one owned string store. Competing targets stop patching with candidate details.
* **Threads:** A failed Android package or Binder lookup keeps the framework's signer result instead of interrupting a family-caller check.
* **Threads:** Password sign-in reached a feed for one account with the published 0.0.2 bundle and six 0.0.3 patch configurations beside signed-in stock Instagram on Threads 449. Both full bundles also passed with Instagram absent. The guide records the two save-login prompts. Continue as wasn't offered, and the reported password failure remains unresolved.

## 0.0.2 (2026-09-29)

The first release, with 6 patches for Threads 449.0.0.54.82.

* **Threads:** Hide ads takes sponsored posts out of each page of the feed before Threads caches or shows it. Whether a post is an ad is Threads' own answer, read from the "injected" block the server puts on sponsored posts.
* **Threads:** Sanitize sharing links takes `xmt`, `slof`, `igsh`, `igshid`, `igsi` and `fbclid` off the links Threads hands out for a post, at the one place every Copy link and share gets its link from.
* **Threads:** Disable analytics points Threads' event log uploads at a port on the phone that nothing listens on, from all three places the app builds that address.
* **Threads:** Remove the advertising ID takes the advertising ID permission out of the manifest, so Google Play services gives Threads zeros.
* **Threads:** Restore screens on re-signed builds answers Threads' own signer check with Meta's certificate, and trusts an Instagram signed with the same key.
* **Threads:** HushThreads settings opens from a launcher shortcut or from Additional settings in the app on Threads' App info page, with pause, safe mode, settings backups, diagnostics and the licences.
* **Threads:** Sign in with your Instagram username and password. Continue as the Instagram account already on your phone doesn't work on a patched Threads yet.
* **Tooling:** The README has the HushThreads logo and a banner in the Hush family's blue and navy look.
