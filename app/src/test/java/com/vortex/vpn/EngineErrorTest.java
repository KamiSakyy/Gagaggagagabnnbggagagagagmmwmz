package com.vortex.vpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.vortex.vpn.core.EngineError;

import org.junit.Test;

/**
 * The dashboard must never show a raw engine sentence to the user.
 *
 * <p>The exact text below is what the phone displayed when the connect button failed
 * ("start or reload service: start dns/udp[dns-direct]: detour to an empty direct outbound makes
 * no sense"), so it is pinned here: it has to be recognised as a configuration problem and
 * translated into Russian.</p>
 */
public class EngineErrorTest {

    @Test
    public void theEmptyDirectOutboundFailureIsRecognised() {
        String raw = "start or reload service: start dns/udp[dns-direct]: "
                + "detour to an empty direct outbound makes no sense";
        assertTrue("the engine failure was not detected", EngineError.isEngineConfigurationProblem(raw));
        assertEquals(R.string.error_engine_title, EngineError.titleRes(raw));
        assertEquals(R.string.error_engine_hint, EngineError.hintRes(raw));
    }

    @Test
    public void typicalFailuresGetTheirOwnExplanation() {
        assertEquals(R.string.error_timeout_title,
                EngineError.titleRes("dial tcp 1.2.3.4:443: i/o timeout"));
        assertEquals(R.string.error_refused_title,
                EngineError.titleRes("connection refused"));
        assertEquals(R.string.error_auth_title,
                EngineError.titleRes("vless: authentication failed"));
        assertEquals(R.string.error_certificate_title,
                EngineError.titleRes("x509: certificate is valid for other.example"));
        assertEquals(R.string.error_permission_title,
                EngineError.titleRes("permission denied: VpnService is not prepared"));
        assertEquals(R.string.error_dns_title,
                EngineError.titleRes("lookup example.com: no such host"));
    }

    @Test
    public void unknownFailuresStillGetAReadableMessage() {
        assertEquals(R.string.error_unknown_title, EngineError.titleRes("something brand new"));
        assertEquals(R.string.error_unknown_title, EngineError.titleRes(null));
        assertNotEquals(0, EngineError.hintRes("something brand new"));
        // the raw text must not leak into the headline
        assertNotEquals(R.string.error_unknown_title,
                EngineError.titleRes("i/o timeout"));
    }
}
