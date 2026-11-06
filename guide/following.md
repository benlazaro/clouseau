---
title: Following live logs
parent: User Guide
nav_order: 5
---

# Following live logs
{: .no_toc }

1. TOC
{:toc}

## Follow mode

**Follow** (at the right of the filter bar) shows new lines as the application writes
them, like `tail -f`. It's on by default for files you open; **Follow new lines by
default** in [Settings](settings.md) changes that. Turn it off and on per tab at any time:
no line is lost or repeated.

A file always opens at the top. Clouseau wakes up as soon as the operating system reports
that the file changed, so new lines appear without delay.

A followed file is indexed like any other, so it can run for hours or days without
running out of memory, and no line is ever dropped.

## Log rotation

Clouseau keeps your rows when the log is rotated:

- **Renamed** (logrotate's default, and log4j and logback rolling): the existing rows stay
  readable from the old file, and the new file is followed from its start.
- **Truncated in place** (logrotate's `copytruncate`): the existing rows keep their time,
  level, logger and thread, but their message reads "Text no longer in the file
  (rotated)", and the status bar says the file was rotated.

## Remote logs over SSH

**File → Connect via SSH…** follows a log on another machine.

1. Enter the **Host**, **Port** and **Username**.
2. Choose how to sign in: **Password**, **Private key** (with its passphrase, if it has
   one), or **No password** for an account that has none (common on embedded devices).
3. Enter the **Remote path** of the log, and pick a **Parser** if auto-detection isn't
   right.
4. Click **Connect**.

The tab is named after the file and the host, such as `app.log — 10.0.0.5`, so the same
log followed on two servers gets two distinct tabs. Point at the name to see the user, the
full path, and the port if it isn't 22.

Clouseau runs `tail -F` on the server, so lines arrive as soon as they're written, and it
keeps going when the log is rotated or replaced. If the path is wrong or you can't read
the file, Clouseau says so. If the connection drops without closing (a router or VPN
timing it out), Clouseau notices within about a minute and a quarter and shows an error,
instead of the tab quietly going still. Closing an SSH tab is immediate, even if the connection has
dropped. Save connections you use often with the **★**
button next to **Favorites**, and pick them from that list next time.

An SSH tab keeps its lines in memory, at most **Max entries per tab** (200,000 by default,
in [Settings](settings.md)); past that, the oldest lines are dropped. The status bar shows
how many were trimmed.

## Source plugins

Plugins can bring in lines from other places, such as a log service or a message queue.
Installed source plugins appear under **File → Open Source**. Each shows a form for its
settings; Clouseau checks required fields, remembers the values, and stores passwords
encrypted. Lines stream into a new tab, with the same limit as SSH tabs.

See [Plugins](settings.md#plugins) to install one, or [For developers](developers.md) to
write one.
