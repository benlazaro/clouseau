# Road to 1.0

What has to be true before tagging `v1.0.0`. Features are not the blocker: every use case
is ✅ and the only known gap, G-15 (Browse finds any artifact, not just Clouseau plugins),
matters only once there are third-party plugins. 1.0 is a promise to users: it installs
cleanly, upgrades never lose their data, and the files it writes stay readable.

Plan: finish sections 1–3, tag `v1.0.0-rc.1` (the Release workflow marks it a
pre-release), put it in front of users (section 4), fix what comes back with `-rc.N` tags,
then tag `v1.0.0` after a release candidate goes about two weeks without a serious bug.

Delete this file once 1.0 ships.

## 1. First launch

- [x] **Unsigned builds: document, don't sign** (decided 2026-11-24). Windows shows
      SmartScreen and macOS Gatekeeper blocks the ad-hoc-signed app; `guide/installing.md`
      explains how to allow each once (More info → Run anyway; Privacy & Security → Open
      Anyway, or `xattr` for "damaged"), and the README's Download section links to it.
      Revisit signing (SignPath or Azure Trusted Signing; an Apple Developer ID with
      notarization, $99 a year) if users report the warnings put them off.
- [ ] **Check the steps in `guide/installing.md` against the real rc.1 zips**, on Windows 11
      and a current macOS: the exact wording of each warning, that Unblock skips
      SmartScreen, and which of the two macOS messages actually appears.
- [ ] Smoke-test each platform's zip from a clean account: unzip, launch, open
      `samples/sample.log`, follow a file, SSH, merge two files, export and reopen a review
      bundle, quit and relaunch (session restore).

## 2. Data that must survive upgrades

Formats with a version field, read leniently (unknown fields ignored, newer version read
with a warning): `session.json` and named sessions (`formatVersion` 1), the annotations
sidecar (`version` 2), the review bundle (`formatVersion` 1/2), `highlight-rules.json`
(`version` 1). These are fine as they are.

- [x] **`parsers.json`** (2026-11-24): loading already skipped what it couldn't read, but the
      Parser Editor saved over a file it couldn't fully read, losing it. It now copies such a
      file to `parsers.json.bad` first, writes through a temporary file, and skips a BOM.
      Rules in DESIGN.md 7 (4, 5).
- [x] **`settings.json`** (2026-11-24): a damaged file threw from `AppPrefs`' static setup,
      which could stop Clouseau starting, and was then overwritten. Now it's moved to
      `settings.json.bad`; saves go through a temporary file; a value of the wrong type reads
      as its default; unknown keys are kept. `AppPrefsFileTest`.
- [x] **Upgrade test** (2026-11-24): a real data folder from before 0.18 (settings with
      column layouts, source timezones and the pre-0.12 `ssh.favorites`, user parsers) plus a
      current session (11 tabs), named sessions, auto-highlights and keystore, opened with a
      0.19.0 Windows build. Every settings key kept, the session restored, parsers loaded, no
      `.bad` files, no warnings. That folder had no saved SSH passwords; reading one saved
      elsewhere is covered by `CredentialStoreTest`. Repeat once with the rc.1 zip.
- [x] **Windows data folder** (D-31, 2026-11-24): `~/.clouseau` by default, portable only
      when a `data` folder sits next to `Clouseau.exe`, so an upgrade no longer risks the
      data. Settings → Data Folder shows it with a hint; `guide/installing.md` covers
      portable use and upgrading from 0.18.
- [ ] **Check D-31 in the real rc.1 Windows zip:** a fresh unzip uses `~/.clouseau`; an
      0.18 install's `data` folder moved next to the new `Clouseau.exe` is picked up as
      portable; the steps in the Settings hint work both ways.
- [x] **A saved password that can't be decrypted** (2026-11-24): a keystore that couldn't be
      opened left `CredentialStore` with no key, so it saved passwords **in plain text** and
      showed `enc:…` as the password. Now the keystore is moved to `keystore.p12.bad` and a new
      one made, an undecryptable password reads as not saved (an empty field), and with no
      key nothing is saved. `CredentialStoreTest`.

## 3. Release plumbing and wording

- [x] README (2026-11-24): "Releases are pre-releases while the plugin API settles" is
      replaced by text that holds both before and after 1.0: pre-releases until 1.0, then
      upgrades keep the data; the plugin API keeps its own 0.x version (D-27) and may still
      change, with notes per `api-v*` release.
- [x] Update check (UC-18, D-23): someone on 0.18.0-alpha is offered `1.0.0-rc.1` and then
      `1.0.0`; someone on `1.0.0` isn't offered a later `-rc`. Already true;
      `UpdateCheckTest.theRoadToOnePointOh` covers it.
- [x] `release.yml` (2026-11-24): a tag with no suffix (`v1.0.0`) is created as a normal
      release with `--latest`, rather than GitHub's automatic choice; a suffixed tag stays a
      pre-release, and `api-v*` releases pass `--latest=false`. The README's and
      `guide/installing.md`'s download links go to `/releases/latest`, which falls back to the
      releases list while there is no Latest. After tagging `v1.0.0`, confirm the links land on it.
- [x] `docs/releases/v1.0.0-rc.1.md` and `docs/releases/v1.0.0.md` (2026-11-25): what 1.0
      means, what's new since 0.18 (rc.1) and what Clouseau does (1.0), how to upgrade. If
      more lands before rc.1 is tagged, add it to its notes.
- [ ] Before tagging `v1.0.0`: fill in the comment in `docs/releases/v1.0.0.md` with what
      changed since the last `-rc` (or delete it), and add notes for any later `-rc.N`.
- [x] Guide pass (2026-11-25): every menu path and bold UI name in the README and guide checked
      against `messages.properties`; the shortcut table gained the copy shortcuts and `F3`; the
      screenshots and the social preview retaken.
- [x] macOS shortcuts (2026-11-25): Find, the copy shortcuts, All Notes, tab digits, first/last
      row, text size and the dialogs' Ctrl+D/N/S were bound to Ctrl while the menus used Cmd.
      Now every shortcut uses the shortcut key (`Keys.MENU`) except `Ctrl+Tab` and
      `Ctrl+PageUp/PageDown`, and on macOS "Ctrl" in the app's text reads "Cmd"
      (`Messages.get`, the shortcut list). Not yet tried on a Mac: do it with the rc.1 zip.
- [x] Clean up stale local branches (2026-11-25): the seven, all merged into `main`, are
      deleted; `shelf/command-source` is kept.

## 4. Real users (between rc.1 and 1.0)

- [ ] Post the release candidate where JVM developers and support engineers will see it
      (r/java, Hacker News "Show HN", the Spring and Quarkus communities), with a screenshot
      and the one-paragraph pitch from the README.
- [x] Issue templates (2026-11-25): `.github/ISSUE_TEMPLATE/` has a bug report (version,
      OS, where the log came from, sample lines, Copy Details and `clouseau.log`) and a log
      format request (what writes it, sample lines, layout pattern). Blank issues stay on, so
      the crash window's Report Issue… still lands on a plain new issue.
- [ ] Triage what comes back: crashes and data loss block 1.0; feature requests go to
      USE_CASES.md / DESIGN.md 10 as ideas.

## Not needed for 1.0

- Plugin API 1.0. It is versioned on its own (D-27); freeze it once outside plugins exist.
- G-15, plugin discovery. Same reason.
- Native installers (exe, dmg, deb). The portable zips are the supported way to install.
