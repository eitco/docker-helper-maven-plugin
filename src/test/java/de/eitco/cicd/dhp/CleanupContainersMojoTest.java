package de.eitco.cicd.dhp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.Assert;
import org.junit.Test;

import java.util.regex.Pattern;

public class CleanupContainersMojoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void acceptsTcpDockerHostAsHttpUrl() throws MojoExecutionException {
        Assert.assertEquals("http://localhost:2375", CleanupContainersMojo.dockerUri("tcp://localhost:2375").toString());
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
}
