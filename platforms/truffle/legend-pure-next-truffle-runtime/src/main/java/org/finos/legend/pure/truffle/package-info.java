/**
 * Stub for the GraalVM Truffle interpreter.
 *
 * <p>Planned contents:
 * <ul>
 *   <li>{@code PureLanguage} — {@code @TruffleLanguage.Registration}</li>
 *   <li>{@code PureContext} — loads PDBs, holds module/function registry</li>
 *   <li>{@code PureRootNode} — function entry point</li>
 *   <li>{@code nodes/*} — AtomicValueNode, VariableReadNode,
 *       FunctionInvocationNode, PropertyAccessNode, LambdaNode, IfNode, ...</li>
 *   <li>{@code builder/PureASTBuilder} — ValueSpecification trees → Truffle AST</li>
 *   <li>{@code runtime/PureObject} — replaces DynamicInstance</li>
 * </ul>
 *
 * <p>Built as a standalone module (not part of {@code bootstrap/}) so Truffle's
 * annotation processor and Graal's native-image packaging stay isolated
 * from the tree-walking interpreter the rest of the project depends on.
 */
package org.finos.legend.pure.truffle;
