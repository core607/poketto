package io.github.core607.poketto.assets.internal;

import static org.assertj.core.api.Assertions.*;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ManagedBlobStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class UnsupportedManagedStorageTests {
    @TempDir
    Path temp;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void refusesToAcknowledgeDurabilityWhenDirectoryFsyncIsUnavailable() {
        assertThatThrownBy(() -> ManagedBlobStore.local(temp.resolve("originals")))
                .isInstanceOfSatisfying(
                        AssetStorageException.class,
                        error -> assertThat(error.reason()).isEqualTo(AssetStorageException.Reason.UNAVAILABLE))
                .hasMessageContaining("directory fsync");
    }
}
