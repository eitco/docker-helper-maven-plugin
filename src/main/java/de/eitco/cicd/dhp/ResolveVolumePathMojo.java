package de.eitco.cicd.dhp;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.Properties;

/**
 * Resolves a local project path to a Docker volume path and exposes it as a Maven property.
 */
@Mojo(name = "resolve-volume-path", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public class ResolveVolumePathMojo extends AbstractMojo {

    /**
     * Local path that should be mounted into a Docker container.
     */
    @Parameter(defaultValue = "${project.basedir}", property = "docker.volumes.localPath", required = true)
    private File localPath;

    /**
     * Name of the Maven property that receives the resolved Docker volume path.
     */
    @Parameter(defaultValue = "docker.volumes.resolvedPath", property = "docker.volumes.propertyName", required = true)
    private String propertyName;

    /**
     * Current Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    public void execute() throws MojoExecutionException {

        if (propertyName == null || propertyName.trim().isEmpty()) {
            throw new MojoExecutionException("Property name must not be empty.");
        }

        String resolvedPath = resolveDockerVolumePath(localPath);
        Properties properties = project.getProperties();
        properties.setProperty(propertyName, resolvedPath);

        getLog().info("Set Maven property '" + propertyName + "' to '" + resolvedPath + "'.");
    }

    static String resolveDockerVolumePath(File path) throws MojoExecutionException {

        if (path == null) {
            throw new MojoExecutionException("Local path must not be null.");
        }

        return resolveDockerVolumePath(path.getAbsolutePath());
    }

    static String resolveDockerVolumePath(String absolutePath) throws MojoExecutionException {

        if (absolutePath == null || absolutePath.trim().isEmpty()) {
            throw new MojoExecutionException("Local path must not be empty.");
        }

        String normalizedPath = absolutePath.replace('\\', '/');

        if (isWindowsDrivePath(normalizedPath)) {
            char driveLetter = Character.toLowerCase(normalizedPath.charAt(0));
            String pathWithoutDrive = normalizedPath.substring(2);
            if (!pathWithoutDrive.startsWith("/")) {
                pathWithoutDrive = "/" + pathWithoutDrive;
            }
            return "/mnt/" + driveLetter + pathWithoutDrive;
        }

        return normalizedPath;
    }

    private static boolean isWindowsDrivePath(String path) {
        if (path.length() < 2 || path.charAt(1) != ':') {
            return false;
        }

        char driveLetter = path.charAt(0);
        return (driveLetter >= 'A' && driveLetter <= 'Z') || (driveLetter >= 'a' && driveLetter <= 'z');
    }
}
