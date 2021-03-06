package tfb.status.handler;

import static com.google.common.net.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static io.undertow.util.Headers.CONTENT_TYPE;
import static io.undertow.util.Methods.GET;
import static io.undertow.util.StatusCodes.NOT_FOUND;

import com.google.common.base.Joiner;
import com.google.common.io.MoreFiles;
import com.google.common.net.MediaType;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.DisableCacheHandler;
import io.undertow.server.handlers.SetHeaderHandler;
import io.undertow.util.MimeMappings;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import tfb.status.config.FileStoreConfig;
import tfb.status.undertow.extensions.DefaultToUtf8Handler;
import tfb.status.undertow.extensions.MethodHandler;
import tfb.status.util.ZipFiles;

/**
 * Handles requests to extract files from within results.zip files.
 */
@Singleton
public final class UnzipResultsHandler implements HttpHandler {
  private final HttpHandler delegate;

  @Inject
  public UnzipResultsHandler(FileStoreConfig fileStoreConfig) {

    HttpHandler handler = new CoreHandler(fileStoreConfig);

    handler = new DefaultToUtf8Handler(handler);
    handler = new MethodHandler().addMethod(GET, handler);
    handler = new DisableCacheHandler(handler);
    handler = new SetHeaderHandler(handler, ACCESS_CONTROL_ALLOW_ORIGIN, "*");

    delegate = handler;
  }

  @Override
  public void handleRequest(HttpServerExchange exchange) throws Exception {
    delegate.handleRequest(exchange);
  }

  private static final class CoreHandler implements HttpHandler {
    private final Path resultsDirectory;

    CoreHandler(FileStoreConfig fileStoreConfig) {
      this.resultsDirectory = Paths.get(fileStoreConfig.resultsDirectory);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

      String relativePath = exchange.getRelativePath()
                                    .substring(1); // omit leading slash

      Path requestedFile;
      try {
        requestedFile = resultsDirectory.resolve(relativePath);
      } catch (InvalidPathException ignored) {
        exchange.setStatusCode(NOT_FOUND);
        return;
      }

      if (!requestedFile.equals(requestedFile.normalize())
          || !requestedFile.startsWith(resultsDirectory)) {
        exchange.setStatusCode(NOT_FOUND);
        return;
      }

      Path relativeToResultsDir = resultsDirectory.relativize(requestedFile);

      if (relativeToResultsDir.getNameCount() < 2) {
        exchange.setStatusCode(NOT_FOUND);
        return;
      }

      Path zipFile = resultsDirectory.resolve(relativeToResultsDir.getName(0));

      if (!Files.isRegularFile(zipFile)
          || !MoreFiles.getFileExtension(zipFile).equals("zip")) {
        exchange.setStatusCode(NOT_FOUND);
        return;
      }

      Path entrySubPath =
          relativeToResultsDir.subpath(1, relativeToResultsDir.getNameCount());

      // TODO: This is showing a deficiency in the ZipFiles.readZipEntry API.
      String nonNullIfEntryExists =
          ZipFiles.readZipEntry(
              /* zipFile= */ zipFile,
              /* entryPath= */ Joiner.on('/').join(entrySubPath),
              /* entryReader= */
              in -> {
                MediaType mediaType = guessMediaType(entrySubPath);

                if (mediaType != null)
                  exchange.getResponseHeaders().put(CONTENT_TYPE,
                                                    mediaType.toString());

                in.transferTo(exchange.getOutputStream());

                return ""; // could use any non-null value here
              });

      if (nonNullIfEntryExists == null) {
        exchange.setStatusCode(NOT_FOUND);
      }
    }

    /**
     * Guesses the media type of the given file.  Returns {@code null} if there
     * is not a good guess.
     */
    @Nullable
    private static MediaType guessMediaType(Path file) {
      String extension = MoreFiles.getFileExtension(file);

      String mediaTypeString =
          MimeMappings.DEFAULT_MIME_MAPPINGS.get(extension);

      if (mediaTypeString == null)
        return null;

      return MediaType.parse(mediaTypeString);
    }
  }
}
