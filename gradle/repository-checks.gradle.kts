import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import org.gradle.api.GradleException

val repositoryRoot: Path = rootDir.toPath().toAbsolutePath().normalize()
val excludedDirectoryNames = setOf(".git", ".gradle", "build")

fun repositoryFiles(predicate: (Path) -> Boolean): List<Path> =
    Files.walk(repositoryRoot).use { paths ->
        paths
            .filter { path ->
                val relative = repositoryRoot.relativize(path)
                relative.none { segment -> segment.toString() in excludedDirectoryNames }
            }
            .filter(predicate)
            .sorted()
            .toList()
    }

fun Path.repositoryPath(): String = repositoryRoot.relativize(this).toString().replace('\\', '/')

tasks.register("repoCheck") {
    group = "verification"
    description = "Validates repository documents, agent rules, and skill metadata."
    inputs.files(
        fileTree(repositoryRoot) {
            exclude(".git/**", ".gradle/**", "build/**", "notes/archived/**")
        },
    )

    doLast {
        val errors = mutableListOf<String>()
        val markdownFiles = repositoryFiles { path ->
            path.isRegularFile() && path.extension.equals("md", ignoreCase = true) &&
                !path.repositoryPath().startsWith("notes/archived/")
        }

        val markdownLink = Regex("""\[[^]]*]\(([^)]+)\)""")
        markdownFiles.forEach { source ->
            val text = Files.readString(source, StandardCharsets.UTF_8)
            markdownLink.findAll(text).forEach { match ->
                val rawTarget = match.groupValues[1].trim().removeSurrounding("<", ">")
                if (
                    rawTarget.startsWith("http://") ||
                    rawTarget.startsWith("https://") ||
                    rawTarget.startsWith("mailto:") ||
                    rawTarget.startsWith("#")
                ) {
                    return@forEach
                }
                val pathPart = rawTarget
                    .substringBefore(" \"")
                    .substringBefore(" '")
                    .substringBefore('#')
                    .replace("%20", " ")
                if (pathPart.isBlank()) {
                    return@forEach
                }
                val target = source.parent.resolve(pathPart).normalize()
                if (!target.startsWith(repositoryRoot) || !Files.exists(target)) {
                    errors += "${source.repositoryPath()}: unresolved local link '$rawTarget'"
                }
            }
        }

        val requiredPairs = listOf(
            "README.md" to "README.zh.md",
            "notes/implemented/2026-08-25-requirements-and-architecture.md" to
                "notes/implemented/2026-08-25-requirements-and-architecture.zh.md",
        )
        requiredPairs.forEach { (english, chinese) ->
            if (!Files.isRegularFile(repositoryRoot.resolve(english))) {
                errors += "missing required English document: $english"
            }
            if (!Files.isRegularFile(repositoryRoot.resolve(chinese))) {
                errors += "missing required Chinese counterpart: $chinese"
            }
        }

        repositoryFiles { path -> path.isRegularFile() && path.name.endsWith(".zh.md") }
            .forEach { chinese ->
                val englishName = chinese.name.removeSuffix(".zh.md") + ".md"
                val english = chinese.resolveSibling(englishName)
                if (!english.isRegularFile()) {
                    errors += "Chinese document has no English counterpart: ${chinese.repositoryPath()}"
                }
            }

        val hanCharacter = Regex("""\p{IsHan}""")
        val englishAgentSurfaces = buildList {
            add(repositoryRoot.resolve("AGENTS.md"))
            add(repositoryRoot.resolve("CLAUDE.md"))
            addAll(
                repositoryFiles { path ->
                    path.isRegularFile() && path.repositoryPath().startsWith(".agents/skills/") &&
                        (path.name == "SKILL.md" || path.extension == "yaml" || path.extension == "yml")
                },
            )
        }
        englishAgentSurfaces.forEach { source ->
            if (!source.isRegularFile()) {
                errors += "missing agent surface: ${source.repositoryPath()}"
            } else if (hanCharacter.containsMatchIn(Files.readString(source, StandardCharsets.UTF_8))) {
                errors += "English-only agent surface contains Han characters: ${source.repositoryPath()}"
            }
        }

        val skillsRoot = repositoryRoot.resolve(".agents/skills")
        val skillDirectories = Files.list(skillsRoot).use { paths ->
            paths.filter(Path::isDirectory).sorted().toList()
        }
        val skillNames = mutableSetOf<String>()
        skillDirectories.forEach { directory ->
            val skillFile = directory.resolve("SKILL.md")
            if (!skillFile.isRegularFile()) {
                errors += "missing SKILL.md: ${directory.repositoryPath()}"
                return@forEach
            }
            val normalized = Files.readString(skillFile, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
            val frontmatterEnd = normalized.indexOf("\n---\n", startIndex = 4)
            if (!normalized.startsWith("---\n") || frontmatterEnd < 0) {
                errors += "invalid skill frontmatter: ${skillFile.repositoryPath()}"
                return@forEach
            }
            val metadata = normalized.substring(4, frontmatterEnd)
                .lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
                .toMap()
            val name = metadata["name"]
            if (name != directory.name) {
                errors += "skill name '$name' does not match directory '${directory.name}'"
            }
            if (metadata["description"].isNullOrBlank()) {
                errors += "skill description is missing: ${skillFile.repositoryPath()}"
            }
            if (name != null && !skillNames.add(name)) {
                errors += "duplicate skill name: $name"
            }
        }

        val agentsText = Files.readString(repositoryRoot.resolve("AGENTS.md"), StandardCharsets.UTF_8)
        val inventoryNames = Regex("""\.agents/skills/([a-z0-9-]+)/SKILL\.md""")
            .findAll(agentsText)
            .map { it.groupValues[1] }
            .toSet()
        val directoryNames = skillDirectories.map(Path::name).toSet()
        if (inventoryNames != directoryNames) {
            errors += "AGENTS.md skill inventory differs from .agents/skills: " +
                "listed=${inventoryNames.sorted()}, actual=${directoryNames.sorted()}"
        }

        val translatePolicy = skillsRoot.resolve("translate-docs/agents/openai.yaml")
        if (!translatePolicy.isRegularFile() ||
            !Files.readString(translatePolicy, StandardCharsets.UTF_8)
                .contains("allow_implicit_invocation: false")
        ) {
            errors += "translate-docs must disable implicit invocation"
        }

        val gitignore = repositoryRoot.resolve(".gitignore")
        if (!gitignore.isRegularFile()) {
            errors += "missing .gitignore"
        } else {
            val lines = Files.readAllLines(gitignore, StandardCharsets.UTF_8)
            if (lines.firstOrNull() != ".env") {
                errors += ".env must be the first line of .gitignore"
            }
            if (".env.*" !in lines) {
                errors += ".gitignore must exclude .env.* files"
            }
        }

        if (errors.isNotEmpty()) {
            throw GradleException(
                errors.joinToString(
                    separator = "\n- ",
                    prefix = "Repository checks failed:\n- ",
                ),
            )
        }

        logger.lifecycle(
            "Repository checks passed: ${markdownFiles.size} Markdown files, " +
                "${skillDirectories.size} skills.",
        )
    }
}
