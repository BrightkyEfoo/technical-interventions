package cm.brightky.serviceopsjava.requests.infrastructure.web;

import cm.brightky.serviceopsjava.shared.error.ErrorCode;
import cm.brightky.serviceopsjava.shared.error.ServiceOpsException;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

final class ImageType {
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] JPEG = {(byte) 255, (byte) 216, (byte) 255};

    static String detect(byte[] bytes) {
        if (bytes.length > 5 * 1024 * 1024) {
            throw new ServiceOpsException(ErrorCode.ATTACHMENT_TOO_LARGE, "Image exceeds 5 MiB");
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (readers.hasNext()) {
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    if ((long) reader.getWidth(0) * reader.getHeight(0) <= 32_000_000) {
                        if (Arrays.equals(Arrays.copyOf(bytes, PNG.length), PNG)) return "image/png";
                        if (Arrays.equals(Arrays.copyOf(bytes, JPEG.length), JPEG)) return "image/jpeg";
                    }
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException failure) {
            throw invalid();
        }
        throw invalid();
    }

    private static ServiceOpsException invalid() {
        return new ServiceOpsException(ErrorCode.INVALID_INPUT, "Expected a JPEG or PNG image");
    }
}
