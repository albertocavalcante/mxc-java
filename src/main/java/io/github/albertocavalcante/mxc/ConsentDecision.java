package io.github.albertocavalcante.mxc;

/** The user's answer to the telemetry consent prompt. */
public enum ConsentDecision {
    /** The user declined. */
    NO(0),
    /** The user agreed. */
    YES(1),
    /** The user closed the prompt without answering. */
    DISMISSED(2);

    private final int wire;

    ConsentDecision(int wire) {
        this.wire = wire;
    }

    int wire() {
        return wire;
    }
}
