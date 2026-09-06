package com.adarsh.tests.config;

import com.adarsh.config.ConfigReader;
import com.adarsh.config.FrameworkConfig;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import org.aeonbits.owner.Config;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final List<String> MUTATED_KEYS =
            List.of(URL_KEY, "BOOKER_USERNAME", "BOOKER_PASSWORD");

    private final Map<String, String> originals = new HashMap<>();

    /**
     * These tests mutate real system properties, which are global to the forked
     * JVM. Restoring rather than clearing matters: a run started with
     * {@code -DBASE_URL=...} would otherwise have that override silently wiped
     * here and every later test would quietly hit the wrong host.
     */
    @BeforeMethod(alwaysRun = true)
    public void captureOverrides() {
        originals.clear();
        MUTATED_KEYS.forEach(key -> originals.put(key, System.getProperty(key)));
    }

    @AfterMethod(alwaysRun = true)
    public void restoreOverrides() {
        originals.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
        ConfigReader.reload();
    }

    /** True when this run was launched with an override for {@code key}. */
    private boolean overriddenExternally(String key) {
        return originals.get(key) != null || System.getenv(key) != null;
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
        if (overriddenExternally(URL_KEY)) {
            throw new SkipException("BASE_URL was overridden for this run and correctly "
                    + "outranks the file; the -D and env cases are covered by their own tests");
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
        if (originals.get(URL_KEY) != null) {
            throw new SkipException("a -D override outranks the environment, by design");
        }
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
        if (!overriddenExternally("BOOKER_USERNAME")) {
            System.clearProperty("BOOKER_USERNAME");
            System.clearProperty("BOOKER_PASSWORD");
            ConfigReader.reload();
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
