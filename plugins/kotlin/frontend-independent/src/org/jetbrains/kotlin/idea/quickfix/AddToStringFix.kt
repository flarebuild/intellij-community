// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.core.replaced
import org.jetbrains.kotlin.idea.inspections.KotlinUniversalQuickFix
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.createExpressionByPattern
import org.jetbrains.kotlin.psi.psiUtil.endOffset

class AddToStringFix(element: KtExpression, private val useSafeCallOperator: Boolean) :
    KotlinPsiOnlyQuickFixAction<KtExpression>(element), KotlinUniversalQuickFix, LowPriorityAction {
    override fun getFamilyName() = KotlinBundle.message("fix.add.tostring.call.family")

    override fun getText(): String {
        return when (useSafeCallOperator) {
            true -> KotlinBundle.message("fix.add.tostring.call.text.safe")
            false -> KotlinBundle.message("fix.add.tostring.call.text")
        }
    }

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val element = element ?: return
        val pattern = if (useSafeCallOperator) "$0?.toString()" else "$0.toString()"
        val expressionToInsert = KtPsiFactory(file).createExpressionByPattern(pattern, element)
        val newExpression = element.replaced(expressionToInsert)
        editor?.caretModel?.moveToOffset(newExpression.endOffset)
    }
}