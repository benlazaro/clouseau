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

**File → Open Remote → SSH…** opens **SSH Connections**: your saved connections on the left,
and on the right a form for whatever you pick there. A *connection* is one way of signing in
to a machine (a user, a host and a port, and a password or key), with an optional name and
the logs you've saved on it. Two accounts on the same machine, such as `deploy` and `root`,
are two connections.

**To follow a log on a new connection**, click **New** at the bottom left of the window (or
press `Ctrl+N`):

1. Under **Connection**, enter the **Host**, **Port** and **Username**, and a **Name** if you
   like, such as *Payments prod*. You can also type or paste `user@host` or
   `user@host:2222` into Host; Clouseau moves the user and port into their own fields.
2. Choose the **Authentication**: **Password**, **Private key**, or **None** for an account
   without a password (common on embedded devices). For a key, Clouseau fills in
   `~/.ssh/id_ed25519`, `id_ecdsa` or `id_rsa`, whichever you have; leave **Passphrase**
   empty if the key has none.
3. Under **Log**, enter the **Remote path** of the log, or click **Browse…** to pick it
   from the server's folders. Pick a **Parser** if auto-detection isn't right.
4. Tick **Save this connection** to keep it for next time (passwords and passphrases are
   stored encrypted), or leave it unticked for a one-off. The box remembers your choice.
5. Click **Connect**. While it's grayed out, the note above the buttons says what's still
   missing. Connect first signs in and checks that the file exists and you can read it, and
   opens the tab only when that works (see below). If you saved a connection with the same
   user, host and port before, the log is added to it, and if you signed in another way (a
   new password, say), Clouseau asks whether to update the saved one.

**Browse…** signs in with the connection's details and lists the server's folders. It
starts in the folder of the path you typed, or in `/var/log`, or in your home folder.
Double-click a folder (or press Enter) to open it, and **Up** (or Backspace) to go back up;
you can also type a folder into the path box. Only files you can read are listed. A note
under the list says how many were left out. Double-click a file, or select it and click
**Choose**, to put its path in **Remote path**. Browsing uses SFTP when the server offers
it, and otherwise lists folders with ordinary shell commands, so it works on servers
without SFTP too (Dropbear without `sftp-server`, for example).

If the check fails, the dialog stays open and the note above the buttons says which step
failed and why, such as a host that can't be found, a port that isn't taking connections, a
password the server turned down, a key file that can't be used, or a file that's missing or
that you can't read. A new connection isn't saved until the check passes, so you can fix the
details and try again. Each step gives up after 15 seconds. Changing any field stops the
check and clears the result.

The tab is named after the file and the host, such as `app.log — 10.0.0.5`, so the same
log followed on two servers gets two distinct tabs; a connection you named shows its name
instead (`app.log — Payments prod`). Point at the name to see the user, the host, the full
path, and the port if it isn't 22.

Clouseau runs `tail -F` on the server, so lines arrive as soon as they're written, and it
keeps going when the log is rotated or replaced. If the path is wrong or you can't read
the file, Clouseau says so. If the connection drops without closing (a router or VPN
timing it out), Clouseau notices within about a minute and a quarter and shows an error,
instead of the tab quietly going still. Closing an SSH tab is immediate, even if the
connection has dropped.

### Saved connections

Saved connections are listed on the left as a tree: each connection in bold, the folders of
its saved logs under it, and the logs in each folder. A connection you named shows the name,
with its address beside it (`Payments prod  deploy@10.0.3.17`); one without a name shows its
address, with the port when it isn't 22 (`ops@10.0.9.4:2222`). Click the arrow beside a
connection or folder (or double-click it, or press Left and Right) to fold it; the dialog
remembers what you folded. Type in the filter box to narrow the list by name, address or
file; every match shows while you filter. Drag the divider, or make the window bigger, to
see long names. The dialog keeps its size and divider for next time.

- **To follow a saved log**, click it, then **Connect** (or double-click it). The form shows
  only the log: its connection on the first line, then **Remote path** and **Parser**. If
  you change them, Connect saves your changes before it connects.
- **To change a connection**, click it. The form shows only its details (**Name**, **Host**,
  **Port**, **Username**, **Authentication**); change what you need and click **Save**.
  Every log saved on it uses the new details. Two connections can't have the same user, host
  and port, or the same name; if they would, Save says which connection already has them.
- **To name a connection**, type it in **Name**, or right-click the connection and choose
  **Rename** (or select it and press `F2`), type the name and press `Enter` (`Esc` cancels).
  Clear the name to show the address again.
- **To follow another file on a saved connection**, right-click it and choose **New Log…**,
  or point at one of its logs and click the duplicate icon at the right of the row (or press
  `Ctrl+D`), which fills in the same folder. Enter the path and click **Connect**.
- **To forget something**, click the trash can at the right of its row (or select it and
  press `Delete`). Forgetting a connection forgets its logs too, after asking; a folder's
  trash can forgets every log in it, after asking when there's more than one.
  Right-clicking a row offers the same, with **Rename** and **New Log…** on a connection and
  **Duplicate** on a log.
- **If you pick something else**, or click **Cancel**, with changes you haven't saved,
  Clouseau asks whether to save them. `Ctrl+S` saves the connection or log the form shows
  without connecting.

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
