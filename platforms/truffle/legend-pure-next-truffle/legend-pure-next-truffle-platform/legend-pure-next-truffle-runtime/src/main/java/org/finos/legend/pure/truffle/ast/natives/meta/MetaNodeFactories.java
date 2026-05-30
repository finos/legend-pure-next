// Copyright 2024 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.truffle.ast.natives.meta;

import org.finos.legend.pure.truffle.ast.AtomicValueNode;
import org.finos.legend.pure.truffle.ast.PropertyAssignNode;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawCollectionNode;
import org.finos.legend.pure.truffle.builder.NativeNodeRegistry;

/**
 * Registers specialized Truffle nodes for meta and element-path natives.
 *
 * <p>Only non-lazy signatures are registered here. Lazy signatures
 * ({@code new_GenericTypeAndMultiplicityHolder_1__KeyExpression_MANY__T_1_}
 * and {@code copy_T_1__KeyExpression_MANY__T_1_}) stay on
 * {@code LazyNativeCallNode} because they need to push the instance onto
 * the construction stack before evaluating key expressions.</p>
 */
public final class MetaNodeFactories
{
    private MetaNodeFactories()
    {
    }

    public static void registerAll(NativeNodeRegistry registry)
    {
        // --- Lazy meta natives (construction stack + key expressions) ---
        // These take the FunctionExpression directly (not lowered args)
        // because they control evaluation order.
        registry.register("new_GenericTypeAndMultiplicityHolder_1__KeyExpression_MANY__T_1_",
                (args, gt, mul, fe) -> new NewWithKeysNode(
                        "new_GenericTypeAndMultiplicityHolder_1__KeyExpression_MANY__T_1_",
                        args[0], decomposeAssignments(args[1]), args[1]));
        registry.register("copy_T_1__KeyExpression_MANY__T_1_",
                (args, gt, mul, fe) -> new CopyWithKeysNode(
                        "copy_T_1__KeyExpression_MANY__T_1_",
                        args[0], decomposeAssignments(args[1])));

        // --- Meta natives ---

        // instanceOf(Any[1], Type[1]) : Boolean[1]
        registry.register("instanceOf_Any_1__Type_1__Boolean_1_",
                (args, gt, mul, fe) -> new InstanceOfNode(args[0], args[1]));

        // type(Any[*]) : Type[1]
        registry.register("type_Any_MANY__Type_1_",
                (args, gt, mul, fe) -> new TypeNode(args[0], gt, mul));

        // genericType(Any[*]) : GenericTypeValue[1]
        registry.register("genericType_Any_MANY__GenericTypeValue_1_",
                (args, gt, mul, fe) -> new GenericTypeNode(args[0], gt, mul));

        // genericTypeHolder(T[m]) : GenericTypeAndMultiplicityHolder[1]
        registry.register("genericTypeHolder_T_m__GenericTypeAndMultiplicityHolder_1_",
                (args, gt, mul, fe) -> new GenericTypeHolderNode(args[0], gt, mul));

        // new(GenericTypeAndMultiplicityHolder[1]) : T[1] — no key expressions
        registry.register("new_GenericTypeAndMultiplicityHolder_1__T_1_",
                (args, gt, mul, fe) -> new NewSimpleNode(args[0]));

        // new(GenericType[1]) : Any[1]
        registry.register("new_GenericType_1__Any_1_",
                (args, gt, mul, fe) -> new NewGenericTypeNode(args[0]));

        // keyExpression — 2-arg and 3-arg variants
        registry.register("keyExpression_String_1__Any_MANY__KeyExpression_1_",
                (args, gt, mul, fe) -> new KeyExpressionNode(args, gt, mul));
        registry.register("keyExpression_String_1__Any_MANY__Boolean_1__KeyExpression_1_",
                (args, gt, mul, fe) -> new KeyExpressionNode(args, gt, mul));

        // copy(T[1]) : T[1] — simple copy with no overrides
        registry.register("copy_T_1__T_1_",
                (args, gt, mul, fe) -> new CopySimpleNode(args[0]));

        // cast variants — all three signatures share one CastNode
        // implementation; they only differ on which dimension(s) of the
        // holder are user-specified vs `?`. The runtime check is the same;
        // CastNode skips T or m validation when its respective slot is
        // UndefinedGenericType / UndefinedMultiplicity.
        registry.register("cast_Any_m__GenericTypeAndMultiplicityHolder_1__T_m_",
                (args, gt, mul, fe) -> new CastNode(args[0], args[1]));
        registry.register("cast_Any_MANY__GenericTypeAndMultiplicityHolder_1__T_m_",
                (args, gt, mul, fe) -> new CastNode(args[0], args[1]));
        registry.register("cast_T_MANY__GenericTypeAndMultiplicityHolder_1__T_m_",
                (args, gt, mul, fe) -> new CastNode(args[0], args[1]));

        // evaluateAndDeactivate — passthrough
        registry.register("evaluateAndDeactivate_Any_m__Any_m_",
                (args, gt, mul, fe) -> new EvaluateAndDeactivateNode(args[0]));
        registry.register("evaluateAndDeactivate",
                (args, gt, mul, fe) -> new EvaluateAndDeactivateNode(args[0]));

        // openVariableValues(LambdaFunction[1]) : Map<String, List<Any>>[1]
        // — reads RawClosure capture frame, builds one List entry per name.
        registry.register("openVariableValues_LambdaFunction_1__Map_1_",
                (args, gt, mul, fe) -> new OpenVariableValuesNode(args[0]));

        // --- Element path natives ---

        // pathToElement(String[1], String[1]) : PackageableElement[1]
        registry.register("pathToElement_String_1__String_1__PackageableElement_1_",
                (args, gt, mul, fe) -> new PathToElementNode(args, gt, mul));

        // elementToPath(PackageableElement[1], String[1]) : String[1]
        registry.register("elementToPath_PackageableElement_1__String_1__String_1_",
                (args, gt, mul, fe) -> new ElementToPathNode(args, gt, mul));

        // elementToPath(PackageableElement[1]) : String[1]
        registry.register("elementToPath_PackageableElement_1__String_1_",
                (args, gt, mul, fe) -> new ElementToPathNode(args, gt, mul));
        registry.register("elementToPath",
                (args, gt, mul, fe) -> new ElementToPathNode(args, gt, mul));

        // elementToPath(Type[1], String[1]) : String[1]
        registry.register("elementToPath_Type_1__String_1__String_1_",
                (args, gt, mul, fe) -> new ElementToPathNode(args, gt, mul));

        // lenientPathToElement — returns null instead of throwing
        registry.register("lenientPathToElement_String_1__String_1__PackageableElement_$0_1$_",
                (args, gt, mul, fe) -> new PathToElementNode(args, gt, mul, true));

        // elementPath — element ancestry chain
        registry.register("elementPath_PackageableElement_1__PackageableElement_$1_MANY$_",
                (args, gt, mul, fe) -> new ElementPathNode(args[0]));

        // newClass — creates a new Class at runtime
        registry.register("newClass_TypeParameter_MANY__MultiplicityParameter_MANY__Class_1_",
                (args, gt, mul, fe) -> new NewClassNode(args[0], args[1]));

        // newEnumeration is implemented in Pure (see newEnumeration.pure) —
        // no Truffle native node needed.

        // parse — invokes the Pure parser (TODO: implement without DynamicInstance)
        registry.register("parse_String_1__String_1__PureFile_1_",
                (args, gt, mul, fe) -> new ParseNode(args[0], args[1]));

        // resolveAndReturnGraph — deep-copies the input element map and resolves
        // every TempCompilerPointer it contains to a live target. Invoked at
        // the end of meta::pure::compiler::compile so every caller (Truffle
        // runtime, PDB writer, IDE, Pure recompile callers in canonical.pure)
        // receives a pointer-free graph at the compile boundary.
        registry.register("resolveAndReturnGraph_Map_1__PackageableElement_MANY_",
                (args, gt, mul, fe) -> new ResolveAndReturnGraphNode(args[0]));

        // findFunctionsByNameAndArity — compiler native: search resolver for functions
        registry.register("findFunctionsByNameAndArity_String_1__Integer_1__PackageableFunction_MANY_",
                (args, gt, mul, fe) -> new FindFunctionsByNameAndArityNode(args[0], args[1]));

        // findAllTypes — compiler native: enumerate every PE Type across all
        // loaded modules. Used by buildLinearizationCache to seed the cache
        // without relying on the package tree (which is split per module).
        registry.register("findAllTypes__Type_MANY_",
                (args, gt, mul, fe) -> new FindAllTypesNode());
    }

    // =========================================================================
    // Key expression decomposition — extract property names and value
    // expressions at build time from RawCollectionNode<KeyExpressionNode>.
    // =========================================================================

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static KeyExpressionNode[] extractKeyNodes(PureNode keysNode)
    {
        if (keysNode instanceof RawCollectionNode col)
        {
            // Access children via the collection node
            KeyExpressionNode[] result = new KeyExpressionNode[col.childCount()];
            // We need to get the children — execute in a null frame won't work.
            // Instead, read the children field reflectively since RawCollectionNode
            // doesn't expose them. Better: use the node's child array.
            com.oracle.truffle.api.nodes.Node[] children = new com.oracle.truffle.api.nodes.Node[col.childCount()];
            int idx = 0;
            for (com.oracle.truffle.api.nodes.Node child : col.getChildren())
            {
                if (child instanceof KeyExpressionNode ken)
                {
                    result[idx++] = ken;
                }
            }
            if (idx == result.length)
            {
                return result;
            }
        }
        else if (keysNode instanceof KeyExpressionNode ken)
        {
            // Single key expression (not wrapped in collection)
            return new KeyExpressionNode[]{ken};
        }
        return new KeyExpressionNode[0];
    }

    static PropertyAssignNode[] decomposeAssignments(PureNode keysNode)
    {
        KeyExpressionNode[] nodes = extractKeyNodes(keysNode);
        PropertyAssignNode[] result = new PropertyAssignNode[nodes.length];
        for (int i = 0; i < nodes.length; i++)
        {
            String name = extractConstantString(nodes[i].nameNode());
            PureNode valueExpr = nodes[i].valueNode();
            boolean isAdd = extractBoolean(nodes[i].addNode());
            result[i] = new PropertyAssignNode(name, valueExpr, isAdd);
        }
        return result;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String extractConstantString(PureNode node)
    {
        if (node instanceof AtomicValueNode av && av.value() instanceof String s) return s;
        throw new RuntimeException("Key expression name is not a constant string: " + node.getClass().getName());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean extractBoolean(PureNode node)
    {
        if (node == null) return false;
        if (node instanceof AtomicValueNode av && av.value() instanceof Boolean b) return b;
        return false;
    }
}
