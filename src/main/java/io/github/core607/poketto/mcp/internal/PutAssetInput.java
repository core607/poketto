package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.assets.ImageTransfers;
import io.github.core607.poketto.assets.ManagedBlobStore;

/** A file reference, a programmatic byte payload, or a request for a raw upload grant. */
record PutAssetInput(String operationKey, String mode, String url, FileInput file, String base64) {
    PutAssetInput {
        ImageTransfers.validateKey(operationKey);
        mode = mode == null ? "import" : mode;
        int sources = (url == null ? 0 : 1) + (file == null ? 0 : 1) + (base64 == null ? 0 : 1);
        if (mode.equals("upload")) {
            if (sources != 0) {
                throw new IllegalArgumentException("upload mode takes no image source; PUT bytes to the returned URL");
            }
        } else if (mode.equals("import")) {
            if (sources != 1) {
                throw new IllegalArgumentException("import requires exactly one of url, file or base64");
            }
        } else {
            throw new IllegalArgumentException("mode must be import or upload");
        }
        if (url != null) {
            ImageTransfers.validateUrl(url);
        }
        if (base64 != null && base64.length() > ((ManagedBlobStore.MAX_UPLOAD_BYTES + 2) / 3) * 4) {
            throw new IllegalArgumentException("base64 image exceeds 16 MiB decoded");
        }
    }

    String downloadUrl() {
        return file == null ? url : file.download_url();
    }

    record FileInput(String download_url, String file_id, String mime_type, String file_name) {
        FileInput {
            ImageTransfers.validateUrl(download_url);
            if (file_id == null || file_id.isBlank() || file_id.length() > 1024) {
                throw new IllegalArgumentException("file requires a bounded file_id");
            }
        }
    }
}
