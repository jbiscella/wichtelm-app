package net.jacopobiscella.wichtelm.error;

/**
 * Thrown when a TOML config file's {@code [sweep]} section violates one of the
 * sweep validation rules C12-C15 (CLAUDE.md section 18). Carries the offending
 * key path and the rule identifier, mirroring {@link ConfigParseException}.
 */
public final class SweepConfigException extends WichtelmException {

    private final String filePath;
    private final String keyPath;
    private final String violatedRule;

    public SweepConfigException(String filePath, String keyPath,
                                String violatedRule, String message) {
        super(message);
        this.filePath = filePath;
        this.keyPath = keyPath;
        this.violatedRule = violatedRule;
    }

    public String filePath() {
        return filePath;
    }

    public String keyPath() {
        return keyPath;
    }

    public String violatedRule() {
        return violatedRule;
    }
}
