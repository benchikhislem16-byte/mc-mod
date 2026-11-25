package dev.wildernesscull;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 entry point for {@code ./gradlew test} that invokes the full verification suite.
 */
public class WildernessCullJUnitTest {

    @Test
    public void runAllVerificationChecks() throws Exception {
        WildernessCullVerificationSuite.main(new String[0]);
    }
}
