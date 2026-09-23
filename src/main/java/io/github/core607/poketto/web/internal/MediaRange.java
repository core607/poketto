package io.github.core607.poketto.web.internal;

/** One selected byte range; unsupported units and multipart requests use the complete representation. */
record MediaRange(long start, long length, boolean partial) {
    static MediaRange parse(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",")) {
            return new MediaRange(0, size, false);
        }
        if (header.length() > 80 || !header.substring(6).matches("[0-9]*-[0-9]*")) {
            throw new IllegalArgumentException("invalid playback byte range");
        }
        String[] ends = header.substring(6).split("-", -1);
        try {
            if (ends[0].isEmpty()) {
                long suffix = Long.parseLong(ends[1]);
                if (suffix <= 0 || size <= 0) {
                    throw new IllegalArgumentException("playback suffix does not select bytes");
                }
                long count = Math.min(suffix, size);
                return new MediaRange(size - count, count, true);
            }
            long start = Long.parseLong(ends[0]);
            long end = ends[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(ends[1]), size - 1);
            if (start >= size || end < start) {
                throw new IllegalArgumentException("playback range is outside the original");
            }
            return new MediaRange(start, end - start + 1, true);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("playback range exceeds numeric bounds", invalid);
        }
    }

    String contentRange(long size) {
        return "bytes " + start + "-" + (start + length - 1) + "/" + size;
    }
}
