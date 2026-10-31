package io.github.core607.poketto.executor.internal;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;
import jdk.jfr.Timespan;

/** Protocol names, sizes, elapsed times and outcomes only; payloads and caller identities stay out. */
final class WorkerRequestEvents {
    private WorkerRequestEvents() {}

    @Name("poketto.WorkerRequestPreparation")
    @Label("Worker request preparation")
    @Category({"Poketto", "Executor"})
    @StackTrace(false)
    @Threshold("0 ns")
    static final class Preparation extends Event {
        @Label("Operation")
        String operation;

        @Label("Request type")
        String requestType;

        @Label("Payload bytes")
        @DataAmount(DataAmount.BYTES)
        int payloadBytes;

        @Label("Serialization elapsed")
        @Timespan(Timespan.NANOSECONDS)
        long serializationNanos;

        @Label("Signing elapsed")
        @Timespan(Timespan.NANOSECONDS)
        long signingNanos;

        @Label("Envelope prepared")
        boolean prepared;
    }

    @Name("poketto.WorkerBridgePoll")
    @Label("Worker bridge poll")
    @Category({"Poketto", "Executor"})
    @StackTrace(false)
    @Threshold("0 ns")
    static final class BridgePoll extends Event {
        @Label("Authorization elapsed")
        @Timespan(Timespan.NANOSECONDS)
        long authorizationNanos;

        @Label("Request elapsed")
        @Timespan(Timespan.NANOSECONDS)
        long requestNanos;

        @Label("Reply received")
        boolean receivedReply;

        @Label("Reply reports success")
        boolean replyOk;

        @Label("Bridge request present")
        boolean requestPresent;
    }

    @Name("poketto.WorkerBridgeAnswer")
    @Label("Worker bridge answer")
    @Category({"Poketto", "Executor"})
    @StackTrace(false)
    @Threshold("0 ns")
    static final class BridgeAnswer extends Event {
        @Label("Authorization elapsed")
        @Timespan(Timespan.NANOSECONDS)
        long authorizationNanos;

        @Label("Completion exchange elapsed")
        @Timespan(Timespan.NANOSECONDS)
        long completionNanos;

        @Label("Completion attempted")
        boolean completionAttempted;

        @Label("Completion confirmed")
        boolean completed;
    }

    @Name("poketto.WorkerRemoteStatus")
    @Label("Worker remote status")
    @Category({"Poketto", "Executor"})
    @StackTrace(false)
    @Threshold("0 ns")
    static final class RemoteStatus extends Event {
        @Label("Full source read")
        boolean fullRead;

        @Label("Status resolved")
        boolean resolved;
    }
}
