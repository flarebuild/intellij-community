// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.indexsort;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.indexing.shared.download.SharedIndexCompression;
import com.intellij.indexing.shared.metadata.SharedIndexMetadata;
import com.intellij.indexing.shared.platform.api.SharedIndexInfrastructureVersion;
import com.intellij.indexing.shared.util.zipFs.UncompressedZipFileSystem;
import com.intellij.indexing.shared.util.zipFs.Zip64Util;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.TaskInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.wm.ex.ProgressIndicatorEx;
import com.intellij.util.indexing.FileBasedIndexExtension;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.impl.MapIndexStorage;
//import com.intellij.util.indexing.impl.MapReduceIndex;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.zip.JBZipEntry;
import com.intellij.util.io.zip.JBZipFile;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;

import static org.jetbrains.indexsort.IndexSortMain.stdout;

public class IndexSort {
  private static final Logger LOG = Logger.getInstance(IndexSort.class);

  protected Path path;
  protected SharedIndexCompression compression;
  protected Path tmpRoot;

  protected ObjectMapper objMapper = new ObjectMapper(); // create once, reuse;

  //private final DateTimeFormatter METADATA_DATE_FORMAT = DateTimeFormatter.ISO_DATE_TIME.withLocale(Locale.US);
  //private final ZonedDateTime dtFixed = ZonedDateTime.parse("2022-02-17T00:49:22+08:00[Asia/Irkutsk]", METADATA_DATE_FORMAT);

  protected IndexSort(Path path) throws IOException {

    if (!Files.isRegularFile(path)) {
      throw new IOException(
        "not a regular file: " + path
      );
    }

    if (!Files.isReadable(path)) {
      throw new IOException(
        "not readable: " + path
      );
    }

    this.path = path;

    this.compression = SharedIndexCompression.findByType(getExtension(this.path));
    if (this.compression == null) {
      throw new IOException(
        "unknown compression type: " + this.path.getFileName().toString()
      );
    }

  }

  public SharedIndexCompression getCompression() {
    return this.compression;
  }
  public Path getPath() {
    return this.path;
  }

  public Path getTmpRoot() {
    if (this.tmpRoot == null) {
      try {
        this.tmpRoot = Files.createTempDirectory("intellij-index-sort");
        //noinspection SSBasedInspection
        this.tmpRoot.toFile().deleteOnExit();
      } catch (Exception e) {
        LOG.error(e);
      }
    }
    return this.tmpRoot;
  }

  public void extractContents() throws IOException {
    this.extractContents(this.getTmpRoot());
  }

  public void extractContents(Path outDir) throws IOException {

    InputStream unpackingStream = this.compression.createUnpackingStream(Files.newInputStream(this.path));
    byte[] buffer = new byte[2048];

    try (ZipInputStream zipStream = new ZipInputStream(unpackingStream)) {
      ZipEntry entry;
      while((entry = zipStream.getNextEntry()) != null) {

        Path filePath = outDir.resolve(entry.getName());
        File targetFile = filePath.toFile();

        if (entry.isDirectory()) {
          boolean res = targetFile.mkdirs();
          continue;
        }

        if (!targetFile.getParentFile().exists()) {
          boolean res = targetFile.getParentFile().mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(targetFile);
             BufferedOutputStream outStream = new BufferedOutputStream(fos, buffer.length)) {

          int len;
          while ((len = zipStream.read(buffer, 0, buffer.length)) != -1) {
            outStream.write(buffer, 0, len);
          }

          outStream.flush();
        }

        //if (getExtension(entry.getName()).equals("storage")) {
        //  loadIndexStorageRaw(filePath);
        //  break;
        //}
      }
    }

    unpackingStream.close();
  }

  public void compressContents(Path outFile) throws IOException {
    Path metadataPath = this.getTmpRoot().resolve(SharedIndexMetadata.getMetadataPath());
    if (!Files.exists(metadataPath)) {
      this.extractContents();
    }

    Path ijxPath = createIjxFromDir();

    this.compression.compress(ijxPath, outFile, new IndexSort.DummyProgressIndicator());
  }

  /**
   * Fix non-hermetic fields in metadata.json
   *
   * @see SharedIndexMetadata.Companion#readSharedIndexMetadata
   * @throws IOException
   */
  public void fixMetadata() throws IOException {

    Path metadataPath = this.getTmpRoot().resolve(SharedIndexMetadata.getMetadataPath());
    if (!Files.exists(metadataPath)) {
      this.extractContents();
    }

    byte[] metadataRaw = Files.readAllBytes(metadataPath);
    ObjectNode jsonRoot = (ObjectNode) this.objMapper.readTree(metadataRaw);

    // Note: indexes.weak_hash and indexes.weak_base_hash fields are not used by index loader, it is OK to leave them as is

    // Set fixed date for the sources.generated field
    // Note: it should be valid METADATA_DATE_FORMAT
    // https://github.com/JetBrains/intellij-shared-indexes/blob/9eb585b4bc30b319d6da775a32663805d51ff468/core/src/com/intellij/indexing/shared/metadata/SharedIndexMetadata.kt#L114
    jsonRoot.with("sources").put("generated", "2022-02-17T00:49:22+08:00[Asia/Irkutsk]");

    // Save updated metadata.json
    metadataRaw = this.objMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(jsonRoot);
    Files.write(metadataPath, metadataRaw);
  }


  public static String getExtension(Path path) {
    if (path == null) return "";
    String fileName = path.toString();
    return getExtension(fileName);
  }

  public static String getExtension(String path) {
    String extension = "";
    if (path == null || path == "") return extension;

    int index = path.lastIndexOf('.');
    if (index > 0) {
      extension = path.substring(index + 1);
    }
    return extension;
  }

  public static IndexSort load(String strPath) throws IOException {
    strPath = strPath.replaceFirst("^~", System.getProperty("user.home"));
    strPath = strPath.replaceFirst("^\\$HOME", System.getProperty("user.home"));

    try {
      Path path = FileSystems.getDefault().getPath(strPath);
      return load(path);
    } catch (InvalidPathException e) {
      LOG.warn("Invalid path: " + strPath);
      throw e;
    }
  }

  public static IndexSort load(Path path) throws IOException {
    try {
      return new IndexSort(
        path.toRealPath()
      );
    } catch (NoSuchFileException e) {
      LOG.warn("No such file or directory: " + path);
      throw e;
    } catch (AccessDeniedException e) {
      LOG.warn("Could not read file: " + path);
      throw e;
    } catch (IOException e) {
      LOG.error(e);
      throw e;
    }
  }


  public Path createIjxFromDir() throws IOException {
    Path metadataPath = this.getTmpRoot().resolve(SharedIndexMetadata.getMetadataPath());
    if (!Files.exists(metadataPath)) {
      this.extractContents();
    }

    return createIjxFromDir(this.getTmpRoot());
  }

  public static Path createIjxFromDir(Path dir) throws IOException {
    Path ijxPath = FileUtil.createTempFile(dir.getFileName().toString(), ".ijx", true).toPath();
    createIjxFromDir(dir, ijxPath);
    return ijxPath;
  }

  /**
   * Produce ijx file from directory contents.
   *
   * @see com.intellij.indexing.shared.util.UtilKt#uncompressedZip
   * @param dir
   * @param ijxFile
   */
  public static void createIjxFromDir(Path dir, Path ijxFile) throws IOException {
    try (JBZipFile file = Zip64Util.openZip64File(ijxFile, false)) {
      List<Path> fileList;
      try (Stream<Path> walk = Files.walk(dir)) {
        fileList = walk.filter(Files::isRegularFile)
          .collect(Collectors.toList());
      }

      fileList.forEach(p -> {
        String relativePath = dir.relativize(p).toString().replace('\\', '/');

        try {
          JBZipEntry entry = file.getOrCreateEntry(relativePath);
          entry.setMethod(ZipEntry.STORED);
          entry.setDataFromPath(p);

        } catch (IOException e) {
          LOG.warn(e);
        }

        //stdout(relativePath);
      });
    }
  }


  public void cleanTmp() {
    try {
      if (this.tmpRoot != null && Files.exists(this.tmpRoot)) {
        Files.deleteIfExists(this.tmpRoot.getParent().resolve(this.tmpRoot.getFileName().toString() + ".ijx"));
        FileUtils.deleteDirectory(this.tmpRoot.toFile());
      }
    } catch (IOException e) {
      LOG.warn(e);
    }
  }


  public void checkMetadata() throws Exception {
    Path ijxPath = this.createIjxFromDir();
    checkMetadata(ijxPath);
  }

  /**
   * TODO check metadata IDE versions and capabilities
   *
   * @see com.intellij.indexing.shared.platform.impl.SharedIndexStorageUtil#openFileBasedIndexChunks
   * @see SharedIndexMetadata.Companion#tryReadSharedIndexMetadata(com.fasterxml.jackson.databind.node.ObjectNode)
   * @see com.intellij.indexing.shared.platform.impl.SharedIndexStorageUtil#openFileBasedIndexChunks
   * @param ijxPath
   */
  public static void checkMetadata(Path ijxPath) throws Exception {
    try (UncompressedZipFileSystem fs = UncompressedZipFileSystem.create(ijxPath)) {

      SharedIndexMetadata metadata = SharedIndexMetadata.readSharedIndexMetadata(fs.getRootDirectory());

      // RAW:
      //Path metadataPath = fs.getRootDirectory().resolve(SharedIndexMetadata.getMetadataPath());

      //byte[] metadataRaw = Files.readAllBytes(metadataPath);
      //ObjectMapper objMapper = new ObjectMapper();
      //ObjectNode jsonRoot = (ObjectNode) objMapper.readTree(metadataRaw);

      //stdout( jsonRoot.get("metadata_version").toString() );
      //stdout( jsonRoot.with("sources").get("name").toString() );
      //stdout( jsonRoot.with("sources").get("project_id").toString() );

      //metadata = SharedIndexMetadata.Companion.tryReadSharedIndexMetadata(jsonRoot);


      if (metadata == null) {
        throw new Exception("Could not read index metadata");
      }

      //
      metadata.getIndexInfrastructureVersion();

      SharedIndexInfrastructureVersion chunkVersion = metadata.getIndexInfrastructureVersion();


      stdout( metadata.getIndexName() );
      stdout( metadata.getSharedIndexInfo().getProjectId() );
      // indexes.base_versions.product_version
      stdout( chunkVersion.getBaseIndexes().get("product_version") );

      // Proper way (not available without IDE instance):
      //SharedIndexInfrastructureVersion ideVersion = SharedIndexInfrastructureVersion.getIdeVersion();
      //if (!ideVersion.isSuitableMetadata(metadata)) {
      //  throw new Exception("Incompatible IDE version");
      //}

      //stdout(chunkVersion.toString());
    }
  }


  /**
   * @see com.intellij.indexing.shared.platform.impl.SharedIndexStorageUtil#openFileBasedIndexChunks
   * @throws IOException
   */
  public void processChunkRoot() throws IOException {
    Path chunkRoot = this.getTmpRoot();
    Path metadataPath = chunkRoot.resolve(SharedIndexMetadata.getMetadataPath());
    if (!Files.exists(metadataPath)) {
      this.extractContents();
    }

    try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(chunkRoot)) {
      for (Path indexDir : directoryStream) {
        String indexId = indexDir.getFileName().toString();

        stdout(indexId);

        ID<?, ?> id = ID.findByName(indexId);
        if (id == null) {
          continue;
        }

        FileBasedIndexExtension<?, ?> extension = findExtension(id);
        if (extension == null) {
          continue;
        }

        stdout(extension.getName().toString());

      }
    }




  }

  @Nullable
  protected static FileBasedIndexExtension<?, ?> findExtension(@Nullable ID<?, ?> id) {
    if (id == null) return null;
    return FileBasedIndexExtension.EXTENSION_POINT_NAME.findFirstSafe(ex -> ex.getName().equals(id));
  }



  public static void loadIndexStorageRaw(Path storagePath) throws IOException {
    /*
    See also:
    StubHashBasedIndexGenerator.openIndex()
    HashBasedIndexGenerator.openIndex()

    MapReduceIndex<>(FileBasedIndexExtension | SharedIndexExtension, MapIndexStorage)
      - MapIndexStorage
        - .storage file path
      - FileBasedIndexExtension<K, V> | SharedIndexExtension
    */

    // storagePath.getParent().resolve(storagePath.getFileName() + ".storage");
    MapIndexStorage<String, String> indexStorage = new MapIndexStorage<>(
      storagePath,
      EnumeratorStringDescriptor.INSTANCE,
      EnumeratorStringDescriptor.INSTANCE,
      1024,
      false,
      true,
      true,
      false,
      null
    );

    //FileBasedIndexExtension<K, V> extension = ???;
    //new MapReduceIndex<>(extension, indexStorage)

    System.out.println(
      indexStorage
    );

  }


  /**
   * ProgressIndicator is required for some actions.
   *
   */
  static class DummyProgressIndicator implements ProgressIndicatorEx {
    @Override
    public void addStateDelegate(@NotNull ProgressIndicatorEx delegate) { }

    @Override
    public void finish(@NotNull TaskInfo task) { }

    @Override
    public boolean isFinished(@NotNull TaskInfo task) {
      return false;
    }

    @Override
    public boolean wasStarted() {
      return false;
    }

    @Override
    public void processFinish() { }

    @Override
    public void initStateFrom(@NotNull ProgressIndicator indicator) { }

    @Override
    public void start() { }

    @Override
    public void stop() { }

    @Override
    public boolean isRunning() {
      return false;
    }

    @Override
    public void cancel() { }

    @Override
    public boolean isCanceled() {
      return false;
    }

    @Override
    public void setText(@Nls(capitalization = Nls.Capitalization.Sentence) String text) { }

    @Override
    public String getText() {
      return null;
    }

    @Override
    public void setText2(@Nls(capitalization = Nls.Capitalization.Sentence) String text) { }

    @Override
    public String getText2() {
      return null;
    }

    @Override
    public double getFraction() {
      return 0;
    }

    @Override
    public void setFraction(double fraction) { }

    @Override
    public void pushState() { }

    @Override
    public void popState() { }

    @Override
    public boolean isModal() {
      return false;
    }

    @Override
    public @NotNull ModalityState getModalityState() {
      return ModalityState.defaultModalityState();
    }

    @Override
    public void setModalityProgress(@Nullable ProgressIndicator modalityProgress) { }

    @Override
    public boolean isIndeterminate() {
      return false;
    }

    @Override
    public void setIndeterminate(boolean indeterminate) { }

    @Override
    public void checkCanceled() throws ProcessCanceledException { }

    @Override
    public boolean isPopupWasShown() {
      return false;
    }

    @Override
    public boolean isShowing() {
      return false;
    }
  }
}
