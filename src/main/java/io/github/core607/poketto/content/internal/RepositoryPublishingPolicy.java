package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

/** Snapshot-local publication policy. Path eligibility alone never authorizes image delivery. */
final class RepositoryPublishingPolicy {

    static final String PATH = ".poketto/publishing.yaml";
    static final int MAX_BYTES = 16 * 1024;
    static final int MAX_EXCLUSIONS = 64;
    private static final Set<String> FIELDS = Set.of("enabled", "mode", "exclude");

    enum State {
        MISSING,
        DISABLED,
        ENABLED,
        INVALID
    }

    private final State state;
    private final List<String[]> exclusions;

    private RepositoryPublishingPolicy(State state, List<String[]> exclusions) {
        this.state = state;
        this.exclusions = List.copyOf(exclusions);
    }

    static RepositoryPublishingPolicy missing() {
        return new RepositoryPublishingPolicy(State.MISSING, List.of());
    }

    /** Invalid bytes produce a closed policy, not a fallback to a previous commit's decision. */
    static RepositoryPublishingPolicy parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            return invalid();
        }
        try {
            String source = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(8);
            options.setCodePointLimit(MAX_BYTES);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            if (!(yaml.compose(new StringReader(source)) instanceof MappingNode mapping)) {
                return invalid();
            }
            // SafeConstructor flattens merge keys before its duplicate-key check.
            Set<String> keys = new HashSet<>();
            for (var entry : mapping.getValue()) {
                if (!(entry.getKeyNode() instanceof ScalarNode key)
                        || !key.getTag().equals(Tag.STR)
                        || !FIELDS.contains(key.getValue())
                        || !keys.add(key.getValue())) {
                    return invalid();
                }
            }
            Object loaded = yaml.load(source);
            if (!(loaded instanceof Map<?, ?> fields)
                    || !FIELDS.containsAll(fields.keySet())
                    || !(fields.get("enabled") instanceof Boolean enabled)
                    || !"public-by-default".equals(fields.get("mode"))) {
                return invalid();
            }
            Object configured = fields.containsKey("exclude") ? fields.get("exclude") : List.of();
            if (!(configured instanceof List<?> patterns) || patterns.size() > MAX_EXCLUSIONS) {
                return invalid();
            }
            List<String[]> exclusions = new ArrayList<>();
            for (Object candidate : patterns) {
                if (!(candidate instanceof String pattern) || !validPattern(pattern)) {
                    return invalid();
                }
                exclusions.add(pattern.split("/", -1));
            }
            return new RepositoryPublishingPolicy(enabled ? State.ENABLED : State.DISABLED, exclusions);
        } catch (CharacterCodingException | RuntimeException exception) {
            // Parser diagnostics can contain private policy text; callers receive only the state.
            return invalid();
        }
    }

    State state() {
        return state;
    }

    /** The caller must separately verify file mode, document eligibility, and image reachability. */
    boolean permitsPath(String path) {
        if (state != State.ENABLED || !validPath(path)) {
            return false;
        }
        String[] segments = path.split("/", -1);
        if (segments[0].equals("private")) {
            return false;
        }
        for (String segment : segments) {
            if (segment.equalsIgnoreCase(".git") || segment.equalsIgnoreCase(".poketto")) {
                return false;
            }
        }
        for (String[] pattern : exclusions) {
            if (matches(pattern, segments)) {
                return false;
            }
        }
        return true;
    }

    private static RepositoryPublishingPolicy invalid() {
        return new RepositoryPublishingPolicy(State.INVALID, List.of());
    }

    private static boolean validPath(String value) {
        if (value == null || value.isEmpty() || value.length() > ContentLimits.MAX_PATH_LENGTH) {
            return false;
        }
        if (value.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '\\' || c == ':')) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static boolean validPattern(String value) {
        if (!validPath(value)) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if ((segment.contains("**") && !segment.equals("**"))
                    || segment.indexOf('[') >= 0
                    || segment.indexOf(']') >= 0
                    || segment.indexOf('{') >= 0
                    || segment.indexOf('}') >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(String[] pattern, String[] path) {
        boolean[][] matched = new boolean[pattern.length + 1][path.length + 1];
        matched[0][0] = true;
        for (int p = 0; p < pattern.length; p++) {
            for (int s = 0; s <= path.length; s++) {
                if (pattern[p].equals("**")) {
                    matched[p + 1][s] = matched[p][s] || (s > 0 && matched[p + 1][s - 1]);
                } else if (s > 0) {
                    matched[p + 1][s] = matched[p][s - 1] && matchesSegment(pattern[p], path[s - 1]);
                }
            }
        }
        return matched[pattern.length][path.length];
    }

    private static boolean matchesSegment(String pattern, String value) {
        int[] characters = value.codePoints().toArray();
        boolean[] previous = new boolean[characters.length + 1];
        previous[0] = true;
        for (int token : pattern.codePoints().toArray()) {
            boolean[] next = new boolean[characters.length + 1];
            next[0] = token == '*' && previous[0];
            for (int s = 1; s <= characters.length; s++) {
                next[s] = token == '*'
                        ? previous[s] || next[s - 1]
                        : previous[s - 1] && (token == '?' || token == characters[s - 1]);
            }
            previous = next;
        }
        return previous[characters.length];
    }
}
