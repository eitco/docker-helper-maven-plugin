package de.eitco.cicd.dhp;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.Assert;
import org.junit.Test;

public class ResolveVolumePathMojoTest
{
    @Test
    public void convertsWindowsDrivePathToWslMountPath()
        throws MojoExecutionException
    {
        Assert.assertEquals(
            "/mnt/c/devpacks/myproject",
            ResolveVolumePathMojo.resolveDockerVolumePath( "C:\\devpacks\\myproject" )
        );
    }

    @Test
    public void convertsLowercaseWindowsDrivePathToWslMountPath()
        throws MojoExecutionException
    {
        Assert.assertEquals(
            "/mnt/d/work/project",
            ResolveVolumePathMojo.resolveDockerVolumePath( "d:/work/project" )
        );
    }

    @Test
    public void keepsLinuxAbsolutePath()
        throws MojoExecutionException
    {
        Assert.assertEquals(
            "/home/me/project",
            ResolveVolumePathMojo.resolveDockerVolumePath( "/home/me/project" )
        );
    }
}
