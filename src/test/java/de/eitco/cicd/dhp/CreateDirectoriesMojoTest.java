package de.eitco.cicd.dhp;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CreateDirectoriesMojoTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testIsLinuxWithLinuxOsNames() {
        assertTrue(CreateDirectoriesMojo.isLinux("Linux"));
        assertTrue(CreateDirectoriesMojo.isLinux("linux"));
        assertTrue(CreateDirectoriesMojo.isLinux("GNU/Linux"));
    }

    @Test
    public void testIsLinuxWithNonLinuxOsNames() {
        assertFalse(CreateDirectoriesMojo.isLinux("Windows 10"));
        assertFalse(CreateDirectoriesMojo.isLinux("Mac OS X"));
        assertFalse(CreateDirectoriesMojo.isLinux("FreeBSD"));
        assertFalse(CreateDirectoriesMojo.isLinux("SunOS"));
    }

    @Test
    public void testIsLinuxWithNullOsName() {
        assertFalse(CreateDirectoriesMojo.isLinux(null));
    }

    @Test
    public void testDecideActionMissing() {
        assertEquals(CreateDirectoriesMojo.DirectoryAction.CREATE, CreateDirectoriesMojo.decideAction(false, false, false, false));
    }

    @Test
    public void testDecideActionExistsNotDirectory() {
        assertEquals(CreateDirectoriesMojo.DirectoryAction.FAIL_NOT_A_DIRECTORY, CreateDirectoriesMojo.decideAction(true, false, false, false));
    }

    @Test
    public void testDecideActionExistsDirectoryOwnedByCurrentUser() {
        assertEquals(CreateDirectoriesMojo.DirectoryAction.ALREADY_OK, CreateDirectoriesMojo.decideAction(true, true, true, false));
    }

    @Test
    public void testDecideActionExistsDirectoryOtherOwnerEmpty() {
        assertEquals(CreateDirectoriesMojo.DirectoryAction.RECREATE_EMPTY, CreateDirectoriesMojo.decideAction(true, true, false, true));
    }

    @Test
    public void testDecideActionExistsDirectoryOtherOwnerNonEmpty() {
        assertEquals(CreateDirectoriesMojo.DirectoryAction.WARN_NOT_EMPTY, CreateDirectoriesMojo.decideAction(true, true, false, false));
    }

    @Test
    public void testCreateDirectoriesActuallyCreated() throws Exception {
        File missingDir = new File(tempFolder.getRoot(), "a/b/c");
        assertFalse(missingDir.exists());

        Files.createDirectories(missingDir.toPath());

        assertTrue(missingDir.exists());
        assertTrue(missingDir.isDirectory());
    }

    @Test
    public void testCreateDirectoriesWithMissingParents() throws Exception {
        File deepDir = new File(tempFolder.getRoot(), "deep/nested/path/to/dir");
        assertFalse(deepDir.exists());

        Files.createDirectories(deepDir.toPath());

        assertTrue(deepDir.exists());
        assertTrue(deepDir.isDirectory());
        assertTrue(deepDir.getParentFile().exists());
    }

    @Test
    public void testIsDirectoryEmptyOnEmptyDirectory() throws Exception {
        File emptyDir = tempFolder.newFolder("empty");

        assertTrue(CreateDirectoriesMojo.isDirectoryEmpty(emptyDir.toPath()));
    }

    @Test
    public void testIsDirectoryEmptyOnNonEmptyDirectory() throws Exception {
        File nonEmptyDir = tempFolder.newFolder("nonempty");
        tempFolder.newFile("nonempty/file.txt");

        assertFalse(CreateDirectoriesMojo.isDirectoryEmpty(nonEmptyDir.toPath()));
    }

    @Test
    public void testIsOwnedByCurrentUserWithOwnedDirectory() throws Exception {
        // This test only makes sense on systems with POSIX attributes.
        Assume.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        File dir = tempFolder.newFolder("owned");
        Path path = dir.toPath();

        // A directory created by this process should be owned by the current user.
        assertTrue(CreateDirectoriesMojo.isOwnedByCurrentUser(path));
    }
}
