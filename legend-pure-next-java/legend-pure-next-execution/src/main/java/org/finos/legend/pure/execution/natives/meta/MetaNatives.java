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
                return false;
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
                return value != null;
            }

            // Get the value's type from VS genericType
            meta.pure.metamodel.type.Type valueType = _E_ValueSpecification.getValueOriginalType(args.get(0));

            // Check type hierarchy
            if (valueType == null)
            {
                return false;
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

        // genericType(Any[*]) : GenericType[1]
        natives.put("genericType_Any_MANY__GenericType_1_", (args, eval, genericType, multiplicity) ->
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
            String classPath = "Unknown";
            if (args.get(0) instanceof GenericTypeAndMultiplicityHolder gtmh
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

            if (args.get(0) instanceof GenericTypeAndMultiplicityHolder gtmh2)
            {
                Object instance = createInstance(classPath, gtmh2);

                if (instance instanceof DynamicInstance di
                        && gtmh2._genericType() != null
                        && _GenericType.typeArguments(gtmh2._genericType()) != null
                        && _GenericType.typeArguments(gtmh2._genericType()).notEmpty())
                {
                    di.setClassifierGenericType(_GenericType.typeArguments(gtmh2._genericType()).getFirst());
                }

                // Validate constraints on the class after construction
                if (args.get(0) instanceof GenericTypeAndMultiplicityHolder gtmh3
                        && gtmh3._genericType() != null
                        && _GenericType.typeArguments(gtmh3._genericType()) != null
                        && _GenericType.typeArguments(gtmh3._genericType()).notEmpty())
                {
                    meta.pure.metamodel.type.generics.GenericType heldGT = _GenericType.typeArguments(gtmh3._genericType()).getFirst();
                    meta.pure.metamodel.type.Type targetType = _GenericType.type(heldGT);
                    if (targetType instanceof meta.pure.metamodel.extension.ElementWithConstraints)
                    {
                        validateConstraints(targetType, heldGT, instance, eval, resolver);
                    }
                }

                return _E_ValueSpecification.wrap(instance, genericType, multiplicity, resolver);
            }
            throw new RuntimeException("Not possible");
        });

        // new(GenericTypeAndMultiplicityHolder[1], KeyExpression[*]) : T[1] — construct from key expressions
        natives.put("new_GenericTypeAndMultiplicityHolder_1__KeyExpression_MANY__T_1_", (args, eval, genericType, multiplicity) ->
        {
            String classPath = "Unknown";
            if (args.get(0) instanceof GenericTypeAndMultiplicityHolder gtmh
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
            if (args.get(0) instanceof GenericTypeAndMultiplicityHolder gtmh2)
            {
                Object instance = createInstance(classPath, gtmh2);
                if (instance instanceof DynamicInstance di
                        && gtmh2._genericType() != null
                        && _GenericType.typeArguments(gtmh2._genericType()) != null
                        && _GenericType.typeArguments(gtmh2._genericType()).notEmpty())
                {
                    di.setClassifierGenericType(_GenericType.typeArguments(gtmh2._genericType()).getFirst());
                }

                // Collect key/value pairs for reverse pointer processing
                List<Map.Entry<String, Object>> keyValues = new ArrayList<>();
                Object keyExprsRaw = _E_ValueSpecification.unwrap(args.get(1));
                if (keyExprsRaw instanceof List<?> keyExprs)
                {
                    for (Object ke : keyExprs)
                    {
                        processKeyExpression(ke, instance, keyValues);
                    }
                }
                else
                {
                    processKeyExpression(keyExprsRaw, instance, keyValues);
                }

                // Set reverse association pointers
                setReverseAssociationPointers(instance, classPath, keyValues, resolver);

                // Validate constraints on the class after construction
                if (args.get(0) instanceof GenericTypeAndMultiplicityHolder gtmh3
                        && gtmh3._genericType() != null
                        && _GenericType.typeArguments(gtmh3._genericType()) != null
                        && _GenericType.typeArguments(gtmh3._genericType()).notEmpty())
                {
                    meta.pure.metamodel.type.generics.GenericType heldGT = _GenericType.typeArguments(gtmh3._genericType()).getFirst();
                    meta.pure.metamodel.type.Type targetType = _GenericType.type(heldGT);
                    if (targetType instanceof meta.pure.metamodel.extension.ElementWithConstraints)
                    {
                        validateConstraints(targetType, heldGT, instance, eval, resolver);
                    }
                }

                return _E_ValueSpecification.wrap(instance, genericType, multiplicity, resolver);
            }
            throw new RuntimeException("Not possible");
        });

        // keyExpression — creates a key-value pair
        NativeImpl keyExprFn = (args, eval, genericType, multiplicity) ->
        {
            Object key = _E_ValueSpecification.unwrap(args.get(0));
            Object value = args.get(1);
            if (value instanceof meta.pure.metamodel.valuespecification.AtomicValue av && !(av._value() instanceof ValueSpecification))
            {
                value = _E_ValueSpecification.unwrap((ValueSpecification) value);
            }
            DynamicInstance keyExpr = new DynamicInstance("meta::pure::functions::lang::KeyExpression");
            keyExpr.put("name", key);
            keyExpr.put("expression", value);
            if (args.size() > 2)
            {
                Object addFlag = _E_ValueSpecification.unwrap(args.get(2));
                if (Boolean.TRUE.equals(addFlag))
                {
                    keyExpr.put("add", true);
                }
            }
            return _E_ValueSpecification.wrap(keyExpr, genericType, multiplicity, resolver);
        };
        natives.put("keyExpression_String_1__Any_MANY__KeyExpression_1_", keyExprFn);
        natives.put("keyExpression_String_1__Any_MANY__Boolean_1__KeyExpression_1_", keyExprFn);

        // copy(T[1]) : T[1] — simple copy with no overrides
        natives.put("copy_T_1__T_1_", (args, eval, genericType, multiplicity) ->
        {
            Object original = _E_ValueSpecification.unwrap(args.get(0));
            DynamicInstance copy;
            if (original instanceof DynamicInstance di)
            {
                copy = new DynamicInstance(di.getClassPath());
                copy.getValues().putAll(di.getValues());
                copy.setClassifierGenericType(di.getClassifierGenericType());
            }
            else if (original instanceof PackageableElement pe)
            {
                String classPath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
                copy = new DynamicInstance(classPath);
                copy.setClassifierGenericType(pe._classifierGenericType());
                copyPackageableElementProperties(pe, copy);
            }
            else
            {
                copy = new DynamicInstance("Unknown");
            }
            return _E_ValueSpecification.wrap(copy, genericType, multiplicity, resolver);
        });

        // copy(T[1], KeyExpression[*]) : T[1] — shallow copy with property overrides
        natives.put("copy_T_1__KeyExpression_MANY__T_1_", (args, eval, genericType, multiplicity) ->
        {
            Object original = _E_ValueSpecification.unwrap(args.get(0));
            DynamicInstance copy;
            String classPath;
            if (original instanceof DynamicInstance di)
            {
                classPath = di.getClassPath();
                copy = new DynamicInstance(classPath);
                copy.getValues().putAll(di.getValues());
                copy.setClassifierGenericType(di.getClassifierGenericType());
            }
            else if (original instanceof PackageableElement pe)
            {
                classPath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
                copy = new DynamicInstance(classPath);
                copy.setClassifierGenericType(pe._classifierGenericType());
            }
            else
            {
                throw new RuntimeException("W");
            }

            // Track deep-copied nested objects
            Map<String, DynamicInstance> deepCopied = new HashMap<>();

            // Apply key expression overrides
            Object keyExprsRaw = _E_ValueSpecification.unwrap(args.get(1));
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
                    applyCopyKeyExpressionWithDotPath(copy, diKe, deepCopied);
                }
            }

            // Collect all properties on the copy for reverse pointer processing
            List<Map.Entry<String, Object>> allProps = new ArrayList<>();
            for (Map.Entry<String, Object> entry : copy.getValues().entrySet())
            {
                if (entry.getValue() != null)
                {
                    allProps.add(Map.entry(entry.getKey(), entry.getValue()));
                }
            }

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
                    if (copySet.contains(val))
                    {
                        continue;
                    }
                    if (val instanceof List<?> listVal && listVal.stream().anyMatch(copySet::contains))
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

            return _E_ValueSpecification.wrap(copy, genericType, multiplicity, resolver);
        });

        // cast(Any[m], T[1]) : T[m]
        natives.put("cast_Any_m__GenericTypeAndMultiplicityHolder_1__T_m_", (args, eval, genericType, multiplicity) ->
        {
            Object value = _E_ValueSpecification.unwrap(args.get(0));
            if (value == null)
            {
                return null;
            }

            ValueSpecification targetVs = args.get(1);
            meta.pure.metamodel.type.Type targetType = null;
            Object targetValue = _E_ValueSpecification.unwrap(targetVs);
            if (targetValue instanceof meta.pure.metamodel.type.Type t)
            {
                targetType = t;
            }
            else if (targetVs instanceof GenericTypeAndMultiplicityHolder gtmh
                    && gtmh._genericType() != null
                    && _GenericType.typeArguments(gtmh._genericType()) != null
                    && _GenericType.typeArguments(gtmh._genericType()).notEmpty())
            {
                targetType = _GenericType.type(_GenericType.typeArguments(gtmh._genericType()).getFirst());
            }
            else if (targetVs._genericType() != null)
            {
                targetType = _GenericType.type(targetVs._genericType());
            }

            if (targetType instanceof PackageableElement targetPe)
            {
                String targetPath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(targetPe);

                if (!"meta::pure::metamodel::type::Any".equals(targetPath))
                {
                    meta.pure.metamodel.type.Type sourceType = _E_ValueSpecification.getValueOriginalType(args.get(0));
                    // TypeParameter/MultiplicityParameter values are compiler-resolved generics;
                    // skip runtime cast validation since the compiler already verified compatibility
                    if (value instanceof meta.pure.metamodel.type.generics.TypeParameter
                            || value instanceof meta.pure.metamodel.multiplicity.MultiplicityParameter)
                    {
                        // pass through — compiler already validated
                    }
                    else if (sourceType instanceof PackageableElement sourcePe)
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
            if (targetType instanceof meta.pure.metamodel.extension.ElementWithConstraints)
            {
                meta.pure.metamodel.type.generics.GenericType heldGT = null;
                if (targetVs instanceof GenericTypeAndMultiplicityHolder gtmh2
                        && gtmh2._genericType() != null
                        && _GenericType.typeArguments(gtmh2._genericType()) != null
                        && _GenericType.typeArguments(gtmh2._genericType()).notEmpty())
                {
                    heldGT = _GenericType.typeArguments(gtmh2._genericType()).getFirst();
                }
                validateConstraints(targetType, heldGT, value, eval, resolver);
            }

            return _E_ValueSpecification.wrap(value, genericType, multiplicity, resolver);
        });

        // evaluateAndDeactivate — passthrough
        NativeImpl evalAndDeactivate = (args, eval, genericType, multiplicity) -> _E_ValueSpecification.unwrap(args.get(0));
        natives.put("evaluateAndDeactivate_Any_m__Any_m_", evalAndDeactivate);
        natives.put("evaluateAndDeactivate", evalAndDeactivate);
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    static void validateConstraints(meta.pure.metamodel.type.Type type,
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

    private static void copyPackageableElementProperties(PackageableElement pe, DynamicInstance copy)
    {
        if (pe._name() != null)
        {
            copy.put("name", pe._name());
        }
        if (pe._package() != null)
        {
            copy.put("package", pe._package());
        }
        if (pe instanceof meta.pure.metamodel.function.FunctionDefinition fd)
        {
            if (fd._expressionSequence() != null)
            {
                copy.put("expressionSequence", new ArrayList<>(fd._expressionSequence()));
            }
            if (fd._parameters() != null)
            {
                copy.put("parameters", new ArrayList<>(fd._parameters()));
            }
            if (fd._classifierGenericType() != null)
            {
                copy.put("classifierGenericType", fd._classifierGenericType());
            }
        }
    }

    static void processKeyExpression(Object ke, Object instance, List<Map.Entry<String, Object>> keyValues)
    {
        if (ke instanceof DynamicInstance di)
        {
            Object nameObj = di.get("name");
            if (nameObj instanceof ValueSpecification vs)
            {
                nameObj = _E_ValueSpecification.unwrap(vs);
            }
            String key = nameObj != null ? nameObj.toString() : null;
            Object value = di.get("expression");

            if (key != null)
            {
                setInstanceProperty(instance, key, value);
                if (value != null)
                {
                    keyValues.add(Map.entry(key, value));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void applyCopyKeyExpressionWithDotPath(DynamicInstance copy, DynamicInstance keyExpr,
                                                  Map<String, DynamicInstance> deepCopied)
    {
        Object nameObj = keyExpr.get("name");
        if (nameObj instanceof ValueSpecification vs)
        {
            nameObj = _E_ValueSpecification.unwrap(vs);
        }
        String fullKey = nameObj != null ? nameObj.toString() : null;
        Object value = keyExpr.get("expression");
        if (fullKey == null)
        {
            return;
        }

        int dotIdx = fullKey.indexOf('.');
        if (dotIdx < 0)
        {
            if (Boolean.TRUE.equals(keyExpr.get("add")))
            {
                appendToProperty(copy, fullKey, value);
            }
            else
            {
                setInstanceProperty(copy, fullKey, value);
            }
        }
        else
        {
            String topProp = fullKey.substring(0, dotIdx);
            String restKey = fullKey.substring(dotIdx + 1);

            DynamicInstance nestedCopy = deepCopied.get(topProp);
            if (nestedCopy == null)
            {
                Object existing = copy.get(topProp);
                if (existing instanceof DynamicInstance existingDi)
                {
                    nestedCopy = new DynamicInstance(existingDi.getClassPath());
                    nestedCopy.getValues().putAll(existingDi.getValues());
                    if (existingDi.getClassifierGenericType() != null)
                    {
                        nestedCopy.setClassifierGenericType(existingDi.getClassifierGenericType());
                    }
                }
                else
                {
                    nestedCopy = new DynamicInstance("Unknown");
                }
                deepCopied.put(topProp, nestedCopy);
                copy.put(topProp, nestedCopy);
            }

            DynamicInstance syntheticKe = new DynamicInstance("KeyExpression");
            syntheticKe.put("name", restKey);
            syntheticKe.put("expression", value);
            if (keyExpr.get("add") != null)
            {
                syntheticKe.put("add", keyExpr.get("add"));
            }
            applyCopyKeyExpressionWithDotPath(nestedCopy, syntheticKe, deepCopied);
        }
    }

    @SuppressWarnings("unchecked")
    static void appendToProperty(DynamicInstance di, String key, Object value)
    {
        if (value instanceof ValueSpecification vs)
        {
            value = _E_ValueSpecification.unwrap(vs);
        }
        Object existing = di.get(key);
        List<Object> list;
        if (existing instanceof List)
        {
            list = new ArrayList<>((List<Object>) existing);
        }
        else if (existing != null)
        {
            list = new ArrayList<>();
            list.add(existing);
        }
        else
        {
            list = new ArrayList<>();
        }
        if (value instanceof List)
        {
            list.addAll((List<Object>) value);
        }
        else
        {
            list.add(value);
        }
        di.put(key, list);
    }

    @SuppressWarnings("unchecked")
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
            Object propValue = kv.getValue();
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
                                setReversePointerOnTarget(propValue, reversePropName, instance);
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

    @SuppressWarnings("unchecked")
    private static void setReversePointerOnTarget(Object target, String reversePropName, Object instance)
    {
        if (target instanceof DynamicInstance di)
        {
            Object existing = di.get(reversePropName);
            if (existing instanceof List<?> existingList)
            {
                List<Object> newList = new ArrayList<>((List<Object>) existingList);
                newList.add(instance);
                di.put(reversePropName, newList);
            }
            else if (existing != null)
            {
                List<Object> newList = new ArrayList<>();
                newList.add(existing);
                newList.add(instance);
                di.put(reversePropName, newList);
            }
            else
            {
                di.put(reversePropName, instance);
            }
        }
        else if (target instanceof List<?> targets)
        {
            for (Object t : targets)
            {
                setReversePointerOnTarget(t, reversePropName, instance);
            }
        }
    }

    static Object createInstance(String classPath, GenericTypeAndMultiplicityHolder gtmh2)
    {
        String javaClassName = classPath.replace("::", ".") + "Impl";
        try
        {
            Class<?> implClass = Class.forName(javaClassName);
            Any any = (Any) implClass.getDeclaredConstructor().newInstance();
            any._classifierGenericType(_GenericType.typeArguments(gtmh2._genericType()).getFirst());
            return any;
        }
        catch (ClassNotFoundException e)
        {
            String baseName = classPath.replace("::", ".");
            int lastDot = baseName.lastIndexOf('.');
            String userDefinedClassName = lastDot >= 0
                    ? baseName.substring(0, lastDot + 1) + "UserDefined" + baseName.substring(lastDot + 1) + "Impl"
                    : "UserDefined" + baseName + "Impl";
            try
            {
                Class<?> udClass = Class.forName(userDefinedClassName);
                return udClass.getDeclaredConstructor().newInstance();
            }
            catch (ReflectiveOperationException ignored)
            {
                return new DynamicInstance(classPath);
            }
        }
        catch (ReflectiveOperationException e)
        {
            return new DynamicInstance(classPath);
        }
    }

    static void setInstanceProperty(Object instance, String key, Object value)
    {
        if (instance instanceof DynamicInstance di)
        {
            if (value instanceof ValueSpecification vs)
            {
                value = _E_ValueSpecification.unwrap(vs);
            }
            di.put(key, value);
            return;
        }

        if (value instanceof meta.pure.metamodel.valuespecification.Collection col)
        {
            value = col._values();
        }
        else if (value instanceof ValueSpecification vs)
        {
            value = _E_ValueSpecification.unwrap(vs);
        }

        String setterName = "_" + key;
        for (java.lang.reflect.Method method : instance.getClass().getMethods())
        {
            if (method.getName().equals(setterName) && method.getParameterCount() == 1)
            {
                try
                {
                    method.invoke(instance, value);
                    return;
                }
                catch (Exception e)
                {
                    if (value instanceof List<?> list
                            && org.eclipse.collections.api.list.MutableList.class.isAssignableFrom(method.getParameterTypes()[0]))
                    {
                        try
                        {
                            method.invoke(instance, org.eclipse.collections.api.factory.Lists.mutable.withAll(list));
                            return;
                        }
                        catch (Exception ignored)
                        {
                        }
                    }
                    throw new RuntimeException("Failed to set property '" + key + "' on " + instance.getClass().getSimpleName(), e);
                }
            }
        }
    }
}
