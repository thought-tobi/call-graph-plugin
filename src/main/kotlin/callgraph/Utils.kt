package callgraph

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import guru.nidi.graphviz.attribute.RankDir
import guru.nidi.graphviz.engine.Format
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.model.Factory.mutGraph
import guru.nidi.graphviz.model.Factory.mutNode
import java.awt.geom.Point2D

object Utils {
    private const val normalizedGridSize = 0.1f

    fun getActiveProject(): Project? {
        return ProjectManager.getInstance()
                .openProjects
                .firstOrNull { WindowManager.getInstance().suggestParentWindow(it)?.isActive ?: false }
    }

    fun getActiveModules(project: Project): List<Module> {
        return ModuleManager.getInstance(project).modules.toList()
    }

    fun getDependencies(canvasConfig: CanvasConfig): Set<Dependency> {
        // create a temporary config to get all files from the entire project
        val wholeProjectScopeCanvasConfig =
                CanvasConfig(canvasConfig.project, CanvasConfig.BuildType.WHOLE_PROJECT_WITH_TEST)
        val allFiles = getSourceCodeFiles(wholeProjectScopeCanvasConfig)
        val methods = getMethodsInScope(wholeProjectScopeCanvasConfig, allFiles)

        // parse method dependencies
        canvasConfig.callGraphToolWindow?.resetProgressBar(methods.size)
        return methods
                .flatMap { method ->
                    canvasConfig.callGraphToolWindow?.incrementProgressBar()
                    PsiTreeUtil.findChildrenOfType(method, PsiIdentifier::class.java)
                            .map { it.context }
                            .filter { it != null }
                            .flatMap { it!!.references.toList() }
                            .map { it.resolve() }
                            .filter { it is PsiMethod }
                            .map { Dependency(method, it as PsiMethod) }
                }
                .toSet()
    }

    fun getDependencyView(
            canvasConfig: CanvasConfig,
            methods: Set<PsiMethod>,
            dependencies: Set<Dependency>): Set<Dependency> {
        return when (canvasConfig.buildType) {
            CanvasConfig.BuildType.WHOLE_PROJECT_WITH_TEST_LIMITED,
            CanvasConfig.BuildType.WHOLE_PROJECT_WITHOUT_TEST_LIMITED,
            CanvasConfig.BuildType.MODULE_LIMITED,
            CanvasConfig.BuildType.DIRECTORY_LIMITED -> dependencies
                    .filter { methods.contains(it.caller) && methods.contains(it.callee) }
                    .toSet()
            CanvasConfig.BuildType.WHOLE_PROJECT_WITH_TEST,
            CanvasConfig.BuildType.WHOLE_PROJECT_WITHOUT_TEST,
            CanvasConfig.BuildType.MODULE,
            CanvasConfig.BuildType.DIRECTORY -> dependencies
                    .filter { methods.contains(it.caller) || methods.contains(it.callee) }
                    .toSet()
            CanvasConfig.BuildType.UPSTREAM ->
                getNestedDependencyView(dependencies, methods, mutableSetOf(), true)
            CanvasConfig.BuildType.DOWNSTREAM ->
                getNestedDependencyView(dependencies, methods, mutableSetOf(), false)
            CanvasConfig.BuildType.UPSTREAM_DOWNSTREAM -> {
                val upstream = getNestedDependencyView(dependencies, methods, mutableSetOf(), true)
                val downstream = getNestedDependencyView(dependencies, methods, mutableSetOf(), false)
                upstream.union(downstream)
            }
        }
    }

    fun getMethodsInScope(canvasConfig: CanvasConfig, files: Set<PsiFile>): Set<PsiMethod> {
        return when (canvasConfig.buildType) {
            CanvasConfig.BuildType.WHOLE_PROJECT_WITH_TEST_LIMITED,
            CanvasConfig.BuildType.WHOLE_PROJECT_WITHOUT_TEST_LIMITED,
            CanvasConfig.BuildType.MODULE_LIMITED,
            CanvasConfig.BuildType.DIRECTORY_LIMITED,
            CanvasConfig.BuildType.WHOLE_PROJECT_WITH_TEST,
            CanvasConfig.BuildType.WHOLE_PROJECT_WITHOUT_TEST,
            CanvasConfig.BuildType.MODULE,
            CanvasConfig.BuildType.DIRECTORY -> files
                    .flatMap { (it as PsiJavaFile).classes.toList() } // get all classes
                    .flatMap { it.methods.toList() } // get all methods
                    .toSet()
            CanvasConfig.BuildType.UPSTREAM,
            CanvasConfig.BuildType.DOWNSTREAM,
            CanvasConfig.BuildType.UPSTREAM_DOWNSTREAM -> canvasConfig.focusedMethods
        }
    }

    fun layout(graph: Graph) {
        // get connected components from the graph, and render each part separately
        val subGraphBlueprints = graph.connectedComponents
                .map { this.getLayoutFromGraphViz(it) }
                .map { this.normalizeBlueprintGridSize(it) }
                .toList()

        // merge all connected components to a single graph, then adjust node coordinates so they fit in the view
        val mergedBlueprint = this.mergeNormalizedLayouts(subGraphBlueprints)
        applyRawLayoutBlueprintToGraph(mergedBlueprint, graph)
        applyLayoutBlueprintToGraph(mergedBlueprint, graph)
    }

    fun runBackgroundTask(project: Project, runnable: Runnable) {
        ProgressManager.getInstance()
                .run(object: Task.Backgroundable(project, "Call Graph") {
                    override fun run(progressIndicator: ProgressIndicator) {
                        ApplicationManager
                                .getApplication()
                                .runReadAction(runnable)
                    }
                })
    }

    fun getMethodPackageName(psiMethod: PsiMethod): String {
        // get class name
        val psiClass = psiMethod.containingClass
        val className = psiClass?.qualifiedName ?: ""
        // get package name
        val psiJavaFile = psiMethod.containingFile as PsiJavaFile
        val packageName = psiJavaFile.packageStatement?.packageName ?: ""
        return if (packageName.isBlank() || className.startsWith(packageName)) className else "$packageName.$className"
    }

    fun getMethodFilePath(method: PsiMethod): String {
        val psiFile = PsiTreeUtil.getParentOfType(method, PsiFile::class.java)
        if (psiFile != null) {
            val currentFile = psiFile.virtualFile
            val project = getActiveProject()
            if (project != null) {
                val rootFile = ProjectFileIndex.SERVICE.getInstance(project).getContentRootForFile(currentFile)
                if (rootFile != null) {
                    val relativePath = VfsUtilCore.getRelativePath(currentFile, rootFile)
                    if (relativePath != null) {
                        return relativePath
                    }
                }
            }
        }
        return "(empty)"
    }

    fun getMethodSignature(method: PsiMethod): String {
        val parameterNames = method.parameterList.parameters.map { it.name }.joinToString()
        val parameters = if (parameterNames.isEmpty()) "" else "($parameterNames)"
        return "${method.name}$parameters"
    }

    fun fitLayoutToViewport(blueprint: Map<String, Point2D.Float>): Map<String, Point2D.Float> {
        val maxPoint = blueprint.values.reduce { a, b -> Point2D.Float(maxOf(a.x, b.x), maxOf(a.y, b.y)) }
        val minPoint = blueprint.values.reduce { a, b -> Point2D.Float(minOf(a.x, b.x), minOf(a.y, b.y)) }
        val xSize = maxPoint.x - minPoint.x
        val ySize = maxPoint.y - minPoint.y
        val bestFitBaseline = 0.1f // make the best fit window between 0.1 - 0.9 of the viewport
        val bestFitSize = 1 - 2 * bestFitBaseline
        return blueprint.mapValues { (_, point) ->
            Point2D.Float(
                    (point.x - minPoint.x) / xSize * bestFitSize + bestFitBaseline,
                    (point.y - minPoint.y) / ySize * bestFitSize + bestFitBaseline
            )
        }
    }

    fun runCallGraphFromAction(anActionEvent: AnActionEvent, buildType: CanvasConfig.BuildType) {
        val project = anActionEvent.project
        val psiElement = anActionEvent.getData(CommonDataKeys.PSI_ELEMENT) // get the element under editor caret
        if (project != null && psiElement is PsiMethod) {
            ToolWindowManager.getInstance(project)
                    .getToolWindow("Call Graph")
                    .activate {
                        ServiceManager.getService(project, CallGraphToolWindowProjectService::class.java)
                                .callGraphToolWindow!!
                                .clearFocusedMethods()
                                .toggleFocusedMethod(psiElement)
                                .run(buildType)
                    }
        }
    }

    fun setActionEnabledAndVisibleByContext(anActionEvent: AnActionEvent) {
        val project = anActionEvent.project
        val psiElement = anActionEvent.getData(CommonDataKeys.PSI_ELEMENT)
        anActionEvent.presentation.isEnabledAndVisible = project != null && psiElement is PsiMethod
    }

    fun isPublic(method: PsiMethod) = method.modifierList.hasModifierProperty(PsiModifier.PUBLIC)

    fun isProtected(method: PsiMethod) = method.modifierList.hasModifierProperty(PsiModifier.PROTECTED)

    fun isPackageLocal(method: PsiMethod) = method.modifierList.hasModifierProperty(PsiModifier.PACKAGE_LOCAL)

    fun isPrivate(method: PsiMethod) = method.modifierList.hasModifierProperty(PsiModifier.PRIVATE)

    fun getSourceCodeFiles(canvasConfig: CanvasConfig): Set<PsiFile> {
        return getSourceCodeRoots(canvasConfig)
                .flatMap { contentSourceRoot ->
                    val childrenVirtualFiles = mutableListOf<VirtualFile>()
                    VfsUtilCore.iterateChildrenRecursively(contentSourceRoot, null, {
                        if (it.isValid && !it.isDirectory) {
                            val extension = it.extension
                            if (extension.equals("java")) {
                                childrenVirtualFiles.add(it)
                            }
                        }
                        true
                    })
                    childrenVirtualFiles
                }
                .mapNotNull { PsiManager.getInstance(canvasConfig.project).findFile(it) }
                .toSet()
    }

    fun applyLayoutBlueprintToGraph(blueprint: Map<String, Point2D.Float>, graph: Graph) {
        blueprint.forEach { (nodeId, point) -> graph.getNode(nodeId).point = point }
    }

    private fun getNestedDependencyView(
            dependencies: Set<Dependency>,
            methods: Set<PsiMethod>,
            seenMethods: MutableSet<PsiMethod>,
            isUpstream: Boolean): Set<Dependency> {
        if (methods.isEmpty()) {
            return emptySet()
        }
        val directPairs = dependencies.filter { methods.contains(if (isUpstream) it.callee else it.caller) }.toSet()
        val nextBatchMethods = directPairs
                .map { if (isUpstream) it.caller else it.callee }
                .filter { !seenMethods.contains(it) }
                .toSet()
        seenMethods.addAll(nextBatchMethods)
        val nestedPairs = getNestedDependencyView(dependencies, nextBatchMethods, seenMethods, isUpstream)
        return directPairs + nestedPairs
    }

    private fun getLayoutFromGraphViz(graph: Graph): Map<String, Point2D.Float> {
        // if graph only has one node, just set its coordinate to (0.5, 0.5), no need to call GraphViz
        if (graph.getNodes().size == 1) {
            return graph.getNodes()
                    .map { it.id to Point2D.Float(0.5f, 0.5f) }
                    .toMap()
        }
        // construct the GraphViz graph
        val gvGraph = mutGraph("test")
                .setDirected(true)
                .graphAttrs()
                .add(RankDir.LEFT_TO_RIGHT)
        graph.getNodes()
                .sortedBy { it.method.name }
                .forEach { node ->
                    val gvNode = mutNode(node.id)
                    node.outEdges.values
                            .map { it.targetNode }
                            .sortedBy { it.method.name }
                            .forEach { gvNode.addLink(it.id) }
                    gvGraph.add(gvNode)
                }

        // parse the GraphViz layout as a mapping from "node name" to "x-y coordinate (percent of full graph size)"
        // GraphViz doc: https://graphviz.gitlab.io/_pages/doc/info/output.html#d:plain
        val layoutRawText = Graphviz.fromGraph(gvGraph).render(Format.PLAIN).toString()
        return layoutRawText.split("\n")
                .filter { it.startsWith("node") }
                .map { it.split(" ") }
                .map { it[1] to Point2D.Float(it[2].toFloat(), it[3].toFloat()) } // (x, y)
                .toMap()
    }

    private fun normalizeBlueprintGridSize(blueprint: Map<String, Point2D.Float>): Map<String, Point2D.Float> {
        if (blueprint.size < 2) {
            return blueprint
        }
        val gridSize = getGridSize(blueprint)
        val desiredGridSize = Point2D.Float(normalizedGridSize, normalizedGridSize)
        val xFactor = if (gridSize.x == 0f) 1.0f else desiredGridSize.x / gridSize.x
        val yFactor = if (gridSize.y == 0f) 1.0f else desiredGridSize.y / gridSize.y
        return blueprint.mapValues { (_, point) -> Point2D.Float(point.x * xFactor, point.y * yFactor) }
    }

    private fun getGridSize(blueprint: Map<String, Point2D.Float>): Point2D.Float {
        val precisionFactor = 1000
        val xUniqueValues = blueprint.values.map { Math.round(precisionFactor * it.x) }.toSet()
        val yUniqueValues = blueprint.values.map { Math.round(precisionFactor * it.y) }.toSet()
        return Point2D.Float(
                getAverageElementDifference(xUniqueValues) / precisionFactor,
                getAverageElementDifference(yUniqueValues) / precisionFactor
        )
    }

    private fun getAverageElementDifference(elements: Set<Int>): Float {
        return if (elements.size < 2) 0f else (elements.max()!! - elements.min()!!) / (elements.size - 1).toFloat()
    }

    private fun mergeNormalizedLayouts(blueprints: List<Map<String, Point2D.Float>>): Map<String, Point2D.Float> {
        if (blueprints.isEmpty()) {
            return emptyMap()
        }
        val blueprintHeights = blueprints
                .associateBy(
                        { it },
                        { blueprint ->
                            // set padding to the average y grid size of the previous sub-graph (but minimum 0.1)
                            val yPoints = blueprint.values.map { it.y }
                            yPoints.max()!! - yPoints.min()!! + normalizedGridSize
                        }
                )
                .toMap()
        val sortedBlueprints = blueprintHeights
                .toList()
                .sortedBy { (_, height) -> -height }
                .map { it.first }
        val sortedHeights = blueprintHeights.values.sortedBy { -it }
        val xBaseline = 0.5f
        val yBaseline = 0.5f
        // put the left-most point of the first sub-graph in the view center, by using its y value as central line
        val yCentralLine = sortedBlueprints.first().values.minBy { it.x }?.y ?: 0.0f
        return sortedBlueprints
                .mapIndexed { index, blueprint ->
                    // calculate the y-offset of this sub-graph (by summing up all the height of previous sub-graphs)
                    val yOffset = sortedHeights.subList(0, index).sum()
                    // left align the graph by the left-most nodesMap, then centering the baseline
                    val minX = blueprint.values.map { it.x }.min() ?: 0f
                    //noinspection UnnecessaryLocalVariable
                    val shiftedBlueprint = blueprint.mapValues { (_, point) ->
                        Point2D.Float(
                                point.x - minX + xBaseline,
                                point.y + yOffset - yCentralLine + yBaseline
                        )
                    }
                    shiftedBlueprint
                }
                .reduce { blueprintA, blueprintB -> blueprintA + blueprintB }
    }

    private fun applyRawLayoutBlueprintToGraph(blueprint: Map<String, Point2D.Float>, graph: Graph) {
        blueprint.forEach { (nodeId, point) -> graph.getNode(nodeId).rawLayoutPoint = point }
    }

    private fun getSelectedModules(project: Project, selectedModuleName: String): Set<Module> {
        return getActiveModules(project).filter { it.name == selectedModuleName }.toSet()
    }

    private fun getSourceCodeRoots(canvasConfig: CanvasConfig): Set<VirtualFile> {
        return when (canvasConfig.buildType) {
            CanvasConfig.BuildType.WHOLE_PROJECT_WITH_TEST_LIMITED,
            CanvasConfig.BuildType.WHOLE_PROJECT_WITH_TEST ->
                ProjectRootManager.getInstance(canvasConfig.project).contentRoots.toSet()
            CanvasConfig.BuildType.WHOLE_PROJECT_WITHOUT_TEST_LIMITED,
            CanvasConfig.BuildType.WHOLE_PROJECT_WITHOUT_TEST ->
                getActiveModules(canvasConfig.project)
                        .flatMap { ModuleRootManager.getInstance(it).getSourceRoots(false).toSet() }
                        .toSet()
            CanvasConfig.BuildType.MODULE_LIMITED, CanvasConfig.BuildType.MODULE ->
                getSelectedModules(canvasConfig.project, canvasConfig.selectedModuleName)
                        .flatMap { ModuleRootManager.getInstance(it).sourceRoots.toSet() }
                        .toSet()
            CanvasConfig.BuildType.DIRECTORY_LIMITED,
            CanvasConfig.BuildType.DIRECTORY -> {
                val directoryPath = canvasConfig.selectedDirectoryPath
                listOfNotNull(LocalFileSystem.getInstance().findFileByPath(directoryPath)).toSet()
            }
            else -> emptySet()
        }
    }
}