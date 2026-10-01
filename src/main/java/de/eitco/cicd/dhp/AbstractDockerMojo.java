package de.eitco.cicd.dhp;

import org.apache.http.HttpHost;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Common Docker daemon communication support for plugin goals.
 */
abstract class AbstractDockerMojo extends AbstractMojo {

    /**
     * Pseudo host selecting WSL Containers (WSLC) on Windows.
     */
    static final String WSLC_HOST = "wslc://";

    /**
     * Address of the Docker daemon. By default it is read from the DOCKER_HOST environment variable. If neither is
     * set and the plugin runs on Windows, WSL Containers are used (also selectable explicitly with <code>wslc://</code>).
     */
    @Parameter(defaultValue = "${env.DOCKER_HOST}", property = "docker.host")
    protected String dockerHost;

    private boolean wslcChecked;
    private boolean wslcAvailable;

    private static String describeConnectionFailure(IOException e) {

        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();

        if (e instanceof ConnectException) {
            return "connection refused (" + detail + "). Is the Docker daemon running and listening on this address?";
        }

        if (e instanceof UnknownHostException) {
            return "unknown host (" + detail + "). Check the DOCKER_HOST / docker.host configuration.";
        }

        if (e instanceof NoRouteToHostException) {
            return "no route to host (" + detail + "). Check network connectivity and firewall rules.";
        }

        if (e instanceof SocketTimeoutException) {
            return "connection timed out (" + detail + "). The Docker daemon may be unreachable or overloaded.";
        }

        return e.getClass().getName() + ": " + detail;
    }

    private static String readBodyQuietly(CloseableHttpResponse response) {
        try {
            return response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Returns the configured Docker host. If none is configured and the plugin runs on Windows, WSL Containers are used.
     */
    protected String resolveDockerHost() throws MojoExecutionException {

        if (dockerHost != null && !dockerHost.trim().isEmpty()) {
            return dockerHost;
        }

        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return dockerHost;
        }

        if (!wslcChecked) {
            wslcChecked = true;
            wslcAvailable = WslcStdioSocket.isAvailable();
            if (wslcAvailable) {
                getLog().info("No Docker host configured, using WSL Containers (" + WSLC_HOST + ").");
            }
        }

        if (!wslcAvailable) {
            throw new MojoExecutionException("No Docker host configured and WSL Containers are not available ('"
                + WslcStdioSocket.COMMAND.get(0) + " version' failed). Set DOCKER_HOST or docker.host, or install WSL Containers.");
        }

        return WSLC_HOST;
    }

    protected CloseableHttpClient createHttpClient() throws MojoExecutionException {

        String host = resolveDockerHost();

        if (host != null && host.trim().equalsIgnoreCase(WSLC_HOST)) {
            return createSocketHttpClient(new ConnectionSocketFactory() {
                @Override
                public Socket createSocket(HttpContext context) {
                    return new WslcStdioSocket();
                }

                @Override
                public Socket connectSocket(
                    int connectTimeout, Socket socket, HttpHost httpHost, InetSocketAddress remoteAddress,
                    InetSocketAddress localAddress, HttpContext context
                ) throws IOException {
                    socket.connect(remoteAddress, connectTimeout);
                    return socket;
                }
            });
        }

        if (host == null || !host.trim().startsWith("unix://")) {
            return HttpClients.createDefault();
        }

        final File socketFile = unixSocketFile(host);

        ConnectionSocketFactory socketFactory = new ConnectionSocketFactory() {
            @Override
            public Socket createSocket(HttpContext context) throws IOException {
                return AFUNIXSocket.newInstance();
            }

            @Override
            public Socket connectSocket(
                int connectTimeout, Socket socket, HttpHost host, InetSocketAddress remoteAddress,
                InetSocketAddress localAddress, HttpContext context
            ) throws IOException {
                socket.connect(AFUNIXSocketAddress.of(socketFile), connectTimeout);
                return socket;
            }
        };

        return createSocketHttpClient(socketFactory);
    }

    private static CloseableHttpClient createSocketHttpClient(ConnectionSocketFactory socketFactory) {

        Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
            .register("http", socketFactory)
            .build();

        return HttpClients.custom().setConnectionManager(new PoolingHttpClientConnectionManager(socketFactoryRegistry)).build();
    }

    protected static URI dockerUri(String configuredHost) throws MojoExecutionException {

        if (configuredHost == null || configuredHost.trim().isEmpty()) {
            throw new MojoExecutionException("Docker host is not configured. Set DOCKER_HOST or docker.host.");
        }

        String host = configuredHost.trim();

        if (host.startsWith("tcp://")) {
            host = "http://" + host.substring("tcp://".length());
        }

        if (host.equalsIgnoreCase(WSLC_HOST)) {
            return URI.create("http://localhost");
        }

        if (host.startsWith("unix://")) {
            unixSocketFile(host);
            return URI.create("http://localhost");
        }

        if (host.startsWith("npipe://")) {
            throw new MojoExecutionException("Docker host '" + configuredHost + "' is not an HTTP endpoint. Configure docker.host with an HTTP URL.");
        }

        try {
            URI uri = new URI(host);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new MojoExecutionException("Docker host must be an HTTP URL: " + configuredHost);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new MojoExecutionException("Docker host is not a valid URL: " + configuredHost, e);
        }
    }

    protected static URI endpoint(URI dockerUri, String path) {

        String base = dockerUri.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private static File unixSocketFile(String configuredHost) throws MojoExecutionException {

        try {
            URI uri = new URI(configuredHost);
            if (!"unix".equalsIgnoreCase(uri.getScheme()) || uri.getPath() == null || uri.getPath().isEmpty()) {
                throw new MojoExecutionException("Docker Unix socket path must not be empty: " + configuredHost);
            }
            return new File(uri.getPath());
        } catch (URISyntaxException e) {
            throw new MojoExecutionException("Docker host is not a valid URL: " + configuredHost, e);
        }
    }

    protected void pingDaemon(
        CloseableHttpClient httpClient,
        URI dockerUri
    ) throws MojoExecutionException {

        URI pingUri = endpoint(dockerUri, "/_ping");

        try (CloseableHttpResponse response = httpClient.execute(new HttpGet(pingUri))) {

            int status = response.getStatusLine().getStatusCode();

            if (!CleanupContainersMojo.isSuccessStatus(status)) {
                String body = AbstractDockerMojo.readBodyQuietly(response);
                throw new MojoExecutionException("Could not ping Docker daemon at " + pingUri + " (configured Docker host: "
                    + resolveDockerHost() + "): received HTTP " + status + " " + response.getStatusLine().getReasonPhrase()
                    + (body.isEmpty() ? "" : " - " + body) + ".");
            }

            getLog().info("Ping Docker daemon successful.");

        } catch (IOException e) {
            throw new MojoExecutionException("Could not ping Docker daemon at " + pingUri + " (configured Docker host: "
                                             + resolveDockerHost() + "): " +AbstractDockerMojo.describeConnectionFailure(e), e);
        }
    }
}
