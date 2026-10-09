package io.github.core607.poketto.plaza;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;

/** One in-process public street action. Ordinary actions never acquire an executor lease. */
public interface PlazaService {
    PlazaResult execute(AuthPrincipal actor, WorkspaceId workspace, String command, String clientName);
}
