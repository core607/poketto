package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import java.util.Locale;
import java.util.Objects;

final class RepositoryPathRules {
    private RepositoryPathRules() {}

    static String validate(String path) {
        Objects.requireNonNull(path, "repository path must not be null");
        if (path.isEmpty() || path.length() > ContentLimits.MAX_PATH_LENGTH || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("repository path must be a bounded relative path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()
                    || segment.equals(".")
                    || segment.equals("..")
                    || segment.equalsIgnoreCase(".git")
                    || segment.codePoints().anyMatch(c -> Character.isISOControl(c) || c == ':')) {
                throw new IllegalArgumentException("repository path contains an unsafe segment");
            }
        }
        return path;
    }

    static boolean markdown(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".md");
    }

    static boolean privatePath(String path) {
        return DocumentPathRules.collisionKey(path).startsWith("private/");
    }

    static boolean reserved(String path) {
        String key = DocumentPathRules.collisionKey(path);
        for (String segment : key.split("/", -1)) {
            if (segment.equals(".poketto")) return true;
        }
        return false;
    }

    static String route(String path) {
        String withoutExtension = path.substring(0, path.length() - 3);
        if (folderPage(path)) {
            return path.lastIndexOf('/') < 0 ? "/" : "/" + path.substring(0, path.lastIndexOf('/'));
        }
        return "/" + withoutExtension;
    }

    static boolean folderPage(String path) {
        return path.equals("index.md") || path.endsWith("/index.md");
    }

    static String validateRoute(String route) {
        if (route.equals("/")) return route;
        if (!route.startsWith("/")
                || route.contains("%")
                || route.contains("?")
                || route.contains("#")
                || route.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("route must be an absolute path without escapes, query, or fragment");
        }
        validate(route.substring(1));
        return route;
    }
}
