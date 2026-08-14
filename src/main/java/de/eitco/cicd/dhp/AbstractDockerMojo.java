package de.eitco.cicd.dhp;

import org.apache.http.HttpHost;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.Socket;

/** Common Docker daemon communication support for plugin goals. */
abstract class AbstractDockerMojo extends AbstractMojo {

    /** HTTP address of the Docker daemon. By default it is read from the DOCKER_HOST environment variable. */
    @Parameter(defaultValue = "${env.DOCKER_HOST}", property = "docker.host", required = true)
    protected String dockerHost;

    protected CloseableHttpClient createHttpClient() throws MojoExecutionException {
        if (!dockerHost.trim().startsWith("unix://")) {
            return HttpClients.createDefault();
        }

        final File socketFile = unixSocketFile(dockerHost);
        ConnectionSocketFactory socketFactory = new ConnectionSocketFactory() {
            @Override
            public Socket createSocket(HttpContext context) throws IOException {
                return AFUNIXSocket.newInstance();
            }

            @Override
            public Socket connectSocket(int connectTimeout, Socket socket, HttpHost host, InetSocketAddress remoteAddress,
                InetSocketAddress localAddress, HttpContext context) throws IOException {
                socket.connect(AFUNIXSocketAddress.of(socketFile), connectTimeout);
                return socket;
            }
        };
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
}
