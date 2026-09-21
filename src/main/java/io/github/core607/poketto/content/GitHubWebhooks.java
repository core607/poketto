package io.github.core607.poketto.content;

/** The App secret authenticates exact request bytes; neither cookies nor bearer tokens authorize this entrance. */
public interface GitHubWebhooks {
    int MAX_BODY_BYTES = 25 * 1024 * 1024;

    void receive(String delivery, String event, String signature, byte[] body);
}
