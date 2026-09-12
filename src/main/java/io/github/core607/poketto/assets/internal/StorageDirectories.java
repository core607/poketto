package io.github.core607.poketto.assets.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.core607.poketto.assets.AssetStorageException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** The rule every local store applies to the directories it writes through. */
final class StorageDirectories {

    private StorageDirectories() {}

    /**
     * Refuses one component of a storage path that is not a plain directory. Stores walk their
     * paths component by component and call this on each, so a symlink planted at any depth is
     * caught before a later write follows it out of the store. The attributes are read with
     * NOFOLLOW_LINKS, so they describe the component itself rather than whatever it points at, and
     * the resolved path is compared against the component so a redirection the attributes do not
     * show -- a Windows junction, a name resolving elsewhere -- is caught as well.
     */
    static void requireContained(Path component) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(component, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isDirectory()
                || attributes.isSymbolicLink()
                || !component.toRealPath().equals(component)) {
            throw new AssetStorageException(AssetStorageException.Reason.UNAVAILABLE);
        }
    }
}
