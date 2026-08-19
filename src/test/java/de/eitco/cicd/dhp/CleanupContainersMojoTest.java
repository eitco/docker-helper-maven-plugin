package de.eitco.cicd.dhp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class CleanupContainersMojoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void acceptsTcpDockerHostAsHttpUrl() throws MojoExecutionException {
        Assert.assertEquals("http://localhost:2375", CleanupContainersMojo.dockerUri("tcp://localhost:2375").toString());
    }

    @Test
    public void acceptsUnixDockerHost() throws MojoExecutionException {
        Assert.assertEquals("http://localhost", CleanupContainersMojo.dockerUri("unix:///var/run/docker.sock").toString());
    }

    @Test
    public void matchesDockerContainerNameWithoutLeadingSlash() throws Exception {
        JsonNode container = objectMapper.readTree("{\"Names\":[\"/build-test-42\"]}");
        Assert.assertTrue(CleanupContainersMojo.matchesName(container, Pattern.compile("build-test-.*")));
    }

    @Test
    public void doesNotMatchDifferentContainerName() throws Exception {
        JsonNode container = objectMapper.readTree("{\"Names\":[\"/database\"]}");
        Assert.assertFalse(CleanupContainersMojo.matchesName(container, Pattern.compile("build-test-.*")));
    }

    @Test
    public void recognizesSuccessStatusRange() {
        Assert.assertTrue(CleanupContainersMojo.isSuccessStatus(200));
        Assert.assertTrue(CleanupContainersMojo.isSuccessStatus(204));
        Assert.assertTrue(CleanupContainersMojo.isSuccessStatus(299));
        Assert.assertFalse(CleanupContainersMojo.isSuccessStatus(199));
        Assert.assertFalse(CleanupContainersMojo.isSuccessStatus(300));
        Assert.assertFalse(CleanupContainersMojo.isSuccessStatus(304));
        Assert.assertFalse(CleanupContainersMojo.isSuccessStatus(409));
    }

    @Test
    public void treatsNotModifiedAsAlreadyStoppedContainer() {
        Assert.assertNotNull(CleanupContainersMojo.expectedStopStatusReason(304));
    }

    @Test
    public void treatsMissingContainerAsExpectedOnStop() {
        Assert.assertNotNull(CleanupContainersMojo.expectedStopStatusReason(404));
    }

    @Test
    public void treatsStopFailureAsUnexpected() {
        Assert.assertNull(CleanupContainersMojo.expectedStopStatusReason(500));
    }

    @Test
    public void treatsMissingContainerAsExpectedOnRemoval() {
        Assert.assertNotNull(CleanupContainersMojo.expectedRemoveStatusReason(404));
    }

    @Test
    public void treatsConflictAsRemovalAlreadyInProgress() {
        Assert.assertNotNull(CleanupContainersMojo.expectedRemoveStatusReason(409));
    }

    @Test
    public void treatsRemovalFailureAsUnexpected() {
        Assert.assertNull(CleanupContainersMojo.expectedRemoveStatusReason(400));
        Assert.assertNull(CleanupContainersMojo.expectedRemoveStatusReason(500));
    }

    @Test
    public void treatsConflictAsConcurrentVolumePrune() {
        Assert.assertNotNull(CleanupContainersMojo.expectedPruneStatusReason(409));
    }

    @Test
    public void treatsPruneFailureAsUnexpected() {
        Assert.assertNull(CleanupContainersMojo.expectedPruneStatusReason(404));
        Assert.assertNull(CleanupContainersMojo.expectedPruneStatusReason(500));
    }

    @Test
    public void registersShutdownHookOnlyOncePerHostAndPattern() {

        Map<String, Object> registry = new ConcurrentHashMap<String, Object>();
        String key = CleanupContainersMojo.shutdownHookKey("tcp://localhost:2375", "build-test-.*");

        Assert.assertTrue(CleanupContainersMojo.claimShutdownHook(registry, key));
        Assert.assertFalse(CleanupContainersMojo.claimShutdownHook(registry, key));
    }

    @Test
    public void registersSeparateShutdownHookPerNamePattern() {

        Map<String, Object> registry = new ConcurrentHashMap<String, Object>();

        Assert.assertTrue(CleanupContainersMojo.claimShutdownHook(registry,
            CleanupContainersMojo.shutdownHookKey("tcp://localhost:2375", "build-test-.*")));
        Assert.assertTrue(CleanupContainersMojo.claimShutdownHook(registry,
            CleanupContainersMojo.shutdownHookKey("tcp://localhost:2375", "integration-.*")));
    }

    @Test
    public void registersSeparateShutdownHookPerUnixSocket() {

        Map<String, Object> registry = new ConcurrentHashMap<String, Object>();

        Assert.assertTrue(CleanupContainersMojo.claimShutdownHook(registry,
            CleanupContainersMojo.shutdownHookKey("unix:///var/run/docker.sock", ".*")));
        Assert.assertTrue(CleanupContainersMojo.claimShutdownHook(registry,
            CleanupContainersMojo.shutdownHookKey("unix:///tmp/other.sock", ".*")));
    }

    @Test
    public void treatsTcpAndHttpDockerHostAsSameTarget() {
        Assert.assertEquals(
            CleanupContainersMojo.shutdownHookKey("http://localhost:2375", "build-test-.*"),
            CleanupContainersMojo.shutdownHookKey("tcp://localhost:2375", "build-test-.*"));
    }

    @Test
    public void ignoresSurroundingWhitespaceInDockerHost() {
        Assert.assertEquals(
            CleanupContainersMojo.shutdownHookKey("tcp://localhost:2375", "build-test-.*"),
            CleanupContainersMojo.shutdownHookKey("  tcp://localhost:2375  ", "build-test-.*"));
    }

    @Test
    public void separatesDockerHostFromNamePattern() {
        Assert.assertFalse(CleanupContainersMojo.shutdownHookKey("http://a", "b")
            .equals(CleanupContainersMojo.shutdownHookKey("http://ab", "")));
    }

    @Test
    public void claimsShutdownHookExactlyOnceUnderConcurrentExecutions() throws Exception {

        final int executions = 16;
        final Map<String, Object> registry = new ConcurrentHashMap<String, Object>();
        final String key = CleanupContainersMojo.shutdownHookKey("tcp://localhost:2375", "build-test-.*");
        final CyclicBarrier startLine = new CyclicBarrier(executions);
        final AtomicInteger winners = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(executions);

        try {
            for (int i = 0; i < executions; i++) {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            startLine.await(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException("Could not synchronize concurrent executions.", e);
                        }
                        if (CleanupContainersMojo.claimShutdownHook(registry, key)) {
                            winners.incrementAndGet();
                        }
                    }
                });
            }

            executor.shutdown();
            Assert.assertTrue("Concurrent executions did not finish in time.", executor.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        Assert.assertEquals(1, winners.get());
    }
}
