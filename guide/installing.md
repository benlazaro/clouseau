---
title: Installing
parent: User Guide
nav_order: 0
---

# Installing

Download the zip for your system from the
[latest release](https://github.com/benlazaro/clouseau/releases): `clouseau-<version>-windows.zip`,
`-macos.zip` or `-linux.zip`. Each zip brings its own Java runtime, so there's nothing else
to install. Unzip it and start Clouseau:

| System | Start |
|---|---|
| Windows | `Clouseau\Clouseau.exe`. Unzip it anywhere; to keep your settings with it, see [Portable on Windows](#portable-on-windows). |
| macOS | `Clouseau.app`. Move it to Applications if you like. |
| Linux | `Clouseau/bin/Clouseau` |

## The first time you start it

Clouseau isn't signed with a paid certificate from Microsoft or Apple, so the first time you
start it, your system warns you that it doesn't recognize the app. You only have to allow it
once.

### Windows

Windows shows **Windows protected your PC**. Click **More info**, then **Run anyway**.

To skip the warning, before you unzip: right-click the downloaded zip, choose
**Properties**, tick **Unblock** at the bottom of the General tab, and click **OK**.

### macOS

macOS says it **could not verify "Clouseau" is free of malware** and offers only
**Done** and **Move to Trash**. Click **Done**, then:

1. Open **System Settings → Privacy & Security**.
2. Scroll down to the message about Clouseau and click **Open Anyway**.
3. Confirm with your password, then click **Open Anyway** once more.

If macOS says instead that **"Clouseau" is damaged and can't be opened**, the download
was quarantined. Clear that once in Terminal, then open Clouseau as usual:

```bash
xattr -dr com.apple.quarantine /Applications/Clouseau.app
```

(Use the path where you put `Clouseau.app`.)

### Linux

Nothing to allow. If your file manager doesn't start it, run `Clouseau/bin/Clouseau` from a
terminal, or make it executable first with `chmod +x Clouseau/bin/Clouseau`.

## Portable on Windows

Clouseau keeps your settings, sessions, parsers and saved connections in a
[data folder](settings.md#the-data-folder): `.clouseau` in your home folder
(`C:\Users\<you>\.clouseau`). To keep them with the app instead, for example to run
Clouseau from a USB stick or a shared tools folder, make it portable:

1. Before you start Clouseau, create an empty folder named `data` inside the `Clouseau`
   folder, next to `Clouseau.exe`.
2. Start Clouseau. It finds the `data` folder and keeps everything there.

**File → Settings… → Data Folder** shows which folder is in use.

To make a copy you've already used portable, close Clouseau, create the `data` folder, and
copy everything from `C:\Users\<you>\.clouseau` into it. To go back, close Clouseau, move the
`data` folder's contents to `C:\Users\<you>\.clouseau`, and delete the `data` folder.

Saved passwords are encrypted for your Windows account on that computer. Started on another
computer, or by another account, a portable Clouseau keeps everything else, but asks for
those passwords again.

## Upgrading

Clouseau tells you when a new version is out (see [New versions](settings.md#new-versions)),
but it doesn't update itself. To install a new version, close Clouseau first, then:

- **Windows:** delete the old `Clouseau` folder and unzip the new version. Your data is in
  your home folder, so nothing is lost. If you use Clouseau
  [portable](#portable-on-windows), keep the old `data` folder and put it next to the new
  `Clouseau.exe`.
- **macOS and Linux:** your data is in `~/.clouseau/`, outside the app, so just replace the
  old app with the new one.

Upgrading on Windows from Clouseau 0.18 or earlier: those versions always kept their data in
the `data` folder next to `Clouseau.exe`. Move that folder next to the new `Clouseau.exe` to
keep using it portable, or copy its contents to `C:\Users\<you>\.clouseau`.

A new download is a new app to your system, so you may have to allow it once more, as
above.

If the old version is still running when you start the new one, Clouseau says which
version is running and asks you to close it, rather than bringing up the old one.

## The Edge build

`clouseau-edge.jar` is built from the latest change on `main`, for trying what's coming
next. It needs Java 21 or later installed: run it with `java -jar clouseau-edge.jar`. Java
runs the jar, so neither of the warnings above appears.
