package eu.neverblink.linkml.schemaview

import scala.collection.mutable

/** Utility object for computing the transitive closure of a directed graph or hierarchical data
  * structure.
  *
  * A transitive closure traverses all reachable nodes starting from a given node (or set of nodes)
  * using a provided edge-generator function. This implementation is safe for cyclic graphs, as it
  * internally tracks visited nodes to prevent infinite loops.
  *
  * The traversal is performed using a Depth-First Search (DFS) strategy.
  */
object Closure {

  /** Computes the reflexive transitive closure starting from a single node.
    *
    * In a reflexive closure, the `start` node is always included in the result, regardless of
    * whether it has any incoming edges.
    *
    * @tparam T
    *   The type of the nodes in the graph.
    * @param start
    *   The initial node to start the traversal from.
    * @param function
    *   A generator function that takes a node and returns an `Iterable` of its immediate neighbors.
    * @return
    *   An `Iterable` containing the `start` node and all nodes reachable from it.
    */
  def reflexive[T](start: T, function: T => Iterable[T]): Iterable[T] =
    get(Seq(start), function, reflexive = true)

  /** Computes the reflexive transitive closure starting from multiple nodes.
    *
    * @tparam T
    *   The type of the nodes in the graph.
    * @param start
    *   A collection of initial nodes to start the traversal from.
    * @param function
    *   A generator function that takes a node and returns an `Iterable` of its immediate neighbors.
    * @return
    *   An `Iterable` containing all `start` nodes and all nodes reachable from them.
    */
  def reflexive[T](start: Iterable[T], function: T => Iterable[T]): Iterable[T] =
    get(start, function, reflexive = true)

  /** Computes the irreflexive transitive closure starting from a single node.
    *
    * In an irreflexive closure, the `start` node will not be included in the result.
    *
    * @tparam T
    *   The type of the nodes in the graph.
    * @param start
    *   The initial node to start the traversal from.
    * @param function
    *   A generator function that takes a node and returns an `Iterable` of its immediate neighbors.
    * @return
    *   An `Iterable` containing all nodes reachable from the `start` node (excluding `start`
    *   itself, unless cyclical).
    */
  def irreflexive[T](start: T, function: T => Iterable[T]): Iterable[T] =
    get(Seq(start), function, reflexive = false)

  /** Computes the irreflexive transitive closure starting from multiple nodes.
    *
    * @tparam T
    *   The type of the nodes in the graph.
    * @param start
    *   A collection of initial nodes to start the traversal from.
    * @param function
    *   A generator function that takes a node and returns an `Iterable` of its immediate neighbors.
    * @return
    *   An `Iterable` containing all nodes reachable from the `start` nodes.
    */
  def irreflexive[T](start: Iterable[T], function: T => Iterable[T]): Iterable[T] =
    get(start, function, reflexive = false)

  /** Computes the transitive closure starting from a single node, with a configurable reflexivity
    * flag.
    *
    * @tparam T
    *   The type of the nodes in the graph.
    * @param start
    *   The initial node to start the traversal from.
    * @param function
    *   A generator function that takes a node and returns an `Iterable` of its immediate neighbors.
    * @param reflexive
    *   If `true`, includes the `start` node in the result. If `false`, excludes it (unless
    *   reachable via cycle).
    * @return
    *   An `Iterable` containing the computed closure.
    */
  def get[T](start: T, function: T => Iterable[T], reflexive: Boolean): Iterable[T] =
    get(Seq(start), function, reflexive)

  /** The core engine for computing the transitive closure, supporting custom output collection
    * types.
    *
    * This method performs a Depth-First Search (DFS) using an `ArrayDeque` as a stack. It includes
    * a performance optimization: if the provided `useHashCode` is `true`, it uses an O(1) `HashSet`
    * to track visited nodes. Otherwise, it falls back to an O(N) `ArrayBuffer` for visited checks
    * to strictly preserve insertion order/duplicates based on the builder's semantics.
    *
    * In an irreflexive closure, the `start` node will not be included in the result.
    *
    * @tparam T
    *   The type of the nodes in the graph.
    * @tparam C
    *   The higher-kinded type of the resulting collection (e.g., `Vector`, `Set`, `List`).
    * @param start
    *   A collection of initial nodes to start the traversal from.
    * @param function
    *   A generator function that takes a node and returns an `Iterable` of its immediate neighbors.
    * @param reflexive
    *   If `true`, the `start` nodes are added to the result builder immediately.
    * @param resultBuilder
    *   A mutable builder used to construct the final output collection. Defaults to a `Vector`
    *   builder.
    * @param useHashCode
    *   A flag that allows using `hashCode` calls of `T` values for the performance optimization.
    * @return
    *   The computed closure, packaged in the collection type `C[T]`.
    */
  def get[T, C[_]](
      start: Iterable[T],
      function: T => Iterable[T],
      reflexive: Boolean,
      resultBuilder: mutable.Builder[T, C[T]] = Vector.newBuilder[T],
      useHashCode: Boolean = false,
  ): C[T] = {
    // Stack for Depth-First Search traversal
    val todo = new mutable.ArrayDeque[T]
    if (useHashCode) {
      // Optimization: If the target collection is Set-based, we can use a fast HashSet for visited checks.
      val visited = new mutable.HashSet[T]
      start.foreach { s =>
        if (visited.add(s)) {
          todo.addOne(s)
          if (reflexive) resultBuilder.addOne(s)
        }
      }
      while (todo.nonEmpty) {
        function(todo.removeLast()).foreach { neighbor =>
          if (visited.add(neighbor)) {
            todo.addOne(neighbor)
            resultBuilder.addOne(neighbor)
          }
        }
      }
    } else {
      // Fallback: For sequence-based collections where order matters, use an ArrayBuffer.
      // Note: `visited.contains` is O(N), which may impact performance on very large graphs.
      val visited = new mutable.ArrayBuffer[T]
      start.foreach { s =>
        if (!visited.contains(s)) {
          visited.addOne(s)
          todo.addOne(s)
          if (reflexive) resultBuilder.addOne(s)
        }
      }
      while (todo.nonEmpty) {
        function(todo.removeLast()).foreach { neighbor =>
          if (!visited.contains(neighbor)) {
            visited.addOne(neighbor)
            todo.addOne(neighbor)
            resultBuilder.addOne(neighbor)
          }
        }
      }
    }
    resultBuilder.result()
  }
}
