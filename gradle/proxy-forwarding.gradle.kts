val proxyForwardingCheck = tasks.register<Exec>("proxyForwardingCheck") {
    group = "verification"
    description = "Verifies production Compose IP allocation, trusted Caddy forwarding and real login buckets."
    dependsOn("stageAcceptanceRuntime")
    outputs.upToDateWhen { false }
    timeout.set(java.time.Duration.ofMinutes(25))
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
        layout.projectDirectory.file("deploy/tests/validate_proxy_forwarding.py").asFile.absolutePath)
}
tasks.named("check") { dependsOn(proxyForwardingCheck) }
