package io.github.core607.poketto.content.internal;

import org.eclipse.jgit.lib.ObjectId;

record GitCommitOutcome(ObjectId commit, boolean mirrored) {
}
