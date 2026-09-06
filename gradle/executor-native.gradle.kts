import java.security.MessageDigest
import java.util.HexFormat

val nativeRuntime = layout.buildDirectory.dir("executor-native/runtime")

tasks.register<Sync>("stageExecutorNativeTest") {
    group = "verification"
    description = "Stages only compiled classes and resolved dependencies for real Linux executor acceptance."
    dependsOn(tasks.named("testClasses"))
    into(nativeRuntime)
    val sources = project.extensions.getByType<SourceSetContainer>()
    from(sources.named("main").get().output.classesDirs) { into("classes") }
    from(sources.named("test").get().output.classesDirs) { into("classes") }
    from(configurations.named("testRuntimeClasspath")) { into("jars") }
    doLast {
        val root = nativeRuntime.get().asFile
        val manifest = root.walkTopDown().filter { it.isFile && it.name != "manifest.sha256" }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .joinToString("\n", postfix = "\n") { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                HexFormat.of().formatHex(digest.digest()) + "  " + file.relativeTo(root).invariantSeparatorsPath
            }
        root.resolve("manifest.sha256").writeText(manifest)
    }
}
