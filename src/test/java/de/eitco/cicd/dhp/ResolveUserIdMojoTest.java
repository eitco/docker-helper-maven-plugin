package de.eitco.cicd.dhp;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResolveUserIdMojoTest {

    @Test
    public void testIsWindowsWithWindowsOsNames() {
        assertTrue(ResolveUserIdMojo.isWindows("Windows 10"));
        assertTrue(ResolveUserIdMojo.isWindows("Windows Server 2022"));
        assertTrue(ResolveUserIdMojo.isWindows("windows 11"));
    }

    @Test
    public void testIsWindowsWithNonWindowsOsNames() {
        assertFalse(ResolveUserIdMojo.isWindows("Linux"));
        assertFalse(ResolveUserIdMojo.isWindows("Mac OS X"));
        assertFalse(ResolveUserIdMojo.isWindows("FreeBSD"));
        assertFalse(ResolveUserIdMojo.isWindows("SunOS"));
    }

    @Test
    public void testIsWindowsWithNullOsName() {
        assertFalse(ResolveUserIdMojo.isWindows(null));
    }

    @Test
    public void testDefaultUidCommandOnWindows() {
        List<String> expected = Arrays.asList("wsl", "id", "-u");
        assertEquals(expected, ResolveUserIdMojo.defaultUidCommand(true));
    }

    @Test
    public void testDefaultUidCommandOnLinux() {
        List<String> expected = Arrays.asList("id", "-u");
        assertEquals(expected, ResolveUserIdMojo.defaultUidCommand(false));
    }

    @Test
    public void testDefaultGidCommandOnWindows() {
        List<String> expected = Arrays.asList("wsl", "id", "-g");
        assertEquals(expected, ResolveUserIdMojo.defaultGidCommand(true));
    }

    @Test
    public void testDefaultGidCommandOnLinux() {
        List<String> expected = Arrays.asList("id", "-g");
        assertEquals(expected, ResolveUserIdMojo.defaultGidCommand(false));
    }

    @Test
    public void testEffectiveCommandWithNullFallback() {
        List<String> override = Arrays.asList("custom", "command");
        List<String> fallback = Arrays.asList("fallback", "command");
        assertEquals(override, ResolveUserIdMojo.effectiveCommand(override, fallback));
    }

    @Test
    public void testEffectiveCommandWithEmptyOverride() {
        List<String> override = Collections.emptyList();
        List<String> fallback = Arrays.asList("fallback", "command");
        assertEquals(fallback, ResolveUserIdMojo.effectiveCommand(override, fallback));
    }

    @Test
    public void testEffectiveCommandWithNullOverride() {
        List<String> fallback = Arrays.asList("fallback", "command");
        assertEquals(fallback, ResolveUserIdMojo.effectiveCommand(null, fallback));
    }

    @Test
    public void testParseIdWithValidId() throws Exception {
        assertEquals("1000", ResolveUserIdMojo.parseId("1000", "UID"));
    }

    @Test
    public void testParseIdWithWhitespace() throws Exception {
        assertEquals("1000", ResolveUserIdMojo.parseId("  1000  \n", "UID"));
    }

    @Test
    public void testParseIdWithEmptyString() {
        try {
            ResolveUserIdMojo.parseId("", "UID");
            assertTrue("Expected MojoExecutionException", false);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("UID"));
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void testParseIdWithNull() {
        try {
            ResolveUserIdMojo.parseId(null, "GID");
            assertTrue("Expected MojoExecutionException", false);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("GID"));
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void testParseIdWithNonNumericValue() {
        try {
            ResolveUserIdMojo.parseId("abc", "UID");
            assertTrue("Expected MojoExecutionException", false);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("UID"));
            assertTrue(e.getMessage().contains("numeric"));
        }
    }

    @Test
    public void testParseIdWithMixedAlphanumeric() {
        try {
            ResolveUserIdMojo.parseId("1000abc", "GID");
            assertTrue("Expected MojoExecutionException", false);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("GID"));
            assertTrue(e.getMessage().contains("numeric"));
        }
    }
}
