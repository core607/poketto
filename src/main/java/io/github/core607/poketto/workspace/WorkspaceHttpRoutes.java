package io.github.core607.poketto.workspace;

/** Canonical workspace routes shared by controllers and pre-controller request bounds. */
public final class WorkspaceHttpRoutes {
    public static final String ADMIN = "/api/admin/workspaces/";

    private WorkspaceHttpRoutes() {}

    public static WorkspaceId workspace(String path) {
        if (!path.startsWith(ADMIN)) {
            throw new IllegalArgumentException("A workspace route is required");
        }
        int end = path.indexOf('/', ADMIN.length());
        if (end < 0) {
            throw new IllegalArgumentException("A workspace operation is required");
        }
        return WorkspaceId.parse(path.substring(ADMIN.length(), end));
    }

    public static String operation(String path) {
        if (!path.startsWith(ADMIN)) {
            return path;
        }
        try {
            workspace(path);
        } catch (IllegalArgumentException invalid) {
            return path;
        }
        return "/api/admin" + path.substring(path.indexOf('/', ADMIN.length()));
    }

    public static String admin(WorkspaceId workspace) {
        return ADMIN + workspace;
    }
}
