package io.github.core607.poketto.auth;

import java.util.Locale;

/** Login addresses use one case-insensitive ASCII identity; provider-specific aliases are not collapsed. */
public final class EmailAddress {
    private EmailAddress() {}

    public static String normalize(String input) {
        if (input == null) {
            throw invalid();
        }
        String email = input.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254) {
            throw invalid();
        }
        int at = email.indexOf('@');
        if (at < 1 || at > 64 || at != email.lastIndexOf('@')) {
            throw invalid();
        }
        String local = email.substring(0, at);
        if (!local.matches("[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*")) {
            throw invalid();
        }
        String domain = email.substring(at + 1);
        if (!domain.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")) {
            throw invalid();
        }
        return email;
    }

    private static EmailChallengeException invalid() {
        return new EmailChallengeException(EmailChallengeException.Code.INVALID_INPUT);
    }
}
