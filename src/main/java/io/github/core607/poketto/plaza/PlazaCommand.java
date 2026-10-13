package io.github.core607.poketto.plaza;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One street action, with quoted literal arguments; never shell evaluation. */
public record PlazaCommand(String name, List<String> arguments) {
    private static final int MAX_BYTES = 8192;

    public PlazaCommand {
        arguments = List.copyOf(arguments);
    }

    public static PlazaCommand parse(String input) {
        if (input == null) {
            return new PlazaCommand("--help", List.of());
        }
        if (input.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("The action is too long.");
        }
        if (input.codePoints().anyMatch(code -> Character.isISOControl(code) || code >= 0xd800 && code <= 0xdfff)) {
            throw invalid("Send one action with valid Unicode and no control characters.");
        }
        if (input.isBlank()) {
            return new PlazaCommand("--help", List.of());
        }
        List<String> words = words(input);
        if (words.size() > 12) {
            throw invalid("The action has too many arguments.");
        }
        return new PlazaCommand(words.getFirst(), words.subList(1, words.size()));
    }

    private static List<String> words(String input) {
        var words = new ArrayList<String>();
        var token = new StringBuilder();
        char quote = 0;
        boolean escape = false;
        boolean started = false;
        for (int index = 0; index < input.length(); index++) {
            char value = input.charAt(index);
            if (escape) {
                token.append(value);
                escape = false;
            } else if (value == '\\') {
                escape = true;
                started = true;
            } else if (quote != 0) {
                if (value == quote) {
                    quote = 0;
                } else {
                    token.append(value);
                }
            } else if (value == '\'' || value == '"') {
                quote = value;
                started = true;
            } else if (value == ' ') {
                if (started) {
                    words.add(token.toString());
                    token.setLength(0);
                    started = false;
                }
            } else {
                token.append(value);
                started = true;
            }
        }
        if (quote != 0 || escape) {
            throw invalid("Close the quote or escape before sending the action.");
        }
        if (started) {
            words.add(token.toString());
        }
        return words;
    }

    public String argument(int index) {
        if (index >= arguments.size()) {
            throw invalid("A required argument is missing.");
        }
        return arguments.get(index);
    }

    public void count(int minimum, int maximum) {
        if (arguments.size() < minimum || arguments.size() > maximum) {
            throw invalid("This action has a different argument count; consult --help.");
        }
    }

    private static PlazaException invalid(String message) {
        return new PlazaException("INVALID_ACTION", message, "--help");
    }
}
