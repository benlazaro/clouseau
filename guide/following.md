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

New lines appear as the application writes them, like `tail -f`, in every tab: files,
merged views, SSH and other live sources. Clouseau wakes up as soon as the operating system
reports that a file changed, so they appear without delay, and no line is lost or repeated.

**Follow** (at the right of the filter bar, and in the **View** menu) keeps the newest line
in view:

- While it's on, the table scrolls to show new lines as they arrive.
- **Scroll up** to read earlier lines and Follow turns off by itself. The table stays where
  you put it while new lines keep coming in below.
- **Scroll back down** to the newest line, or press **Ctrl+End**, and Follow turns back on.
- **Click Follow** on to jump to the newest line, or off to stop the table moving where it is.

A file opens at its newest line, with Follow on. To open files at the top instead, turn off
**Start at the newest line** in [Settings](settings.md).

On an SSH or plugin source, the oldest rows are dropped once the tab passes its entry
limit. If you're reading earlier rows when that happens, the table keeps them where they are
on the screen.

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

**File → Open Remote → SSH…** follows a log on another machine.

1. Under **Server**, enter the **Host**, **Port** and **Username**. You can also type or
   paste `user@host` or `user@host:2222` into Host; Clouseau moves the user and port into
   their own fields.
2. Under **Authentication**, choose the **Method**: **Password**, **Private key**, or
   **None** for an account without a password (common on embedded devices). For a key,
   Clouseau fills in `~/.ssh/id_ed25519`, `id_ecdsa` or `id_rsa`, whichever you have; leave
   **Passphrase** empty if the key has none.
3. Under **Log**, enter the **Remote path** of the log, or click **Browse…** to pick it
   from the server's folders. Pick a **Parser** if auto-detection isn't right.
4. Click **Connect**. While it's grayed out, the note above the buttons says what's still
   missing.

**Browse…** signs in with the details you've entered and lists the server's folders. It
starts in the folder of the path you typed, or in `/var/log`, or in your home folder.
Double-click a folder (or press Enter) to open it, and **Up** (or Backspace) to go back up;
you can also type a folder into the path box. Only files you can read are listed. A note
under the list says how many were left out. Double-click a file, or select it and click
**Choose**, to put its path in **Remote path**. Browsing uses SFTP when the server offers
it, and otherwise lists folders with ordinary shell commands, so it works on servers
without SFTP too (Dropbear without `sftp-server`, for example).

**Test**, after **Browse…** on the **Remote path** line, checks the connection without
opening a tab. It signs in and checks that the
file exists and you can read it, then reports in the note above the buttons: **Signed in ·
/var/log/app.log is readable**, or which step failed and why, such as a host that can't be
found, a port that isn't taking connections, a password the server turned down, a key file
that can't be used, or a file that's missing or that you can't read. Each step gives up
after 15 seconds. Changing any field clears the result.

The tab is named after the file and the host, such as `app.log — 10.0.0.5`, so the same
log followed on two servers gets two distinct tabs; a server you named shows its name
instead (`app.log — Payments prod`). Point at the name to see the user, the host, the full
path, and the port if it isn't 22.

Clouseau runs `tail -F` on the server, so lines arrive as soon as they're written, and it
keeps going when the log is rotated or replaced. If the path is wrong or you can't read
the file, Clouseau says so. If the connection drops without closing (a router or VPN
timing it out), Clouseau notices within about a minute and a quarter and shows an error,
instead of the tab quietly going still. Closing an SSH tab is immediate, even if the connection has
dropped. To keep a connection for next time, tick
**Save this connection** before you click **Connect** (passwords and passphrases are stored
encrypted). Saved connections are listed on the left of the dialog as a tree: each server
in bold, the folders of its saved logs under it, and the logs in each folder. Click the
arrow beside a server or folder (or double-click it, or press Left and Right) to fold it;
the dialog remembers what you folded. Type in the filter box to narrow the list by host,
server name or file; every match shows while you filter. Click a log to fill in the form,
or double-click it to connect right away.
Drag the divider, or make the window bigger, to see long names; a list wider than the
sidebar scrolls sideways. The dialog keeps its size and divider for next time.

- **To name a server**, right-click it in Saved connections and choose **Rename** (or
  select it and press `F2`), type a name such as *Payments prod*, and press `Enter` (`Esc`
  cancels). The list then shows the name with the host beside it (`Payments prod
  10.0.3.17`), every log on that server shares it, and the form shows it under **Server**.
  Clear the name to show the host again. Two servers can't share a name. A server you're
  adding for the first time can be named once it's saved.
- **To change a saved connection**, click it, edit anything (the host, user and path
  included), and click **Connect**: it's updated in place.
- **To follow another file on the same server**, point at a saved log and click the
  duplicate icon at the right of its row (or press `Ctrl+D`). The form keeps every detail
  and selects the file name in **Remote path**; type the new one and click **Connect** to
  save it as a new entry.
- **To forget one**, click the trash can at the right of its row (or select it and press
  `Delete`). The trash can on a folder or a server forgets every log in it, after asking
  when there's more than one. Right-clicking a row offers the same, with **Rename** on a
  server and **Duplicate** on a log.

The **Save this connection** box remembers whether you ticked it last time.

### Connection dot and Reconnect

SSH and source-plugin tabs show a dot next to the title:

- **Yellow**: connecting.
- **Green**: connected.
- **Gray**: disconnected. The stream ended or failed; point at the dot to see why.

To connect again, right-click the tab and choose **Reconnect**, or press `Ctrl+R`
(**File → Reconnect**, `Ctrl+R`) on the tab. Reconnecting empties the tab and streams the log again
from the last lines, as if you had just opened it, so no line is shown twice. The tab
keeps its title, parser, filters and columns; notes on its lines are not kept. It signs
in with the same details you entered, without asking again.

An SSH tab keeps its lines in memory, at most **Max entries per tab** (200,000 by default,
in [Settings](settings.md)); past that, the oldest lines are dropped. The status bar shows
how many were trimmed.

## Source plugins

Plugins can bring in lines from other places, such as a log service or a message queue.
Installed source plugins appear under **File → Open Remote**, after SSH, each under its own
name. **Browse Plugins…** at the bottom opens the Plugin Manager's Browse tab. If one
can't open its source, the error names the plugin. Each shows a form for its
settings; Clouseau checks required fields, remembers the values, and stores passwords
encrypted. Lines stream into a new tab, with the same limit, connection dot and
**Reconnect** as SSH tabs.

See [Plugins](settings.md#plugins) to install one, or [For developers](developers.md) to
write one.
