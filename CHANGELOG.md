# Changelog

Every HushThreads release, newest first.

## 0.0.4 (2026-10-02)

Source builds only. The published bundle remains at 0.0.3.

* **Threads:** Sanitize sharing links swaps a short share link for the post's own link. Copy link and the share sheet handed out `threads.com/share/<code>/`, a code made fresh for every share that opens the post with that share's xmt token. HushThreads now builds `threads.com/@name/post/code` from the post's author and code, and leaves the short link alone when either is missing.
* **Tooling:** The final APK check covers the short-link hook after both of the share sheet's link reads. Its test builds include a missing hook, a swapped register, a wrong post field and a check that skips the call.
* **Threads:** Block background-return feed refresh now hooks For you's swap only where it asks the same MobileConfig getter for the same key the feed's reload check compares with, holds the time away first and branches on that comparison. On Threads 448, where both checks share one method, any 64-bit number in that method used to count as the threshold.
* **Tooling:** The injected-register device suite keeps another run's marker in the rolled-over log, so a check that took any marker for its own fails. Its scan for scripts that clear a phone's log now reads the parsed script, which catches a clear spread over several lines or written inside one quoted command.
* **Threads:** Safe mode and the ways into settings were checked on a Galaxy S22 with Android 16 and Threads 449. Three crashes in a row within a minute of starting paused HushThreads on the next start, a force-stop before them didn't count toward it, and the report showed every hook taking Threads' own path with the saved switches kept. Resume and a restart brought every hook back, and the account stayed signed in. The launcher shortcut and Configure in Threads on the App info page both opened settings, every page went Back to the one it came from, and every control had a name TalkBack can read. No Android 9 phone was on hand, so Android 9 is covered by tests only.
* **Threads:** The Pause, Resume and Undo button beside HushThreads' status is at least 48 dp tall now. It took its height from the two lines of text next to it, which left it 39.5 dp tall on a Galaxy S22.
* **Tooling:** The injected-register device check doesn't clear the phone's log anymore. It writes a marker of its own before dex2oat runs and counts only the verifier lines that follow it. If the log has rolled past that marker by the time it's read, the check stops with an error instead of guessing.
* **Tooling:** The settings file test resets the release check it turns on, so the release check tests start from a phone that never checked in any suite order.

* **Tooling:** Localization file-picker tests wait for queued work with a finite completion fence, so recurring animations can't keep the suite running indefinitely. Every existing language and toast assertion remains.

* **Threads:** Hide suggested users has its own feed switch for verified server cards that suggest accounts to follow. Ads and suggestions share one page filter with separate removal counts. Unknown card types, ordinary posts and reposts stay visible. A failed check keeps the whole original page. Both supported builds pass fixture checks, but removing a real card from a live feed hasn't been seen yet.

* **Tooling:** A patch that finds more than one match now lists the candidates when it stops, so a changed Threads build can be checked before anyone installs it.

* **Threads:** Block background-return feed refresh keeps your place in the feed when you come back to Threads within ten minutes, or after any time away with No time limit on. Threads' background refresh of For you, its reset to the main feed, the feed's own reload and its swap to posts fetched while you were away all get one answer per return. Pull to refresh and a fresh launch still load new posts. It isn't selected by default. Checked on a Galaxy S22 with Threads 449.

* **Tooling:** `scripts/upstream-drift.ps1` lists the files ported from Hushfacebook that changed there after the commit their rule records, and exits 1 when any did. A ported file with no upstream counterpart, or an upstream it can't read, exits 2 so neither passes for a clean answer.

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
