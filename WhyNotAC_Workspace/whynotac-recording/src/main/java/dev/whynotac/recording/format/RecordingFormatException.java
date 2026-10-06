package dev.whynotac.recording.format;

/** Malformed recording data. Readers convert it into a reported, bounded loss, never into garbage events. */
public final class RecordingFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RecordingFormatException(String message) {
        super(message);
    }
}
