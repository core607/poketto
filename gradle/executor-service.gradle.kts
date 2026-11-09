import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.zip.ZipInputStream

val executorServiceSource = layout.projectDirectory.dir("executor-service")

val releaseName = "sandcrab-linux-x86_64.zip"
val releaseDocuments = listOf("LICENSE", "THIRD-PARTY-NOTICES.txt", "rust-library-copyright.html", "THREAT-MODEL.md",
    "bwrap-notices.txt", "bwrap-source.tar.xz")
val releasePin = executorServiceSource.file("sandcrab-release.properties")
val suppliedRelease = providers.gradleProperty("sandcrabBundle").map { project.file(it) }
    .orElse(gradle.gradleUserHomeDir.resolve("sandcrab/$releaseName"))
val verifiedRelease = layout.buildDirectory.file("sandcrab-release/$releaseName")
val stageSandcrabRelease = tasks.register("stageSandcrabRelease") {
    group = "distribution"
    description = "Verify and stage the operator-supplied Sandcrab release."
    inputs.file(releasePin)
    inputs.files(suppliedRelease)
    outputs.file(verifiedRelease)
    doLast {
        val pin = Properties().apply { releasePin.asFile.inputStream().use { load(it) } }
        val source = suppliedRelease.get()
        require(source.isFile) { "Supply the Sandcrab ${pin.getProperty("version")} ZIP with -PsandcrabBundle=<path> or place it at $source" }
        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val bound = 16 * 1024 * 1024
        val archive = source.inputStream().use { it.readNBytes(bound + 1) }
        require(archive.size <= bound && sha256(archive) == pin.getProperty("bundleSha256")) { "Sandcrab release ZIP checksum mismatch" }
        ZipInputStream(ByteArrayInputStream(archive)).use { entries ->
            val binary = generateSequence { entries.nextEntry }.firstOrNull { it.name == "sandcrab" }
            require(binary != null && sha256(entries.readNBytes(bound + 1)) == pin.getProperty("sandcrabSha256")) {
                "Sandcrab executable checksum mismatch"
            }
        }
        val target = verifiedRelease.get().asFile.toPath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "sandcrab-", ".zip")
        try {
            Files.write(temporary, archive)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

tasks.register<Sync>("stageGameWorker") {
    group = "build"
    description = "Stage Sandcrab, the game profile and a synthetic example for native acceptance."
    dependsOn(stageSandcrabRelease)
    from(verifiedRelease.map { zipTree(it) }) {
        include("sandcrab", "bwrap")
        filePermissions { unix("755") }
    }
    from(verifiedRelease.map { zipTree(it) }) {
        include(releaseDocuments)
    }
    from(executorServiceSource) {
        include("game-config.example.toml", "sandcrab-games.service", "pokettogames.slice")
    }
    from(layout.projectDirectory.file("examples/pocket-game/rules.mjs")) { rename { "game-example.mjs" } }
    into(layout.buildDirectory.dir("game-worker"))
    doLast {
        releaseDocuments.forEach { name ->
            val document = destinationDir.resolve(name)
            check(document.isFile && document.length() > 0) { "Sandcrab staging is missing required release material: $name" }
        }
    }
}
