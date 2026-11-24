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

- [ ] **`parsers.json`** is a bare JSON array with no version. A later format can still be
      recognized (an object instead of an array), so this is acceptable; write that rule
      into DESIGN.md 7 so it's a decision, not an accident.
- [ ] **`settings.json`** is flat keys with no version. Same: confirm `AppPrefs` ignores
      unknown keys and survives a missing or damaged file, and note it in DESIGN.md 7.
- [ ] **Upgrade test:** take a data folder from v0.18.0-alpha (sessions, saved SSH
      connections with encrypted passwords, parsers, auto-highlights, a plugin) and a log with
      a `.clouseau` sidecar, then open them with the release candidate. Nothing lost, no
      `.bad` files.
- [ ] **Windows portable upgrade:** the data lives in `<install>/data/`, and the guide says
      to copy it across (`guide/settings.md`, New versions). Check that the instructions
      actually work, and consider making the update notice say it too, since this is where
      a user is most likely to lose their data.

## 3. Release plumbing and wording

- [ ] README: "Releases are pre-releases while the plugin API settles" no longer holds.
      Replace it with: the app is stable at 1.0; the plugin API keeps its own 0.x version
      (D-27) and may still change, with notes per `api-v*` release.
- [ ] Update check (UC-18, D-23): someone on 0.18.0-alpha is offered `1.0.0-rc.1` and then
      `1.0.0`; someone on `1.0.0` isn't offered a later `-rc`. Cover both in `UpdateCheckTest`
      if they aren't already.
- [ ] `release.yml`: `v1.0.0` (no suffix) is created as a normal release and becomes
      GitHub's Latest; check that the README's "Latest release" links then land on it.
- [ ] `docs/releases/v1.0.0-rc.1.md` and `docs/releases/v1.0.0.md`, written for users: what
      1.0 means, the highlights since the first alpha, how to upgrade.
- [ ] Guide pass: every page matches the current app (menu names such as *Open via SSH…*,
      screenshots in `docs/screenshots/` current with the design system).
- [ ] Clean up stale local branches (`claude/*`, `design-system-sync`, `row-menu-alignment`,
      `toggle-off-border-contrast`); keep `shelf/command-source`.

## 4. Real users (between rc.1 and 1.0)

- [ ] Post the release candidate where JVM developers and support engineers will see it
      (r/java, Hacker News "Show HN", the Spring and Quarkus communities), with a screenshot
      and the one-paragraph pitch from the README.
- [ ] Issue templates for bugs and format requests (`.github/ISSUE_TEMPLATE/`), asking for
      the OS, version, and a sample line for format problems.
- [ ] Triage what comes back: crashes and data loss block 1.0; feature requests go to
      USE_CASES.md / DESIGN.md 10 as ideas.

## Not needed for 1.0

- Plugin API 1.0. It is versioned on its own (D-27); freeze it once outside plugins exist.
- G-15, plugin discovery. Same reason.
- Native installers (exe, dmg, deb). The portable zips are the supported way to install.
