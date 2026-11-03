# Packaging assets

Place platform-specific icons here before running `jpackage`. The build will
include them automatically if found; if a file is missing the installer is
still produced, just without a custom icon.

```
packaging/
├── windows/
│   └── clouseau.ico    256×256 (multi-size ICO recommended)
├── macos/
│   └── clouseau.icns   1024×1024 (use iconutil or an online converter from the SVG)
└── linux/
    └── clouseau.png    128×128 PNG
```

The source SVG is at:
`clouseau-ui/src/main/resources/com/droidenx/clouseau/ui/icons/clouseau.svg`

## Building the distribution

Requires JDK 21+. No additional tools needed on any platform.

```bash
# Zipped app image (bundled JRE, no installer required) — recommended for releases
./gradlew :clouseau-ui:distZipImage "-PappVersion=1.0.0"

# Output location
clouseau-ui/build/distributions/clouseau-1.0.0.zip

# App image only (unzipped folder, useful for testing)
./gradlew :clouseau-ui:jpackageImage "-PappVersion=1.0.0"

# Native installer (local builds only, not published by CI;
# requires WiX on Windows, no extra tools on macOS/Linux)
./gradlew :clouseau-ui:jpackage "-PappVersion=1.0.0"
```

The zip bundles a trimmed JRE — users just extract and run, no Java installation needed.
Releases publish only the portable zips (one per platform). The Windows installer is
configured as per-user (`--win-per-user-install`) so its `data/` folder stays writable
if installers are published later.

## Heap size

The default heap is `-Xms64m -Xmx2g`, baked into the native launcher via
`applicationDefaultJvmArgs` in `clouseau-ui/build.gradle.kts`. Adjust there
before packaging if your users typically work with very large files.
