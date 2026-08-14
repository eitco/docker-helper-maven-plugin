
[![Build status](https://img.shields.io/github/actions/workflow/status/eitco/docker-helper-maven-plugin/deploy.yaml?branch=main&style=for-the-badge&logo=github)](https://github.com/eitco/docker-helper-maven-plugin/actions/workflows/deploy.yaml)
[![Maven Central Version](https://img.shields.io/maven-central/v/de.eitco.cicd/docker-helper-maven-plugin?style=for-the-badge&logo=apachemaven)](https://central.sonatype.com/artifact/de.eitco.cicd/docker-helper-maven-plugin)

# Docker Helper Maven Plugin

This Maven plugin provides tools to use docker containers in a maven project. For example, it resolves a local project 
path so it can be used as a Docker volume path and exposes the result as a Maven property.

The typical use case is a Maven project that starts Docker containers with local volume mappings. On Linux, Docker can 
use absolute paths directly. On Windows with Docker in WSL, a path like `C:\myproject` must be passed as `/mnt/c/myproject`.

## Goal

```text
docker-helper:resolve-volume-path
```

The goal runs in the `validate` phase by default.

## Default behavior

Without further configuration, `${project.basedir}` is resolved and stored in the Maven property `docker.volumes.resolvedPath`.

Examples:

```text
C:\myproject -> /mnt/c/myproject
/home/me/myproject -> /home/me/myproject
```

## Usage

```xml
<plugin>
  <groupId>de.eitco.cicd</groupId>
  <artifactId>docker-helper-maven-plugin</artifactId>
  <version>1.0-SNAPSHOT</version>
  <executions>
    <execution>
      <id>resolve-docker-volume-path</id>
      <phase>validate</phase>
      <goals>
        <goal>resolve-volume-path</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

The property can then be used in later Maven phases:

```xml
${docker.volumes.resolvedPath}
```

## Custom Property

```xml
<plugin>
  <groupId>de.eitco.cicd</groupId>
  <artifactId>docker-helper-maven-plugin</artifactId>
  <version>1.0-SNAPSHOT</version>
  <executions>
    <execution>
      <id>resolve-docker-volume-path</id>
      <phase>validate</phase>
      <goals>
        <goal>resolve-volume-path</goal>
      </goals>
      <configuration>
        <propertyName>my.docker.volume.path</propertyName>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Usage:

```xml
${my.docker.volume.path}
```

## Custom Local Path

By default, `${project.basedir}` is used. A different path can be configured with `localPath`.

```xml
<configuration>
  <localPath>${project.basedir}/src/test/resources</localPath>
  <propertyName>test.resources.docker.path</propertyName>
</configuration>
```

## Docker Plugin Example

```xml
<volume>${docker.volumes.resolvedPath}:/app</volume>
```

Make sure that `resolve-volume-path` runs in an earlier phase than the plugin that starts the Docker containers.

## Parameters

| Parameter | Maven Property | Default | Description |
| --- | --- | --- | --- |
| `localPath` | `docker.volumes.localPath` | `${project.basedir}` | Local path that is resolved for Docker. |
| `propertyName` | `docker.volumes.propertyName` | `docker.volumes.resolvedPath` | Name of the Maven property that receives the resolved path. |

## Container cleanup

`docker-helper:cleanup-containers` lists all Docker containers, matches their names against a regular expression, and stops and removes every match. Docker's leading slash is removed before the name is matched. The Docker daemon is read from `DOCKER_HOST` by default; `tcp://` values are used as HTTP URLs. The goal has no default lifecycle phase, so it can be bound where the build's containers are no longer needed.

```xml
<execution>
  <id>remove-build-containers</id>
  <phase>post-integration-test</phase>
  <goals>
    <goal>cleanup-containers</goal>
  </goals>
  <configuration>
    <namePattern>my-build-.*</namePattern>
  </configuration>
</execution>
```

Failures while stopping or removing an individual matching container are logged as warnings and do not fail the build. Listing containers still fails the goal when the Docker daemon cannot be reached.

To clean up after all Maven phases have completed, the cleanup can be registered as a JVM shutdown hook. In this mode the goal only registers the hook; it lists, stops, and removes containers when Maven's JVM exits.

```xml
<configuration>
  <namePattern>my-build-.*</namePattern>
  <registerShutdownHook>true</registerShutdownHook>
</configuration>
```

| Parameter | Maven Property | Default | Description |
| --- | --- | --- | --- |
| `dockerHost` | `docker.cleanup.host` | `${env.DOCKER_HOST}` | HTTP URL of the Docker daemon. `tcp://` is accepted and converted to HTTP. |
| `namePattern` | `docker.cleanup.namePattern` | `.*` | Regular expression matched against the container name. |
| `registerShutdownHook` | `docker.cleanup.shutdownHook` | `false` | Registers cleanup for Maven JVM shutdown instead of executing it immediately. |
| `skip` | `docker.cleanup.skip` | `false` | Skips cleanup completely, including shutdown-hook registration. |

## Build

```shell
mvn clean verify
```

# continuous integration

The directories `.github` and `deployment` contain the CI. While the directory `.github` contains actions that build
each commit and release the project on demand, the directory `deployment` contains configuration for the release.
A lot of the build however is configured by the project object model (pom.xml).

# .mvn

The `.mvn` directory activates and configures the `maven-git-versioning-extension`. This extension changes the 
projects version depending on the current branch. This way every branch can be deployed without their artifacts
overriding each other.
