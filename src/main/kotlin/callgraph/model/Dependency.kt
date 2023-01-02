package callgraph.model

import com.intellij.psi.PsiMethod

@Suppress("StatefulEp")
data class Dependency(val caller: PsiMethod, val callee: PsiMethod)
