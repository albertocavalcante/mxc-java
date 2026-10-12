package io.github.albertocavalcante.mxc;

/**
 * Shows the telemetry consent prompt to the user. It is called synchronously, on the thread that
 * called {@link Mxc#requestConsent}. Throwing is reported to MXC as a presenter failure and is
 * never treated as consent.
 */
@FunctionalInterface
public interface ConsentPresenter {
    /**
     * Presents the prompt and returns the user's decision.
     *
     * @param prompt the canonical prompt to show
     * @return the user's decision
     * @throws Exception if the prompt could not be shown
     */
    ConsentDecision present(ConsentPrompt prompt) throws Exception;
}
