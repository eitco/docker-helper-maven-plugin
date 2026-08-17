package de.eitco.cicd.dhp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Stops and removes Docker containers whose names match a configured regular expression.
 */
@Mojo(name = "cleanup-containers", threadSafe = true)
public class CleanupContainersMojo extends AbstractDockerMojo {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Regular expression that is matched against container names (without Docker's leading slash).
     */
    @Parameter(defaultValue = ".*", property = "docker.cleanup.namePattern", required = true)
    private String namePattern;

    /**
     * Registers the cleanup for execution when the Maven JVM is shut down instead of executing it immediately.
     */
    @Parameter(defaultValue = "false", property = "docker.cleanup.shutdownHook")
    private boolean registerShutdownHook;

    /**
     * Skips Docker container cleanup and does not register a shutdown hook.
     */
    @Parameter(defaultValue = "false", property = "docker.cleanup.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {

        if (skip) {
            getLog().info("Skipping Docker container cleanup.");
            return;
        }

        final Pattern pattern = compilePattern(namePattern);
        final URI dockerUri = dockerUri(dockerHost);

        final CloseableHttpClient httpClient = createHttpClient();

        getLog().info("Docker host: " + dockerHost);

        try (CloseableHttpResponse response = httpClient.execute(new HttpGet(endpoint(dockerUri, "/_ping")))) {
            ensureSuccess(response.getStatusLine().getStatusCode(), "ping Docker daemon");
            getLog().info("Ping Docker daemon successful.");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to ping Docker daemon", e);
        }

        if (registerShutdownHook) {
            // Maven may dispose the plugin realm before JVM shutdown hooks run. Create the client while the
            // realm is still available and retain it for the hook, rather than loading HttpClients in the hook.
            preloadRequestClasses();
            preloadJacksonClasses();

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        cleanupContainers(httpClient, pattern, dockerUri);
                    } catch (MojoExecutionException e) {
                        getLog().warn("Could not clean up Docker containers during JVM shutdown: " + e.getMessage(), e);
                    } catch (IOException e) {
                        getLog().warn("Could not communicate with Docker daemon during JVM shutdown: " + e.getMessage(), e);
                    } catch (RuntimeException e) {
                        getLog().warn("Unexpected error while cleaning up Docker containers during JVM shutdown: " + e.getMessage(), e);
                    } finally {
                        try {
                            httpClient.close();
                        } catch (IOException e) {
                            getLog().warn("Could not close Docker HTTP client during JVM shutdown: " + e.getMessage(), e);
                        }
                    }
                }
            }, "docker-helper-container-cleanup"));

            getLog().info("Registered Docker container cleanup as JVM shutdown hook.");
            return;
        }

        cleanupContainers(pattern, dockerUri);
    }

    private void cleanupContainers(
        Pattern pattern,
        URI dockerUri
    ) throws MojoExecutionException {

        try (CloseableHttpClient httpClient = createHttpClient()) {
            cleanupContainers(httpClient, pattern, dockerUri);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not communicate with Docker daemon at " + dockerUri + ".", e);
        }
    }

    private void cleanupContainers(
        CloseableHttpClient httpClient,
        Pattern pattern,
        URI dockerUri
    ) throws MojoExecutionException, IOException {

        JsonNode containers = getContainers(httpClient, dockerUri);

        for (JsonNode container : containers) {
            if (matchesName(container, pattern)) {
                String id = requiredText(container, "Id");
                getLog().info("Stopping and removing Docker container '" + displayName(container) + "' (" + id + ").");
                stopContainer(httpClient, dockerUri, id);
                removeContainer(httpClient, dockerUri, id);
            }
        }
    }

    private static void preloadRequestClasses() {
        new HttpGet("http://localhost/");
        new HttpPost("http://localhost/");
        new HttpDelete("http://localhost/");
        preloadClass("org.apache.http.impl.conn.PoolingHttpClientConnectionManager$ConfigData");
        preloadClass("org.apache.http.impl.conn.PoolingHttpClientConnectionManager$InternalConnectionFactory");
        preloadClass("org.apache.http.impl.conn.PoolingHttpClientConnectionManager$1");
        preloadClass("org.apache.http.impl.conn.PoolingHttpClientConnectionManager$2");
        preloadClass("org.apache.http.impl.execchain.RequestEntityProxy");
        preloadClass("org.apache.http.message.BasicNameValuePair");
        preloadClass("org.apache.http.message.BasicHeaderElement");
        preloadClass("org.apache.http.TruncatedChunkException");
        preloadClass("com.fasterxml.jackson.core.io.NumberInput");
    }

    private static void preloadClass(String className) {
        try {
            Class.forName(className, true, CleanupContainersMojo.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not initialize Apache HTTP client class " + className + ".", e);
        }
    }

    private static void preloadJacksonClasses() {
        try {
            // Parse the shape returned by Docker so Jackson's parser and tree-model classes are loaded before
            // Maven disposes the plugin realm during shutdown.
            byte[] response = "[{\"Id\":\"container-id\",\"Names\":[\"/container-name\"]}]".getBytes(StandardCharsets.UTF_8);
            OBJECT_MAPPER.readTree(new ByteArrayInputStream(response));
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialize Jackson JSON parser.", e);
        }
    }

    private JsonNode getContainers(
        CloseableHttpClient httpClient,
        URI dockerUri
    ) throws IOException, MojoExecutionException {

        try (CloseableHttpResponse response = httpClient.execute(new HttpGet(endpoint(dockerUri, "/containers/json?all=true")))) {
            ensureSuccess(response.getStatusLine().getStatusCode(), "list Docker containers");
            JsonNode containers = OBJECT_MAPPER.readTree(response.getEntity().getContent());
            if (!containers.isArray()) {
                throw new MojoExecutionException("Docker returned an invalid container list.");
            }
            return containers;
        }
    }

    private void stopContainer(
        CloseableHttpClient httpClient,
        URI dockerUri,
        String id
    ) {
        try (CloseableHttpResponse response = httpClient.execute(new HttpPost(endpoint(dockerUri, "/containers/" + id + "/stop")))) {

            int status = response.getStatusLine().getStatusCode();

            if (status < 200 || status >= 300) {
                getLog().warn("Could not stop Docker container '" + id + "' (HTTP " + status + ").");
            }
        } catch (IOException e) {
            getLog().warn("Could not stop Docker container '" + id + ": " + e.getMessage());
        }
    }

    private void removeContainer(
        CloseableHttpClient httpClient,
        URI dockerUri,
        String id
    ) {
        try (CloseableHttpResponse response = httpClient.execute(new HttpDelete(endpoint(dockerUri, "/containers/" + id)))) {

            int status = response.getStatusLine().getStatusCode();

            if (status < 200 || status >= 300) {
                getLog().warn("Could not remove Docker container '" + id + "' (HTTP " + status + ").");
            }
        } catch (IOException e) {
            getLog().warn("Could not remove Docker container '" + id + "': " + e.getMessage());
        }
    }

    private static Pattern compilePattern(String expression) throws MojoExecutionException {

        if (expression == null || expression.trim().isEmpty()) {
            throw new MojoExecutionException("Container name pattern must not be empty.");
        }

        try {
            return Pattern.compile(expression);
        } catch (PatternSyntaxException e) {
            throw new MojoExecutionException("Container name pattern is not a valid regular expression.", e);
        }
    }

    static boolean matchesName(JsonNode container, Pattern pattern) {

        JsonNode names = container.path("Names");

        if (!names.isArray()) {
            return false;
        }

        Iterator<JsonNode> iterator = names.elements();

        while (iterator.hasNext()) {

            String name = iterator.next().asText();

            if (name.startsWith("/")) {
                name = name.substring(1);
            }

            if (pattern.matcher(name).matches()) {
                return true;
            }
        }

        return false;
    }

    private static String displayName(JsonNode container) {

        JsonNode names = container.path("Names");

        return names.isArray() && names.size() > 0 ? names.get(0).asText() : requiredText(container, "Id");
    }

    private static String requiredText(JsonNode node, String field) {

        String value = node.path(field).asText();

        if (value.isEmpty()) {
            throw new IllegalArgumentException("Docker container response has no " + field + ".");
        }

        return value;
    }

    private static void ensureSuccess(int status, String operation) throws MojoExecutionException {

        if (status < 200 || status >= 300) {
            throw new MojoExecutionException("Could not " + operation + " (HTTP " + status + ").");
        }
    }
}
