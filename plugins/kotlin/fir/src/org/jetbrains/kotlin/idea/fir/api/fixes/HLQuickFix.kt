// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.fir.api.fixes

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.api.applicator.HLApplicator
import org.jetbrains.kotlin.idea.api.applicator.HLApplicatorInput
import org.jetbrains.kotlin.idea.quickfix.KotlinQuickFixAction
import org.jetbrains.kotlin.psi.KtFile

class HLQuickFix<PSI : PsiElement, in INPUT : HLApplicatorInput>(
    target: PSI,
    private val input: INPUT,
    val applicator: HLApplicator<PSI, INPUT>,
) : KotlinQuickFixAction<PSI>(target) {
    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val element = element ?: return
        if (applicator.isApplicableByPsi(element, project) && input.isValidFor(element)) {
            applicator.applyTo(element, input, project, editor)
        }
    }

    override fun getText(): String {
        val element = element ?: return familyName
        return if (input.isValidFor(element)) {
            applicator.getActionName(element, input)
        } else {
            applicator.getFamilyName()
        }
    }

    override fun getFamilyName(): String =
        applicator.getFamilyName()
}