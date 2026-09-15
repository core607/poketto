package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.assets.ImageTransfers;

/** A URL, a platform file reference, or a request for a raw upload grant. */
record PutAssetInput(String operationKey, String mode, String url, FileInput file) {
    PutAssetInput {
        ImageTransfers.validateKey(operationKey);
        mode = mode == null ? "import" : mode;
        int sources = (url == null ? 0 : 1) + (file == null ? 0 : 1);
        if (mode.equals("upload")) {
            if (sources != 0) {
                throw new IllegalArgumentException("upload mode takes no image source; PUT bytes to the returned URL");
            }
        } else if (mode.equals("import")) {
            if (sources != 1) {
                throw new IllegalArgumentException("import requires exactly one of url or file");
            }
        } else {
            throw new IllegalArgumentException("mode must be import or upload");
        }
        if (url != null) {
            ImageTransfers.validateUrl(url);
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
