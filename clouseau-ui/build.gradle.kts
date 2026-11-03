// clouseau-ui — Swing presentation layer (MVP)
// Rule: no business logic, no direct log parsing.

import java.awt.RenderingHints
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ColorConvertOp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

plugins {
    java
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("org.beryx.runtime") version "1.13.1"
}

application {
    mainClass.set("com.droidenx.clouseau.ui.ClouseauApp")
    // Applied to installDist launchers and the jpackage image.
    // Users can override at runtime with -J-Xmx in the launcher or JAVA_OPTS.
    applicationDefaultJvmArgs = listOf("-Xms64m", "-Xmx2g")
}

tasks.processResources {
    filesMatching("version.properties") {
        expand("appVersion" to project.version)
    }
}

tasks.shadowJar {
    archiveBaseName.set("clouseau")
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// Version used for zip filenames and in-app display — strip -SNAPSHOT only.
val zipVersion: String = (project.version as String).removeSuffix("-SNAPSHOT")

// jpackage bundle version: must be numeric only (no pre-release labels like -alpha/-beta).
// macOS also requires the first component to be >= 1, so 0.x.y becomes 1.x.y in package
// metadata only (the in-app display is unaffected).
val bundleVersion: String = run {
    val parts = zipVersion.substringBefore("-").split(".")
    if (parts[0].toIntOrNull() == 0) "1.${parts.drop(1).joinToString(".")}" else parts.joinToString(".")
}

// Pre-renders the splash SVG at 2x into a classpath PNG so startup doesn't pay for Batik
// (cold class loading + rasterizing cost 0.7-1.9 s before the splash could appear).
val splashPngDir = layout.buildDirectory.dir("generated/splash")
val generateSplashPng = tasks.register("generateSplashPng") {
    val svgFile = file("src/main/resources/com/droidenx/clouseau/ui/images/clouseau_splash.svg")
    val pngFile = splashPngDir.map { it.file("com/droidenx/clouseau/ui/images/clouseau_splash.png").asFile }
    inputs.file(svgFile)
    outputs.dir(splashPngDir)

    doLast {
        val transcoder = org.apache.batik.transcoder.image.PNGTranscoder()
        transcoder.addTranscodingHint(
            org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_WIDTH, 1360f)
        transcoder.addTranscodingHint(
            org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_HEIGHT, 600f)
        val baos = ByteArrayOutputStream()
        transcoder.transcode(
            org.apache.batik.transcoder.TranscoderInput(svgFile.toURI().toString()),
            org.apache.batik.transcoder.TranscoderOutput(baos as OutputStream))
        val out = pngFile.get()
        out.parentFile.mkdirs()
        out.writeBytes(baos.toByteArray())
        logger.lifecycle("Generated ${out.name} (${out.length() / 1024} KB)")
    }
}

// Pre-renders the app icons, About icon and empty-state watermark from clouseau.svg, using the
// same Batik/Java2D steps the app used to run at startup, so the runtime needs no SVG library.
val iconPngDir = layout.buildDirectory.dir("generated/icons")
val generateIconPngs = tasks.register("generateIconPngs") {
    val svgFile = file("src/main/resources/com/droidenx/clouseau/ui/icons/clouseau.svg")
    val outDir = iconPngDir.map { it.dir("com/droidenx/clouseau/ui/icons").asFile }
    inputs.file(svgFile)
    outputs.dir(iconPngDir)

    doLast {
        fun transcode(renderSize: Int): BufferedImage {
            val transcoder = org.apache.batik.transcoder.image.PNGTranscoder()
            transcoder.addTranscodingHint(
                org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_WIDTH, renderSize.toFloat())
            transcoder.addTranscodingHint(
                org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_HEIGHT, renderSize.toFloat())
            val baos = ByteArrayOutputStream()
            transcoder.transcode(
                org.apache.batik.transcoder.TranscoderInput(svgFile.toURI().toString()),
                org.apache.batik.transcoder.TranscoderOutput(baos as OutputStream))
            return ImageIO.read(ByteArrayInputStream(baos.toByteArray()))
        }

        // Render slightly larger than the target and center-crop back to it.
        fun zoomCropped(size: Int, zoom: Float): BufferedImage {
            val renderSize = Math.round(size * zoom)
            val rendered = transcode(renderSize)
            val offset = (renderSize - size) / 2
            val cropped = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g2 = cropped.createGraphics()
            g2.drawImage(rendered, 0, 0, size, size, offset, offset, offset + size, offset + size, null)
            g2.dispose()
            return cropped
        }

        fun watermark(size: Int): BufferedImage {
            val hi = transcode(size * 2)
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g2 = img.createGraphics()
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.drawImage(hi, 0, 0, size, size, null)
            g2.dispose()
            ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null)
                .filter(img, img)
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val argb = img.getRGB(x, y)
                val alpha = (argb ushr 24) and 0xFF
                img.setRGB(x, y, ((alpha * 0.23).toInt() shl 24) or (argb and 0x00FFFFFF))
            }
            return img
        }

        val dir = outDir.get()
        dir.mkdirs()
        listOf(256, 64, 16).forEach { size ->
            ImageIO.write(zoomCropped(size, 1.19f), "png", File(dir, "app-$size.png"))
        }
        ImageIO.write(zoomCropped(64, 1.15f), "png", File(dir, "about-64.png"))
        ImageIO.write(watermark(256), "png", File(dir, "watermark-256.png"))
        logger.lifecycle("Generated icon PNGs in ${dir.name}/")
    }
}

sourceSets.main.get().resources.srcDir(files(splashPngDir).builtBy(generateSplashPng))
sourceSets.main.get().resources.srcDir(files(iconPngDir).builtBy(generateIconPngs))
tasks.processResources { exclude("**/clouseau_splash.svg", "**/icons/clouseau.svg") }

// Generates packaging/windows/clouseau.ico from the SVG at 16/32/48/256 px.
// Batik is available via the root buildscript classpath.
tasks.register("generateWindowsIcon") {
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }
    val svgFile = file("src/main/resources/com/droidenx/clouseau/ui/icons/clouseau.svg")
    val icoFile = rootProject.file("packaging/windows/clouseau.ico")
    inputs.file(svgFile)
    outputs.file(icoFile)

    doLast {
        val sizes = listOf(16, 32, 48, 256)

        // Render SVG to PNG bytes at each size using Batik
        val pngList: List<ByteArray> = sizes.map { size ->
            val transcoder = org.apache.batik.transcoder.image.PNGTranscoder()
            transcoder.addTranscodingHint(
                org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_WIDTH, size.toFloat())
            transcoder.addTranscodingHint(
                org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_HEIGHT, size.toFloat())
            val baos = ByteArrayOutputStream()
            transcoder.transcode(
                org.apache.batik.transcoder.TranscoderInput(svgFile.toURI().toString()),
                org.apache.batik.transcoder.TranscoderOutput(baos as OutputStream))
            baos.toByteArray()
        }

        // Assemble ICO file (little-endian, PNG-embedded format supported by Windows Vista+)
        val count     = sizes.size
        val dirBytes  = 6 + count * 16
        val offsets   = IntArray(count)
        offsets[0]    = dirBytes
        for (i in 1 until count) offsets[i] = offsets[i - 1] + pngList[i - 1].size
        val total     = offsets.last() + pngList.last().size

        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)

        // ICONDIR header
        buf.putShort(0); buf.putShort(1); buf.putShort(count.toShort())

        // ICONDIRENTRY per image (width/height 0 means 256)
        for (i in sizes.indices) {
            val d = if (sizes[i] == 256) 0 else sizes[i]
            buf.put(d.toByte()); buf.put(d.toByte())
            buf.put(0);          buf.put(0)
            buf.putShort(1);     buf.putShort(32)
            buf.putInt(pngList[i].size)
            buf.putInt(offsets[i])
        }

        pngList.forEach { buf.put(it) }

        icoFile.parentFile.mkdirs()
        icoFile.writeBytes(buf.array())
        logger.lifecycle("Generated ${icoFile.name} (${sizes.joinToString(", ")}px)")
    }
}

// Generates packaging/linux/clouseau.png (128x128) from the SVG.
tasks.register("generateLinuxIcon") {
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isLinux }
    val svgFile = file("src/main/resources/com/droidenx/clouseau/ui/icons/clouseau.svg")
    val pngFile = rootProject.file("packaging/linux/clouseau.png")
    inputs.file(svgFile)
    outputs.file(pngFile)

    doLast {
        val transcoder = org.apache.batik.transcoder.image.PNGTranscoder()
        transcoder.addTranscodingHint(
            org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_WIDTH, 128f)
        transcoder.addTranscodingHint(
            org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_HEIGHT, 128f)
        val baos = ByteArrayOutputStream()
        transcoder.transcode(
            org.apache.batik.transcoder.TranscoderInput(svgFile.toURI().toString()),
            org.apache.batik.transcoder.TranscoderOutput(baos as OutputStream))
        pngFile.parentFile.mkdirs()
        pngFile.writeBytes(baos.toByteArray())
        logger.lifecycle("Generated ${pngFile.name} (128x128)")
    }
}

// Generates packaging/macos/clouseau.icns from the SVG at 128/256/512/1024 px.
tasks.register("generateMacOsIcon") {
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isMacOsX }
    val svgFile = file("src/main/resources/com/droidenx/clouseau/ui/icons/clouseau.svg")
    val icnsFile = rootProject.file("packaging/macos/clouseau.icns")
    inputs.file(svgFile)
    outputs.file(icnsFile)

    doLast {
        // ICNS type codes → sizes (PNG-embedded, supported macOS 10.7+)
        val entries = listOf("ic07" to 128, "ic08" to 256, "ic09" to 512, "ic10" to 1024)

        val pngList: List<ByteArray> = entries.map { (_, size) ->
            val transcoder = org.apache.batik.transcoder.image.PNGTranscoder()
            transcoder.addTranscodingHint(
                org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_WIDTH, size.toFloat())
            transcoder.addTranscodingHint(
                org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_HEIGHT, size.toFloat())
            val baos = ByteArrayOutputStream()
            transcoder.transcode(
                org.apache.batik.transcoder.TranscoderInput(svgFile.toURI().toString()),
                org.apache.batik.transcoder.TranscoderOutput(baos as OutputStream))
            baos.toByteArray()
        }

        // Assemble ICNS file (big-endian)
        val totalSize = 8 + entries.indices.sumOf { 8 + pngList[it].size }
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // ICNS header: magic + file size
        "icns".forEach { buf.put(it.code.toByte()) }
        buf.putInt(totalSize)

        // One resource block per size: type code + block size + PNG bytes
        for (i in entries.indices) {
            entries[i].first.forEach { buf.put(it.code.toByte()) }
            buf.putInt(8 + pngList[i].size)
            buf.put(pngList[i])
        }

        icnsFile.parentFile.mkdirs()
        icnsFile.writeBytes(buf.array())
        logger.lifecycle("Generated ${icnsFile.name} (${entries.map { it.second }.joinToString(", ")}px)")
    }
}

tasks.named("jpackageImage") {
    onlyIf { !(project.version as String).endsWith("-SNAPSHOT") }
    val os = org.gradle.internal.os.OperatingSystem.current()
    when {
        os.isWindows -> dependsOn("generateWindowsIcon")
        os.isMacOsX  -> dependsOn("generateMacOsIcon")
        os.isLinux   -> dependsOn("generateLinuxIcon")
    }
}

// Zip the jpackageImage output for distribution (no installer toolchain needed).
tasks.register<Zip>("distZipImage") {
    group       = "distribution"
    description = "Zips the jpackageImage output for distribution."
    onlyIf { !(project.version as String).endsWith("-SNAPSHOT") }
    dependsOn("jpackageImage")
    from(layout.buildDirectory.dir("jpackage"))
    archiveBaseName.set("clouseau")
    archiveVersion.set(zipVersion)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

tasks.named("jpackage") {
    onlyIf { !(project.version as String).endsWith("-SNAPSHOT") }
}

runtime {
    // jlink options: produce a lean JRE image.
    options.set(listOf(
        "--strip-debug",
        "--compress", "zip-6",
        "--no-header-files",
        "--no-man-pages"
    ))

    // Modules required by the app and its dependencies.
    // java.desktop  — Swing / AWT
    // java.logging  — java.util.logging (used internally)
    // java.management — JMX (Guava, log4j2)
    // java.naming   — JNDI (log4j2 lookups)
    // java.prefs    — Preferences API
    // java.sql      — JDBC stubs pulled in transitively
    // java.xml      — XML (log4j2 config)
    // jdk.unsupported — sun.misc.Unsafe (Guava, Gson)
    // jdk.crypto.ec — TLS EC ciphers (HTTPS downloads, update checks)
    // jdk.zipfs     — ZipFileSystem (reading .zip log files)
    modules.set(listOf(
        "java.desktop",
        "java.logging",
        "java.management",
        "java.naming",
        "java.prefs",
        "java.sql",
        "java.xml",
        "jdk.unsupported",
        "jdk.crypto.ec",
        "jdk.zipfs"
    ))

    jpackage {
        imageName     = "Clouseau"
        installerName = "Clouseau"
        appVersion    = bundleVersion
        mainJar       = tasks.shadowJar.get().archiveFileName.get()

        val os           = org.gradle.internal.os.OperatingSystem.current()
        val packagingDir = rootProject.file("packaging")

        // JVM flags baked into the native launcher. On macOS/Linux the working directory of an
        // installed app is not meaningful, so keep data in the home folder (AppDirs expands "~/").
        jvmArgs = listOf("-Xms64m", "-Xmx2g") +
                if (os.isWindows) emptyList() else listOf("-Dclouseau.data.dir=~/.clouseau")

        when {
            os.isWindows -> {
                installerType = "exe"
                imageOptions = listOf("--icon", packagingDir.resolve("windows/clouseau.ico").absolutePath)
                installerOptions = listOf(
                    "--vendor", "Tlaloc",
                    "--win-per-user-install",
                    "--win-dir-chooser",
                    "--win-menu",
                    "--win-shortcut",
                    "--win-menu-group", "Clouseau"
                )
            }
            os.isMacOsX -> {
                installerType = "dmg"
                imageOptions = listOf("--icon", packagingDir.resolve("macos/clouseau.icns").absolutePath)
                installerOptions = listOf(
                    "--vendor", "Tlaloc",
                    "--mac-package-name", "Clouseau"
                )
            }
            else -> {
                installerType = "deb"
                imageOptions = listOf("--icon", packagingDir.resolve("linux/clouseau.png").absolutePath)
                installerOptions = listOf(
                    "--vendor", "Tlaloc",
                    "--linux-shortcut",
                    "--linux-menu-group", "Development;Utility;",
                    "--linux-app-category", "utils"
                )
            }
        }
    }
}

dependencies {
    implementation(project(":clouseau-core"))
    implementation(project(":clouseau-plugin"))

    implementation("com.google.guava:guava:${rootProject.extra["guavaVersion"]}")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.apache.logging.log4j:log4j-core:2.23.0")
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl:2.23.0")
    implementation("com.formdev:flatlaf:${rootProject.extra["flatlafVersion"]}")
    implementation("com.formdev:flatlaf-intellij-themes:${rootProject.extra["flatlafVersion"]}")
    implementation("com.miglayout:miglayout-swing:${rootProject.extra["migLayoutVersion"]}")
    implementation("com.fifesoft:rsyntaxtextarea:${rootProject.extra["rstaVersion"]}")
    implementation("com.hierynomus:sshj:0.38.0")
    // PF4J is on the runtime classpath via clouseau-plugin; needed here only for compilation
    // because LogParser extends ExtensionPoint
    compileOnly("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")
}

