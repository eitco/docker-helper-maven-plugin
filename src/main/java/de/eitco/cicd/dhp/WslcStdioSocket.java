package de.eitco.cicd.dhp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * A pseudo socket tunnelling the Docker API of WSL Containers (WSLC) through the stdio of
 * <code>wslc system session run docker system dial-stdio</code>. WSLC publishes neither a named pipe nor a TCP port,
 * so every connection spawns its own bridge process.
 */
class WslcStdioSocket extends Socket {

    private static final String EXECUTABLE = System.getenv("WSLC_EXECUTABLE") != null ? System.getenv("WSLC_EXECUTABLE") : "wslc";

    static final List<String> COMMAND = Arrays.asList(EXECUTABLE, "system", "session", "run", "docker", "system", "dial-stdio");

    /**
     * Tells whether WSLC is the Docker environment in use: either selected explicitly with <code>wslc://</code> or,
     * on Windows without any configured Docker host, because it is available.
     */
    static boolean isSelected(String dockerHost) {

        if (dockerHost != null && !dockerHost.trim().isEmpty()) {
            return dockerHost.trim().equalsIgnoreCase(AbstractDockerMojo.WSLC_HOST);
        }

        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") && isAvailable();
    }

    /**
     * Checks whether WSLC is usable. <code>wslc version</code> is a cheap metadata call that does not start the container VM.
     */
    static boolean isAvailable() {

        Process probe = null;

        try {
            probe = new ProcessBuilder(EXECUTABLE, "version")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(new File("NUL")))
                .start();

            if (!probe.waitFor(10, TimeUnit.SECONDS)) {
                return false;
            }

            return probe.exitValue() == 0;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (probe != null && probe.isAlive()) {
                probe.destroyForcibly();
            }
        }
    }

    private Process process;
    private volatile boolean closed;
    private volatile boolean inputShutdown;
    private volatile boolean outputShutdown;

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {

        try {
            process = new ProcessBuilder(COMMAND).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        } catch (IOException e) {
            throw new IOException("Could not start WSLC bridge '" + String.join(" ", COMMAND) + "'. Is WSL Containers installed and 'wslc' on the PATH?", e);
        }
    }

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
        connect(endpoint, 0);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return connectedProcess().getInputStream();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return connectedProcess().getOutputStream();
    }

    private Process connectedProcess() throws IOException {
        if (process == null || closed) {
            throw new IOException("WSLC bridge is not connected.");
        }
        return process;
    }

    @Override
    public boolean isConnected() {
        return process != null;
    }

    @Override
    public boolean isClosed() {
        // a dead bridge counts as closed, so the connection pool does not reuse a stale keep-alive connection
        return closed || (process != null && !process.isAlive());
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        return process == null ? null : InetSocketAddress.createUnresolved("localhost", 0);
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (process != null) {
            process.destroyForcibly();
        }
    }

    @Override
    public void shutdownOutput() throws IOException {
        outputShutdown = true;
        if (process != null) {
            process.getOutputStream().close();
        }
    }

    @Override
    public void shutdownInput() {
        inputShutdown = true;
    }

    @Override
    public boolean isInputShutdown() {
        return inputShutdown;
    }

    @Override
    public boolean isOutputShutdown() {
        return outputShutdown;
    }

    // Socket options are meaningless for a process pipe; the base implementation must never touch a real socket.

    @Override
    public void setSoTimeout(int timeout) {
    }

    @Override
    public int getSoTimeout() {
        return 0;
    }

    @Override
    public void setTcpNoDelay(boolean on) {
    }

    @Override
    public void setKeepAlive(boolean on) {
    }

    @Override
    public void setSoLinger(boolean on, int linger) {
    }

    @Override
    public void setReceiveBufferSize(int size) {
    }

    @Override
    public void setSendBufferSize(int size) {
    }
}
