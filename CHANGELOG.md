# Changelog

## Unreleased

HushThreads 0.0.1, the first build. Nothing has been released yet.

### Added

- **Hide ads** takes sponsored posts out of each page of the feed before Threads caches or shows it. Whether a post is an ad is Threads' own answer, read from the "injected" block the server puts on sponsored posts.
- **Sanitize sharing links** takes `xmt`, `slof`, `igsh`, `igshid`, `igsi` and `fbclid` off the links Threads hands out for a post, at the one place every Copy link and share gets its link from.
- **Disable analytics** points Threads' event log uploads at a port on the phone that nothing listens on, from all three places the app builds that address.
- **Remove the advertising ID** takes the advertising ID permission out of the manifest, so Google Play services gives Threads zeros.
- **Restore screens on re-signed builds** answers Threads' own signer check with Meta's certificate, and trusts an Instagram signed with the same key.
- **HushThreads settings**, opened from a launcher shortcut or from Additional settings in the app on Threads' App info page, with pause, safe mode, settings backups, diagnostics and the licences.
