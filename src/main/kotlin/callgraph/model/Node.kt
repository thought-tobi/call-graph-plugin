package callgraph.model

import callgraph.Utils
import com.intellij.psi.PsiMethod
import java.awt.geom.Point2D

data class Node(val id: String, val method: PsiMethod) {
    val outEdges = mutableMapOf<String, Edge>()
    val inEdges = mutableMapOf<String, Edge>()
    val filePath = Utils.getMethodFilePath(method) ?: "(no file)"
    val packageName = Utils.getMethodPackageName(method)
    val signature = Utils.getMethodSignature(method)
    val point = Point2D.Float()
    val rawLayoutPoint = Point2D.Float()

    fun addInEdge(edge: Edge) {
        if (!this.inEdges.containsKey(edge.id)) {
            this.inEdges[edge.id] = edge
        }
    }

    fun addOutEdge(edge: Edge) {
        if (!this.outEdges.containsKey(edge.id)) {
            this.outEdges[edge.id] = edge
        }
    }

    fun getNeighbors(): List<Node> {
        val upstreamNodes = this.inEdges.values.map { it.sourceNode }
        val downstreamNodes = this.outEdges.values.map { it.targetNode }
        return upstreamNodes.union(downstreamNodes).toList()
    }
}
