package io.github.core607.poketto.mcp.internal;

import java.io.Writer;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

/** Streams envelope limits before the SDK constructs its argument tree or echoes a request id. */
final class McpEnvelopeBounds {
    static final int MAX_TOKENS = 4096;
    static final int MAX_DEPTH = 32;
    private final ObjectMapper json;

    McpEnvelopeBounds(ObjectMapper json) {
        this.json = json;
    }

    Result inspect(byte[] body, int length) {
        try (var parser = json.createParser(body, 0, length)) {
            int count = 0;
            int depth = 0;
            boolean identifier = false;
            boolean nameValue = false;
            boolean toolName = false;
            String rootField = null;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (++count > MAX_TOKENS) return Result.TOO_COMPLEX;
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    if (++depth > MAX_DEPTH) return Result.TOO_COMPLEX;
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
                if (identifier) {
                    if (token == JsonToken.VALUE_STRING) parser.readString(new IdentifierCounter());
                    else if (token == JsonToken.VALUE_NUMBER_INT) {
                        try {
                            parser.getLongValue();
                        } catch (RuntimeException invalid) {
                            return Result.INVALID_ID;
                        }
                    } else return Result.INVALID_ID;
                }
                if (nameValue && token == JsonToken.VALUE_STRING) {
                    var name = new NameCounter();
                    parser.readString(name);
                    if (toolName
                            && name.value.toString().equals("get_asset")
                            && length > McpBodyLimitFilter.MAX_INITIALIZE_BYTES) return Result.TOO_COMPLEX;
                }
                if (depth == 1 && token == JsonToken.PROPERTY_NAME) rootField = parser.currentName();
                toolName = depth == 2
                        && "params".equals(rootField)
                        && token == JsonToken.PROPERTY_NAME
                        && parser.currentName().equals("name");
                nameValue = toolName
                        || (depth == 1
                                && token == JsonToken.PROPERTY_NAME
                                && parser.currentName().equals("method"));
                identifier = depth == 1
                        && token == JsonToken.PROPERTY_NAME
                        && parser.currentName().equals("id");
            }
            return Result.VALID;
        } catch (IdentifierTooLong invalid) {
            return Result.INVALID_ID;
        } catch (NameTooLong invalid) {
            return Result.TOO_COMPLEX;
        } catch (RuntimeException malformed) {
            return Result.INVALID_JSON;
        }
    }

    enum Result {
        VALID,
        INVALID_ID,
        INVALID_JSON,
        TOO_COMPLEX
    }

    private static final class IdentifierCounter extends Writer {
        private int count;

        @Override
        public void write(char[] characters, int offset, int length) {
            if ((count += length) > 128) throw new IdentifierTooLong();
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    private static final class IdentifierTooLong extends RuntimeException {}

    private static final class NameCounter extends Writer {
        private final StringBuilder value = new StringBuilder();

        @Override
        public void write(char[] characters, int offset, int length) {
            if (value.length() + length > 128) throw new NameTooLong();
            value.append(characters, offset, length);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    private static final class NameTooLong extends RuntimeException {}
}
