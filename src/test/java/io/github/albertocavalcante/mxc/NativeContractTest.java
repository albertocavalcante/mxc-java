package io.github.albertocavalcante.mxc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/** Pins behavior of the native C ABI that the binding has to get exactly right. */
class NativeContractTest {

    @Test
    void telemetryGettersMatchTheirNativeFormats() {
        // consent and policy are bare strings ("not-applicable", ...), not JSON.
        if (TestPlatform.windows()) {
            // Consent applies on Windows, so only the shape is stable there.
            Mxc.telemetryConsent();
            Mxc.telemetryPolicy();
        } else {
            assertEquals(Mxc.ConsentState.NOT_APPLICABLE, Mxc.telemetryConsent());
            assertEquals(Mxc.PolicyState.NOT_APPLICABLE, Mxc.telemetryPolicy());
        }
        // status and withdraw are JSON documents.
        assertTrue(Mxc.telemetryConsentStatus().isObject());
        assertTrue(Mxc.telemetryWithdrawConsent().isObject());
    }

    @Test
    void rawJsonWithNulByteIsRejectedNotTruncated() {
        // A NUL would silently truncate the C string, running a different request than written.
        String json =
                "{\"version\":\"1.0.0\",\"process\":{\"commandLine\":\"echo a\"}}\u0000garbage";
        MxcException e =
                assertThrows(MxcException.class, () -> Mxc.runJson(json, Mxc.Options.DEFAULT));
        assertEquals(ErrorCode.MALFORMED_REQUEST, e.code());
        assertThrows(MxcException.class, () -> Mxc.spawnJson(json, Mxc.Options.DEFAULT));
    }

    @Test
    void oneShotOutputIsLossyButStreamsAreRaw() throws Exception {
        TestPlatform.needPosixSandbox();
        Request req =
                Request.of("printf 'a\\000b\\377c'")
                        .withFilesystem(
                                Request.Filesystem.readwrite(System.getProperty("java.io.tmpdir")));
        // One-shot capture decodes lossily (NUL/invalid UTF-8 become U+FFFD)...
        Result r = Mxc.run(req);
        assertEquals("a�b�c", r.stdoutString());
        // ...while the streaming API carries raw bytes.
        try (MxcProcess p = Mxc.spawn(req)) {
            byte[] raw = p.stdout().readAllBytes();
            assertEquals("a\u0000bÿc", new String(raw, StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    void presenterDispatchNeverThrows() {
        String prompt =
                "{\"resourceVersion\":\"1\",\"locale\":\"en-US\",\"title\":{\"id\":\"t\",\"text\":\"T\"},"
                    + "\"body\":{\"id\":\"b\",\"text\":\"B\"},\"affirmativeLabel\":{\"id\":\"y\",\"text\":\"Y\"},"
                    + "\"negativeLabel\":{\"id\":\"n\",\"text\":\"N\"},\"learnMoreLabel\":{\"id\":\"l\",\"text\":\"L\"},"
                    + "\"learnMoreUrl\":\"https://example.com\",\"futureField\":1}";
        assertEquals(1, Mxc.dispatchPresenter(p -> ConsentDecision.YES, prompt));
        assertEquals(0, Mxc.dispatchPresenter(p -> ConsentDecision.NO, prompt));
        assertEquals(2, Mxc.dispatchPresenter(p -> ConsentDecision.DISMISSED, prompt));
        assertEquals(-1, Mxc.dispatchPresenter(p -> null, prompt));
        assertEquals(
                -1,
                Mxc.dispatchPresenter(
                        p -> {
                            throw new IllegalStateException("boom");
                        },
                        prompt));
        assertEquals(
                -1,
                Mxc.dispatchPresenter(
                        p -> {
                            throw new StackOverflowError();
                        },
                        prompt));
        assertEquals(-1, Mxc.dispatchPresenter(p -> ConsentDecision.YES, "not json"));
        assertEquals(
                "Y",
                seen(prompt).affirmativeLabel().text(),
                "prompt JSON must map onto ConsentPrompt");
    }

    private static ConsentPrompt seen(String prompt) {
        ConsentPrompt[] box = new ConsentPrompt[1];
        Mxc.dispatchPresenter(
                p -> {
                    box[0] = p;
                    return ConsentDecision.NO;
                },
                prompt);
        return box[0];
    }

    @Test
    void requestConsentIsNotApplicableOffWindows() {
        Assumptions.assumeFalse(TestPlatform.windows(), "windows may prompt");
        boolean[] called = {false};
        JsonNode out =
                Mxc.requestConsent(
                        "en-US",
                        p -> {
                            called[0] = true;
                            return ConsentDecision.NO;
                        });
        assertTrue(out.isObject());
        assertEquals("notApplicable", out.get("result").asText());
        assertFalse(called[0], "presenter must not run where consent is not applicable");
        assertFalse(Mxc.telemetryNeedsConsentPrompt());
        assertThrows(NullPointerException.class, () -> Mxc.requestConsent(null, null));
    }
}
