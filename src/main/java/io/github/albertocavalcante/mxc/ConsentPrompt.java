package io.github.albertocavalcante.mxc;

/**
 * The canonical telemetry consent prompt the host must show.
 *
 * @param resourceVersion version of the prompt text resources
 * @param locale locale the text was resolved for
 * @param learnMoreUrl where "learn more" should lead
 */
public record ConsentPrompt(
        String resourceVersion,
        String locale,
        Message title,
        Message body,
        Message affirmativeLabel,
        Message negativeLabel,
        Message learnMoreLabel,
        String learnMoreUrl) {

    /**
     * One localized string of the prompt.
     *
     * @param id stable message identifier
     * @param text localized text
     */
    public record Message(String id, String text) {}
}
