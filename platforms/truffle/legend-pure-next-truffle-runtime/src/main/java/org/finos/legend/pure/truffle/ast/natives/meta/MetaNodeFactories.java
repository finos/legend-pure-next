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
                        "new_GenericTypeAndMultiplicityHolder_1__KeyExpression_MANY__T_1_", fe));
        registry.register("copy_T_1__KeyExpression_MANY__T_1_",
                (args, gt, mul, fe) -> new CopyWithKeysNode(
                        "copy_T_1__KeyExpression_MANY__T_1_", fe));

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
                (args, gt, mul, fe) -> new NewSimpleNode(args[0], gt, mul));

        // new(GenericType[1]) : Any[1]
        registry.register("new_GenericType_1__Any_1_",
                (args, gt, mul, fe) -> new NewGenericTypeNode(args[0], gt, mul));

        // keyExpression — 2-arg and 3-arg variants
        registry.register("keyExpression_String_1__Any_MANY__KeyExpression_1_",
                (args, gt, mul, fe) -> new KeyExpressionNode(args, gt, mul));
        registry.register("keyExpression_String_1__Any_MANY__Boolean_1__KeyExpression_1_",
                (args, gt, mul, fe) -> new KeyExpressionNode(args, gt, mul));

        // parentReference(Integer[1], String[1]) : Any[1]
        registry.register("parentReference_Integer_1__String_1__Any_1_",
                (args, gt, mul, fe) -> new ParentReferenceNode(args[0], args[1], gt, mul));

        // copy(T[1]) : T[1] — simple copy with no overrides
        registry.register("copy_T_1__T_1_",
                (args, gt, mul, fe) -> new CopySimpleNode(args[0], gt, mul));

        // cast(Any[m], GenericTypeAndMultiplicityHolder[1]) : T[m]
        registry.register("cast_Any_m__GenericTypeAndMultiplicityHolder_1__T_m_",
                (args, gt, mul, fe) -> new CastNode(args[0], args[1]));

        // evaluateAndDeactivate — passthrough
        registry.register("evaluateAndDeactivate_Any_m__Any_m_",
                (args, gt, mul, fe) -> new EvaluateAndDeactivateNode(args[0]));
        registry.register("evaluateAndDeactivate",
                (args, gt, mul, fe) -> new EvaluateAndDeactivateNode(args[0]));

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

        // parse — invokes the Pure parser (TODO: implement without DynamicInstance)
        registry.register("parse_String_1__String_1__PureFile_1_",
                (args, gt, mul, fe) -> new ParseNode(args[0], args[1]));
    }
}
