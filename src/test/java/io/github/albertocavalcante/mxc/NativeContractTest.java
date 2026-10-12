package io.github.albertocavalcante.mxc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/** Pins behavior of the native C ABI that the binding has to get exactly right. */
class NativeContractTest {

    @Test
    void telemetryGettersMatchTheirNativeFormats() {
        // consent and policy are bare strings ("not-applicable", ...), not JSON.
        assertEquals(Mxc.ConsentState.NOT_APPLICABLE, Mxc.telemetryConsent()); // non-Windows host
        assertEquals(Mxc.PolicyState.NOT_APPLICABLE, Mxc.telemetryPolicy());
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
        Assumptions.assumeTrue(Mxc.platformSupport().isSupported(), "no sandbox backend");
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
}
