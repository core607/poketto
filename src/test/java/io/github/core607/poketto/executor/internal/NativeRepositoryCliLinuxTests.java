package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real CLI subprocesses with a transport stub; these tests do not verify native sandbox isolation. */
@EnabledOnOs(OS.LINUX)
class NativeRepositoryCliLinuxTests {
    private static final String TRANSPORT = """
            import base64,hashlib,json,os,sys
            assert sys.argv[1:]==['call','-']
            raw=sys.stdin.buffer.read()
            request=json.loads(raw)
            with open(os.environ['CLI_TEST_LOG'],'a') as log:
                log.write(json.dumps(request)+'\\n')
            mode=os.environ['CLI_TEST_MODE']
            if mode=='transport-failure':
                sys.stdout.buffer.write(b'transport stopped\\n')
                sys.exit(37)
            if mode=='denied':
                print(json.dumps({'ok':False,'code':'SYNTHETIC_DENIED'}))
            elif mode=='size':
                print(json.dumps({'ok':True,'bytes':len(raw)}))
            elif mode.startswith('receipt'):
                prefix=b'{"text":"'
                suffix=b'"}'
                length=65536-len(prefix)-len(suffix)
                content=prefix+'汉'.encode()*(length//3)+b'x'*(length%3)+suffix
                digest=hashlib.sha256(content).hexdigest()
                arguments=request['arguments']
                offset=arguments['offset']
                assert arguments['limit']==16384
                assert arguments['sha256']==(None if offset==0 else digest)
                chunk=content[offset:offset+16384]
                next_offset=offset+len(chunk)
                if mode=='receipt-changed' and offset:
                    digest='0'*64
                print(json.dumps({'ok':True,'result':{
                    'offset':offset,'sha256':digest,'base64':base64.b64encode(chunk).decode(),
                    'bytes':len(content)+(1 if mode=='receipt-oversized' else 0),
                    'nextOffset':None if next_offset==len(content) else next_offset}}))
            else:
                print(json.dumps({'ok':True,'result':request}))
            """;

    @TempDir
    Path root;

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Path> installedClients = new ArrayList<>();
    private Path repository;
    private Path tools;

    @BeforeEach
    void tools() throws Exception {
        repository = Files.createDirectory(root.resolve("repository with spaces"));
        tools = Files.createDirectory(root.resolve("tools"));
        var process = new ProcessBuilder("python3", "-I", "-c", "import sys;print(sys.executable)")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS))
                .as("Python tool discovery")
                .isTrue();
        assertThat(process.exitValue()).isZero();
        String python = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        Files.createSymbolicLink(tools.resolve("python3"), Path.of(python));
        Files.createSymbolicLink(tools.resolve("sh"), Path.of("/bin/sh"));
        executable(tools.resolve("sandcrab"), "#!" + python + " -I\n" + TRANSPORT);
    }

    @AfterEach
    void removeOnlyTheInstalledPrivateClients() throws IOException {
        for (Path client : installedClients) {
            assertThat(client.isAbsolute()).isTrue();
            assertThat(client.getFileName().toString()).isEqualTo("poketto");
            assertThat(client.getParent().getParent()).isEqualTo(Path.of("/tmp"));
            assertThat(client.getParent().getFileName().toString()).startsWith("poketto-cli.");
            assertThat(Files.isRegularFile(client, LinkOption.NOFOLLOW_LINKS)).isTrue();
            Files.delete(client);
            Files.delete(client.getParent());
        }
    }

    @Test
    void executableClientReachesPythonAndShChildrenWhereAnExportedFunctionCannot() throws Exception {
        Finished old = run("poketto() { return 0; }; export -f poketto; " + python("""
                                import subprocess
                                try:
                                    subprocess.run(['poketto','status'],check=True)
                                except FileNotFoundError:
                                    print('not an executable')
                                else:
                                    raise AssertionError('exported function unexpectedly became an executable')
                                """), "echo", false);
        assertThat(old.exit()).isZero();
        assertThat(old.output()).isEqualTo("not an executable\n");
        Finished installed = installed(python("""
                        import json,subprocess
                        commands=[['poketto','status'],['sh','-c','poketto status --remote']]
                        replies=[json.loads(subprocess.run(command,check=True,stdout=subprocess.PIPE).stdout)
                                 for command in commands]
                        print(json.dumps(replies))
                        """), "echo");
        assertThat(installed.exit()).as(installed.error()).isZero();
        JsonNode replies = json.readTree(installed.output());
        assertThat(replies.get(0).at("/result/operation").stringValue()).isEqualTo("status");
        assertThat(replies.get(0).at("/result/arguments").size()).isZero();
        assertThat(replies.get(1).at("/result/arguments/remote").booleanValue()).isTrue();
        assertThat(installed.requests()).hasSize(2);
    }

    @Test
    void privateInstallationLeavesRepositoryFilesAndShellUmaskAlone() throws Exception {
        Path retained = Files.writeString(root.resolve("retained-user-file"), "keep these bytes");
        Path userCommand = Files.createSymbolicLink(repository.resolve("poketto"), retained);
        for (String module : List.of("tempfile.py", "base64.py", "argparse.py")) {
            Files.writeString(repository.resolve(module), "raise RuntimeError('repository module was imported')\n");
        }
        Finished installed = run(
                "umask 0022; " + NativeRepositoryCli.install() + " && " + recordClient() + " && "
                        + "test \"$(umask)\" = 0022 && " + python("""
                                import json,os,shutil,stat,subprocess
                                client=shutil.which('poketto')
                                assert os.path.dirname(os.path.dirname(client))=='/tmp'
                                assert stat.S_IMODE(os.stat(os.path.dirname(client)).st_mode)==0o700
                                assert stat.S_IMODE(os.stat(client).st_mode)==0o700
                                result=subprocess.run(['poketto','status'],stdout=subprocess.PIPE,check=True)
                                print(json.loads(result.stdout)['ok'])
                                """),
                "echo",
                true);
        assertThat(installed.exit()).as(installed.error()).isZero();
        assertThat(installed.output()).isEqualTo("True\n");
        assertThat(Files.isSymbolicLink(userCommand)).isTrue();
        assertThat(Files.readString(retained)).isEqualTo("keep these bytes");
        try (var files = Files.list(repository)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("poketto", "tempfile.py", "base64.py", "argparse.py");
        }
    }

    @Test
    void childProcessesPreserveQuotedMediaAndSelectedFileArguments() throws Exception {
        Finished installed = installed(python("""
                        import json,subprocess
                        commands=[['poketto','media','import',"local '汉.bin",'--path',"public/with '汉.bin",
                                   '--type','image/png','--key','synthetic-import-key','--replace'],
                                  ['poketto','save',"private/with '汉.txt",'--delete','private/removed file.txt']]
                        for command in commands:
                            result=subprocess.run(command,stdout=subprocess.PIPE,check=True)
                            assert json.loads(result.stdout)['ok'] is True
                        """), "echo");
        assertThat(installed.exit()).as(installed.error()).isZero();
        JsonNode media = installed.requests().getFirst();
        assertThat(media.path("operation").stringValue()).isEqualTo("media_import");
        assertThat(media.at("/arguments/file").stringValue()).isEqualTo("local '汉.bin");
        assertThat(media.at("/arguments/path").stringValue()).isEqualTo("public/with '汉.bin");
        assertThat(media.at("/arguments/mediaType").stringValue()).isEqualTo("image/png");
        assertThat(media.at("/arguments/key").stringValue()).isEqualTo("synthetic-import-key");
        assertThat(media.at("/arguments/replace").booleanValue()).isTrue();
        JsonNode save = installed.requests().getLast();
        assertThat(save.path("operation").stringValue()).isEqualTo("save");
        assertThat(save.at("/arguments/writes/0").stringValue()).isEqualTo("private/with '汉.txt");
        assertThat(save.at("/arguments/deletes/0").stringValue()).isEqualTo("private/removed file.txt");
    }

    @Test
    void exactRequestBoundaryPassesAndOversizedRequestNeverReachesTransport() throws Exception {
        Finished installed = installed(python("""
                        import json,subprocess
                        empty={'operation':'save','arguments':{'writes':[''],'deletes':[]}}
                        length=65536-len(json.dumps(empty).encode())
                        exact=subprocess.run(['poketto','save','x'*length],stdout=subprocess.PIPE,check=True)
                        assert json.loads(exact.stdout)['bytes']==65536
                        oversized=subprocess.run(['poketto','save','x'*(length+1)],capture_output=True)
                        assert oversized.returncode==2 and not oversized.stdout
                        assert b'host request exceeds 64 KiB' in oversized.stderr
                        """), "size");
        assertThat(installed.exit()).as(installed.error()).isZero();
        assertThat(installed.requests()).hasSize(1);
    }

    @Test
    void transportAndApplicationFailureExitCodesRemainDistinct() throws Exception {
        Finished transport = installed("poketto status", "transport-failure");
        assertThat(transport.exit()).isEqualTo(37);
        assertThat(transport.output()).isEqualTo("transport stopped\n");
        Finished denied = installed("poketto status", "denied");
        assertThat(denied.exit()).isEqualTo(1);
        assertThat(json.readTree(denied.output()).path("code").stringValue()).isEqualTo("SYNTHETIC_DENIED");
    }

    @Test
    void completeMaximumReceiptIsReassembledThroughFourDigestBoundChildCalls() throws Exception {
        Finished receipt = installed("sh -c 'poketto receipt save'", "receipt");
        assertThat(receipt.exit()).as(receipt.error()).isZero();
        byte[] output = receipt.output().getBytes(StandardCharsets.UTF_8);
        assertThat(output).hasSize(65537);
        assertThat(json.readTree(receipt.output()).path("text").stringValue()).startsWith("汉");
        assertThat(receipt.requests()).hasSize(4);
        String digest = SandcrabProtocol.sha256(receipt.output().stripTrailing().getBytes(StandardCharsets.UTF_8));
        for (int page = 0; page < 4; page++) {
            JsonNode request = receipt.requests().get(page);
            assertThat(request.path("operation").stringValue()).isEqualTo("receipt");
            assertThat(request.at("/arguments/offset").intValue()).isEqualTo(page * 16384);
            assertThat(request.at("/arguments/kind").stringValue()).isEqualTo("save");
            if (page == 0) {
                assertThat(request.at("/arguments/sha256").isNull()).isTrue();
            } else {
                assertThat(request.at("/arguments/sha256").stringValue()).isEqualTo(digest);
            }
        }
    }

    @Test
    void changedOrOversizedReceiptsFailWithoutPublishingPartialBytes() throws Exception {
        Finished changed = installed("poketto receipt import", "receipt-changed");
        assertThat(changed.exit()).isEqualTo(2);
        assertThat(changed.output()).isEmpty();
        assertThat(changed.error()).contains("receipt changed during delivery");
        assertThat(changed.requests()).hasSize(2);
        Finished oversized = installed("poketto receipt save", "receipt-oversized");
        assertThat(oversized.exit()).isEqualTo(2);
        assertThat(oversized.output()).isEmpty();
        assertThat(oversized.error()).contains("invalid receipt delivery length");
        assertThat(oversized.requests()).hasSize(1);
    }

    @Test
    void failedInstallationDoesNotPublishAnEmptyPathOrHideItsExitCode() throws Exception {
        Files.delete(tools.resolve("python3"));
        executable(tools.resolve("python3"), "#!/bin/sh\nexit 19\n");
        Finished failed = run(
                "before=$PATH; " + NativeRepositoryCli.install()
                        + "; result=$?; test \"$PATH\" = \"$before\" || exit 99; exit \"$result\"",
                "echo",
                false);
        assertThat(failed.exit()).isEqualTo(19);
        assertThat(failed.output()).isEmpty();
        assertThat(failed.requests()).isEmpty();
    }

    private Finished installed(String command, String mode) throws Exception {
        return run(NativeRepositoryCli.install() + " && " + recordClient() + " && " + command, mode, true);
    }

    private static String recordClient() {
        return "command -v poketto > \"$CLI_INSTALL_PATH_FILE\"";
    }

    private static String python(String source) {
        return "python3 -I -c '" + source.replace("'", "'\"'\"'") + "'";
    }

    private Finished run(String source, String mode, boolean installed) throws Exception {
        String id = UUID.randomUUID().toString();
        Path output = root.resolve(id + ".out");
        Path error = root.resolve(id + ".err");
        Path requests = root.resolve(id + ".requests");
        Path client = root.resolve(id + ".client");
        var builder = new ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", source)
                .directory(repository.toFile())
                .redirectOutput(output.toFile())
                .redirectError(error.toFile());
        builder.environment().put("PATH", tools.toString());
        builder.environment().put("TMPDIR", repository.toString());
        builder.environment().put("PYTHONPATH", repository.toString());
        builder.environment().put("CLI_TEST_MODE", mode);
        builder.environment().put("CLI_TEST_LOG", requests.toString());
        builder.environment().put("CLI_INSTALL_PATH_FILE", client.toString());
        builder.environment().remove("BASH_ENV");
        Process process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS))
                    .as("bounded CLI process")
                    .isTrue();
            if (installed && Files.exists(client)) {
                installedClients.add(Path.of(Files.readString(client).strip()));
            }
            List<JsonNode> sent = new ArrayList<>();
            if (Files.exists(requests)) {
                for (String line : Files.readAllLines(requests)) {
                    sent.add(json.readTree(line));
                }
            }
            return new Finished(process.exitValue(), Files.readString(output), Files.readString(error), sent);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static void executable(Path path, String source) throws IOException {
        Files.writeString(path, source);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }

    private record Finished(int exit, String output, String error, List<JsonNode> requests) {}
}
