---
title: Plugins
parent: User Guide
nav_order: 7
---

# Plugins
{: .no_toc }

Plugins add parsers, message formatters, syntax highlighters and
[live sources](following.md#source-plugins) to Clouseau. **Tools → Plugins…** opens the
Plugin Manager, where you find, install, update and remove them. To write one, see
[For developers](developers.md#writing-a-plugin).

1. TOC
{:toc}

## Finding and installing a plugin

The Plugin Manager's **Browse** tab opens on the Clouseau plugins in the selected
repository: for Maven Central, every plugin on Clouseau's plugin list, at its latest
version; for a Nexus, every artifact named like a plugin (`<name>-clouseau-plugin`). Hover
over a plugin to see its coordinates and description, and select it to read the
description under the table. **Adds** says what it brings (parser, source, and so on), and
**Latest** its newest version.

- Type to filter the plugins by name, description or what they add: `source` shows every
  source plugin.
- Double-click a plugin, or select it and click **Install**. The new plugin is then
  selected in **Installed**, with a note saying what was installed (or updated) and its
  version.
- **Installed** shows the version you have. When the repository has a newer one, it says
  *update* next to it and the button says **Update**, which replaces your version with the
  new one. On an older version than yours the button says **Downgrade**: it asks first,
  then replaces your version with that one, to go back after an update that broke
  something. The version you have says **Installed**.
- Click a column's header to sort by it, again to reverse it, and a third time to go back
  to the repository's order; Clouseau keeps the sort. Drag a column's edge to make it
  wider; Clouseau keeps the widths too.
- While a slow repository answers, the results area shows what it's waiting for and for
  how long; **Cancel** (where Search was) stops it. A repository that doesn't answer within
  a minute stops with a note saying so.

Browse opens on the repository you used last. Until you choose one, it opens on the Nexus
repository you added last, if you have one.

New plugins are available without restarting; tabs you open afterwards use them.

### Searching the whole repository

For a plugin that isn't listed, type part of its name and press `Enter` (or click
**Search**) to search the whole repository. Only plugins show up (artifacts named
`<name>-clouseau-plugin` or `clouseau-<name>-plugin`), not other libraries that match.

- On Maven Central, `g:` and `a:` search the group or the artifact name only
  (`a:logfmt-clouseau-plugin`).
- On Nexus, any part of the group or artifact name works: `fmt` finds
  `logfmt-clouseau-plugin` (some servers allow only the start of a name: `logfmt`).

The column then says **Version**: a Nexus search lists every version of each plugin it
finds, one per row, so you can pick an older one; Maven Central shows the latest. Clear the
field to go back to the plugins.

## Your installed plugins

The **Installed** tab lists each plugin by name, with its description under it and what it
adds (parser, source, and so on) on the right. Select one to see the details: what it adds,
its ID, author, license and file.

- Untick its box (or press `Space`) to turn it off.
- The trash can on the row under the mouse (or `Delete`) removes it.
- A file in the `plugins` folder that couldn't be loaded shows as **Failed**, in red, with
  the reason, such as "Not a Clouseau plugin: its manifest has no Plugin-Id".

## Updates

Clouseau checks your plugins a few seconds after it starts, then once a day while it stays
open, and each time you open the Plugin Manager. When one has a newer version:

- A small window lists the plugins with updates (*logfmt 1.2.0 → 1.3.0*). **Open Plugins**
  opens the Plugin Manager; **Later** closes the notice, and you won't hear about those
  versions again, only about newer ones.
- **Tools → Plugins…** says how many updates there are, and so does the Plugin Manager's
  **Installed** tab.
- In **Installed**, the plugin's row shows the new version and a green arrow on the right,
  beside where the trash can appears. Click the arrow, or select the plugin and click
  **Update to …** in its details. The new version replaces the old one, and a note over the
  list says which plugin was updated and from which version to which.

A plugin is checked against the repository you installed it from in **Browse**. A plugin
you put in the `plugins` folder yourself is checked only if it's on Clouseau's plugin list,
against Maven Central. The check asks each repository for the plugin's latest version and
sends nothing else. Plugins never update by themselves. The **Tell me about new versions**
setting, which also covers [new versions of Clouseau](settings.md#new-versions), turns
these notices off.

## Your company's Nexus

**Manage…** next to the repository list adds a Nexus repository (Maven Central is always
there). A repository you add is selected so you can search it right away.

- **URL** is the Nexus server's address, the part before `/repository/`. If you download
  plugins from `https://nexus.example.com/repository/maven-releases/…`, enter
  `https://nexus.example.com`. A server under a path keeps it: `https://example.com/nexus`.
- **Repository** is optional. Leave it empty to search every repository on the server, or
  enter one repository's name as Nexus lists it, such as `maven-releases`, to search only
  that one.
- **Authentication**: none, a username and password, or a Nexus user token (its name code
  and passcode).

> Nexus answers wrong credentials as if you weren't signed in, so a search that finds
> nothing may mean the password or token is wrong. Try a shorter part of the name first.

## The plugins folder

**Open Plugins Folder** opens the `plugins` folder, in the
[data folder](settings.md#the-data-folder). A plugin JAR you drop there, or remove, shows up
in the list while the Plugin Manager is open, and is loaded without restarting.
