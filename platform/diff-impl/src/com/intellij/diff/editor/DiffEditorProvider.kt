// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.diff.editor

import com.intellij.diff.tools.combined.CombinedDiffRequestProcessorEditor
import com.intellij.diff.tools.combined.CombinedDiffVirtualFile
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.impl.DefaultPlatformFileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.NonNls

class DiffEditorProvider : DefaultPlatformFileEditorProvider, DumbAware {
  companion object {
    @NonNls
    const val DIFF_EDITOR_PROVIDER_ID = "DiffEditor"
  }

  override fun accept(project: Project, file: VirtualFile): Boolean {
    return file is CombinedDiffVirtualFile<*> || file is DiffVirtualFile
  }

  override fun createEditor(project: Project, file: VirtualFile): FileEditor {
    if (file is CombinedDiffVirtualFile<*>) {
      val processor = file.createProcessor(project)
      return CombinedDiffRequestProcessorEditor(file, processor)
    }

    val processor = (file as DiffVirtualFile).createProcessor(project)
    return DiffRequestProcessorEditor(file, processor)
  }

  override fun disposeEditor(editor: FileEditor) {
    Disposer.dispose(editor)
  }

  override fun getEditorTypeId(): String = DIFF_EDITOR_PROVIDER_ID
  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.NONE
}
