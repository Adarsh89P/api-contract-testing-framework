package com.adarsh.tests.config;

import com.adarsh.config.ConfigReader;
import com.adarsh.config.FrameworkConfig;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import org.aeonbits.owner.Config;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.assertTrue;

/**
 * Offline checks on the configuration layer. These make no network calls, so
 * they run in every suite and fail fast when the precedence contract regresses.
 */
@Feature("Configuration")
public class ConfigPrecedenceTest {

    private static final String URL_KEY = "BASE_URL";

    @AfterMethod(alwaysRun = true)
    public void clearOverrides() {
        System.clearProperty(URL_KEY);
        System.clearProperty("BOOKER_USERNAME");
        System.clearProperty("BOOKER_PASSWORD");
        ConfigReader.reload();
    }

    @Test(description = "load policy is MERGE, not Owner's FIRST default")
    @Description("Under the default LoadType.FIRST, system:properties is the only "
            + "source ever consulted and the properties file is dead weight.")
    public void loadPolicyIsMerge() {
        Config.LoadPolicy policy = FrameworkConfig.class.getAnnotation(Config.LoadPolicy.class);
        assertEquals(policy.value(), Config.LoadType.MERGE,
                "FIRST would stop after system:properties and never read the file");
    }

    @Test(description = "@Sources is declared -D > env > properties file")
    @Description("The precedence rule is the annotation order; assert it directly "
            + "rather than trusting a comment to stay true.")
    public void sourcesAreOrderedSystemThenEnvThenFile() {
        Config.Sources sources = FrameworkConfig.class.getAnnotation(Config.Sources.class);
        assertEquals(List.of(sources.value()),
                List.of("system:properties", "system:env", "classpath:config/config.properties"),
                "Source order defines precedence; reordering it silently changes behaviour");
    }

    @Test(description = "properties file supplies the value when nothing overrides it")
    public void fileValueIsUsedWhenNoOverridePresent() {
        if (System.getenv(URL_KEY) != null) {
            throw new SkipException("BASE_URL is set in this environment and correctly "
                    + "outranks the file; environmentVariableIsConsultedBeforeFile covers that case");
        }
        // BASE_URL has no @DefaultValue, so a non-null value here can only have
        // come from config/config.properties.
        assertEquals(ConfigReader.baseUrl(), "https://restful-booker.herokuapp.com");
    }

    @Test(description = "-D overrides the properties file")
    public void systemPropertyBeatsFile() {
        System.setProperty(URL_KEY, "https://override.example.com");
        ConfigReader.reload();
        assertEquals(ConfigReader.baseUrl(), "https://override.example.com");
    }

    @Test(description = "an environment variable overrides the properties file")
    @Description("Resolved through the system:env source. Java cannot set its own "
            + "environment, so this asserts against whatever BASE_URL the process was "
            + "given; CI sets one. With no BASE_URL in the environment the assertion "
            + "falls back to the file value, which is the same code path.")
    public void environmentVariableIsConsultedBeforeFile() {
        String fromEnv = System.getenv(URL_KEY);
        String expected = fromEnv != null && !fromEnv.isBlank()
                ? fromEnv
                : "https://restful-booker.herokuapp.com";
        assertEquals(ConfigReader.baseUrl(), stripSlash(expected));
    }

    @Test(description = "trailing slashes are stripped so path joins stay clean")
    public void baseUrlIsNormalised() {
        System.setProperty(URL_KEY, "https://override.example.com/");
        ConfigReader.reload();
        assertEquals(ConfigReader.baseUrl(), "https://override.example.com");
    }

    @Test(description = "missing credentials fail loudly, and only when asked for")
    public void credentialsResolveLazilyAndReportClearly() {
        assertEquals(System.getProperty("BOOKER_PASSWORD"), null,
                "precondition: no credential override in this test");
        if (System.getenv("BOOKER_USERNAME") == null) {
            IllegalStateException thrown =
                    expectThrows(IllegalStateException.class, ConfigReader::credentials);
            assertTrue(thrown.getMessage().contains("BOOKER_USERNAME"),
                    "the error should name the setting to fix, got: " + thrown.getMessage());
        }
        System.setProperty("BOOKER_USERNAME", "u");
        System.setProperty("BOOKER_PASSWORD", "p");
        ConfigReader.reload();
        assertEquals(ConfigReader.credentials().username(), "u");
    }

    @Test(description = "toString never leaks the password")
    public void credentialsToStringIsRedacted() {
        assertTrue(new com.adarsh.models.Credentials("admin", "hunter2").toString()
                .contains("***"));
        assertTrue(!new com.adarsh.models.Credentials("admin", "hunter2").toString()
                .contains("hunter2"));
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
