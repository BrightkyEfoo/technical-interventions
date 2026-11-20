package cm.brightky.serviceopsjava.requests.domain;

import java.util.UUID;

public record RequestId(UUID value) {
    public RequestId(UUID value) {
        if(value == null) throw new IllegalArgumentException("The value of a request Id must not be null");
        this.value = value;
    }
}
