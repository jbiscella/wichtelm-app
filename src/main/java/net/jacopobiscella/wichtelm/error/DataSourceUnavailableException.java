package net.jacopobiscella.wichtelm.error;

/**
 * Thrown when a data source (CSV file or EODHD API) cannot be reached or returns
 * malformed data not caught by the driver's own validation. Wraps the underlying
 * data-source exception as cause.
 */
public final class DataSourceUnavailableException extends WichtelmException {

    public DataSourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataSourceUnavailableException(String message) {
        super(message);
    }
}
