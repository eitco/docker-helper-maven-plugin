package de.eitco.cicd.dhp;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Locale;

/**
 * Creates host directories that are intended to be used as Docker volume mount sources.
 * Automatically handles ownership to ensure non-root container processes can write into them.
 * Only takes effect when Maven's own JVM runs on Linux; silent no-op on Windows/macOS.
 */
@Mojo(name = "create-directories", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public class CreateDirectoriesMojo extends AbstractMojo {

    enum DirectoryAction {
        CREATE, ALREADY_OK, RECREATE_EMPTY, WARN_NOT_EMPTY, FAIL_NOT_A_DIRECTORY
    }

    /**
     * Host directories to create (and their missing parents) before Docker containers start.
     */
    @Parameter(property = "docker.volumes.directories")
    private List<File> directories;

    /**
     * Skips directory creation entirely.
     */
    @Parameter(defaultValue = "false", property = "docker.volumes.directories.skip")
    private boolean skip;

    public void execute() throws MojoExecutionException {

        if (skip) {
            getLog().info("Skipping directory creation.");
            return;
        }

        if (!isLinux()) {
            getLog().info("Skipping directory creation: this goal only creates directories when Maven's own JVM runs on Linux (detected os.name='" + System.getProperty("os.name") + "').");
            return;
        }

        if (directories == null || directories.isEmpty()) {
            getLog().info("No directories configured; nothing to do.");
            return;
        }

        for (File directory : directories) {
            if (directory == null || directory.getAbsolutePath().trim().isEmpty()) {
                throw new MojoExecutionException("Directory path must not be null or empty.");
            }

            Path path = directory.toPath();
            boolean exists = Files.exists(path);
            boolean isDirectory = Files.isDirectory(path);
            boolean ownedByCurrentUser = false;
            boolean isEmpty = false;

            if (exists && isDirectory) {
                try {
                    ownedByCurrentUser = isOwnedByCurrentUser(path);
                    isEmpty = isDirectoryEmpty(path);
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to check ownership/emptiness of directory '" + path + "'.", e);
                }
            }

            DirectoryAction action = decideAction(exists, isDirectory, ownedByCurrentUser, isEmpty);

            switch (action) {
                case CREATE:
                    try {
                        Files.createDirectories(path);
                        getLog().info("Created directory '" + path + "' (and missing parents).");
                    } catch (IOException e) {
                        throw new MojoExecutionException("Failed to create directory '" + path + "'.", e);
                    }
                    break;

                case ALREADY_OK:
                    getLog().info("Directory '" + path + "' already exists and is owned by current user.");
                    break;

                case RECREATE_EMPTY:
                    try {
                        Files.delete(path);
                        Files.createDirectories(path);
                        getLog().info("Recreated empty directory '" + path + "' to fix ownership (was owned by a different user).");
                    } catch (IOException e) {
                        getLog().warn("Failed to recreate empty directory '" + path + "' (owned by different user) — it may not have the correct ownership. Consider running 'chown' manually or using 'mvn clean'. Error: " + e.getMessage());
                    }
                    break;

                case WARN_NOT_EMPTY:
                    getLog().warn("Directory '" + path + "' exists but is owned by a different user and is non-empty. Ownership will not be changed (to avoid data loss). Consider running 'chown " + System.getProperty("user.name") + ":" + System.getProperty("user.name") + " " + path + "' or 'mvn clean' to remove it.");
                    break;

                case FAIL_NOT_A_DIRECTORY:
                    throw new MojoExecutionException("Path '" + path + "' exists but is not a directory.");
            }
        }
    }

    static boolean isLinux() {
        return isLinux(System.getProperty("os.name"));
    }

    static boolean isLinux(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("linux");
    }

    static DirectoryAction decideAction(boolean exists, boolean isDirectory, boolean ownedByCurrentUser, boolean isEmpty) {
        if (!exists) {
            return DirectoryAction.CREATE;
        }
        if (!isDirectory) {
            return DirectoryAction.FAIL_NOT_A_DIRECTORY;
        }
        if (ownedByCurrentUser) {
            return DirectoryAction.ALREADY_OK;
        }
        return isEmpty ? DirectoryAction.RECREATE_EMPTY : DirectoryAction.WARN_NOT_EMPTY;
    }

    static boolean isOwnedByCurrentUser(Path path) throws IOException {
        UserPrincipal owner = Files.getOwner(path);
        return owner != null && owner.getName().equals(System.getProperty("user.name"));
    }

    static boolean isDirectoryEmpty(Path path) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            return !stream.iterator().hasNext();
        }
    }
}
