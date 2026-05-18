package net.jacopobiscella.wichtelm.error;

/**
 * Thrown when a TOML config file violates one of the config validation rules
 * C1-C11. Carries the offending key path and the rule identifier.
 */
public final class ConfigParseException extends WichtelmException {

    private final String filePath;
    private final String keyPath;
    private final String violatedRule;

    public ConfigParseException(String filePath, String keyPath,
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
