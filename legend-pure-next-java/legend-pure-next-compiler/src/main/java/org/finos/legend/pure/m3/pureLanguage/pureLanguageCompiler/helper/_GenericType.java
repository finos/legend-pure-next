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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.Inferred;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.relation.GenericTypeOperation;
import meta.pure.metamodel.relationship.Generalization;
import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.InferredGenericTypeImpl;
import meta.pure.metamodel.type.generics.TypeParameter;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Helper methods for {@link GenericType}.
 */
public class _GenericType
{
    private _GenericType()
    {
        // static utility
    }

    /**
     * Find the closest common GenericType (Lowest Common Ancestor) for a list
     * of GenericTypes. Uses C3 linearization to walk each type's hierarchy and
     * finds the first type that appears in all linearizations.
     *
     * <p>When recursively unifying type arguments, the variance of each type
     * parameter position is considered:
     * <ul>
     *   <li><b>Covariant</b> (default): finds the LCA (toward Any)</li>
     *   <li><b>Contravariant</b> ({@code TypeParameter._contravariant() == true}):
     *       finds the GLB (toward Nil)</li>
     * </ul>
     *
     * @param genericTypes the list of generic types to unify
     * @return the closest common GenericType, or {@code null} if the list is empty
     *         or any element has a null rawType
     */
    public static GenericType findCommonGenericType(MutableList<GenericType> genericTypes, MetadataAccess model)
    {
        return findCommonGenericType(genericTypes, false, model);
    }

    /**
     * Find the closest common GenericType for a list of GenericTypes.
     *
     * @param genericTypes  the list of generic types to unify
     * @param contravariant if true, uses greatest lower bound (toward Nil)
     *                      instead of least common ancestor (toward Any)
     * @return the closest common GenericType, or {@code Any} if the list is empty
     */
    public static GenericType findCommonGenericType(MutableList<GenericType> genericTypes, boolean contravariant, MetadataAccess model)
    {
        if (genericTypes == null || genericTypes.isEmpty())
        {
            // Empty list: return Nil (bottom of hierarchy, subtype of everything)
            return new InferredGenericTypeImpl()
                    ._rawType((Type) model.getElement("meta::pure::metamodel::type::Nil"));
        }

        // If all elements are the same type parameter reference (no rawType),
        // return that type parameter directly. This preserves type arguments
        // like <T, V, U> when unifying a Collection of identical generic types.
        MutableList<GenericType> nonNull = genericTypes.select(gt -> gt != null);
        if (nonNull.notEmpty() && nonNull.allSatisfy(gt -> gt._typeParameter() != null && gt._rawType() == null))
        {
            String firstName = nonNull.getFirst()._typeParameter()._name();
            if (nonNull.allSatisfy(gt -> firstName.equals(gt._typeParameter()._name())))
            {
                return nonNull.getFirst();
            }
        }

        // Collect raw types, skipping nulls
        MutableList<Type> rawTypes = nonNull
                .select(gt -> gt._rawType() != null)
                .collect(GenericType::_rawType);

        if (rawTypes.isEmpty())
        {
            return null;
        }

        // If all raw types are RelationType instances, use structural unification
        MutableList<meta.pure.metamodel.relation.RelationType> rawRelationTypes = rawTypes
                .selectInstancesOf(meta.pure.metamodel.relation.RelationType.class);
        if (rawRelationTypes.size() == rawTypes.size() && rawRelationTypes.notEmpty())
        {
            return new InferredGenericTypeImpl()._rawType(
                    _RelationType.findCommonRelationType(rawRelationTypes, contravariant, model));
        }

        // If all raw types are FunctionType instances, use structural unification
        MutableList<FunctionType> rawFunctionTypes = rawTypes
                .selectInstancesOf(FunctionType.class);
        if (rawFunctionTypes.size() == rawTypes.size() && rawFunctionTypes.notEmpty())
        {
            FunctionType common = _FunctionType.findCommonFunctionType(rawFunctionTypes, model);
            return common != null ? new InferredGenericTypeImpl()._rawType(common) : null;
        }

        Type commonType = _Type.findCommonType(rawTypes, contravariant, model);

        // Build a GenericType wrapping the common rawType.
        InferredGenericTypeImpl result = new InferredGenericTypeImpl()._rawType(commonType);

        // Collect projected GenericTypes at the common-type level.
        // If all inputs already have the same raw type, use their type args directly.
        // Otherwise, project each input onto the common type via resolveForTarget.
        MutableList<GenericType> projected;
        if (rawTypes.allSatisfy(t -> t == commonType))
        {
            projected = genericTypes.select(gt -> gt != null);
        }
        else
        {
            projected = Lists.mutable.empty();
            for (GenericType gt : genericTypes)
            {
                if (gt == null)
                {
                    continue;
                }
                GenericType p = resolveForTarget(gt, commonType, model);
                if (p != null)
                {
                    projected.add(p);
                }
            }
        }

        // Unify type arguments from the projected GenericTypes
        MutableList<GenericType> withArgs = projected.select(gt -> gt._typeArguments() != null && gt._typeArguments().notEmpty());
        if (withArgs.size() == projected.size() && withArgs.notEmpty())
        {
            int argCount = withArgs.getFirst()._typeArguments().size();
            boolean allSameArgCount = withArgs.allSatisfy(gt -> gt._typeArguments().size() == argCount);
            if (allSameArgCount && argCount > 0)
            {
                // Get type parameters from the common type to determine variance
                MutableList<TypeParameter> typeParams = (commonType instanceof Class cls && cls._typeParameters() != null)
                        ? cls._typeParameters()
                        : Lists.mutable.empty();

                // Recursively find common type for each type argument position
                MutableList<GenericType> commonArgs = Lists.mutable.empty();
                for (int i = 0; i < argCount; i++)
                {
                    final int idx = i;
                    MutableList<GenericType> argsAtPosition = withArgs.collect(gt -> gt._typeArguments().get(idx));

                    // Check variance: contravariant → GLB, covariant → LCA
                    boolean argContravariant = idx < typeParams.size()
                            && Boolean.TRUE.equals(typeParams.get(idx)._contravariant());

                    GenericType commonArg;
                    // Check if the type arguments wrap FunctionTypes
                    MutableList<FunctionType> functionTypes = argsAtPosition
                            .select(gt -> gt._rawType() instanceof FunctionType)
                            .collect(gt -> (FunctionType) gt._rawType());
                    if (functionTypes.size() == argsAtPosition.size() && functionTypes.notEmpty())
                    {
                        // Unify FunctionTypes with variance-aware logic
                        FunctionType commonFT = _FunctionType.findCommonFunctionType(functionTypes, model);
                        commonArg = commonFT != null
                                ? new InferredGenericTypeImpl()._rawType(commonFT)
                                : null;
                    }
                    // Check if the type arguments wrap RelationTypes
                    else
                    {
                        MutableList<meta.pure.metamodel.relation.RelationType> relationTypes = argsAtPosition
                                .select(gt -> gt._rawType() instanceof meta.pure.metamodel.relation.RelationType)
                                .collect(gt -> (meta.pure.metamodel.relation.RelationType) gt._rawType());
                        if (relationTypes.size() == argsAtPosition.size() && relationTypes.notEmpty())
                        {
                            Type commonRT = _RelationType.findCommonRelationType(relationTypes, argContravariant, model);
                            commonArg = commonRT != null
                                    ? new InferredGenericTypeImpl()._rawType(commonRT)
                                    : null;
                        }
                        else
                        {
                            commonArg = findCommonGenericType(argsAtPosition, argContravariant, model);
                        }
                    }

                    if (commonArg != null)
                    {
                        commonArgs.add(commonArg);
                    }
                }
                if (commonArgs.size() == argCount)
                {
                    result._typeArguments(commonArgs);
                }
            }
        }

        return result;
    }

    /**
     * Return a human-readable string representation of a GenericType,
     * including type arguments and type parameters.
     *
     * @param genericType the generic type to print
     * @param fullPath    if true, use fully-qualified paths; if false, use short names
     */
    public static String print(GenericType genericType, boolean fullPath)
    {
        if (genericType == null)
        {
            return "Unknown";
        }
        StringBuilder sb = new StringBuilder();
        printTo(genericType, fullPath, sb);
        return sb.toString();
    }

    /**
     * Convenience overload using full paths.
     */
    public static String print(GenericType genericType)
    {
        return print(genericType, true);
    }

    static void printTo(GenericType genericType, boolean fullPath, StringBuilder sb)
    {
        if (genericType == null)
        {
            sb.append("Unknown");
            return;
        }

        // Handle GenericTypeOperation (type algebra: T+R, T-R, T=R, T⊆R)
        if (genericType instanceof GenericTypeOperation gto)
        {
            _GenericTypeOperation.printTo(gto, fullPath, sb);
            return;
        }

        if (genericType._typeParameter() != null && genericType._typeParameter()._name() != null)
        {
            sb.append(genericType._typeParameter()._name());
            return;
        }

        Type rawType = genericType._rawType();
        if (rawType == null)
        {
            sb.append("Unknown");
            return;
        }

        if (rawType instanceof meta.pure.metamodel.type.FunctionType ft)
        {
            sb.append(_FunctionType.print(ft, fullPath));
            return;
        }

        if (rawType instanceof meta.pure.metamodel.relation.RelationType rt)
        {
            sb.append(_RelationType.print(rt, fullPath));
            return;
        }

        if (rawType instanceof PackageableElement pe)
        {
            if (fullPath)
            {
                String path = _PackageableElement.path(pe);
                if (path != null && (path.startsWith("meta::pure::metamodel::") || path.startsWith("meta::pure::functions::")))
                {
                    // Use simple name for well-known TTL metamodel types
                    String name = pe._name();
                    sb.append(name != null && !name.isEmpty() ? name : rawType.getClass().getSimpleName());
                }
                else
                {
                    sb.append(path != null && !path.isEmpty() ? path : rawType.getClass().getSimpleName());
                }
            }
            else
            {
                String name = pe._name();
                sb.append(name != null && !name.isEmpty() ? name : rawType.getClass().getSimpleName());
            }
        }
        else
        {
            sb.append(rawType.getClass().getSimpleName());
        }

        if (genericType._typeArguments() != null && genericType._typeArguments().notEmpty())
        {
            sb.append('<');
            boolean first = true;
            for (GenericType arg : genericType._typeArguments())
            {
                if (!first)
                {
                    sb.append(", ");
                }
                first = false;
                printTo(arg, fullPath, sb);
            }
            sb.append('>');
        }
    }

    /**
     * Recursively walk a parameter {@link GenericType} and an argument
     * {@link GenericType} in parallel. When the parameter side references
     * a {@code TypeParameter}, record the argument side's {@code rawType}
     * in the bindings. When both sides have {@code typeArguments},
     * recurse into them positionally. When both sides have a
     * {@code FunctionType} as rawType, also recurse into the function
     * parameters and return type, collecting multiplicity parameter bindings
     * alongside type bindings.
     *
     * @param paramGT  the parameter's generic type
     * @param argGT    the argument's generic type
     * @param bindings the parameter bindings to store the collected type and multiplicity bindings
     */
    /**
     * Returns true iff the GenericType is fully concrete — i.e. it has no type-parameter
     * references anywhere in its structure (rawType, typeArguments, FunctionType params/return).
     * A GenericType is non-concrete if {@code _typeParameter() != null} at any level.
     */
    public static boolean isConcrete(GenericType genericType)
    {
        if (genericType == null)
        {
            return false;
        }
        // GenericTypeOperation: delegate concreteness check
        if (genericType instanceof GenericTypeOperation gto)
        {
            return _GenericTypeOperation.isConcrete(gto);
        }
        // Type-parameter reference: e.g. K, T
        if (genericType._typeParameter() != null)
        {
            return false;
        }
        // No raw type at all — incomplete
        if (genericType._rawType() == null)
        {
            return false;
        }
        // Recurse into FunctionType parameters and return type
        if (genericType._rawType() instanceof FunctionType ft)
        {
            if (!_FunctionType.isConcrete(ft))
            {
                return false;
            }
        }
        // Recurse into RelationType column types
        if (genericType._rawType() instanceof meta.pure.metamodel.relation.RelationType rt)
        {
            if (!_RelationType.isConcrete(rt))
            {
                return false;
            }
        }
        // Recurse into type arguments (e.g. List<K>)
        if (genericType._typeArguments() != null)
        {
            for (GenericType arg : genericType._typeArguments())
            {
                if (!isConcrete(arg))
                {
                    return false;
                }
            }
        }
        return true;
    }


    /**
     * Returns true if the GenericType is concrete in the given context —
     * i.e. it is fully concrete, or any type parameter references it contains
     * are in-scope enclosing parameters (which is valid).
     *
     * @param genericType    the generic type to check
     * @param scopeTypeParams the set of type parameter names that are in scope
     */
    public static boolean isConcreteInContext(GenericType genericType, MutableSet<String> scopeTypeParams)
    {
        if (isConcrete(genericType))
        {
            return true;
        }
        MutableSet<String> referencedParams = Sets.mutable.empty();
        collectReferencedTypeParameterNames(genericType, referencedParams);
        // If no type params were found but isConcrete was false, the type has
        // structural incompleteness (e.g. null rawType) — not concrete in any context.
        return referencedParams.notEmpty() && scopeTypeParams.containsAll(referencedParams);
    }

    /**
     * Recursively collect all type parameter names referenced within a GenericType.
     * For example, for {@code MyClass<T>}, this returns {@code {"T"}}.
     */
    public static void collectReferencedTypeParameterNames(GenericType genericType, MutableSet<String> names)
    {
        if (genericType == null)
        {
            return;
        }
        // GenericTypeOperation: recurse into left and right
        if (genericType instanceof GenericTypeOperation gto)
        {
            _GenericTypeOperation.collectReferencedTypeParameterNames(gto, names);
            return;
        }
        if (genericType._typeParameter() != null)
        {
            names.add(genericType._typeParameter()._name());
        }
        if (genericType._rawType() instanceof FunctionType ft)
        {
            if (ft._parameters() != null)
            {
                ft._parameters().forEach(p -> { if (p != null) collectReferencedTypeParameterNames(p._genericType(), names); });
            }
            collectReferencedTypeParameterNames(ft._returnType(), names);
        }
        if (genericType._typeArguments() != null)
        {
            genericType._typeArguments().forEach(arg -> collectReferencedTypeParameterNames(arg, names));
        }
    }

    public static void collectTypeParameterBindings(GenericType paramGT, GenericType argGT, ParametersBinding bindings)
    {
        if (paramGT == null || argGT == null)
        {
            return;
        }

        // GenericTypeOperation: decompose SUBSET/EQUAL for type parameter binding
        if (paramGT instanceof GenericTypeOperation gto)
        {
            _GenericTypeOperation.collectTypeParameterBindings(gto, argGT, bindings);
            return;
        }

        // If the parameter side is a type parameter reference, bind it
        if (paramGT._typeParameter() != null && paramGT._rawType() == null)
        {
            String name = paramGT._typeParameter()._name();
            bindings.typeBindings().computeIfAbsent(name, k -> Sets.mutable.empty()) .add(argGT);
            return;
        }

        // If both sides have FunctionType as rawType, recurse into parameters and return type
        if (paramGT._rawType() instanceof FunctionType paramFT && argGT._rawType() instanceof FunctionType argFT)
        {
            _FunctionType.collectTypeParameterBindings(paramFT, argFT, bindings);
        }

        // If both sides have RelationType as rawType, match columns (wildcard support for (?:K))
        if (paramGT._rawType() instanceof meta.pure.metamodel.relation.RelationType paramRT
                && argGT._rawType() instanceof meta.pure.metamodel.relation.RelationType argRT)
        {
            _RelationType.collectTypeParameterBindings(paramRT, argRT, bindings);
        }

        // Recurse into typeArguments positionally
        MutableList<GenericType> paramTypeArgs = paramGT._typeArguments();
        MutableList<GenericType> argTypeArgs = argGT._typeArguments();
        if (paramTypeArgs != null && argTypeArgs != null)
        {
            int count = Math.min(paramTypeArgs.size(), argTypeArgs.size());
            for (int i = 0; i < count; i++)
            {
                if (paramTypeArgs.get(i) != null && argTypeArgs.get(i) != null)
                {
                    collectTypeParameterBindings(paramTypeArgs.get(i), argTypeArgs.get(i), bindings);
                }
            }
        }
    }

    /**
     * Recursively substitute type parameter and multiplicity parameter
     * references in a {@link GenericType} tree using the provided bindings.
     * If the generic type itself references a type parameter, replace it
     * with the bound concrete type. If it has type arguments, recurse into
     * each one. If the rawType is a {@code FunctionType}, also resolve
     * multiplicity parameters on its parameters and return type.
     *
     * @param genericType the generic type to make concrete
     * @param bindings    the parameter bindings with type and multiplicity mappings
     * @return a concrete generic type with all resolvable parameters substituted
     */
    public static GenericType makeAsConcreteAsPossible(GenericType genericType, ParametersBinding bindings, MetadataAccess model)
    {
        // GenericTypeOperation: delegate resolution
        if (genericType instanceof GenericTypeOperation gto)
        {
            return _GenericTypeOperation.makeAsConcreteAsPossible(gto, bindings, model);
        }

        // If this is a type parameter reference, substitute it
        if (genericType._typeParameter() != null && genericType._rawType() == null)
        {
            String name = genericType._typeParameter()._name();
            MutableSet<GenericType> boundTypes = bindings.typeBindings().get(name);
            if (boundTypes != null && boundTypes.notEmpty())
            {
                return boundTypes.size() == 1
                        ? boundTypes.getFirst()
                        : findCommonGenericType(Lists.mutable.withAll(boundTypes), model);
            }
            return genericType; // unresolved — return as-is
        }

        // If rawType is a FunctionType, resolve type and multiplicity parameters inside it
        if (genericType._rawType() instanceof FunctionType ft)
        {
            GenericType resolved = _FunctionType.makeAsConcreteAsPossible(genericType, ft, bindings, model);
            if (resolved != null)
            {
                return resolved;
            }
        }

        // If rawType is a RelationType, resolve type parameters in column types
        if (genericType._rawType() instanceof meta.pure.metamodel.relation.RelationType rt)
        {
            GenericType resolved = _RelationType.makeAsConcreteAsPossible(rt, bindings, model);
            if (resolved != null)
            {
                return resolved;
            }
        }


        // If there are type arguments or multiplicity arguments, resolve them
        MutableList<GenericType> typeArgs = genericType._typeArguments();
        MutableList<Multiplicity> mulArgs = genericType._multiplicityArguments();
        MutableList<GenericType> resolvedTypeArgs = null;
        MutableList<Multiplicity> resolvedMulArgs = null;
        boolean changed = false;

        if (typeArgs != null && typeArgs.notEmpty())
        {
            resolvedTypeArgs = typeArgs.collect(arg -> makeAsConcreteAsPossible(arg, bindings, model));
            changed = !resolvedTypeArgs.equals(typeArgs);
        }

        if (mulArgs != null && mulArgs.notEmpty())
        {
            resolvedMulArgs = mulArgs.collect(m -> _Multiplicity.makeAsConcreteAsPossible(m, bindings));
            changed = changed || !resolvedMulArgs.equals(mulArgs);
        }

        if (changed)
        {
            InferredGenericTypeImpl result = new InferredGenericTypeImpl()
                    ._rawType(genericType._rawType());
            if (resolvedTypeArgs != null)
            {
                result._typeArguments(resolvedTypeArgs);
            }
            if (resolvedMulArgs != null)
            {
                result._multiplicityArguments(resolvedMulArgs);
            }
            return result;
        }

        return genericType;
    }



    /**
     * Mark a GenericType as inferred (compiler-resolved).
     * If it is already an {@code Inferred} instance, return as-is.
     */
    public static GenericType asInferred(GenericType gt)
    {
        if (gt == null || gt instanceof Inferred || gt instanceof GenericTypeOperation)
        {
            return gt;
        }
        return copyInto(gt, new InferredGenericTypeImpl());
    }

    private static GenericType copyInto(GenericType source, InferredGenericTypeImpl target)
    {
        target._rawType(source._rawType());
        if (source._typeArguments() != null)
        {
            target._typeArguments(source._typeArguments());
        }
        if (source._multiplicityArguments() != null)
        {
            target._multiplicityArguments(source._multiplicityArguments());
        }
        if (source._typeParameter() != null)
        {
            target._typeParameter(source._typeParameter());
        }
        return target;
    }
    /**
     * Resolve a source {@link GenericType} for a target supertype by walking
     * up the generalization hierarchy, substituting type and multiplicity
     * parameters at each step.
     *
     * <p>For example, given {@code Property<Person, String|*>} and target
     * type {@code Function}, this walks:
     * <ol>
     *   <li>{@code Property<Person, String|*>} → generalizes to
     *       {@code AbstractProperty<FunctionType{Person[1]->String[*]}>}
     *       (substituting U→Person, V→String, m→*)</li>
     *   <li>{@code AbstractProperty<...>} → generalizes to
     *       {@code Function<FunctionType{Person[1]->String[*]}>}
     *       (substituting T→FunctionType{...})</li>
     * </ol>
     *
     * @param sourceGenericType the concrete generic type to resolve from
     * @param targetType        the target supertype to resolve to
     * @return the resolved GenericType for the target type, or {@code null}
     *         if the target is not in the source's hierarchy
     */
    public static GenericType resolveForTarget(GenericType sourceGenericType, Type targetType, MetadataAccess model)
    {
        if (sourceGenericType == null || targetType == null)
        {
            return null;
        }

        Type sourceType = sourceGenericType._rawType();
        if (sourceType == null)
        {
            return null;
        }

        // Base case: already at the target type
        if (sourceType == targetType)
        {
            return sourceGenericType;
        }

        // Build bindings from the source type's declared parameters to the
        // source GenericType's concrete arguments
        ParametersBinding bindings = new ParametersBinding();

        if (sourceType instanceof Class cls)
        {
            // Type parameter bindings: T → concrete type arg
            cls._typeParameters().zip(sourceGenericType._typeArguments())
                    .forEach(pair -> bindings.typeBindings()
                            .computeIfAbsent(pair.getOne()._name(), k -> Sets.mutable.empty())
                            .add(pair.getTwo()));

            // Multiplicity parameter bindings: m → concrete multiplicity arg
            if (sourceGenericType._multiplicityArguments() != null)
            {
                cls._multiplicityParameters().zip(sourceGenericType._multiplicityArguments())
                        .forEach(pair -> bindings.multiplicityBindings()
                                .computeIfAbsent(pair.getOne()._name(), k -> Sets.mutable.empty())
                                .add(pair.getTwo()));
            }
        }

        // Walk generalizations looking for the path to targetType
        if (sourceType._generalizations() != null)
        {
            for (Generalization gen : sourceType._generalizations())
            {
                GenericType generalGT = gen._general();
                if (generalGT == null || generalGT._rawType() == null)
                {
                    continue;
                }

                // Check if this generalization's rawType leads to the target
                if (_Type.linearize(generalGT._rawType(), model).contains(targetType))
                {
                    // Substitute the current bindings into the generalization's GenericType
                    GenericType resolvedGT = makeAsConcreteAsPossible(generalGT, bindings, model);
                    // Recurse to continue up the hierarchy
                    return resolveForTarget(resolvedGT, targetType, model);
                }
            }
        }

        return null;
    }

    /**
     * Check whether {@code actual} is compatible with {@code declared}.
     * The actual type must be a subtype of the declared type (covariant at the top level).
     *
     * <p>When recursing into type arguments, the variance for each position
     * is determined by the corresponding {@code TypeParameter._contravariant()}
     * on the declared type's class definition.</p>
     *
     * @param declared the expected generic type
     * @param actual   the actual generic type
     * @return true if actual is compatible with declared
     */
    public static boolean isCompatible(GenericType declared, GenericType actual, MetadataAccess model)
    {
        return isCompatible(declared, actual, false, model);
    }

    /**
     * Check whether {@code actual} is compatible with {@code declared}.
     *
     * @param declared      the expected generic type
     * @param actual        the actual generic type
     * @param contravariant if true, the check is in a contravariant position (subtype direction is flipped)
     * @return true if actual is compatible with declared
     */
    public static boolean isCompatible(GenericType declared, GenericType actual, boolean contravariant, MetadataAccess model)
    {
        if (declared == null || actual == null)
        {
            return false;
        }
        // GenericTypeOperation (SUBSET/EQUAL): if the right side is concrete, check compatibility against it
        if (declared instanceof GenericTypeOperation declaredOp)
        {
            return _GenericTypeOperation.isCompatible(declaredOp, actual, contravariant, model);
        }
        if (actual instanceof GenericTypeOperation actualOp)
        {
            return _GenericTypeOperation.isCompatible(actualOp, declared, !contravariant, model);
        }

        // Unresolved type parameters — assume compatible
        if (declared._rawType() == null || actual._rawType() == null)
        {
            return true;
        }

        Type declaredType = declared._rawType();
        Type actualType = actual._rawType();

        // FunctionTypes are structural — compare param and return types
        if (declaredType instanceof FunctionType declaredFT && actualType instanceof FunctionType actualFT)
        {
            return _FunctionType.isCompatible(declaredFT, actualFT, contravariant, model);
        }

        // RelationTypes are structural — compare columns by name, type, and multiplicity
        if (declaredType instanceof meta.pure.metamodel.relation.RelationType declaredRT
                && actualType instanceof meta.pure.metamodel.relation.RelationType actualRT)
        {
            return _RelationType.isCompatible(declaredRT, actualRT, contravariant, model);
        }

        // In contravariant position the subtype check is flipped: subtypeOf(declared, actual).
        // When actual is Nil (bottom type), that check fails because nothing is below Nil.
        // But Nil IS a subtype of every type, so the contravariant requirement is satisfied.
        // Example: Property<Nil, Any> where the owner type param is contravariant.
        if (contravariant && _Type.isBottomType(actualType))
        {
            return true;
        }

        // Check raw type subtyping, flipped in contravariant position
        boolean rawCompatible;
        if (!contravariant)
        {
            // Covariant: actual must be a subtype of declared
            rawCompatible = _Type.subtypeOf(actualType, declaredType, model);
        }
        else
        {
            // Contravariant: declared must be a subtype of actual
            rawCompatible = _Type.subtypeOf(declaredType, actualType, model);
        }

        if (!rawCompatible)
        {
            return false;
        }

        GenericType resolvedActual = declaredType != actualType ? resolveForTarget(actual, declaredType, model) : actual;

        if (resolvedActual == null)
        {
            return true; // bottom type (Nil) is compatible with any type
        }

        if (declared._typeArguments().notEmpty() && resolvedActual._typeArguments().notEmpty())
        {
            if (declared._typeArguments().size() != resolvedActual._typeArguments().size())
            {
                return false;
            }

            // Get the type parameters from the declared raw type to read variance
            MutableList<TypeParameter> typeParams = (declaredType instanceof Class cls && cls._typeParameters() != null)
                    ? cls._typeParameters()
                    : null;

            for (int i = 0; i < declared._typeArguments().size(); i++)
            {
                // Determine variance for this position from the type parameter declaration
                boolean argContravariant = contravariant;
                if (typeParams != null && i < typeParams.size())
                {
                    Boolean isContra = typeParams.get(i)._contravariant();
                    if (Boolean.TRUE.equals(isContra))
                    {
                        // Flip variance for contravariant type parameters
                        argContravariant = !argContravariant;
                    }
                }

                if (!isCompatible(declared._typeArguments().get(i), resolvedActual._typeArguments().get(i), argContravariant, model))
                {
                    return false;
                }
            }
        }

        return true;
    }


    /**
     * Walk two GenericTypes in parallel and widen any {@link Inferred} types and multiplicities
     * in {@code actual} to match {@code expected}. This reconciles stale compiler-computed
     * values (e.g. from pre-unification lambda resolution) with the expected values
     * from unified bindings. Only widens — never narrows, and never touches non-Inferred
     * (user-declared) values.
     */
    public static void reconcileInferred(GenericType expected, GenericType actual, MetadataAccess model)
    {
        if (expected == null || actual == null)
        {
            return;
        }
        // GenericTypeOperations: recurse into left and right
        if (expected instanceof GenericTypeOperation expectedOp && actual instanceof GenericTypeOperation actualOp)
        {
            _GenericTypeOperation.reconcileInferred(expectedOp, actualOp, model);
            return;
        }

        Type expectedType = expected._rawType();
        Type actualType = actual._rawType();

        // FunctionTypes: reconcile param and return types/multiplicities
        if (expectedType instanceof FunctionType expectedFT && actualType instanceof FunctionType actualFT)
        {
            _FunctionType.reconcileInferred(expectedFT, actualFT, model);
        }
        // RelationTypes: reconcile column types/multiplicities by name
        if (expectedType instanceof meta.pure.metamodel.relation.RelationType expectedRT
                && actualType instanceof meta.pure.metamodel.relation.RelationType actualRT)
        {
            _RelationType.reconcileInferred(expectedRT, actualRT, model);
        }

        // Recurse into type arguments (e.g. Function<{FunctionType}>)
        if (expected._typeArguments() != null && actual._typeArguments() != null)
        {
            int count = Math.min(expected._typeArguments().size(), actual._typeArguments().size());
            for (int i = 0; i < count; i++)
            {
                reconcileInferred(expected._typeArguments().get(i), actual._typeArguments().get(i), model);
            }
        }
    }

}
