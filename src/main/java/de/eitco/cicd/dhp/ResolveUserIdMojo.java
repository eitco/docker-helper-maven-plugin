package de.eitco.cicd.dhp;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Resolves the current user's numeric UID and GID and exposes them as Maven properties.
 * On Windows, invokes `wsl id -u` and `wsl id -g`; on Linux/Unix, invokes `id -u` and `id -g` directly.
 */
@Mojo(name = "resolve-user-id", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public class ResolveUserIdMojo extends AbstractMojo {

    /**
     * Name of the Maven property that receives the current user's numeric UID.
     */
    @Parameter(defaultValue = "docker.user.uid", property = "docker.user.uid.propertyName", required = true)
    private String uidPropertyName;

    /**
     * Name of the Maven property that receives the current user's numeric GID.
     */
    @Parameter(defaultValue = "docker.user.gid", property = "docker.user.gid.propertyName", required = true)
    private String gidPropertyName;

    /**
     * Overrides the command used to determine the UID. Auto-detected when not set:
     * `id -u` on Linux, `wsl id -u` on Windows.
     */
    @Parameter(property = "docker.user.uid.command")
    private List<String> uidCommand;

    /**
     * Overrides the command used to determine the GID. Auto-detected when not set:
     * `id -g` on Linux, `wsl id -g` on Windows.
     */
    @Parameter(property = "docker.user.gid.command")
    private List<String> gidCommand;

    /**
     * Skips resolution of the current user's UID/GID.
     */
    @Parameter(defaultValue = "false", property = "docker.user.skip")
    private boolean skip;

    /**
     * Current Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    public void execute() throws MojoExecutionException {

        if (skip) {
            getLog().info("Skipping user ID resolution.");
            return;
        }

        if (uidPropertyName == null || uidPropertyName.trim().isEmpty()) {
            throw new MojoExecutionException("UID property name must not be empty.");
        }
        if (gidPropertyName == null || gidPropertyName.trim().isEmpty()) {
            throw new MojoExecutionException("GID property name must not be empty.");
        }

        boolean windows = isWindows();
        List<String> uidCmd = effectiveCommand(uidCommand, defaultUidCommand(windows));
        List<String> gidCmd = effectiveCommand(gidCommand, defaultGidCommand(windows));

        String uid = parseId(runCommand(uidCmd), "UID");
        String gid = parseId(runCommand(gidCmd), "GID");

        Properties properties = project.getProperties();
        properties.setProperty(uidPropertyName, uid);
        properties.setProperty(gidPropertyName, gid);

        getLog().info("Set Maven property '" + uidPropertyName + "' to '" + uid + "'.");
        getLog().info("Set Maven property '" + gidPropertyName + "' to '" + gid + "'.");
    }

    static boolean isWindows() {
        return isWindows(System.getProperty("os.name"));
    }

    static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    static List<String> defaultUidCommand(boolean windows) {
        return windows ? Arrays.asList("wsl", "id", "-u") : Arrays.asList("id", "-u");
    }

    static List<String> defaultGidCommand(boolean windows) {
        return windows ? Arrays.asList("wsl", "id", "-g") : Arrays.asList("id", "-g");
    }

    static List<String> effectiveCommand(List<String> configured, List<String> fallback) {
        return (configured != null && !configured.isEmpty()) ? configured : fallback;
    }

    static String parseId(String rawOutput, String label) throws MojoExecutionException {

        if (rawOutput == null || rawOutput.trim().isEmpty()) {
            throw new MojoExecutionException(label + " output is empty.");
        }

        String trimmed = rawOutput.trim();

        if (!Pattern.matches("\\d+", trimmed)) {
            throw new MojoExecutionException(label + " output is not a valid numeric ID: '" + trimmed + "'.");
        }

        return trimmed;
    }

    static String runCommand(List<String> command) throws MojoExecutionException {

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process;

        try {
            process = pb.start();
        } catch (Exception e) {
            throw new MojoExecutionException("Could not start command " + command + ".", e);
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        // Drain stderr in a separate thread to avoid deadlock from full pipes.
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (stderr.length() > 0) {
                        stderr.append("\n");
                    }
                    stderr.append(line);
                }
            } catch (Exception ignored) {
            }
        });
        stderrThread.setDaemon(true);
        stderrThread.start();

        // Read stdout on the main thread.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (stdout.length() > 0) {
                    stdout.append("\n");
                }
                stdout.append(line);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to read command output.", e);
        }

        // Wait for the process and the stderr thread.
        int exitCode;
        try {
            exitCode = process.waitFor();
            stderrThread.join(5000); // Give stderr thread up to 5 seconds to finish.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while waiting for command " + command + ".", e);
        }

        if (exitCode != 0) {
            String errorMsg = stderr.toString().trim();
            if (errorMsg.isEmpty()) {
                throw new MojoExecutionException("Command " + command + " failed with exit code " + exitCode + ".");
            } else {
                throw new MojoExecutionException("Command " + command + " failed with exit code " + exitCode + ": " + errorMsg);
            }
        }

        return stdout.toString();
    }
}
