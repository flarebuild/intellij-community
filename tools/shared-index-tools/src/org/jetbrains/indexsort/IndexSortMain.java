// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.indexsort;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.diagnostic.Logger;
import java.nio.file.Path;

public class IndexSortMain {
  private static final Logger LOG = Logger.getInstance(IndexSortMain.class);

  public static void main(String[] args) throws Exception {

    if (args.length < 1) {
      LOG.warn("path argument is empty");
      System.exit(1);
    }

    IndexSort sorter = IndexSort.load(args[0]);

    // sorter.extractContents();
    // stdout(sorter.getPath().toString()); // xz path

    sorter.fixMetadata();
    stdout(sorter.getTmpRoot().toString()); // temp unpacked
    //sorter.checkMetadata();

    sorter.processChunkRoot();

    // Save updated xz
    Path outPath = FileUtil.createTempFile("intellij-index-sorted", sorter.getCompression().getExtensionSuffix()).toPath();
    sorter.compressContents(outPath);
    stdout(outPath.toString());

    sorter.cleanTmp();

    stdout("Done");

    System.exit(0);
  }


  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void stdout(String str) {
    System.out.println(str);
  }
}
