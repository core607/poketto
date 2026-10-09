package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.games.GameBundle;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual Java adapter, signed worker and SRT jobs; only account identities are synthetic. */
final class GameNativeProbe {
    private final JsonMapper json = JsonMapper.shared();
    private final JsonNode config;
    private final IsolatedGameRunner runner;
    private final GameRunner.Identity identity =
            new GameRunner.Identity(UUID.randomUUID(), UUID.randomUUID(), WorkspaceId.random());

    GameNativeProbe(JsonNode config) {
        this.config = config;
        runner = new IsolatedGameRunner(
                ExecutorConfiguration.workerClient(json, path("gameSocket"), path("gamePrivateKey")), json, 4);
    }

    void run(RepositoryRead repository, Control control) throws Exception {
        example();
        confinement();
        limits(control);
        contention(repository, control);
        revocation(control);
        restart(control);
        control.run("game-assert-clean");
        System.out.println(json.writeValueAsString(new Summary("PASS")));
    }

    private void example() throws Exception {
        String source = Files.readString(path("gameExample"));
        var bundle = new GameBundle(1, source, null, Map.of());
        GameRunner.Result result = runner.run(identity, bundle, new GameRunner.Request("init", null, null, 42L));
        for (String action : new String[] {"inspect", "light", "open"}) {
            result = runner.run(identity, bundle, new GameRunner.Request("act", result.state(), action, null));
        }
        assertThat(result.observation().done()).isTrue();
        assertThat(result.observation().text()).contains("gate opens");
        GameRunner.Result large = initialize(rules("return {text:'猫'.repeat(8000)};"));
        assertThat(large.state().path("text").stringValue()).hasSize(8000);
        passed("native-game-shared-example-and-complete-utf8-save", null, null);
    }

    private void confinement() {
        String source = """
                import fs from 'node:fs'; import net from 'node:net';
                const blocked = await new Promise(resolve => {
                  const s=net.connect({host:'127.0.0.1',port:%s});
                  s.setTimeout(700); s.on('connect',()=>{s.destroy();resolve(false)});
                  s.on('error',()=>resolve(true)); s.on('timeout',()=>{s.destroy();resolve(true)});
                });
                const unixBlocked = await new Promise(resolve => {
                  const s=net.connect({path:%s});
                  s.setTimeout(700); s.on('connect',()=>{s.destroy();resolve(false)});
                  s.on('error',()=>resolve(true)); s.on('timeout',()=>{s.destroy();resolve(true)});
                });
                function denied(path) { try {fs.readFileSync(path); return false} catch {return true} }
                export function init(){return {blocked,unixBlocked,hostDenied:denied(%s),keyDenied:denied(%s),noBridge:!process.env.POKETTO_BRIDGE}}
                export function observe(){return {text:'Checked',actions:[],done:false}}
                export function act(s){return s}
                """.formatted(
                        config.path("gameHostPort").intValue(),
                        quoted("socket"),
                        quoted("gameHostFile"),
                        quoted("gamePrivateKey"));
        JsonNode state = initialize(source).state();
        for (String name : new String[] {"blocked", "unixBlocked", "hostDenied", "keyDenied", "noBridge"}) {
            assertThat(state.path(name).booleanValue()).as(name).isTrue();
        }
        passed("native-game-denies-host-files-credentials-tcp-unix-and-bridge", null, null);
    }

    private void limits(Control control) throws Exception {
        assertThatThrownBy(() -> initialize(rules("console.log('x'.repeat(200000));return {};")))
                .isInstanceOf(GameException.class);
        control.run("game-assert-clean");
        assertThatThrownBy(() -> initialize(rules("const a=[];for(;;){a.push(Buffer.alloc(8*1024*1024,1))}")))
                .isInstanceOf(GameException.class);
        control.run("game-assert-clean");
        String children = "import {spawn} from 'node:child_process';\n"
                + rules(
                        "for(let i=0;i<100;i++){try{const p=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{detached:true,stdio:'ignore'});p.on('error',()=>{});p.unref()}catch{}} return {}; ");
        try {
            initialize(children);
        } catch (GameException bounded) {
            assertThat(bounded.code()).isIn("INVALID_GAME", "GAME_UNAVAILABLE");
        }
        control.run("game-assert-clean");
        assertThat(initialize(rules("return {afterLimits:true};"))
                        .state()
                        .path("afterLimits")
                        .booleanValue())
                .isTrue();
        passed("native-game-output-memory-descendants-and-readmission", null, null);
    }

    private void contention(RepositoryRead repository, Control control) throws Exception {
        repository.run();
        double baseline = repository.run();
        CompletableFuture<GameRunner.Result> active = busy();
        control.run("game-await-running");
        assertThatThrownBy(() -> initialize(rules("return {};")))
                .isInstanceOfSatisfying(
                        GameException.class, error -> assertThat(error.code()).isEqualTo("GAME_BUSY"));
        double concurrent = repository.run();
        assertThatThrownBy(() -> active.get(12, TimeUnit.SECONDS)).hasCauseInstanceOf(GameException.class);
        control.run("game-assert-clean");
        assertThat(initialize(rules("return {ready:true};"))
                        .state()
                        .path("ready")
                        .booleanValue())
                .isTrue();
        passed("native-game-admission-does-not-consume-repository-capacity", baseline, concurrent);
    }

    private void revocation(Control control) throws Exception {
        CompletableFuture<GameRunner.Result> active = busy();
        control.run("game-await-running");
        runner.revoked(new AuthRevocation(identity.workspaceId(), Set.of(identity.accountId()), Set.of()));
        assertThatThrownBy(() -> active.get(12, TimeUnit.SECONDS)).hasCauseInstanceOf(GameException.class);
        assertThatThrownBy(() -> initialize(rules("return {};"))).isInstanceOf(GameException.class);
        control.run("game-assert-clean");
        passed("native-game-account-revocation-terminates-active-job", null, null);
    }

    private void restart(Control control) throws Exception {
        var other = new GameRunner.Identity(UUID.randomUUID(), UUID.randomUUID(), identity.workspaceId());
        CompletableFuture<GameRunner.Result> active = CompletableFuture.supplyAsync(() -> runner.run(
                other,
                new GameBundle(1, rules("process.title='pkt-game-live';for(;;){}"), null, Map.of()),
                new GameRunner.Request("init", null, null, 1L)));
        control.run("game-await-running");
        control.run("game-restart-worker");
        assertThatThrownBy(() -> active.get(12, TimeUnit.SECONDS)).hasCauseInstanceOf(GameException.class);
        assertThat(initialize(rules("return {afterRestart:true};"))
                        .state()
                        .path("afterRestart")
                        .booleanValue())
                .isTrue();
        control.run("game-assert-clean");
        passed("native-game-supervisor-loss-cleans-jobs-before-readmission", null, null);
    }

    private CompletableFuture<GameRunner.Result> busy() {
        return CompletableFuture.supplyAsync(() -> initialize(rules("process.title='pkt-game-live';for(;;){}")));
    }

    private GameRunner.Result initialize(String source) {
        return runner.run(
                identity, new GameBundle(1, source, null, Map.of()), new GameRunner.Request("init", null, null, 1L));
    }

    private static String rules(String body) {
        return "export function init(){" + body
                + "} export function observe(){return {text:'Ready',actions:[],done:false}} export function act(s){return s}";
    }

    private String quoted(String property) {
        return json.writeValueAsString(config.path(property).stringValue());
    }

    private Path path(String property) {
        return Path.of(config.path(property).stringValue());
    }

    private void passed(String test, Double repositoryBaselineMillis, Double repositoryWithGameMillis) {
        System.out.println(json.writeValueAsString(
                new Evidence(test, "PASS", repositoryBaselineMillis, repositoryWithGameMillis)));
    }

    private record Evidence(
            String test, String result, Double repositoryBaselineMillis, Double repositoryWithGameMillis) {}

    private record Summary(String gameNative) {}

    @FunctionalInterface
    interface RepositoryRead {
        double run() throws Exception;
    }

    @FunctionalInterface
    interface Control {
        void run(String operation) throws Exception;
    }
}
