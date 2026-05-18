Feature: Backtest execution flow
  Increment 6b. The backtest runner resolves the data source, loads the
  primary and higher-timeframe series, builds the SignalGenerator and
  BacktestSpec, and runs the frau-holle backtester (CLAUDE.md section 6.1).

  Scenario: A CSV-backed single-timeframe backtest runs end to end
    Given a single-timeframe strategy with sma-based entry and exit
    And a CSV dataset for the symbol on the 1h timeframe
    When the backtest runner executes
    Then a BacktestResult is produced
    And the result has one equity point per primary bar

  Scenario: A multi-timeframe strategy resolves its higher-timeframe Background series
    Given a multi-timeframe strategy with a 1d Background series
    And a CSV dataset for the symbol on the 1h and 1d timeframes
    When the backtest runner executes
    Then a BacktestResult is produced

  Scenario: A higher-timeframe series with interior gaps but full span is accepted
    Given a multi-timeframe strategy with a 1d Background series
    And a CSV dataset for the symbol on the 1h timeframe
    And a 1d dataset with interior gaps that still spans the primary range
    When the backtest runner executes
    Then a BacktestResult is produced

  Scenario: A higher-timeframe series ending before the primary range end is rejected
    Given a multi-timeframe strategy with a 1d Background series
    And a CSV dataset for the symbol on the 1h timeframe
    And a 1d dataset that ends before the primary range end
    When the backtest runner is executed expecting failure
    Then a higher-timeframe coverage error is raised

  Scenario: An EODHD data source is instantiated when the API-token env var is set
    Given an EODHD-backed config whose api_token_env is "EODHD_API_TOKEN"
    And the environment variable "EODHD_API_TOKEN" is set to "demo-token"
    When the data source is resolved
    Then an EODHD market data source is created

  Scenario: An EODHD data source with a missing API-token env var is rejected
    Given an EODHD-backed config whose api_token_env is "EODHD_API_TOKEN"
    And the environment variable "EODHD_API_TOKEN" is not set
    When the data source is resolved expecting failure
    Then a DataSourceUnavailableException names the missing "EODHD_API_TOKEN" variable
