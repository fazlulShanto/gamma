---
name: release
description: Release the desktop app, the Android app, the browser extension and the iPad app from main by dispatching desktop.yml → GitHub Release v<next>, which carries the installers, the Android APK, the Gamma Connector zip and the AI plugins, then the Microsoft Store and Google Play submissions and a TestFlight marker on main for the iPad. A merge to main never does this on its own. A TestFlight build alone, between releases, is `release-ipad`.
---

# Release from main

A merge to `main` releases no app. Shipping a new version of the apps is
this explicit step: dispatch the workflow on `main` and it computes the
next version from the tags, builds, and publishes the GitHub Release.
Nothing is bumped or tagged by hand; the version rule (newest `v*` tag +
patch, or `desktop/package.json`'s floor when that is higher — raise it
for a minor/major and merge first) and the whole flow are in
`docs/dev/github_actions.md` "Versions and tags" and "desktop.yml". The
release carries the desktop installers, the Android APK (when the
Android release key is set), the browser extension's zip and the AI
plugins, all with the same version. After it, the MSIX goes to the
Microsoft Store and the Android bundle to Google Play's internal track
(each when its secrets exist), the Connector to the Chrome Web Store, and
the publish job's last step pushes an empty commit carrying the next
`ipad-test-release-NNN` marker to `main`, which the Mac mini builds for
TestFlight. A dry run or a pre-release sends nothing to any store;
`release-ipad` is for a TestFlight build between releases.

**Argument**: none needed. Optional `--dry` builds without publishing
(`-f publish=false`), `--prerelease` marks the release as a pre-release.
A release that is not a pre-release must be dispatched on `main`: the
workflow refuses one from another branch.

## Steps

1. **Make sure main is what you want to ship**: `git fetch -q origin`,
   `git log --oneline origin/main -5`. Unmerged branch work → run `merge`
   first; this skill never commits, pushes or merges.

2. **Nothing already running**: `gh run list --workflow desktop.yml --limit 1`.
   An in-progress run queues the new one behind it (concurrency group,
   one waiting run at most: a third dispatch would cancel the waiting
   one); say so rather than double-dispatching.

3. **Dispatch**:
   ```bash
   gh workflow run desktop.yml --ref main                   # next v<version>
   gh workflow run desktop.yml --ref main -f publish=false  # --dry
   gh workflow run desktop.yml --ref main -f prerelease=true
   ```

4. **Report**: a few seconds later
   `gh run list --workflow desktop.yml --limit 1 --json status,url,databaseId`
   gives the run; the `meta` job's notice names the version it will
   publish and Android's versionCode
   (`gh run view <id> --json jobs --jq '.jobs[] | select(.name=="meta")'`).
   Report the link and version. Do not wait for the build (~25 min)
   unless asked; `gh run watch <id> --exit-status` if so. Once it is done,
   what to read and report:
   - **The release** (`gh release view v<version> --json url,assets`): the
     installers, the Connector zip, the AI plugins, and
     `Gamma-<version>-android-arm64.apk`. No APK means the Android build
     was unsigned: the `android` job warns "UNSIGNED build" until the
     `ANDROID_*` secrets exist (`docs/dev/github_actions.md` "Google Play,
     once"); say so.
   - **Docker**: the publish job re-dispatches `docker.yml` with the
     version (a release from `main` only), so the image gets a matching
     `:<version>` tag on its own.
   - **Chrome Web Store**: when the `CWS_*` repository variables exist the
     publish job dispatches `chrome-store.yml` (`gh run list --workflow
     chrome-store.yml --limit 1`); a notice says when an earlier version
     is still in review and this one was skipped, and the workflow's daily
     run submits it once that review is over.
   - **Microsoft Store**: the `store` job's notice names the submission's
     state, or its warning says it did not go through (usually a previous
     submission still in certification: the next release carries it, or
     upload the `store-windows` artifact by hand).
   - **Google Play**: the `play` job's notice names the version, its
     versionCode and the track; its warning lists the usual causes (a
     versionCode Play has seen, an app still a draft that takes
     `PLAY_RELEASE_STATUS=draft`, no first bundle uploaded by hand); a
     notice says when the `PLAY_SERVICE_ACCOUNT_JSON` secret is missing.
     The job does not run for an unsigned build. Promotion beyond the
     internal track is done in the Play Console.
   - **TestFlight**: the `Send the iPad app to TestFlight` step's notice
     names the marker it pushed (`git log origin/main -1` shows the
     commit). When it warns that `main` moved past the release since the
     dispatch, it pushed none: offer `release-ipad`.
