package io.github.albertocavalcante.mxc;

/** Failure classification. 1-12 mirror {@code mxc_sdk::v1::ErrorCode}; 100+ are FFI-local. */
public enum ErrorCode {
    MALFORMED_REQUEST(1),
    UNSUPPORTED_CONTAINMENT(2),
    UNSUPPORTED_PHASE(3),
    BACKEND_UNAVAILABLE(4),
    MALFORMED_ID(5),
    STALE_ID(6),
    NOT_PROVISIONED(7),
    NOT_STARTED(8),
    ALREADY_STARTED(9),
    ALREADY_STOPPED(10),
    POLICY_VALIDATION(11),
    BACKEND_ERROR(12),
    NULL_ARGUMENT(100),
    INVALID_UTF8(101),
    PANIC(102),
    CONSENT_WRITE_FAILED(103),
    UNKNOWN(-1);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static ErrorCode fromStatus(int status) {
        for (ErrorCode c : values()) if (c.status == status) return c;
        return UNKNOWN;
    }
}
