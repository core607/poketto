package io.github.core607.poketto.acceptance;

import io.github.core607.poketto.PokettoApplication;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.springframework.boot.SpringApplication;

/** Synthetic real-service entrance, compiled only with integration fixtures and never into the production image. */
public final class AcceptanceApplication {
    private AcceptanceApplication() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(required("POKETTO_ACCEPTANCE_ROOT"));
        if (!root.isAbsolute()) throw new IllegalArgumentException("acceptance root must be absolute");
        Files.createDirectories(root);
        try (var existing = Files.list(root)) {
            if (existing.findAny().isPresent()) throw new IllegalArgumentException("acceptance root must be empty");
        }
        String password = required("POKETTO_ACCEPTANCE_PASSWORD");
        String initialization = UUID.randomUUID().toString();
        Path remote = root.resolve("remote.git");
        seed(remote, root.resolve("seed"));
        SpringApplication app =
                new SpringApplication(PokettoApplication.class, RemoteRepositoryIntegrationConfiguration.class);
        app.setDefaultProperties(Map.of(
                "poketto.data-dir", root.resolve("data").toString(),
                "poketto.test.repository-path", remote.toString(),
                "poketto.auth.initialization-token", initialization,
                "poketto.security.allowed-origins", required("POKETTO_ACCEPTANCE_ORIGIN"),
                "server.servlet.session.cookie.secure", false));
        var context = app.run(args);
        context.getBean(AuthService.class).initializeOwner(initialization, "owner", password);
        System.out.println("Synthetic acceptance services are ready; production repositories are not used.");
    }

    private static void seed(Path remote, Path directory) throws Exception {
        try (Git ignored = Git.init()
                        .setBare(true)
                        .setInitialBranch("main")
                        .setDirectory(remote.toFile())
                        .call();
                Git git = Git.init()
                        .setInitialBranch("main")
                        .setDirectory(directory.toFile())
                        .call()) {
            write(
                    directory,
                    ".poketto/publishing.yaml",
                    "enabled: true\nmode: public-by-default\nexclude: [drafts/**]\n");
            write(directory, "index.md", "# 窗边的知识站\n\n这是一份隔离验收样例，用于检查真实的阅读、图片和编辑流程。\n\n[翻开第一篇](随记/雨后.md)\n");
            write(
                    directory,
                    "随记/雨后.md",
                    "---\ntitle: 雨停之后，留一页给散步\ntags: [日常, 观察]\ncreated_at: 2026-09-01T08:00:00Z\n---\n\n# 雨停之后\n\n街边的叶子还亮着。把今天读到的一句话收好，回家再慢慢想。\n\n![验收图片](../sample.png)\n\n## 留下来的东西\n\n- 一段可以检索的中文文字\n- 一个带有 Git 历史的普通文件\n\n```text\n原文、路径和修改记录都属于内容仓。\n```\n");
            write(directory, "手册/index.md", "# 使用手册\n\n文件夹页面附带同目录的图片画廊。\n");
            write(
                    directory,
                    "手册/写作.md",
                    "---\ntags: [手册]\ncreated_at: 2026-08-30T08:00:00Z\n---\n# 从一段 Markdown 开始\n\n正文无需补齐元数据即可读取。修改时保留未改动的内容。\n");
            write(directory, "private/日记.md", "# 私有验收样例\n\nPRIVATE_ACCEPTANCE_SENTINEL\n\n![私有图片](hidden.png)\n");
            write(directory, "drafts/草稿.md", "# 排除路径\n\nEXCLUDED_ACCEPTANCE_SENTINEL\n");
            BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++)
                    image.setRGB(x, y, ((120 + x / 4) << 16) | ((150 + y / 3) << 8) | 160);
            }
            ImageIO.write(image, "png", directory.resolve("sample.png").toFile());
            ImageIO.write(image, "png", directory.resolve("手册/gallery.png").toFile());
            ImageIO.write(image, "png", directory.resolve("private/hidden.png").toFile());
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("Create synthetic acceptance content")
                    .setAuthor("Acceptance", "acceptance@example.invalid")
                    .call();
            git.push()
                    .setRemote(remote.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
        }
    }

    private static void write(Path directory, String relative, String value) throws Exception {
        Path target = directory.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, value);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " is required for synthetic acceptance");
        return value;
    }
}
