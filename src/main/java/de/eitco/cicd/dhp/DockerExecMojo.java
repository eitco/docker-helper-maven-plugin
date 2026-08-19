package de.eitco.cicd.dhp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes a command in a running Docker container through the Docker API.
 */
@Mojo(name = "exec", threadSafe = true)
public class DockerExecMojo extends AbstractDockerMojo {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Name or ID of the container in which the command is executed.
     */
    @Parameter(property = "docker.exec.container", required = true)
    private String container;

    /**
     * Command and its arguments. Every list entry is passed to Docker as one argument, without shell parsing.
     */
    @Parameter(property = "docker.exec.arguments", required = true)
    private List<String> arguments;

    /**
     * Keeps standard input attached to the command, equivalent to docker exec -i.
     */
    @Parameter(defaultValue = "false", property = "docker.exec.interactive")
    private boolean interactive;

    /**
     * Allocates a TTY for the command, equivalent to docker exec -t.
     */
    @Parameter(defaultValue = "false", property = "docker.exec.tty")
    private boolean tty;

    /**
     * Maximum time to wait for the command to finish, in seconds.
     */
    @Parameter(defaultValue = "10", property = "docker.exec.timeoutSeconds")
    private long timeoutSeconds;

    /**
     * Skips the Docker exec invocation.
     */
    @Parameter(defaultValue = "false", property = "docker.exec.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {

        if (skip) {
            getLog().info("Skipping Docker exec.");
            return;
        }

        if (containerName() == null || containerName().isEmpty()) {
            throw new MojoExecutionException("Docker exec container must not be empty.");
        }

        if (arguments == null || arguments.isEmpty()) {
            throw new MojoExecutionException("Docker exec arguments must not be empty.");
        }

        URI dockerUri = dockerUri(dockerHost);

        try (CloseableHttpClient httpClient = createHttpClient()) {
            String execId = createExec(httpClient, dockerUri);
            startExec(httpClient, dockerUri, execId);
            waitForSuccessfulExit(httpClient, dockerUri, execId);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not communicate with Docker daemon at " + dockerUri + ".", e);
        }
    }

    private String createExec(
        CloseableHttpClient httpClient,
        URI dockerUri
    ) throws IOException, MojoExecutionException {

        ObjectNode request = OBJECT_MAPPER.createObjectNode();
        request.put("AttachStdin", interactive);
        request.put("AttachStdout", true);
        request.put("AttachStderr", true);
        request.put("Tty", tty);
        ArrayNode command = request.putArray("Cmd");

        for (String argument : arguments) {
            if (argument == null) {
                throw new MojoExecutionException("Docker exec arguments must not contain null values.");
            }
            command.add(argument);
        }

        HttpPost post = jsonPost(endpoint(dockerUri, "/containers/" + containerName() + "/exec"), request);

        try (CloseableHttpResponse response = httpClient.execute(post)) {

            ensureSuccess(response, "create Docker exec");
            JsonNode result = OBJECT_MAPPER.readTree(response.getEntity().getContent());
            String id = result.path("Id").asText();

            if (id.isEmpty()) {
                throw new MojoExecutionException("Docker returned no exec ID.");
            }

            return id;
        }
    }

    private void startExec(
        CloseableHttpClient httpClient,
        URI dockerUri,
        String execId
    ) throws IOException, MojoExecutionException {

        ObjectNode request = OBJECT_MAPPER.createObjectNode();

        // Docker hijacks the HTTP connection for attached execs. Starting detached avoids a stream that some
        // HTTP clients cannot reliably detect as finished; the status endpoint below keeps this Mojo synchronous.
        request.put("Detach", true);
        request.put("Tty", tty);

        try (CloseableHttpResponse response = httpClient.execute(jsonPost(endpoint(dockerUri, "/exec/" + execId + "/start"), request))) {
            ensureSuccess(response, "start Docker exec");
        }
    }

    private void waitForSuccessfulExit(
        CloseableHttpClient httpClient,
        URI dockerUri,
        String execId
    ) throws IOException, MojoExecutionException {

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

        while (true) {

            try (CloseableHttpResponse response = httpClient.execute(new org.apache.http.client.methods.HttpGet(endpoint(dockerUri, "/exec/" + execId + "/json")))) {
                ensureSuccess(response, "inspect Docker exec");
                JsonNode result = OBJECT_MAPPER.readTree(response.getEntity().getContent());

                if (!result.path("Running").asBoolean()) {
                    int exitCode = result.path("ExitCode").asInt(-1);
                    if (exitCode != 0) {
                        getLog().debug("Received error response from Docker exec:\n " + result.toPrettyString());
                        throw new MojoExecutionException("Docker exec in container '" + container + "' failed with exit code " + exitCode + ".");
                    }
                    return;
                }
            }

            if (System.nanoTime() >= deadlineNanos) {
                throw new MojoExecutionException("Docker exec in container '" + container + "' did not finish within " + timeoutSeconds + " second(s).");
            }

            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MojoExecutionException("Interrupted while waiting for Docker exec in container '" + container + "'.", e);
            }
        }
    }

    private static HttpPost jsonPost(URI endpoint, ObjectNode request) {
        HttpPost post = new HttpPost(endpoint);
        post.setEntity(new StringEntity(request.toString(), ContentType.APPLICATION_JSON));
        return post;
    }

    private String containerName() {
        if (container == null) {
            throw new IllegalArgumentException("Container name must be specified");
        }
        return container.trim().replaceFirst("^/+", "");
    }

    private static void ensureSuccess(
        CloseableHttpResponse response,
        String operation
    ) throws IOException, MojoExecutionException {

        int status = response.getStatusLine().getStatusCode();

        if (status < 200 || status >= 300) {
            String message = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8).trim();
            throw new MojoExecutionException("Could not " + operation + " (HTTP " + status + ")" + (message.isEmpty() ? "." : ": " + message));
        }
    }

}
