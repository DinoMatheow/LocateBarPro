package one.yuqas;

public record PlayerPreferences(
        VisibilityMode lastTransmissionMode,
        boolean transmissionEnabled,
        VisibilityMode reception
) {

    public VisibilityMode transmission() {
        return transmissionEnabled ? lastTransmissionMode : VisibilityMode.OFF;
    }
}
