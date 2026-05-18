package net.jacopobiscella.wichtelm;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;

/**
 * JUnit Platform suite that runs the Cucumber feature files describing the
 * behavioral specification Blocks (CLAUDE.md sections 9-14).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "net.jacopobiscella.wichtelm")
public class RunCucumberTest {
}
