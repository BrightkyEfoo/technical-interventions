package cm.brightky.serviceopsjava.shared.error;

import java.util.Objects;

public final class ServiceOpsException extends RuntimeException {
    private final ErrorCode code;

    public ServiceOpsException(ErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "Error code is required");
    }

    public ErrorCode code() {
        return code;
    }
}
