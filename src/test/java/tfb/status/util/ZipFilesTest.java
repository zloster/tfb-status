package tfb.status.util;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tfb.status.util.ZipFiles.ZipEntryReader;

/**
 * Tests for {@link ZipFiles}.
 */
public final class ZipFilesTest {
  private static final String PRESENT_ENTRY_PATH = "hello.txt";
  private static final String PRESENT_ENTRY_ABSOLUTE_PATH = "/" + PRESENT_ENTRY_PATH;
  private static final byte[] PRESENT_ENTRY_BYTES = "Hello!".getBytes(UTF_8);
  private static final String DIR_ENTRY_PATH = "dir/";

  private static final ZipEntryReader<Void> UNUSED_READER =
      in -> {
        throw new AssertionError("This reader should not have been used");
      };

  private static Path zipFile;
  private static Path textFile;
  private static Path dir;
  private static Path missingFile;

  @BeforeAll
  public static void beforeAll() throws Exception {
    zipFile = Files.createTempFile("ZipFilesTest", ".zip");
    try (ZipOutputStream out =
             new ZipOutputStream(
                 new BufferedOutputStream(Files.newOutputStream(zipFile)))) {
      ZipEntry entry = new ZipEntry(PRESENT_ENTRY_PATH);
      out.putNextEntry(entry);
      out.write(PRESENT_ENTRY_BYTES);
      out.closeEntry();
      ZipEntry dir = new ZipEntry(DIR_ENTRY_PATH);
      out.putNextEntry(dir);
      out.closeEntry();
    }
    textFile = Files.createTempFile("ZipFilesTest", ".txt");
    Files.write(textFile, List.of("This is not a zip file"));
    dir = Files.createTempDirectory("ZipFilesTest");
    missingFile = dir.resolve("missing");
  }

  @AfterAll
  public static void afterAll() throws Exception {
    Exception thrown = null;
    for (Path file : List.of(zipFile, textFile, missingFile, dir)) {
      if (file != null) {
        try {
          Files.deleteIfExists(file);
        } catch (Exception e) {
          if (thrown == null) {
            thrown = e;
          } else {
            thrown.addSuppressed(e);
          }
        }
      }
    }
    if (thrown != null) {
      throw thrown;
    }
  }

  /**
   * Tests that {@link ZipFiles#readZipEntry(Path, String, ZipEntryReader)} is
   * able to read a present entry from a valid zip file given a relative entry
   * path.
   */
  @Test
  public void testReadZipEntry_relativePath() throws IOException {
    assertArrayEquals(
        PRESENT_ENTRY_BYTES,
        ZipFiles.readZipEntry(zipFile,
                              PRESENT_ENTRY_PATH,
                              InputStream::readAllBytes));
  }

  /**
   * Tests that {@link ZipFiles#readZipEntry(Path, String, ZipEntryReader)} is
   * able to read a present entry from a valid zip file given an absolute entry
   * path.
   */
  @Test
  public void testReadZipEntry_absolutePath() throws IOException {
    assertArrayEquals(
        PRESENT_ENTRY_BYTES,
        ZipFiles.readZipEntry(zipFile,
                              PRESENT_ENTRY_ABSOLUTE_PATH,
                              InputStream::readAllBytes));
  }

  /**
   * Tests that {@link ZipFiles#readZipEntry(Path, String, ZipEntryReader)}
   * rejects a zip file that does not exist.
   */
  @Test
  public void testReadZipEntry_rejectMissingFile() {
    assertThrows(
        IOException.class,
        () -> ZipFiles.readZipEntry(missingFile,
                                    PRESENT_ENTRY_PATH,
                                    UNUSED_READER));
  }

  /**
   * Tests that {@link ZipFiles#readZipEntry(Path, String, ZipEntryReader)}
   * rejects a zip file that is not actually in zip format.
   */
  @Test
  public void testReadZipEntry_rejectWrongFileFormat() {
    assertThrows(
        IOException.class,
        () -> ZipFiles.readZipEntry(textFile,
                                    PRESENT_ENTRY_PATH,
                                    UNUSED_READER));
  }

  /**
   * Tests that {@link ZipFiles#readZipEntry(Path, String, ZipEntryReader)}
   * rejects a zip file that is a directory.
   */
  @Test
  public void testReadZipEntry_rejectDirectory() {
    assertThrows(
        IOException.class,
        () -> ZipFiles.readZipEntry(dir,
                                    PRESENT_ENTRY_PATH,
                                    UNUSED_READER));
  }

  /**
   * Tests that {@link ZipFiles#readZipEntry(Path, String, ZipEntryReader)}
   * rejects an entry path containing invalid characters.  The exception thrown
   * should be {@link IOException} rather than {@link InvalidPathException}.
   *
   * <p>Note that zip file paths are tolerant of many characters that are
   * typically rejected for paths on the main file system.
   */
  @Test
  public void testReadZipEntry_rejectInvalidPath() {
    assertThrows(
        IOException.class,
        () -> ZipFiles.readZipEntry(zipFile,
                                    "\0",
                                    UNUSED_READER));
  }

  /**
   * Tests that {@link ZipFiles#readZipEntry(Path, String, ZipEntryReader)}
   * returns {@code null} (rather than throwing an exception) when the entry
   * path refers to a directory entry in the zip file.
   */
  @Test
  public void testReadZipEntry_skipDirectoryEntry() throws IOException {
    assertNull(ZipFiles.readZipEntry(zipFile,
                                     DIR_ENTRY_PATH,
                                     UNUSED_READER));
  }

  /**
   * Tests that {@link ZipFiles#readZipEntry(Path, String, ZipEntryReader)}
   * throws a {@link NullPointerException} when the entry reader produces a
   * {@code null} result.
   */
  @Test
  public void testReadZipEntry_rejectNullValueFromReader() {
    @SuppressWarnings("NullAway") // Violating nullness contract on purpose.
    ZipEntryReader<Void> entryReader = in -> null;

    assertThrows(
        NullPointerException.class,
        () -> ZipFiles.readZipEntry(zipFile,
                                    PRESENT_ENTRY_PATH,
                                    entryReader));
  }

  /**
   * Tests that {@link ZipFiles#readZipEntry(Path, String, ZipEntryReader)}
   * propagates an {@link IOException} thrown by the entry reader.
   */
  @Test
  public void testReadZipEntry_ioExceptionFromReaderIsUncaught() {
    IOException e1 = new IOException();

    IOException e2 =
        assertThrows(
            IOException.class,
            () -> ZipFiles.readZipEntry(zipFile,
                                        PRESENT_ENTRY_PATH,
                                        in -> { throw e1; }));
    assertSame(e1, e2);
  }

  /**
   * Tests that {@link ZipFiles#readZipEntry(Path, String, ZipEntryReader)}
   * propagates a {@link RuntimeException} thrown by the entry reader.
   */
  @Test
  public void testReadZipEntry_runtimeExceptionFromReaderIsUncaught() {
    RuntimeException e1 = new RuntimeException();

    RuntimeException e2 =
        assertThrows(
            RuntimeException.class,
            () -> ZipFiles.readZipEntry(zipFile,
                                        PRESENT_ENTRY_PATH,
                                        in -> { throw e1; }));
    assertSame(e1, e2);
  }
}
