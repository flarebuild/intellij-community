// "Remove forbidden opt-in annotation targets" "true"
// COMPILER_ARGUMENTS: -Xopt-in=kotlin.RequiresOptIn
// WITH_STDLIB
@RequiresOptIn
@Target(<caret>AnnotationTarget.CLASS, AnnotationTarget.EXPRESSION, AnnotationTarget.FUNCTION)
annotation class SomeOptInAnnotation
