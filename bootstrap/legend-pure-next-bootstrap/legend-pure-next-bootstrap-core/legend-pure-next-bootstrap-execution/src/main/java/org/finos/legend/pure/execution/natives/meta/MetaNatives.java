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

package org.finos.legend.pure.execution.natives.meta;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.type.Any;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution.ValueSpecificationEvaluator;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MetaNatives
{
    public static void register(Map<String, NativeImpl> natives,
                                Map<String, LazyNativeImpl> lazyNatives,
                                MetadataAccess resolver)
    {
        // instanceOf(Any[1], Type[1]) : Boolean[1]
        natives.put("instanceOf_Any_1__Type_1__Boolean_1_", (args, eval, genericType, multiplicity) ->
        {
            Object value = _E_ValueSpecification.unwrap(args.get(0));
            if (value == null)
            {
                return _E_ValueSpecification.wrap(false, genericType, multiplicity, resolver);
            }

            // Get the target type from the second arg via its genericType
            ValueSpecification targetVs = args.get(1);
            meta.pure.metamodel.type.Type targetType = null;
            Object targetValue = _E_ValueSpecification.unwrap(targetVs);
            if (targetValue instanceof meta.pure.metamodel.type.Type t)
            {
                targetType = t;
            }
            else if (targetVs._genericType() != null)
            {
                targetType = _GenericType.type(targetVs._genericType());
            }
            if (targetType == null)
            {
                return _E_ValueSpecification.wrap(value != null, genericType, multiplicity, resolver);
            }

            // Get the value's type from VS genericType
            meta.pure.metamodel.type.Type valueType = _E_ValueSpecification.getValueOriginalType(args.get(0), resolver);

            // Check type hierarchy
            if (valueType == null)
            {
                return _E_ValueSpecification.wrap(false, genericType, multiplicity, resolver);
            }
            return _E_ValueSpecification.wrap(_Type.subtypeOf(valueType, targetType, resolver), genericType, multiplicity, resolver);
        });

        // type(Any[*]) : Type[1] — returns the raw type of the value
        natives.put("type_Any_MANY__Type_1_", (args, eval, genericType, multiplicity) ->
        {
            Type type = _E_ValueSpecification.getValueOriginalType(args.getFirst());
            // If the resolved type is itself a TypeParameter (unresolved generic like T),
            // fall back to the VS's genericType which has the concrete binding from the compiler
            if (type instanceof meta.pure.metamodel.type.generics.TypeParameter
                    && args.getFirst()._genericType() != null)
            {
                type = _GenericType.type(args.getFirst()._genericType());
            }
            return _E_ValueSpecification.wrap(type, genericType, multiplicity, resolver);
        });

        // genericType(Any[*]) : GenericTypeValue[1]
        natives.put("genericType_Any_MANY__GenericTypeValue_1_", (args, eval, genericType, multiplicity) ->
        {
            ValueSpecification vs = args.get(0);
            Object val = _E_ValueSpecification.unwrap(vs);
            // DynamicInstance may carry its own classifierGenericType (set by new/copy)
            if (val instanceof DynamicInstance di && di.getClassifierGenericType() != null)
            {
                return _E_ValueSpecification.wrap(di.getClassifierGenericType(), genericType, multiplicity, resolver);
            }
            // Fall back to the ValueSpecification's generic type
            if (val instanceof Any pe)
            {
                return _E_ValueSpecification.wrap(pe._classifierGenericType(), genericType, multiplicity, resolver);
            }
            return _E_ValueSpecification.wrap(vs._genericType(), genericType, multiplicity, resolver);
        });

        // genericTypeHolder(Any[m]) : GenericTypeAndMultiplicityHolder<T|m>[1]
        natives.put("genericTypeHolder_T_m__GenericTypeAndMultiplicityHolder_1_", (args, eval, genericType, multiplicity) ->
        {
            ValueSpecification vs = args.get(0);
            meta.pure.metamodel.type.generics.GenericType heldGT = vs._genericType();
            meta.pure.metamodel.multiplicity.Multiplicity heldMul = vs._multiplicity();
            // Build classifier GT: GenericTypeAndMultiplicityHolder<heldGT|heldMul>
            meta.pure.metamodel.type.Type holderType = (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::valuespecification::GenericTypeAndMultiplicityHolder");
            meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl classifierGT = _GenericType.buildUserDefinedGenericType(holderType, resolver);
            if (heldGT != null)
            {
                classifierGT._typeArguments(org.eclipse.collections.impl.factory.Lists.mutable.with(heldGT));
            }
            if (heldMul != null)
            {
                classifierGT._multiplicityArguments(org.eclipse.collections.impl.factory.Lists.mutable.with(heldMul));
            }
            meta.pure.metamodel.valuespecification.UserDefinedGenericTypeAndMultiplicityHolderImpl holder =
                    new meta.pure.metamodel.valuespecification.UserDefinedGenericTypeAndMultiplicityHolderImpl()
                            ._classifierGenericType(classifierGT)
                            ._genericType(classifierGT)
                            ._multiplicity((meta.pure.metamodel.multiplicity.Multiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureOne"));
            return _E_ValueSpecification.wrap(holder, genericType, multiplicity, resolver);
        });

        // new(GenericTypeAndMultiplicityHolder[1]) : T[1] — construct an object with no property assignments
        natives.put("new_GenericTypeAndMultiplicityHolder_1__T_1_", (args, eval, genericType, multiplicity) ->
        {
            Object unwrapped = _E_ValueSpecification.unwrap(args.get(0));
            if (!(unwrapped instanceof GenericTypeAndMultiplicityHolder gtmh))
            {
                throw new RuntimeException("new(GenericTypeAndMultiplicityHolder[1]) requires a GenericTypeAndMultiplicityHolder argument, got: " + (unwrapped == null ? "null" : unwrapped.getClass().getSimpleName()));
            }

            String classPath = "Unknown";
            if (gtmh._genericType() != null
                    && _GenericType.typeArguments(gtmh._genericType()) != null
                    && _GenericType.typeArguments(gtmh._genericType()).notEmpty())
            {
                meta.pure.metamodel.type.generics.GenericType heldGT = _GenericType.typeArguments(gtmh._genericType()).getFirst();
                if (_GenericType.type(heldGT) instanceof meta.pure.metamodel.PackageableElement pe)
                {
                    classPath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
                    if (classPath.isEmpty())
                    {
                        classPath = pe._name() != null ? pe._name() : "Unknown";
                    }
                }
            }

            Object instance = createInstance(classPath, gtmh);

            if (gtmh._genericType() != null
                    && _GenericType.typeArguments(gtmh._genericType()) != null
                    && _GenericType.typeArguments(gtmh._genericType()).notEmpty())
            {
                GenericType cgt = _GenericType.typeArguments(gtmh._genericType()).getFirst();
                cgt = preferCanonicalAnchor(cgt, resolver);
                if (instance instanceof Any any)
                {
                    any._classifierGenericType((GenericTypeValue) cgt);
                }
                else if (instance instanceof DynamicInstance di)
                {
                    di.setClassifierGenericType((GenericTypeValue) cgt);
                }

                meta.pure.metamodel.type.Type targetType = _GenericType.type(_GenericType.typeArguments(gtmh._genericType()).getFirst());
                if (targetType instanceof meta.pure.metamodel.extension.ElementWithConstraints)
                {
                    validateConstraints(targetType, _GenericType.typeArguments(gtmh._genericType()).getFirst(), instance, eval, resolver);
                }
            }
            else if (instance instanceof Any any && !"Unknown".equals(classPath))
            {
                // No type arguments — set classifier from resolved class path.
                // Mirror Java direct's `new XxxImpl(model)` ctor pattern: prefer
                // the canonical `GenericType_<fullPath>` UDPGT-PE from core.pdb
                // when one exists (full path encoded with `::` -> `_`, so user
                // modules whose simple names collide with platform types don't
                // pick up the wrong anchor). Falls through to a fresh UDGT
                // wrapping the class when no canonical anchor exists.
                Object typeElement = resolver.getElement(classPath);
                if (typeElement instanceof meta.pure.metamodel.type.Type t && t instanceof meta.pure.metamodel.PackageableElement pe)
                {
                    Object canonical = resolver.getElement("meta::pure::metamodel::type::generics::optimization::GenericType_"
                            + classPath.replace("::", "_"));
                    if (canonical instanceof GenericTypeValue canonicalGT)
                    {
                        any._classifierGenericType(canonicalGT);
                        return _E_ValueSpecification.wrap(instance, genericType, multiplicity, resolver);
                    }
                    any._classifierGenericType(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.buildUserDefinedGenericType(t, resolver));
                }
            }

            return _E_ValueSpecification.wrap(instance, genericType, multiplicity, resolver);
        });

        // new(GenericType[1]) : Any[1] — construct an instance with the given GenericType as classifierGenericType
        natives.put("new_GenericType_1__Any_1_", (args, eval, genericType, multiplicity) ->
        {
            Object rawArg = _E_ValueSpecification.unwrap(args.get(0));
            if (!(rawArg instanceof meta.pure.metamodel.type.generics.GenericTypeValue gt))
            {
                throw new RuntimeException("new(GenericType[1]) requires a GenericType argument");
            }

            meta.pure.metamodel.type.Type rawType = _GenericType.type(gt);
            String classPath = "Unknown";
            if (rawType instanceof meta.pure.metamodel.PackageableElement pe)
            {
                classPath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
                if (classPath.isEmpty())
                {
                    classPath = pe._name() != null ? pe._name() : "Unknown";
                }
            }

            if ("meta::pure::metamodel::type::Class".equals(classPath))
            {
                if (_GenericType.typeArguments(gt) == null || _GenericType.typeArguments(gt).isEmpty())
                {
                    throw new RuntimeException("Cannot instantiate Class<Class<T>> because the typeArgs are not set for the typeParam");
                }
            }

            Object instance = createInstanceByPath(classPath);
            // Platform-level canonical anchor: when the input GT has no type/mult args,
            // prefer the canonical GenericType_<TypeName> UDPGT from core.pdb. Mirrors
            // Truffle's NewGenericTypeNode.doNewGenericType — without this, calls like
            // `new(buildUserDefinedGenericType(FunctionType))->cast(@FunctionType)` from
            // compiler-pure's functionTypeCompiler leave classifier as a fresh inline
            // UDGT, while Java direct's `new FunctionTypeImpl(model)` ctor anchors at
            // the canonical UDPGT. Aligning the platform-level new() native here keeps
            // compile-via-pure output byte-equivalent to Java direct + Truffle.
            meta.pure.metamodel.type.generics.GenericType anchoredGt = preferCanonicalAnchor(gt, resolver);
            if (instance instanceof Any any)
            {
                any._classifierGenericType((GenericTypeValue) anchoredGt);
            }
            else if (instance instanceof DynamicInstance di)
            {
                di.setClassifierGenericType((GenericTypeValue) anchoredGt);
            }

            return _E_ValueSpecification.wrap(instance, genericType, multiplicity, resolver);
        });

        // new(GenericTypeAndMultiplicityHolder[1], KeyExpression[*]) : T[1] — construct from key expressions
        // Registered as a LAZY native so we can push the instance onto the construction stack
        // BEFORE evaluating key expressions, enabling parentReference (~) resolution.
        lazyNatives.put("new_GenericTypeAndMultiplicityHolder_1__KeyExpression_MANY__T_1_", (fe, eval) ->
        {
            // Open a fresh-instance scope so this instance — and any inner
            // new/copy created during key evaluation — is tracked as fresh
            // relative to the enclosing expression. The check in copy's
            // verifyAssocPropsAreFresh inspects the top scope; without this
            // push/pop, `^Person(firm = ^Firm())` inside `^$pierre(...)` would
            // not register the inner Firm in the outer copy's scope, and the
            // check would reject it even though it was created inline.
            eval.pushFreshScope();
            Object newResult = null;
            try
            {
            org.eclipse.collections.api.list.MutableList<ValueSpecification> paramSpecs = fe._parametersValues();
            // Step 1: Evaluate ONLY the type holder (first arg)
            Object typeHolder = _E_ValueSpecification.unwrap(eval.evaluate(paramSpecs.get(0)));

            String classPath = "Unknown";
            if (typeHolder instanceof GenericTypeAndMultiplicityHolder gtmh
                    && gtmh._genericType() != null
                    && _GenericType.typeArguments(gtmh._genericType()) != null
                    && _GenericType.typeArguments(gtmh._genericType()).notEmpty())
            {
                meta.pure.metamodel.type.generics.GenericType heldGT = _GenericType.typeArguments(gtmh._genericType()).getFirst();
                if (_GenericType.type(heldGT) instanceof meta.pure.metamodel.PackageableElement pe)
                {
                    classPath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
                    if (classPath.isEmpty())
                    {
                        classPath = pe._name() != null ? pe._name() : "Unknown";
                    }
                }
            }
            if (typeHolder instanceof GenericTypeAndMultiplicityHolder gtmh2)
            {
                // Step 2: Create the instance
                Object instance = createInstance(classPath, gtmh2);
                if (gtmh2._genericType() != null
                        && _GenericType.typeArguments(gtmh2._genericType()) != null
                        && _GenericType.typeArguments(gtmh2._genericType()).notEmpty())
                {
                    meta.pure.metamodel.type.generics.GenericType cgt = _GenericType.typeArguments(gtmh2._genericType()).getFirst();
                    // Platform-level canonical anchor: when ^Type(...) has no type/mult args,
                    // prefer the canonical GenericType_<TypeName> UDPGT element from core.pdb
                    // (matches Java's `new XxxImpl(model)` ctor pattern). Falls through to the
                    // parser-built UDGT when no canonical exists or args are present.
                    cgt = preferCanonicalAnchor(cgt, resolver);
                    assertNotSelf(instance, cgt, "new(GenericTypeAndMultiplicityHolder, KeyExpressions) classPath=" + classPath);
                    if (instance instanceof Any any)
                    {
                        any._classifierGenericType((GenericTypeValue) cgt);
                    }
                    else if (instance instanceof DynamicInstance di)
                    {
                        di.setClassifierGenericType((GenericTypeValue) cgt);
                    }
                }
                else if (instance instanceof Any any && !"Unknown".equals(classPath))
                {
                    // Same as the simple-new-helper above: prefer canonical
                    // GenericType_<fullPath> UDPGT-PE when one exists.
                    Object typeElement = resolver.getElement(classPath);
                    if (typeElement instanceof meta.pure.metamodel.type.Type t && t instanceof meta.pure.metamodel.PackageableElement pe)
                    {
                        boolean setClassifier = false;
                        Object canonical = resolver.getElement("meta::pure::metamodel::type::generics::optimization::GenericType_"
                                + classPath.replace("::", "_"));
                        if (canonical instanceof GenericTypeValue canonicalGT)
                        {
                            any._classifierGenericType(canonicalGT);
                            setClassifier = true;
                        }
                        if (!setClassifier)
                        {
                            any._classifierGenericType(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.buildUserDefinedGenericType(t, resolver));
                        }
                    }
                }

                // Step 3: Push onto construction stack, then evaluate and apply key
                // expressions ONE AT A TIME so that each slot value can read
                // already-set slots via `~.<prop>` (parent reference with property
                // path). If we batch-evaluated paramSpecs.get(1) up front,
                // `~.typeParameters` inside a later slot would read the instance
                // BEFORE typeParameters has been written.
                eval.pushConstruction(instance);
                try
                {
                    List<Map.Entry<String, Object>> keyValues = new ArrayList<>();
                    ValueSpecification keyExprsParam = paramSpecs.get(1);
                    if (keyExprsParam instanceof meta.pure.metamodel.valuespecification.Collection col)
                    {
                        // Inline `^X(slot=value, slot=value)` form: items are
                        // unevaluated keyExpression FunctionInvocations.
                        // Evaluate + apply each in source order.
                        for (ValueSpecification item : col._values())
                        {
                            Object ke = _E_ValueSpecification.unwrap(eval.evaluate(item));
                            processKeyExpression(ke, instance, keyValues, resolver);
                        }
                    }
                    else
                    {
                        // Non-inline form (e.g. `^X($alreadyBuiltKeys)`): the
                        // KeyExpression list is already constructed, slot values
                        // already resolved — no `~` references possible inside.
                        ValueSpecification keyExprsVS = eval.evaluate(keyExprsParam);
                        Object keyExprsRaw = _E_ValueSpecification.unwrap(keyExprsVS);
                        if (keyExprsRaw instanceof List<?> keyExprs)
                        {
                            for (Object ke : keyExprs)
                            {
                                processKeyExpression(ke, instance, keyValues, resolver);
                            }
                        }
                        else
                        {
                            processKeyExpression(keyExprsRaw, instance, keyValues, resolver);
                        }
                    }

                    // Immutability check: same rule as copy_T_1__KeyExpression_MANY__T_1_.
                    // Every association property's value on this fresh instance
                    // must itself have been instantiated within this new/copy
                    // expression. Catches `^LA_Person(firm=$firmX)` — $firmX
                    // exists outside this expression, and the bidir step below
                    // would add the new person to $firmX.employees, mutating
                    // the original. Use `firm = ^LA_Firm(...)` (inline) or
                    // `firm = ^$x()` (inline copy) instead.
                    verifyAssocPropsAreFresh(classPath, keyValues, eval, resolver);

                    // Set reverse association pointers
                    setReverseAssociationPointers(instance, classPath, keyValues, resolver);
                }
                finally
                {
                    eval.popConstruction();
                }

                // Validate constraints on the class after construction
                if (gtmh2._genericType() != null
                        && _GenericType.typeArguments(gtmh2._genericType()) != null
                        && _GenericType.typeArguments(gtmh2._genericType()).notEmpty())
                {
                    meta.pure.metamodel.type.generics.GenericType heldGT = _GenericType.typeArguments(gtmh2._genericType()).getFirst();
                    meta.pure.metamodel.type.Type targetType = _GenericType.type(heldGT);
                    if (targetType instanceof meta.pure.metamodel.extension.ElementWithConstraints)
                    {
                        validateConstraints(targetType, heldGT, instance, eval, resolver);
                    }
                }

                newResult = instance;
                return _E_ValueSpecification.wrap(instance, fe._genericType(), fe._multiplicity(), resolver);
            }
            throw new RuntimeException("Not possible");
            }
            finally
            {
                eval.popFreshScopeAndRegister(newResult);
            }
        });

        // keyExpression — creates a key-value pair
        NativeImpl keyExprFn = (args, eval, genericType, multiplicity) ->
        {
            // Store the key as an AtomicValue<String>
            ValueSpecification keyVS = args.get(0); // already a VS from evaluateArgs
            // Store the expression value as-is (already a VS)
            ValueSpecification valueVS = args.get(1);
            DynamicInstance keyExpr = new DynamicInstance("meta::pure::functions::lang::KeyExpression");
            keyExpr.put("name", keyVS);
            keyExpr.put("expression", valueVS);
            if (args.size() > 2)
            {
                // 'add' flag — wrap as a Boolean AtomicValue
                keyExpr.put("add", args.get(2));
            }
            return _E_ValueSpecification.wrap(keyExpr, genericType, multiplicity, resolver);
        };
        natives.put("keyExpression_String_1__Any_MANY__KeyExpression_1_", keyExprFn);
        natives.put("keyExpression_String_1__Any_MANY__Boolean_1__KeyExpression_1_", keyExprFn);

        // copy(T[1]) : T[1] — simple copy with no overrides
        natives.put("copy_T_1__T_1_", (args, eval, genericType, multiplicity) ->
        {
            // Open + close a fresh-instance scope so this copy's product
            // registers in the enclosing new/copy expression's scope (see
            // ValueSpecificationEvaluator#freshScopeStack). Simple copy itself
            // has no keys, so there's nothing to verify — just propagate.
            eval.pushFreshScope();
            Object simpleCopyResult = null;
            try
            {
            Object original = _E_ValueSpecification.unwrap(args.get(0));
            String classPath;
            GenericTypeValue cgt;
            if (original instanceof DynamicInstance di)
            {
                classPath = di.getClassPath();
                cgt = di.getClassifierGenericType();
            }
            else if (original instanceof PackageableElement pe)
            {
                cgt = pe._classifierGenericType();
                classPath = pe.getClass().getInterfaces()[0].getName().replace(".", "::");
            }
            else if (original instanceof Any any)
            {
                cgt = any._classifierGenericType();
                classPath = any.getClass().getInterfaces()[0].getName().replace(".", "::");
            }
            else
            {
                throw new RuntimeException("Cannot copy: " + (original == null ? "null" : original.getClass().getSimpleName()));
            }

            // If path is empty (e.g., instance has no name yet), derive from classifierGenericType
            if ((classPath == null || classPath.isEmpty()) && cgt != null)
            {
                classPath = resolveClassPathFromCGT(cgt);
            }

            Object copy = createInstanceByPath(classPath);
            // Shallow copy all properties (including classifierGenericType from original).
            // We do NOT rewrite self-references (classifierGenericType, TypeParameter.owner,
            // Property.owner, etc.) to point to the copy — that would silently mutate
            // graph identity. Users who want the copy to be referenced in such slots
            // must explicitly write `~.~` (parent reference) in their copy expression.
            shallowCopyProperties(original, copy, cgt, resolver);
            // Platform-level canonical anchor: if the copy's classifier could be
            // a canonical GenericType_<TypeName> UDPGT-PE, prefer it. Symmetric
            // to new() — preserves canonical references through copy operations.
            GenericTypeValue copyCgt = cgt;
            if (copyCgt != null)
            {
                copyCgt = (GenericTypeValue) preferCanonicalAnchor(copyCgt, resolver);
            }
            if (copy instanceof Any anyC && copyCgt != null)
            {
                anyC._classifierGenericType(copyCgt);
            }
            else if (copy instanceof DynamicInstance diC && copyCgt != null)
            {
                diC.setClassifierGenericType(copyCgt);
            }
            simpleCopyResult = copy;
            return _E_ValueSpecification.wrap(copy, genericType, multiplicity, resolver);
            }
            finally
            {
                eval.popFreshScopeAndRegister(simpleCopyResult);
            }
        });

        // copy(T[1], KeyExpression[*]) : T[1] — shallow copy with property overrides
        // Registered as a LAZY native so we can push the copy onto the construction stack
        // BEFORE evaluating key expressions, enabling parentReference (~) resolution.
        lazyNatives.put("copy_T_1__KeyExpression_MANY__T_1_", (fe, eval) ->
        {
            // Open a fresh-instance scope. Every PDO created by `^Type(...)` or
            // `^$x()` while evaluating the keys (or this very copy itself)
            // registers in this scope; the post-build check uses it to
            // require that any association property's value originated within
            // the copy expression. `popFreshScopeAndRegister(copy)` in the
            // finally below registers this copy's result into any enclosing
            // expression's scope.
            eval.pushFreshScope();
            Object copyResult = null;
            try
            {
            org.eclipse.collections.api.list.MutableList<ValueSpecification> paramSpecs = fe._parametersValues();
            // Step 1: Evaluate the source object (first arg)
            ValueSpecification sourceVS = eval.evaluate(paramSpecs.get(0));
            Object original = _E_ValueSpecification.unwrap(sourceVS);
            String classPath;
            GenericTypeValue cgt;
            if (original instanceof DynamicInstance di)
            {
                classPath = di.getClassPath();
                cgt = di.getClassifierGenericType();
            }
            else if (original instanceof PackageableElement pe)
            {
                cgt = pe._classifierGenericType();
                classPath = pe.getClass().getInterfaces()[0].getName().replace(".", "::");
            }
            else if (original instanceof Any any)
            {
                cgt = any._classifierGenericType();
                classPath = any.getClass().getInterfaces()[0].getName().replace(".", "::");
            }
            else
            {
                throw new RuntimeException("Cannot copy: " + (original == null ? "null" : original.getClass().getSimpleName()));
            }

            // If path is empty (e.g., instance has no name yet), derive from classifierGenericType
            if ((classPath == null || classPath.isEmpty()) && cgt != null)
            {
                classPath = resolveClassPathFromCGT(cgt);
            }

            // Step 2: Create the copy. No self-reference rewriting — see the
            // copy(T[1]) variant above for the rationale.
            Object copy = createInstanceByPath(classPath);
            shallowCopyProperties(original, copy, cgt, resolver);
            GenericTypeValue copyCgt = cgt;
            if (copyCgt != null)
            {
                copyCgt = (GenericTypeValue) preferCanonicalAnchor(copyCgt, resolver);
            }
            if (copy instanceof Any anyC && copyCgt != null)
            {
                anyC._classifierGenericType(copyCgt);
            }
            else if (copy instanceof DynamicInstance diC && copyCgt != null)
            {
                diC.setClassifierGenericType(copyCgt);
            }

            // Step 3: Push onto construction stack, then evaluate key expressions
            eval.pushConstruction(copy);
            Map<String, DynamicInstance> deepCopied = new HashMap<>();
            try
            {
                ValueSpecification keyExprsVS = eval.evaluate(paramSpecs.get(1));

                Object keyExprsRaw = _E_ValueSpecification.unwrap(keyExprsVS);
                List<Object> keyExprList;
                if (keyExprsRaw instanceof List<?> list)
                {
                    keyExprList = new ArrayList<>(list);
                }
                else
                {
                    keyExprList = new ArrayList<>();
                    if (keyExprsRaw != null)
                    {
                        keyExprList.add(keyExprsRaw);
                    }
                }

                for (Object ke : keyExprList)
                {
                    if (ke instanceof DynamicInstance diKe)
                    {
                        applyCopyKeyExpressionWithDotPath(copy, diKe, deepCopied, resolver);
                    }
                }
            }
            finally
            {
                eval.popConstruction();
            }


            // Register deep-copied intermediates from `prop.X = ...` paths as
            // fresh. They're products of this copy expression (just not
            // produced via the new()/copy() natives), so the immutability
            // check should accept them.
            for (DynamicInstance nested : deepCopied.values())
            {
                eval.registerFreshInCurrentScope(nested);
            }

            // Collect key/value pairs for reverse pointer processing
            List<Map.Entry<String, Object>> allProps = getAllPropertyEntries(copy, cgt, resolver);

            // Immutability check: every association property's value on `copy`
            // must be a PDO created within this copy expression. Catches
            // `^$pierre(firstName='Bob')` — bob.firm is shallow-copied from
            // pierre, points at $firmX which exists outside this expression,
            // and the bidir step below would add bob to $firmX.employees
            // (mutating the original). Throws with guidance to use a fresh
            // value, an empty `[]`, or `^$x()`.
            verifyAssocPropsAreFresh(classPath, allProps, eval, resolver);

            // Set reverse association pointers for ALL properties on the copy
            setReverseAssociationPointers(copy, classPath, allProps, resolver);

            // Set reverse association pointers for deep-copied nested objects
            java.util.Set<Object> copySet = new java.util.HashSet<>();
            copySet.add(copy);
            copySet.addAll(deepCopied.values());

            for (Map.Entry<String, DynamicInstance> dcEntry : deepCopied.entrySet())
            {
                DynamicInstance nestedCopy = dcEntry.getValue();
                String nestedClassPath = nestedCopy.getClassPath();
                List<Map.Entry<String, Object>> nestedProps = new ArrayList<>();
                for (Map.Entry<String, Object> nestedEntry : nestedCopy.getValues().entrySet())
                {
                    Object val = nestedEntry.getValue();
                    if (val == null)
                    {
                        continue;
                    }
                    Object rawVal = _E_ValueSpecification.unwrap(val);
                    if (copySet.contains(rawVal))
                    {
                        continue;
                    }
                    if (rawVal instanceof List<?> listVal && listVal.stream().anyMatch(copySet::contains))
                    {
                        continue;
                    }
                    nestedProps.add(Map.entry(nestedEntry.getKey(), val));
                }
                if (!nestedProps.isEmpty())
                {
                    setReverseAssociationPointers(nestedCopy, nestedClassPath, nestedProps, resolver);
                }
            }

            copyResult = copy;
            return _E_ValueSpecification.wrap(copy, fe._genericType(), fe._multiplicity(), resolver);
            }
            finally
            {
                eval.popFreshScopeAndRegister(copyResult);
            }
        });

        // cast(Any[m], T[1]) : T[m]
        natives.put("cast_Any_m__GenericTypeAndMultiplicityHolder_1__T_m_", (args, eval, genericType, multiplicity) ->
        {
            ValueSpecification inputVs = args.get(0);
            ValueSpecification targetVs = args.get(1);

            // Resolve the target GenericType from the GenericTypeAndMultiplicityHolder
            meta.pure.metamodel.type.generics.GenericType targetGT = null;
            meta.pure.metamodel.type.Type targetType = null;
            if (targetVs instanceof GenericTypeAndMultiplicityHolder gtmh
                    && gtmh._genericType() != null
                    && _GenericType.typeArguments(gtmh._genericType()) != null
                    && _GenericType.typeArguments(gtmh._genericType()).notEmpty())
            {
                targetGT = _GenericType.typeArguments(gtmh._genericType()).getFirst();
                targetType = _GenericType.type(targetGT);
            }
            else if (targetVs._genericType() != null)
            {
                targetGT = targetVs._genericType();
                targetType = _GenericType.type(targetGT);
            }

            // Validate type compatibility for scalar values.
            // Skip Collections — their common element type is lossy and cast is per-element.
            Object value = _E_ValueSpecification.unwrap(inputVs);
            if (value != null
                    && !(inputVs instanceof meta.pure.metamodel.valuespecification.Collection)
                    && targetType instanceof PackageableElement targetPe
                    && !(value instanceof meta.pure.metamodel.type.generics.TypeParameter)
                    && !(value instanceof meta.pure.metamodel.multiplicity.MultiplicityParameter))
            {
                String targetPath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(targetPe);
                if (!"meta::pure::metamodel::type::Any".equals(targetPath))
                {
                    meta.pure.metamodel.type.Type sourceType = _E_ValueSpecification.getValueOriginalType(inputVs, resolver);
                    if (sourceType instanceof PackageableElement sourcePe)
                    {
                        String sourcePath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(sourcePe);
                        if (!"meta::pure::metamodel::type::Nil".equals(sourcePath))
                        {
                            boolean related = _Type.subtypeOf(sourceType, targetType, resolver)
                                    || _Type.subtypeOf(targetType, sourceType, resolver);
                            if (!related)
                            {
                                throw new RuntimeException("Cast exception: " + sourcePe._name() + " cannot be cast to " + targetPe._name());
                            }
                        }
                    }
                }
            }

            // Validate constraints on the target type
            if (value != null && targetType instanceof meta.pure.metamodel.extension.ElementWithConstraints)
            {
                validateConstraints(targetType, targetGT, value, eval, resolver);
            }

            // Restamp the wrapper's genericType to the target type
            if (targetGT == null)
            {
                return inputVs;
            }
            if (inputVs instanceof meta.pure.metamodel.valuespecification.CollectionImpl col)
            {
                meta.pure.metamodel.valuespecification.CollectionImpl result = col._copy();
                result._genericType(targetGT);
                return result;
            }
            return _E_ValueSpecification.wrap(value, targetGT, inputVs._multiplicity(), resolver);
        });

        // evaluateAndDeactivate — passthrough
        NativeImpl evalAndDeactivate = (args, eval, genericType, multiplicity) -> args.get(0);
        natives.put("evaluateAndDeactivate_Any_m__Any_m_", evalAndDeactivate);
        natives.put("evaluateAndDeactivate", evalAndDeactivate);

        // newClass(TypeParameter[*], MultiplicityParameter[*]) : Class<Any>[1]
        natives.put("newClass_TypeParameter_MANY__MultiplicityParameter_MANY__Class_1_", (args, eval, genericType, multiplicity) ->
        {
            meta.pure.metamodel.type.ClassImpl newClass = new meta.pure.metamodel.type.ClassImpl();

            // Build classifierGenericType = Class<self> where the typeArgument points to this class.
            meta.pure.metamodel.type.Type classType = (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::Class");
            meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl selfRef = _GenericType.buildUserDefinedGenericType(newClass, resolver);

            // Build typeArguments from the provided typeParameters
            Object typeParamsRaw = _E_ValueSpecification.unwrap(args.get(0));
            List<Object> typeParams;
            if (typeParamsRaw instanceof List<?> list)
            {
                typeParams = new ArrayList<>(list);
            }
            else
            {
                typeParams = new ArrayList<>();
                if (typeParamsRaw != null)
                {
                    typeParams.add(typeParamsRaw);
                }
            }
            if (typeParams != null && !typeParams.isEmpty())
            {
                org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.type.generics.GenericType> innerTypeArgs = org.eclipse.collections.impl.factory.Lists.mutable.empty();
                org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.type.generics.TypeParameter> tpList = org.eclipse.collections.impl.factory.Lists.mutable.empty();
                for (Object tp : typeParams)
                {
                    if (tp instanceof meta.pure.metamodel.type.generics.TypeParameter tpObj)
                    {
                        tpObj._owner(newClass);
                        tpList.add(tpObj);
                    }
                    meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl tpArg = _GenericType.buildUserDefinedGenericType((meta.pure.metamodel.type.Type) tp, resolver);
                    innerTypeArgs.add(tpArg);
                }
                selfRef._typeArguments(innerTypeArgs);
                newClass._typeParameters(tpList);
            }
            // Build multiplicityArguments from provided multiplicityParameters
            Object mulParamsRaw = _E_ValueSpecification.unwrap(args.get(1));
            List<Object> mulParams;
            if (mulParamsRaw instanceof List<?> list)
            {
                mulParams = new ArrayList<>(list);
            }
            else
            {
                mulParams = new ArrayList<>();
                if (mulParamsRaw != null)
                {
                    mulParams.add(mulParamsRaw);
                }
            }
            if (mulParams != null && !mulParams.isEmpty())
            {
                org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.multiplicity.Multiplicity> innerMulArgs = org.eclipse.collections.impl.factory.Lists.mutable.empty();
                org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.multiplicity.MultiplicityParameter> mpList = org.eclipse.collections.impl.factory.Lists.mutable.empty();
                for (Object mp : mulParams)
                {
                    if (mp instanceof meta.pure.metamodel.multiplicity.MultiplicityParameter mpObj)
                    {
                        mpObj._owner(newClass);
                        mpList.add(mpObj);
                    }
                    innerMulArgs.add((meta.pure.metamodel.multiplicity.Multiplicity) mp);
                }
                selfRef._multiplicityArguments(innerMulArgs);
                newClass._multiplicityParameters(mpList);
            }

            meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl cgt = _GenericType.buildUserDefinedGenericType(classType, resolver);
            cgt._typeArguments(org.eclipse.collections.impl.factory.Lists.mutable.with(selfRef));
            newClass._classifierGenericType(cgt);

            return _E_ValueSpecification.wrap(newClass, genericType, multiplicity, resolver);
        });

        // newEnumeration(String[1], Package[1], String[*]) : Enumeration<Any>[1]
        natives.put("newEnumeration_String_1__Package_1__String_MANY__Enumeration_1_", (args, eval, genericType, multiplicity) ->
        {
            String name = (String) _E_ValueSpecification.unwrap(args.get(0));
            Object pkg = _E_ValueSpecification.unwrap(args.get(1));
            Object valueNamesRaw = _E_ValueSpecification.unwrap(args.get(2));
            List<String> valueNames = new ArrayList<>();
            if (valueNamesRaw instanceof List<?> list)
            {
                list.forEach(v -> valueNames.add((String) v));
            }
            else if (valueNamesRaw instanceof String s)
            {
                valueNames.add(s);
            }

            meta.pure.metamodel.type.EnumerationImpl enumeration = new meta.pure.metamodel.type.EnumerationImpl();
            enumeration._name(name);
            if (pkg instanceof meta.pure.metamodel.Package p)
            {
                enumeration._package(p);
            }

            // Add generalization: every user-defined enumeration extends Enum
            meta.pure.metamodel.type.Type enumType = (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::Enum");
            meta.pure.metamodel.relationship.GeneralizationImpl gen = new meta.pure.metamodel.relationship.GeneralizationImpl();
            gen._general(_GenericType.buildUserDefinedGenericType(enumType, resolver));
            gen._specific(enumeration);
            meta.pure.metamodel.type.Type genType = (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::relationship::Generalization");
            if (genType != null)
            {
                gen._classifierGenericType(_GenericType.buildUserDefinedGenericType(genType, resolver));
            }
            enumeration._generalizations(org.eclipse.collections.impl.factory.Lists.mutable.with(gen));

            // Self-referencing CGT: Enumeration<self>
            meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl selfRef = _GenericType.buildUserDefinedGenericType(enumeration, resolver);
            meta.pure.metamodel.type.Type enumerationType = (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::Enumeration");
            meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl cgt = _GenericType.buildUserDefinedGenericType(enumerationType, resolver);
            cgt._typeArguments(org.eclipse.collections.impl.factory.Lists.mutable.with(selfRef));
            enumeration._classifierGenericType(cgt);

            // GenericType for this enumeration (used as CGT for enum values)
            meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl enumGT = _GenericType.buildUserDefinedGenericType(enumeration, resolver);

            meta.pure.metamodel.multiplicity.Multiplicity pureOne = (meta.pure.metamodel.multiplicity.Multiplicity) resolver.getElement("meta::pure::metamodel::multiplicity::PureOne");
            meta.pure.metamodel.type.Type propertyType = (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::function::property::Property");

            // Build properties for each value
            org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.function.property.Property> properties = org.eclipse.collections.impl.factory.Lists.mutable.empty();
            for (String valueName : valueNames)
            {
                // Create Enum instance
                meta.pure.metamodel.type.EnumImpl enumInstance = new meta.pure.metamodel.type.EnumImpl();
                enumInstance._name(valueName);
                enumInstance._classifierGenericType(enumGT);

                // Build defaultValue lambda
                meta.pure.metamodel.type.FunctionTypeImpl ft = new meta.pure.metamodel.type.FunctionTypeImpl();
                meta.pure.metamodel.type.Type ftType = (meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::type::FunctionType");
                if (ftType != null) ft._classifierGenericType(_GenericType.buildUserDefinedGenericType(ftType, resolver));
                ft._returnType(enumGT);
                ft._returnMultiplicity(pureOne);
                meta.pure.metamodel.type.generics.InferredGenericTypeImpl lambdaCGT = new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(resolver);
                lambdaCGT._type((meta.pure.metamodel.type.Type) resolver.getElement("meta::pure::metamodel::function::LambdaFunction"));
                lambdaCGT._typeArguments(org.eclipse.collections.impl.factory.Lists.mutable.with(
                        new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(resolver)._type(ft)));

                meta.pure.metamodel.function.LambdaFunctionImpl lambda = new meta.pure.metamodel.function.LambdaFunctionImpl();
                lambda._classifierGenericType(lambdaCGT);
                lambda._expressionSequence(org.eclipse.collections.impl.factory.Lists.mutable.with(
                        new meta.pure.metamodel.valuespecification.AtomicValueImpl(resolver)
                                ._value(enumInstance)
                                ._genericType(enumGT)
                                ._multiplicity(pureOne)));

                // Build Property
                meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl propCGT = _GenericType.buildUserDefinedGenericType(propertyType, resolver);
                propCGT._typeArguments(org.eclipse.collections.impl.factory.Lists.mutable.with(cgt, enumGT));
                propCGT._multiplicityArguments(org.eclipse.collections.impl.factory.Lists.mutable.with(pureOne));

                meta.pure.metamodel.function.property.PropertyImpl prop = new meta.pure.metamodel.function.property.PropertyImpl();
                prop._name(valueName);
                prop._classifierGenericType(propCGT);
                prop._genericType(enumGT);
                prop._multiplicity(pureOne);
                prop._owner(enumeration);
                // AggregationKind.None — resolve from the PDB
                Object aggNone = resolver.getElement("meta::pure::metamodel::function::property::AggregationKind");
                if (aggNone instanceof meta.pure.metamodel.type.Enumeration aggEnum && aggEnum._properties() != null)
                {
                    for (meta.pure.metamodel.function.property.Property aggProp : aggEnum._properties())
                    {
                        if ("None".equals(aggProp._name()) && aggProp._defaultValue() != null)
                        {
                            Object aggVal = _E_ValueSpecification.unwrap(aggProp._defaultValue()._expressionSequence().getFirst());
                            if (aggVal instanceof meta.pure.metamodel.function.property.AggregationKind ak)
                            {
                                prop._aggregation(ak);
                            }
                            break;
                        }
                    }
                }
                prop._defaultValue(lambda);
                properties.add(prop);
            }

            enumeration._properties(properties);
            return _E_ValueSpecification.wrap(enumeration, genericType, multiplicity, resolver);
        });
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * After copying a SimplePropertyOwner, update property owners and nested
     * enum value CGTs to point to the copy instead of the original.
     */
    private static void fixTypeParameterOwners(Object original, Object copy)
    {
        if (copy instanceof meta.pure.metamodel.type.Class cls)
        {
            if (cls._typeParameters() != null)
            {
                for (meta.pure.metamodel.type.generics.TypeParameter tp : cls._typeParameters())
                {
                    if (tp._owner() == original)
                    {
                        tp._owner(cls);
                    }
                }
            }
            if (cls._multiplicityParameters() != null)
            {
                for (meta.pure.metamodel.multiplicity.MultiplicityParameter mp : cls._multiplicityParameters())
                {
                    if (mp._owner() == original)
                    {
                        mp._owner(cls);
                    }
                }
            }
        }
    }

    private static void fixPropertyOwners(Object original, Object copy, MetadataAccess resolver)
    {
        if (!(copy instanceof meta.pure.metamodel.SimplePropertyOwner spo))
        {
            return;
        }
        if (spo._properties() == null)
        {
            return;
        }
        meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl copyGT = null;
        if (copy instanceof meta.pure.metamodel.type.Type copyType)
        {
            copyGT = _GenericType.buildUserDefinedGenericType(copyType, resolver);
        }
        for (meta.pure.metamodel.function.property.Property prop : spo._properties())
        {
            if (prop._owner() == original)
            {
                prop._owner(spo);
            }
            if (copyGT != null && prop._defaultValue() != null
                    && prop._defaultValue()._expressionSequence() != null
                    && !prop._defaultValue()._expressionSequence().isEmpty())
            {
                Object vs = prop._defaultValue()._expressionSequence().getFirst();
                if (vs instanceof meta.pure.metamodel.valuespecification.AtomicValue av
                        && av._value() instanceof Any enumVal)
                {
                    var enumCgt = enumVal._classifierGenericType();
                    if (enumCgt != null && _GenericType.type(enumCgt) == original)
                    {
                        enumVal._classifierGenericType(copyGT);
                    }
                }
            }
        }
    }

    /**
     * Fix self-referential classifierGenericType during copy.
     * If the original's CGT has a typeArgument whose rawType IS the original instance itself
     * (e.g., Class&lt;self&gt;), create a new CGT with the typeArgument pointing to the copy.
     * Returns the updated CGT, or the original if no self-reference was found.
     */
    public static GenericTypeValue fixSelfReferentialCGT(
            GenericTypeValue cgt,
            Object original, Object copy,
            MetadataAccess resolver)
    {
        if (cgt == null)
        {
            return null;
        }

        org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.type.generics.GenericType> typeArgs = cgt._typeArguments();
        if (typeArgs != null && typeArgs.notEmpty())
        {
            boolean hasSelfRef = false;
            for (meta.pure.metamodel.type.generics.GenericType arg : typeArgs)
            {
                if (arg instanceof meta.pure.metamodel.type.generics.GenericTypeValue argV)
                {
                    var argType = argV._type();
                    if (argType == original || org.finos.legend.pure.execution.NativeRepository.pureEquals(argType, original, resolver))
                    {
                        hasSelfRef = true;
                        break;
                    }
                }
            }
            if (hasSelfRef && copy instanceof meta.pure.metamodel.type.Type copyType)
            {
                // Build a new CGT with updated self-references
                org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.type.generics.GenericType> newArgs =
                        typeArgs.collect(arg ->
                        {
                            if (arg instanceof meta.pure.metamodel.type.generics.GenericTypeValue argV
                                    && (argV._type() == original || org.finos.legend.pure.execution.NativeRepository.pureEquals(argV._type(), original, resolver)))
                            {
                                meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl selfRef =
                                        _GenericType.buildUserDefinedGenericType(copyType, resolver);
                                // Copy inner typeArguments (e.g., TypeParameters like T)
                                // and fix their owners to point to the copy
                                if (argV._typeArguments() != null && argV._typeArguments().notEmpty())
                                {
                                    org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.type.generics.GenericType> innerArgs =
                                            argV._typeArguments().collect(innerArg ->
                                            {
                                                if (innerArg instanceof meta.pure.metamodel.type.generics.GenericTypeValue innerV
                                                        && innerV._type() instanceof meta.pure.metamodel.type.generics.TypeParameter tp
                                                        && copyType instanceof meta.pure.metamodel.type.generics.TypeAndMultiplicityParametersOwner owner)
                                                {
                                                    tp._owner(owner);
                                                }
                                                return innerArg;
                                            });
                                    selfRef._typeArguments(innerArgs);
                                }
                                if (argV._multiplicityArguments() != null && argV._multiplicityArguments().notEmpty())
                                {
                                    // Fix MultiplicityParameter owners too
                                    for (meta.pure.metamodel.multiplicity.Multiplicity mp : argV._multiplicityArguments())
                                    {
                                        if (mp instanceof meta.pure.metamodel.multiplicity.MultiplicityParameter mulParam
                                                && copyType instanceof meta.pure.metamodel.type.generics.TypeAndMultiplicityParametersOwner owner)
                                        {
                                            mulParam._owner(owner);
                                        }
                                    }
                                    selfRef._multiplicityArguments(argV._multiplicityArguments());
                                }
                                return (meta.pure.metamodel.type.generics.GenericType) selfRef;
                            }
                            return arg;
                        });
                meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl newCgt =
                        _GenericType.buildUserDefinedGenericType(cgt._type(), resolver);
                newCgt._typeArguments(newArgs);
                if (cgt._multiplicityArguments() != null)
                {
                    newCgt._multiplicityArguments(cgt._multiplicityArguments());
                }
                return newCgt;
            }
        }
        return cgt;
    }

    public static void validateConstraints(meta.pure.metamodel.type.Type type,
                                    meta.pure.metamodel.type.generics.GenericType targetGT,
                                    Object value, ValueSpecificationEvaluator eval,
                                    MetadataAccess resolver)
    {
        if (type == null)
        {
            return;
        }
        validateConstraintsOnType(type, targetGT, value, eval, resolver);
        if (type._generalizations() != null)
        {
            for (var gen : type._generalizations())
            {
                if (gen._general() != null && _GenericType.type(gen._general()) != null)
                {
                    meta.pure.metamodel.type.Type superType = _GenericType.type(gen._general());
                    validateConstraints(superType, gen._general(), value, eval, resolver);
                }
            }
        }
    }

    private static void validateConstraintsOnType(meta.pure.metamodel.type.Type type,
                                                  meta.pure.metamodel.type.generics.GenericType targetGT,
                                                  Object value, ValueSpecificationEvaluator eval,
                                                  MetadataAccess resolver)
    {
        if (!(type instanceof meta.pure.metamodel.extension.ElementWithConstraints ewc))
        {
            return;
        }
        org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.constraint.Constraint> constraints = ewc._constraints();
        if (constraints == null || constraints.isEmpty())
        {
            return;
        }

        String typeName = (type instanceof PackageableElement pe) ? pe._name() : "Unknown";

        Map<String, ValueSpecification> typeVarBindings = new HashMap<>();
        if (targetGT instanceof meta.pure.metamodel.type.generics.GenericTypeValue gtv
                && gtv._typeVariableValues() != null && gtv._typeVariableValues().notEmpty())
        {
            org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.valuespecification.VariableExpression> typeVars = null;
            if (type instanceof meta.pure.metamodel.type.PrimitiveType pt)
            {
                typeVars = pt._typeVariables();
            }
            else if (type instanceof meta.pure.metamodel.type.Class cls)
            {
                typeVars = cls._typeVariables();
            }
            if (typeVars != null)
            {
                org.eclipse.collections.api.list.MutableList<ValueSpecification> typeVarVals = gtv._typeVariableValues();
                int count = Math.min(typeVars.size(), typeVarVals.size());
                for (int i = 0; i < count; i++)
                {
                    typeVarBindings.put(typeVars.get(i)._name(), typeVarVals.get(i));
                }
            }
        }

        ValueSpecification wrappedValue = _E_ValueSpecification.wrap(value, targetGT, null, resolver);

        eval.pushScope();
        eval.currentScope().putAll(typeVarBindings);

        try
        {
            for (int idx = 0; idx < constraints.size(); idx++)
            {
                meta.pure.metamodel.constraint.Constraint c = constraints.get(idx);
                meta.pure.metamodel.function.FunctionDefinition funcDef = c._functionDefinition();
                if (funcDef == null)
                {
                    continue;
                }

                List<ValueSpecification> constraintArgs = new ArrayList<>();
                constraintArgs.add(wrappedValue);
                if (funcDef._parameters() != null && funcDef._parameters().size() > 1)
                {
                    for (int p = 1; p < funcDef._parameters().size(); p++)
                    {
                        String paramName = funcDef._parameters().get(p)._name();
                        ValueSpecification tvVal = typeVarBindings.get(paramName);
                        if (tvVal != null)
                        {
                            constraintArgs.add(tvVal);
                        }
                    }
                }

                ValueSpecification result = eval.evaluateFunctionDefinition(funcDef, constraintArgs);
                Object resultVal = _E_ValueSpecification.unwrap(result);
                if (Boolean.FALSE.equals(resultVal))
                {
                    String constraintName = c._name() != null ? c._name() : String.valueOf(idx);
                    String message = "Constraint :[" + constraintName + "] violated in the Class " + typeName;

                    if (c._messageFunction() != null)
                    {
                        try
                        {
                            List<ValueSpecification> msgArgs = new ArrayList<>();
                            msgArgs.add(wrappedValue);
                            if (c._messageFunction()._parameters() != null && c._messageFunction()._parameters().size() > 1)
                            {
                                for (int p = 1; p < c._messageFunction()._parameters().size(); p++)
                                {
                                    String paramName = c._messageFunction()._parameters().get(p)._name();
                                    ValueSpecification tvVal = typeVarBindings.get(paramName);
                                    if (tvVal != null)
                                    {
                                        msgArgs.add(tvVal);
                                    }
                                }
                            }
                            ValueSpecification msgResult = eval.evaluateFunctionDefinition(c._messageFunction(), msgArgs);
                            Object msgVal = _E_ValueSpecification.unwrap(msgResult);
                            if (msgVal != null)
                            {
                                message += ", Message: " + msgVal;
                            }
                        }
                        catch (Exception ignored)
                        {
                        }
                    }
                    throw new RuntimeException(message);
                }
            }
        }
        finally
        {
            eval.popScope();
        }
    }

    /**
     * Create a new instance of the given class by path, using the same strategy as {@code new}.
     * Tries the direct Impl class, then UserDefined prefix, then falls back to DynamicInstance.
     */
    public static Object createInstanceByPath(String classPath)
    {
        String javaClassName = classPath.replace("::", ".") + "Impl";
        try
        {
            Class<?> implClass = Class.forName(javaClassName);
            return implClass.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            return new DynamicInstance(classPath);
        }
    }

    /**
     * For a parser-built classifier UDGT (from {@code ^Type(...)} syntax), prefer
     * the pre-built canonical {@code GenericType_<TypeName>} anchor from core.pdb
     * when one exists and the UDGT has no type/multiplicity arguments. Mirrors
     * Java's {@code new XxxImpl(model)} ctor pattern, which sets classifier
     * directly to the canonical UDPGT — keeping classifier chains identical
     * between {@code ^} construction (Pure) and Impl constructors (Java).
     *
     * Falls through to the original {@code cgt} when no canonical exists or args
     * are present (e.g. {@code ^Class<T>(...)}).
     */
    public static meta.pure.metamodel.type.generics.GenericType preferCanonicalAnchorPublic(
            meta.pure.metamodel.type.generics.GenericType cgt,
            MetadataAccess resolver)
    {
        return preferCanonicalAnchor(cgt, resolver);
    }

    private static meta.pure.metamodel.type.generics.GenericType preferCanonicalAnchor(
            meta.pure.metamodel.type.generics.GenericType cgt,
            MetadataAccess resolver)
    {
        if (!(cgt instanceof meta.pure.metamodel.type.generics.GenericTypeValue gtv))
        {
            return cgt;
        }
        if (gtv._typeArguments() != null && gtv._typeArguments().notEmpty())
        {
            return cgt;
        }
        if (gtv._multiplicityArguments() != null && gtv._multiplicityArguments().notEmpty())
        {
            return cgt;
        }
        meta.pure.metamodel.type.Type rawType = gtv._type();
        if (!(rawType instanceof meta.pure.metamodel.PackageableElement pe))
        {
            return cgt;
        }
        // Encode the type's full Pure path in the anchor key so user modules
        // whose simple names collide with platform types (e.g. a user
        // `Package` class) don't fall through to the platform anchor and end
        // up classified as the platform type.
        String typePath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
        if (typePath == null || typePath.isEmpty())
        {
            return cgt;
        }
        Object canonical = resolver.getElement("meta::pure::metamodel::type::generics::optimization::GenericType_"
                + typePath.replace("::", "_"));
        if (canonical instanceof meta.pure.metamodel.type.generics.GenericTypeValue canonicalGT)
        {
            return canonicalGT;
        }
        return cgt;
    }


    /**
     * Derive the class path from a classifierGenericType.
     * Used as fallback when the instance has no name/package yet (freshly created via new(GenericType)).
     */
    public static String resolveClassPathFromCGT(meta.pure.metamodel.type.generics.GenericType cgt)
    {
        meta.pure.metamodel.type.Type rawType = _GenericType.type(cgt);
        if (rawType instanceof meta.pure.metamodel.PackageableElement pe)
        {
            String path = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
            if (path != null && !path.isEmpty())
            {
                return path;
            }
        }
        return "Unknown";
    }

    /**
     * Shallow-copy all properties from source to target.
     * For DynamicInstance sources, copies from the value map.
     * For Java class sources, uses metamodel-driven reflection.
     */
    public static void shallowCopyProperties(Object source, Object target,
                                      meta.pure.metamodel.type.generics.GenericType cgt,
                                      MetadataAccess resolver)
    {
        if (source instanceof DynamicInstance diSource)
        {
            if (target instanceof DynamicInstance diTarget)
            {
                // Direct raw-value copy: values in the DI map are already unwrapped.
                // Going through setInstanceProperty/put would double-unwrap VS objects
                // (e.g. a CollectionImpl stored as a meta-level value gets flattened to a List).
                for (Map.Entry<String, Object> entry : diSource.getValues().entrySet())
                {
                    if (entry.getValue() != null)
                    {
                        diTarget.getValues().put(entry.getKey(), entry.getValue());
                    }
                }
            }
            else
            {
                for (Map.Entry<String, Object> entry : diSource.getValues().entrySet())
                {
                    if (entry.getValue() != null)
                    {
                        setInstanceProperty(target, entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        else
        {
            meta.pure.metamodel.type.Type sourceType = cgt != null ? _GenericType.type(cgt) : null;
            if (sourceType == null)
            {
                return;
            }
            List<String> propNames = collectAllPropertyNames(sourceType);
            for (String propName : propNames)
            {
                copyPropertyDirect(source, target, propName, resolver);
            }
        }
    }

    /**
     * Copy a single property value from source to target via reflection.
     */
    private static void copyPropertyDirect(Object source, Object target, String propName, MetadataAccess resolver)
    {
        String methodName = "_" + propName;
        java.lang.reflect.Method getter = PropertyAccessorCache.getterFor(source.getClass(), methodName);
        if (getter == null)
        {
            return;
        }
        try
        {
            Object value = getter.invoke(source);
            if (value == null)
            {
                return;
            }
            if (target instanceof DynamicInstance di)
            {
                // put() auto-unwraps, so pass raw value directly
                di.put(propName, value);
            }
            else
            {
                for (java.lang.reflect.Method setter : PropertyAccessorCache.settersFor(target.getClass(), methodName))
                {
                    try
                    {
                        setter.invoke(target, value);
                        break;
                    }
                    catch (IllegalArgumentException ignored)
                    {
                    }
                }
            }
        }
        catch (ReflectiveOperationException ignored)
        {
        }
    }

    /**
     * Collect all property names from a type and its supertypes.
     */
    private static List<String> collectAllPropertyNames(meta.pure.metamodel.type.Type type)
    {
        List<String> names = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        collectPropertyNamesRecursive(type, names, seen, visited);
        return names;
    }

    private static void collectPropertyNamesRecursive(meta.pure.metamodel.type.Type type,
                                                      List<String> names,
                                                      java.util.Set<String> seen,
                                                      java.util.Set<String> visited)
    {
        String typeId = (type instanceof PackageableElement pe)
                ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe)
                : String.valueOf(System.identityHashCode(type));
        if (!visited.add(typeId))
        {
            return;
        }
        if (type instanceof meta.pure.metamodel.type.Class cls)
        {
            if (cls._properties() != null)
            {
                for (var prop : cls._properties())
                {
                    if (prop._name() != null && seen.add(prop._name()))
                    {
                        names.add(prop._name());
                    }
                }
            }
            if (cls._propertiesFromAssociations() != null)
            {
                for (var prop : cls._propertiesFromAssociations())
                {
                    if (prop._name() != null && seen.add(prop._name()))
                    {
                        names.add(prop._name());
                    }
                }
            }
        }
        if (type._generalizations() != null)
        {
            for (var gen : type._generalizations())
            {
                if (gen._general() != null && _GenericType.type(gen._general()) != null)
                {
                    collectPropertyNamesRecursive(_GenericType.type(gen._general()), names, seen, visited);
                }
            }
        }
    }

    /**
     * Get a property value from any instance type (DynamicInstance or Java class).
     */
    static ValueSpecification getInstanceProperty(Object instance, String propName, MetadataAccess resolver)
    {
        Object value;
        if (instance instanceof DynamicInstance di)
        {
            value = di.get(propName);
        }
        else
        {
            java.lang.reflect.Method getter = PropertyAccessorCache.getterFor(instance.getClass(), "_" + propName);
            if (getter == null)
            {
                return null;
            }
            try
            {
                value = getter.invoke(instance);
            }
            catch (ReflectiveOperationException e)
            {
                return null;
            }
        }

        if (value == null)
        {
            return null;
        }
        if (value instanceof java.util.List<?> list)
        {
            java.util.List<ValueSpecification> wrapped = new java.util.ArrayList<>();
            for (Object item : list)
            {
                wrapped.add(_E_ValueSpecification.wrap(item, null, null, resolver));
            }
            return org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(wrapped, resolver);
        }
        return _E_ValueSpecification.wrap(value, null, null, resolver);
    }

    /**
     * Get all property entries from any instance type.
     */
    private static List<Map.Entry<String, Object>> getAllPropertyEntries(
            Object instance, meta.pure.metamodel.type.generics.GenericType cgt, MetadataAccess resolver)
    {
        List<Map.Entry<String, Object>> entries = new ArrayList<>();
        if (instance instanceof DynamicInstance di)
        {
            for (Map.Entry<String, Object> entry : di.getValues().entrySet())
            {
                if (entry.getValue() != null)
                {
                    entries.add(entry);
                }
            }
        }
        else
        {
            meta.pure.metamodel.type.Type type = cgt != null ? _GenericType.type(cgt) : null;
            if (type != null)
            {
                for (String propName : collectAllPropertyNames(type))
                {
                    ValueSpecification vs = getInstanceProperty(instance, propName, resolver);
                    if (vs != null)
                    {
                        entries.add(Map.entry(propName, vs));
                    }
                }
            }
        }
        return entries;
    }

    /**
     * Reject a user-supplied {@code classifierGenericType=...} key expression
     * whose raw type doesn't match the system-derived classifier. The raw type
     * is the metaclass identity ({@code LA_Person}, {@code RelationType}, ...)
     * and is system-managed — changing it would corrupt type integrity (covered
     * by spec test {@code testCantSetClassifierGenericType}). Type-arguments,
     * multiplicity-arguments and type-variable values can be user-supplied
     * without changing identity, so an override whose raw type matches the
     * system-set one is allowed (this is what the {@code RelationTypeCompiler}
     * self-classifier wiring relies on).
     */
    static void validateClassifierOverride(Object proposed, Object instance)
    {
        meta.pure.metamodel.type.generics.GenericType currentCgt = null;
        if (instance instanceof meta.pure.metamodel.type.Any any)
        {
            currentCgt = any._classifierGenericType();
        }
        else if (instance instanceof DynamicInstance di)
        {
            currentCgt = di.getClassifierGenericType();
        }
        meta.pure.metamodel.type.Type expected = currentCgt != null ? _GenericType.type(currentCgt) : null;
        meta.pure.metamodel.type.Type proposedType = proposed instanceof meta.pure.metamodel.type.generics.GenericTypeValue gtv ? _GenericType.type(gtv) : null;
        if (expected != null && proposedType != null && samePackageableElement(expected, proposedType))
        {
            return;
        }
        // Allow proposed to be a *subtype* of expected — covers the enum-value
        // pattern where {@code ^Enum(...)} is intentionally re-classified as a
        // specific user enumeration. Walks generalizations directly; safer than
        // a {@code TypeCache} lookup since freshly-built types may not be in
        // the cache yet. Mirrors {@code NewWithKeysNode.isSubtypeViaGeneralizations}
        // on the Truffle side.
        if (expected != null && proposedType != null && isSubtypeViaGeneralizations(proposedType, expected))
        {
            return;
        }
        // Compile-pure pass-1 pattern: proposedType is a {@link TempCompilerPointer}
        // whose target is being built right now and isn't yet in the elementMap
        // (e.g. {@code buildEnumerationSkeleton} wires an enum value's classifier
        // as a pointer to the enum it's about to register). The pointer carries
        // the path the producer intends; the post-processor canonicalises it at
        // module construction. Accept — a pointer is compile-pure-internal, not
        // a user-supplied raw type swap.
        if (proposedType instanceof meta.pure.metamodel.pointer.TempCompilerPointer)
        {
            return;
        }
        String expectedName = expected instanceof meta.pure.metamodel.PackageableElement peE
                ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(peE) : "<unknown>";
        String proposedName = proposedType instanceof meta.pure.metamodel.PackageableElement peP
                ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(peP) : "<unknown>";
        throw new RuntimeException("Cannot change classifierGenericType.type from '" + expectedName + "' to '" + proposedName
                + "'. The classifier's raw type is system-managed (derived from the instance's metaclass)"
                + " — only typeArguments, multiplicityArguments and typeVariableValues are user-customizable."
                + " Use meta::pure::functions::lang::new(GenericType[1]) to construct an instance with a different metaclass.");
    }

    private static boolean samePackageableElement(meta.pure.metamodel.type.Type a, meta.pure.metamodel.type.Type b)
    {
        if (a == b)
        {
            return true;
        }
        if (a instanceof meta.pure.metamodel.PackageableElement peA && b instanceof meta.pure.metamodel.PackageableElement peB)
        {
            return org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(peA).equals(
                    org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(peB));
        }
        return false;
    }

    /**
     * Walk {@code sub}'s generalization chain looking for {@code sup} by path.
     * Bounded depth so a pathological cycle terminates. Used by
     * {@link #validateClassifierOverride} to allow {@code ^Enum(...)} to be
     * re-classified as a specific user enumeration (subtype of Enum).
     */
    private static boolean isSubtypeViaGeneralizations(meta.pure.metamodel.type.Type sub, meta.pure.metamodel.type.Type sup)
    {
        if (samePackageableElement(sub, sup)) return true;
        meta.pure.metamodel.type.Type current = sub;
        for (int depth = 0; depth < 32 && current != null; depth++)
        {
            org.eclipse.collections.api.RichIterable<? extends meta.pure.metamodel.relationship.Generalization> gens = current._generalizations();
            if (gens == null || gens.isEmpty()) return false;
            meta.pure.metamodel.type.Type next = null;
            for (meta.pure.metamodel.relationship.Generalization g : gens)
            {
                meta.pure.metamodel.type.generics.GenericType generalGT = g._general();
                meta.pure.metamodel.type.Type parent = generalGT != null ? _GenericType.type(generalGT) : null;
                if (parent == null) continue;
                if (samePackageableElement(parent, sup)) return true;
                if (next == null) next = parent;
            }
            current = next;
        }
        return false;
    }

    static void processKeyExpression(Object ke, Object instance,
                                     List<Map.Entry<String, Object>> keyValues,
                                     MetadataAccess resolver)
    {
        if (ke instanceof DynamicInstance di)
        {
            // name is stored as an Object; unwrap to get the String key
            Object nameVS = di.get("name");
            Object nameObj = _E_ValueSpecification.unwrap(nameVS);
            String key = nameObj != null ? nameObj.toString() : null;
            // DI stores raw values; wrap back for the execution stack
            Object rawExpr = di.get("expression");
            Object value = _E_ValueSpecification.wrap(rawExpr, null, null, resolver);

            if (key != null)
            {
                if ("classifierGenericType".equals(key))
                {
                    // Allow override only when the proposed classifier wraps the
                    // same raw type as the system-derived one — this preserves
                    // spec test `testCantSetClassifierGenericType` while letting
                    // legitimate self-classifier wiring through (e.g. setting
                    // `RelationType.classifierGenericType.typeArguments[0]` to
                    // `~` so it points back at the instance).
                    validateClassifierOverride(rawExpr, instance);
                    if (rawExpr instanceof meta.pure.metamodel.type.generics.GenericTypeValue gtv)
                    {
                        if (instance instanceof meta.pure.metamodel.type.Any any)
                        {
                            any._classifierGenericType(gtv);
                        }
                        else if (instance instanceof DynamicInstance dynInstance)
                        {
                            dynInstance.setClassifierGenericType(gtv);
                        }
                    }
                    return;
                }
                // For DynamicInstance targets with [0..1] properties, pass the raw
                // expression value to avoid the wrap→unwrap cycle that flattens Collections.
                setInstanceProperty(instance, key, value);
                if (value != null)
                {
                    keyValues.add(Map.entry(key, value));
                }
            }
        }
    }

    static void applyCopyKeyExpressionWithDotPath(Object copy, DynamicInstance keyExpr,
                                                  Map<String, DynamicInstance> deepCopied,
                                                  MetadataAccess resolver)
    {
        Object nameVS = keyExpr.get("name");
        Object nameObj = _E_ValueSpecification.unwrap(nameVS);
        String fullKey = nameObj != null ? nameObj.toString() : null;
        // DI stores raw values; wrap back for the execution stack
        Object value = _E_ValueSpecification.wrap(keyExpr.get("expression"), null, null, resolver);
        if (fullKey == null)
        {
            return;
        }

        if (fullKey.indexOf('.') >= 0)
        {
            // Deep-path copy keys (e.g. `^$pierre(firm.legalName='X')`) are no
            // longer supported. They created an implicit deep-copy of the
            // intermediate, which is confusing and obscures whether `firm` is
            // shared or duplicated. Users must instantiate the nested value
            // inline: `^$pierre(firm = ^LA_Firm(legalName='X', ...))`.
            throw new RuntimeException("Deep-path copy keys (e.g. '" + fullKey
                    + "') are not supported. Instantiate the nested value inline: `"
                    + fullKey.substring(0, fullKey.indexOf('.')) + " = ^Type(...)`.");
        }
        Object addVS = keyExpr.get("add");
        if (Boolean.TRUE.equals(_E_ValueSpecification.unwrap(addVS)))
        {
            appendToProperty(copy, fullKey, value, resolver);
        }
        else
        {
            setInstanceProperty(copy, fullKey, value);
        }
    }

    static void appendToProperty(Object instance, String key, Object value,
                                 MetadataAccess resolver)
    {
        // Read existing raw value
        Object existing;
        if (instance instanceof DynamicInstance di)
        {
            existing = di.get(key);
        }
        else
        {
            java.lang.reflect.Method getter = PropertyAccessorCache.getterFor(instance.getClass(), "_" + key);
            if (getter == null)
            {
                existing = null;
            }
            else
            {
                try
                {
                    existing = getter.invoke(instance);
                }
                catch (ReflectiveOperationException e)
                {
                    existing = null;
                }
            }
        }

        // Existing items are already at the right level from previous appends — don't re-unwrap
        List<Object> list = new ArrayList<>();
        if (existing instanceof java.util.List<?> l)
        {
            list.addAll(l);
        }
        else if (existing != null)
        {
            list.add(existing);
        }

        Object unwrappedValue = _E_ValueSpecification.unwrap(value);
        if (unwrappedValue instanceof java.util.List<?> l)
        {
            list.addAll(l);
        }
        else if (unwrappedValue != null)
        {
            list.add(unwrappedValue);
        }

        // Store back
        if (instance instanceof DynamicInstance di)
        {
            di.put(key, list);
        }
        else
        {
            setPropertyViaReflection(instance, key, org.eclipse.collections.api.factory.Lists.mutable.withAll(list));
        }
    }

    /**
     * Throws if any association property on {@code classPath} carries a value
     * that was NOT created within the current new/copy expression. Called from
     * copy_T_1__KeyExpression_MANY__T_1_ before {@link #setReverseAssociationPointers}.
     *
     * The rule enforces immutability of any pre-existing instance: setting
     * {@code bob.firm = $firmX} (an existing firm) would otherwise mutate
     * {@code $firmX.employees} via the bidirectional binding. The caller must
     * either pass a freshly-created value (`firm = ^Firm(...)`, `firm = ^$x()`,
     * `firm = new(...)`) or set the property to empty (`firm = []`).
     */
    public static void verifyAssocPropsAreFresh(String classPath,
                                                List<Map.Entry<String, Object>> allProps,
                                                ValueSpecificationEvaluator eval,
                                                MetadataAccess resolver)
    {
        if (resolver == null || allProps.isEmpty())
        {
            return;
        }
        PackageableElement classElement = resolver.getElement(classPath);
        if (!(classElement instanceof meta.pure.metamodel.type.Class cls))
        {
            return;
        }
        List<meta.pure.metamodel.function.property.Property> allAssocProps = new ArrayList<>();
        collectAssocPropsFromHierarchy(cls, allAssocProps, new java.util.HashSet<>());
        if (allAssocProps.isEmpty())
        {
            return;
        }
        java.util.Set<String> assocPropNames = new java.util.HashSet<>();
        for (meta.pure.metamodel.function.property.Property p : allAssocProps)
        {
            if (p != null && p._name() != null)
            {
                assocPropNames.add(p._name());
            }
        }
        for (Map.Entry<String, Object> kv : allProps)
        {
            String propName = kv.getKey();
            if (!assocPropNames.contains(propName))
            {
                continue;
            }
            Object value = kv.getValue();
            if (value == null)
            {
                continue;
            }
            Object raw = _E_ValueSpecification.unwrap(value);
            if (raw == null)
            {
                continue;
            }
            if (raw instanceof List<?> listVal)
            {
                for (Object item : listVal)
                {
                    Object itemRaw = item instanceof ValueSpecification vs ? _E_ValueSpecification.unwrap(vs) : item;
                    if (itemRaw != null && !eval.isFreshInCurrentScope(itemRaw))
                    {
                        throw new RuntimeException(immutabilityMessage(classPath, propName));
                    }
                }
            }
            else
            {
                if (!eval.isFreshInCurrentScope(raw))
                {
                    throw new RuntimeException(immutabilityMessage(classPath, propName));
                }
            }
        }
    }

    private static String immutabilityMessage(String classPath, String propName)
    {
        return "Immutability violation: association property '" + propName + "' on '"
                + classPath + "' must be instantiated within the new/copy expression. "
                + "Use `" + propName + " = ^Type(...)`, `" + propName + " = ^$x()`, or `"
                + propName + " = []`.";
    }

    public static void setReverseAssociationPointers(Object instance, String classPath,
                                                     List<Map.Entry<String, Object>> keyValues,
                                                     MetadataAccess resolver)
    {
        if (resolver == null || keyValues.isEmpty())
        {
            return;
        }

        PackageableElement classElement = resolver.getElement(classPath);
        if (!(classElement instanceof meta.pure.metamodel.type.Class cls))
        {
            return;
        }

        List<meta.pure.metamodel.function.property.Property> allAssocProps = new ArrayList<>();
        collectAssocPropsFromHierarchy(cls, allAssocProps, new java.util.HashSet<>());
        if (allAssocProps.isEmpty())
        {
            return;
        }

        for (Map.Entry<String, Object> kv : keyValues)
        {
            String propName = kv.getKey();
            Object propVS = kv.getValue();
            if (propVS == null)
            {
                continue;
            }
            Object propValue = _E_ValueSpecification.unwrap(propVS);
            if (propValue == null)
            {
                continue;
            }

            for (meta.pure.metamodel.function.property.Property assocProp : allAssocProps)
            {
                if (assocProp != null && propName.equals(assocProp._name()))
                {
                    meta.pure.metamodel.SimplePropertyOwner owner = assocProp._owner();
                    if (owner != null && owner._properties() != null)
                    {
                        for (meta.pure.metamodel.function.property.Property otherProp : owner._properties())
                        {
                            if (otherProp != null && !propName.equals(otherProp._name()))
                            {
                                String reversePropName = otherProp._name();
                                setReversePointerOnTarget(propValue, reversePropName,
                                        _E_ValueSpecification.wrap(instance, null, null, resolver),
                                        resolver);
                            }
                        }
                    }
                    break;
                }
            }
        }
    }

    private static void collectAssocPropsFromHierarchy(meta.pure.metamodel.type.Class cls,
                                                       List<meta.pure.metamodel.function.property.Property> result,
                                                       java.util.Set<String> visited)
    {
        collectAssocPropsFromHierarchyInner(cls, result, visited, new java.util.HashSet<>());
    }

    private static void collectAssocPropsFromHierarchyInner(meta.pure.metamodel.type.Class cls,
                                                            List<meta.pure.metamodel.function.property.Property> result,
                                                            java.util.Set<String> visited,
                                                            java.util.Set<String> seenPropNames)
    {
        String classId;
        if (cls instanceof PackageableElement pe)
        {
            classId = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
        }
        else
        {
            classId = cls._name();
        }
        if (classId == null || classId.isEmpty() || !visited.add(classId))
        {
            return;
        }

        org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.function.property.Property> assocProps =
                cls._propertiesFromAssociations();
        if (assocProps != null)
        {
            for (meta.pure.metamodel.function.property.Property p : assocProps)
            {
                if (p != null && p._name() != null && seenPropNames.add(p._name()))
                {
                    result.add(p);
                }
            }
        }

        if (cls._generalizations() != null)
        {
            for (meta.pure.metamodel.relationship.Generalization gen : cls._generalizations())
            {
                if (gen != null && gen._general() != null)
                {
                    meta.pure.metamodel.type.Type superType = _GenericType.type(gen._general());
                    if (superType instanceof meta.pure.metamodel.type.Class superCls)
                    {
                        collectAssocPropsFromHierarchyInner(superCls, result, visited, seenPropNames);
                    }
                }
            }
        }
    }

    private static void setReversePointerOnTarget(Object target, String reversePropName,
                                                  ValueSpecification instanceVS,
                                                  MetadataAccess resolver)
    {
        if (target instanceof List<?> targets)
        {
            for (Object t : targets)
            {
                setReversePointerOnTarget(t, reversePropName, instanceVS, resolver);
            }
        }
        else
        {
            appendToProperty(target, reversePropName, instanceVS, resolver);
        }
    }

    public static Object createInstance(String classPath, GenericTypeAndMultiplicityHolder gtmh2)
    {
        String javaClassName = classPath.replace("::", ".") + "Impl";
        try
        {
            Class<?> implClass = Class.forName(javaClassName);
            Any any = (Any) implClass.getDeclaredConstructor().newInstance();
            GenericTypeValue cgt = (GenericTypeValue) _GenericType.typeArguments(gtmh2._genericType()).getFirst();
            assertNotSelf(any, cgt, "createInstance(" + classPath + ")");
            any._classifierGenericType(cgt);
            return any;
        }
        catch (Exception e)
        {
            return new DynamicInstance(classPath);
        }
    }

    /**
     * Diagnostic: explode (with a stack trace) when a freshly-built instance
     * is about to be classified by its own self. The runtime invariant is
     * that the new instance's classifier must be a *separate* anchor — never
     * itself — except for the canonical UDGT meta-class anchor built via
     * `_GenericType.buildUserDefinedGenericType`.
     */
    private static void assertNotSelf(Object instance, Object cgt, String site)
    {
        if (instance == cgt)
        {
            throw new IllegalStateException("[SELF-CLASSIFIER BUG] " + site
                    + " is about to set _classifierGenericType to the new instance itself ("
                    + instance.getClass().getName() + "). This isn't a canonical anchor; "
                    + "the holder GT's typeArguments[0] should reference a separate UDGT.");
        }
    }

    static void setInstanceProperty(Object instance, String key, Object value)
    {
        // DynamicInstance: put() auto-unwraps ValueSpecification wrappers
        if (instance instanceof DynamicInstance di)
        {
            di.put(key, value);
            return;
        }

        // Compiled Java targets: unwrap and set via reflection
        if (value instanceof meta.pure.metamodel.valuespecification.Collection col)
        {
            if (col._values().isEmpty())
            {
                // Empty collection assignment:
                //   [*]    → clear to empty list
                //   [0..1] → set to null
                //   [1]    → error (cannot clear a required property)
                setEmptyValue(instance, key);
                return;
            }
            org.eclipse.collections.api.list.MutableList<Object> items = col._values().collect(_E_ValueSpecification::unwrap);
            setPropertyViaReflection(instance, key, items);
            return;
        }

        Object rawValue = value != null ? _E_ValueSpecification.unwrap(value) : null;
        setPropertyViaReflection(instance, key, rawValue);
    }

    private static void setPropertyViaReflection(Object instance, String key, Object rawValue)
    {
        for (java.lang.reflect.Method method : PropertyAccessorCache.settersFor(instance.getClass(), "_" + key))
        {
            try
            {
                method.invoke(instance, rawValue);
                return;
            }
            catch (Exception e)
            {
                Class<?> _paramType = method.getParameterTypes()[0];
                if (org.eclipse.collections.api.RichIterable.class.isAssignableFrom(_paramType) || java.util.Collection.class.isAssignableFrom(_paramType))
                {
                    try
                    {
                        if (rawValue instanceof List<?> list)
                        {
                            method.invoke(instance, org.eclipse.collections.api.factory.Lists.mutable.withAll(list));
                        }
                        else if (rawValue != null)
                        {
                            method.invoke(instance, org.eclipse.collections.api.factory.Lists.mutable.with(rawValue));
                        }
                        else
                        {
                            method.invoke(instance, org.eclipse.collections.api.factory.Lists.mutable.empty());
                        }
                        return;
                    }
                    catch (Exception ignored)
                    {
                    }
                }
                throw new RuntimeException("Failed to set property '" + key + "' on " + instance.getClass().getSimpleName() + " value type: " + (rawValue == null ? "null" : rawValue.getClass().getSimpleName()), e);
            }
        }
    }

    /**
     * Handle empty collection assignment on compiled Java targets.
     * <ul>
     *   <li>[*] property (setter takes Collection/RichIterable) → clear to empty list</li>
     *   <li>[0..1] property (setter takes nullable scalar) → set to null</li>
     *   <li>[1] property (setter takes non-nullable scalar) → throw</li>
     * </ul>
     */
    private static void setEmptyValue(Object instance, String key)
    {
        for (java.lang.reflect.Method method : PropertyAccessorCache.settersFor(instance.getClass(), "_" + key))
        {
            Class<?> paramType = method.getParameterTypes()[0];
            if (org.eclipse.collections.api.RichIterable.class.isAssignableFrom(paramType)
                    || java.util.Collection.class.isAssignableFrom(paramType))
            {
                // [*] property → clear to empty list
                setPropertyViaReflection(instance, key, org.eclipse.collections.api.factory.Lists.mutable.empty());
            }
            else if (!paramType.isPrimitive())
            {
                // [0..1] property (nullable reference type) → set to null
                setPropertyViaReflection(instance, key, (Object) null);
            }
            else
            {
                // [1] property (primitive: long, boolean, etc.) → cannot clear
                throw new RuntimeException("Cannot set property '" + key + "' on "
                        + instance.getClass().getSimpleName()
                        + " to an empty collection — the property has multiplicity [1]");
            }
            return;
        }
    }
}
