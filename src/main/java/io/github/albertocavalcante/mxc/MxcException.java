package io.github.albertocavalcante.mxc;

/** A failure reported by the native MXC engine. */
public class MxcException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final int status;
    private final String operation;
    private final String nativeCode;
    private final String remediation;

    public MxcException(
            int status, String message, String operation, String nativeCode, String remediation) {
        super(
                format(
                        ErrorCode.fromStatus(status),
                        status,
                        message,
                        operation,
                        nativeCode,
                        remediation));
        this.code = ErrorCode.fromStatus(status);
        this.status = status;
        this.operation = operation;
        this.nativeCode = nativeCode;
        this.remediation = remediation;
    }

    public MxcException(int status, String message) {
        this(status, message, null, null, null);
    }

    private static String format(
            ErrorCode c, int status, String msg, String op, String nc, String rem) {
        StringBuilder sb =
                new StringBuilder("mxc: ")
                        .append(
                                c == ErrorCode.UNKNOWN
                                        ? "status " + status
                                        : c.name().toLowerCase())
                        .append(": ")
                        .append(msg);
        if (op != null) {
            sb.append(" (").append(op);
            if (nc != null) sb.append(' ').append(nc);
            sb.append(')');
        }
        if (rem != null) sb.append(": ").append(rem);
        return sb.toString();
    }

    public ErrorCode code() {
        return code;
    }

    public int status() {
        return status;
    }

    /** Failing API call, when known; otherwise null. */
    public String operation() {
        return operation;
    }

    /** Platform status such as 0x80070490, when known; otherwise null. */
    public String nativeCode() {
        return nativeCode;
    }

    /** Actionable hint, when provided; otherwise null. */
    public String remediation() {
        return remediation;
    }
}
