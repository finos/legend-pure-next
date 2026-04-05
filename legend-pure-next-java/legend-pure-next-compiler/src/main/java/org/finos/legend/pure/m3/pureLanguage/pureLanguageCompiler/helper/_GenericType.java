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
import meta.pure.metamodel.relation.RelationType;
import meta.pure.metamodel.relationship.Generalization;
import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import meta.pure.metamodel.type.generics.InferredGenericTypeImpl;
import meta.pure.metamodel.type.generics.TypeParameter;
import meta.pure.metamodel.type.generics.UndefinedGenericType;
import meta.pure.metamodel.type.generics.UndefinedGenericTypeImpl;
import meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PlainParametersBinding;

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
     * Extract the raw {@link Type} from a {@link GenericType}, requiring it to be a {@link GenericTypeValue}.
     */
    public static Type type(GenericType genericType)
    {
        return switch (genericType)
        {
            case UndefinedGenericType undefined -> null;
            case GenericTypeValue gtv -> gtv._type();
            default -> throw new IllegalArgumentException("Expected GenericTypeValue, got: " + genericType.getClass());
        };
    }

    public static meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl buildUserDefinedGenericType(meta.pure.metamodel.type.Type rawType, MetadataAccess model)
    {
        meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl gt = new meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl();
        if (rawType != null)
        {
            gt._type(rawType);
        }

        meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl cgt = new meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl();
        cgt._type((meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::type::generics::UserDefinedGenericType"));
        cgt._classifierGenericType(cgt);
        
        gt._classifierGenericType(cgt);
        return gt;
    }

    /**
     * Extract the type arguments from a {@link GenericType}, requiring it to be a {@link GenericTypeValue}.
     */
    public static MutableList<GenericType> typeArguments(GenericType genericType)
    {
        return switch (genericType)
        {
            case UndefinedGenericType undefined -> Lists.mutable.empty();
            case GenericTypeValue gtv -> gtv._typeArguments();
            default -> throw new IllegalArgumentException("Expected GenericTypeValue, got: " + genericType.getClass());
        };
    }

    /**
     * Extract the multiplicity arguments from a {@link GenericType}, requiring it to be a {@link GenericTypeValue}.
     */
    public static MutableList<Multiplicity> multiplicityArguments(GenericType genericType)
    {
        return switch (genericType)
        {
            case UndefinedGenericType undefined -> Lists.mutable.empty();
            case GenericTypeValue gtv -> gtv._multiplicityArguments();
            default -> throw new IllegalArgumentException("Expected GenericTypeValue, got: " + genericType.getClass());
        };
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
     * or any element has a null rawType
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
            return new InferredGenericTypeImpl(model)
                    ._type((Type) model.getElement("meta::pure::metamodel::type::Nil"));
        }

        // If all elements are the same type parameter reference, return it directly.
        MutableList<GenericType> nonNull = genericTypes.select(gt -> gt != null);
        MutableList<GenericTypeValue> nonNullValues = nonNull.selectInstancesOf(GenericTypeValue.class);
        if (nonNullValues.notEmpty())
        {
            String firstName = nonNullValues.getFirst()._type() instanceof TypeParameter tp ? tp._name() : null;
            if (firstName != null && nonNullValues.allSatisfy(gt -> gt._type() instanceof TypeParameter tp2
                    && firstName.equals(tp2._name())))
            {
                return nonNullValues.getFirst();
            }
        }

        // Collect raw types, skipping nulls and TypeParameter references
        MutableList<Type> rawTypes = nonNullValues
                .select(gt -> gt._type() != null && !(gt._type() instanceof TypeParameter))
                .collect(GenericTypeValue::_type);

        if (rawTypes.isEmpty())
        {
            return null;
        }

        // If all raw types are RelationType instances, use structural unification
        MutableList<meta.pure.metamodel.relation.RelationType> rawRelationTypes = rawTypes
                .selectInstancesOf(meta.pure.metamodel.relation.RelationType.class);
        if (rawRelationTypes.size() == rawTypes.size() && rawRelationTypes.notEmpty())
        {
            return new InferredGenericTypeImpl(model)._type(
                    _RelationType.findCommonRelationType(rawRelationTypes, contravariant, model));
        }

        // If all raw types are FunctionType instances, use structural unification
        MutableList<FunctionType> rawFunctionTypes = rawTypes
                .selectInstancesOf(FunctionType.class);
        if (rawFunctionTypes.size() == rawTypes.size() && rawFunctionTypes.notEmpty())
        {
            FunctionType common = _FunctionType.findCommonFunctionType(rawFunctionTypes, model);
            return common != null ? new InferredGenericTypeImpl(model)._type(common) : null;
        }

        Type commonType = _Type.findCommonType(rawTypes, contravariant, model);

        // Build a GenericType wrapping the common rawType.
        InferredGenericTypeImpl result = new InferredGenericTypeImpl(model)._type(commonType);

        // Collect projected GenericTypes at the common-type level.
        // If all inputs already have the same raw type, use their type args directly.
        // Otherwise, project each input onto the common type via resolveForTarget.
        MutableList<GenericType> projected;
        if (rawTypes.allSatisfy(t -> t == commonType))
        {
            projected = nonNull;  // already filtered above
        }
        else
        {
            projected = Lists.mutable.empty();
            for (GenericType gt : nonNull)
            {
                GenericType p = resolveForTarget(gt, commonType, model);
                if (p != null)
                {
                    projected.add(p);
                }
            }
        }

        // Unify type arguments from the projected GenericTypes
        MutableList<GenericTypeValue> projectedValues = projected.selectInstancesOf(GenericTypeValue.class);
        MutableList<GenericTypeValue> withArgs = projectedValues.select(gt -> gt._typeArguments() != null && gt._typeArguments().notEmpty());
        if (withArgs.size() == projectedValues.size() && withArgs.notEmpty())
        {
            int argCount = withArgs.getFirst()._typeArguments().size();
            if (withArgs.allSatisfy(gt -> gt._typeArguments().size() == argCount) && argCount > 0)
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

                    GenericType commonArg = findCommonGenericType(argsAtPosition, argContravariant, model);
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

        switch (genericType)
        {
            case UndefinedGenericType undefined ->
            {
                sb.append("?");
                return;
            }
            case GenericTypeOperation gto ->
            {
                _GenericTypeOperation.printTo(gto, fullPath, sb);
                return;
            }
            case GenericTypeValue gtv ->
            {
                printGenericTypeValue(gtv, fullPath, sb);
                return;
            }
            default -> throw new IllegalArgumentException("Unexpected GenericType: " + genericType.getClass());
        }
    }

    private static void printGenericTypeValue(GenericTypeValue genericType, boolean fullPath, StringBuilder sb)
    {
        if (genericType._type() instanceof TypeParameter tp)
        {
            sb.append(tp._name());
            return;
        }

        Type rawType = genericType._type();

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

        boolean hasTypeArgs = genericType._typeArguments() != null && genericType._typeArguments().notEmpty();
        boolean hasMulArgs = genericType._multiplicityArguments() != null && genericType._multiplicityArguments().notEmpty();
        if (hasTypeArgs || hasMulArgs)
        {
            sb.append('<');
            if (hasTypeArgs)
            {
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
            }
            if (hasMulArgs)
            {
                for (meta.pure.metamodel.multiplicity.Multiplicity mul : genericType._multiplicityArguments())
                {
                    sb.append('|');
                    sb.append(_Multiplicity.printBare(mul));
                }
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
     * A GenericType is non-concrete if its {@code _type()} is a {@code TypeParameter}.
     */
    public static boolean isConcrete(GenericType genericType)
    {
        if (genericType == null)
        {
            return false;
        }
        return switch (genericType)
        {
            case UndefinedGenericType undefined -> true;
            case GenericTypeOperation gto -> _GenericTypeOperation.isConcrete(gto);
            case GenericTypeValue gtv ->
            {
                // Type-parameter reference: e.g. K, T
                if (gtv._type() instanceof TypeParameter)
                {
                    yield false;
                }
                // Recurse into FunctionType parameters and return type
                if (gtv._type() instanceof FunctionType ft)
                {
                    if (!_FunctionType.isConcrete(ft))
                    {
                        yield false;
                    }
                }
                // Recurse into RelationType column types
                if (gtv._type() instanceof meta.pure.metamodel.relation.RelationType rt)
                {
                    if (!_RelationType.isConcrete(rt))
                    {
                        yield false;
                    }
                }
                // Recurse into type arguments (e.g. List<K>)
                if (gtv._typeArguments() != null)
                {
                    for (GenericType arg : gtv._typeArguments())
                    {
                        if (!isConcrete(arg))
                        {
                            yield false;
                        }
                    }
                }
                yield true;
            }
            default -> throw new IllegalArgumentException("Unexpected GenericType: " + genericType.getClass());
        };
    }

    /**
     * Replace all TypeParameter references in a GenericType tree with UndefinedGenericType.
     * Used when a PackageableElement is referenced as a value (e.g., {@code instanceOf(List)}) —
     * the element's classifierGenericType has intrinsic type params (e.g., {@code Class<List<T>>})
     * that are not bound by the call site and should be treated as wildcards.
     */
    public static GenericType withUndefinedTypeParams(GenericType genericType, MetadataAccess model)
    {
        if (genericType == null)
        {
            return null;
        }
        if (genericType instanceof GenericTypeValue gtv)
        {
            if (gtv._type() instanceof TypeParameter)
            {
                return new UndefinedGenericTypeImpl(model);
            }
            if (gtv._typeArguments() != null && gtv._typeArguments().anySatisfy(arg -> !isConcrete(arg)))
            {
                MutableList<GenericType> newArgs = gtv._typeArguments().collect(arg -> withUndefinedTypeParams(arg, model));
                return ((InferredGenericTypeImpl) asInferred(genericType, model))._typeArguments(newArgs);
            }
        }
        return genericType;
    }


    /**
     * Returns true if the GenericType is concrete in the given context —
     * i.e. it is fully concrete, or any type parameter references it contains
     * are in-scope enclosing parameters (which is valid).
     *
     * @param genericType     the generic type to check
     * @param scopeTypeParams the set of type parameter names that are in scope
     */
    public static boolean isConcreteInContext(GenericType genericType, MutableSet<String> scopeTypeParams)
    {
        if (isConcrete(genericType))
        {
            return true;
        }
        MutableSet<String> referencedParams = collectReferencedTypeParameterNames(genericType);
        // If no type params were found but isConcrete was false, the type has
        // structural incompleteness (e.g. null rawType) — not concrete in any context.
        return referencedParams.notEmpty() && scopeTypeParams.containsAll(referencedParams);
    }

    /**
     * Recursively collect all type parameter names referenced within a GenericType.
     * For example, for {@code MyClass<T>}, this returns {@code {"T"}}.
     */
    public static MutableSet<String> collectReferencedTypeParameterNames(GenericType genericType)
    {
        MutableSet<String> names = Sets.mutable.empty();
        collectReferencedTypeParameterNames(genericType, names);
        return names;
    }

    static void collectReferencedTypeParameterNames(GenericType genericType, MutableSet<String> names)
    {
        if (genericType == null)
        {
            return;
        }
        switch (genericType)
        {
            case UndefinedGenericType undefined ->
            { /* no-op */ }
            case GenericTypeOperation gto -> _GenericTypeOperation.collectReferencedTypeParameterNames(gto, names);
            case GenericTypeValue gtv ->
            {
                if (gtv._type() instanceof TypeParameter tp)
                {
                    names.add(tp._name());
                }
                if (gtv._type() instanceof FunctionType ft)
                {
                    names.addAll(_FunctionType.collectReferencedTypeParameterNames(ft));
                }
                if (gtv._type() instanceof meta.pure.metamodel.relation.RelationType rt)
                {
                    names.addAll(_RelationType.collectReferencedTypeParameterNames(rt));
                }
                if (gtv._typeArguments() != null)
                {
                    gtv._typeArguments().forEach(arg -> collectReferencedTypeParameterNames(arg, names));
                }
            }
            default -> throw new IllegalArgumentException("Unexpected GenericType: " + genericType.getClass());
        }
    }

    /**
     * Recursively collect all multiplicity parameter names referenced
     * within a GenericType (including nested FunctionTypes and typeArguments).
     */
    public static MutableSet<String> collectReferencedMultiplicityParameterNames(GenericType genericType)
    {
        MutableSet<String> names = Sets.mutable.empty();
        collectReferencedMultiplicityParameterNames(genericType, names);
        return names;
    }

    static void collectReferencedMultiplicityParameterNames(GenericType genericType, MutableSet<String> names)
    {
        if (genericType == null)
        {
            return;
        }
        switch (genericType)
        {
            case UndefinedGenericType undefined -> { /* no-op */ }
            case GenericTypeOperation gto ->
            {
                collectReferencedMultiplicityParameterNames(gto._left(), names);
                collectReferencedMultiplicityParameterNames(gto._right(), names);
            }
            case GenericTypeValue gtv ->
            {
                if (gtv._type() instanceof FunctionType ft)
                {
                    names.addAll(_FunctionType.collectReferencedMultiplicityParameterNames(ft));
                }
                if (gtv._type() instanceof meta.pure.metamodel.relation.RelationType rt)
                {
                    names.addAll(_RelationType.collectReferencedMultiplicityParameterNames(rt));
                }
                if (gtv._typeArguments() != null)
                {
                    gtv._typeArguments().forEach(arg -> collectReferencedMultiplicityParameterNames(arg, names));
                }
                if (gtv._multiplicityArguments() != null)
                {
                    gtv._multiplicityArguments().forEach(mul ->
                    {
                        if (mul instanceof meta.pure.metamodel.multiplicity.MultiplicityParameter mp)
                        {
                            names.add(mp._name());
                        }
                    });
                }
            }
            default -> throw new IllegalArgumentException("Unexpected GenericType: " + genericType.getClass());
        }
    }

    public static void collectTypeParameterBindings(GenericType paramGT, GenericType argGT, ParametersBinding bindings)
    {
        if (paramGT == null || argGT == null)
        {
            return;
        }

        switch (paramGT)
        {
            case UndefinedGenericType undefined -> { /* no-op: undefined wildcard doesn't bind */ }
            case GenericTypeOperation gto ->
            {
                _GenericTypeOperation.collectTypeParameterBindings(gto, argGT, bindings);
            }
            case GenericTypeValue paramV ->
            {
                // If the parameter side is a type parameter reference, bind it
                if (paramV._type() instanceof TypeParameter tp)
                {
                    bindings.addTypeBinding(tp, argGT);
                    return;
                }

                // For structural type matching, argGT must also be a value
                if (argGT instanceof GenericTypeValue argV)
                {
                    // If both sides have FunctionType as rawType, recurse into parameters and return type
                    if (paramV._type() instanceof FunctionType paramFT && argV._type() instanceof FunctionType argFT)
                    {
                        _FunctionType.collectTypeParameterBindings(paramFT, argFT, bindings);
                    }

                    // If both sides have RelationType as rawType, match columns
                    if (paramV._type() instanceof meta.pure.metamodel.relation.RelationType paramRT
                            && argV._type() instanceof meta.pure.metamodel.relation.RelationType argRT)
                    {
                        _RelationType.collectTypeParameterBindings(paramRT, argRT, bindings);
                    }

                    // Recurse into typeArguments positionally
                    MutableList<GenericType> paramTypeArgs = paramV._typeArguments();
                    MutableList<GenericType> argTypeArgs = argV._typeArguments();
                    if (paramTypeArgs != null && argTypeArgs != null)
                    {
                        paramTypeArgs.zip(argTypeArgs).forEach(pair ->
                        {
                            if (pair.getOne() != null && pair.getTwo() != null)
                            {
                                collectTypeParameterBindings(pair.getOne(), pair.getTwo(), bindings);
                            }
                        });
                    }

                    // Recurse into multiplicityArguments positionally
                    MutableList<Multiplicity> paramMulArgs = paramV._multiplicityArguments();
                    MutableList<Multiplicity> argMulArgs = argV._multiplicityArguments();
                    if (paramMulArgs != null && argMulArgs != null)
                    {
                        paramMulArgs.zip(argMulArgs).forEach(pair ->
                                _Multiplicity.collectMultiplicityParameterBindings(pair.getOne(), pair.getTwo(), bindings));
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unexpected GenericType: " + paramGT.getClass());
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
        return switch (genericType)
        {
            case UndefinedGenericType undefined -> genericType;
            case GenericTypeOperation gto -> _GenericTypeOperation.makeAsConcreteAsPossible(gto, bindings, model);
            case GenericTypeValue gtv ->
            {
                // If this is a type parameter reference, substitute it
                if (gtv._type() instanceof TypeParameter tp)
                {
                    String name = tp._name();
                    MutableSet<GenericType> boundTypes = bindings.typeBindings().get(name);
                    if (boundTypes != null && boundTypes.notEmpty())
                    {
                        yield boundTypes.size() == 1
                                ? boundTypes.getFirst()
                                : findCommonGenericType(Lists.mutable.withAll(boundTypes), model);
                    }
                    yield genericType; // unresolved — return as-is
                }

                // If rawType is a FunctionType, resolve type and multiplicity parameters inside it
                if (gtv._type() instanceof FunctionType ft)
                {
                    GenericType resolved = _FunctionType.makeAsConcreteAsPossible(genericType, ft, bindings, model);
                    if (resolved != null)
                    {
                        yield resolved;
                    }
                }

                // If rawType is a RelationType, resolve type parameters in column types
                if (gtv._type() instanceof meta.pure.metamodel.relation.RelationType rt)
                {
                    GenericType resolved = _RelationType.makeAsConcreteAsPossible(rt, bindings, model);
                    if (resolved != null)
                    {
                        yield resolved;
                    }
                }

                // If there are type arguments or multiplicity arguments, resolve them
                MutableList<GenericType> typeArgs = gtv._typeArguments();
                MutableList<Multiplicity> mulArgs = gtv._multiplicityArguments();
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
                    InferredGenericTypeImpl result = new InferredGenericTypeImpl(model)
                            ._type(gtv._type());
                    if (resolvedTypeArgs != null)
                    {
                        result._typeArguments(resolvedTypeArgs);
                    }
                    if (resolvedMulArgs != null)
                    {
                        result._multiplicityArguments(resolvedMulArgs);
                    }
                    yield result;
                }

                yield genericType;
            }
            default -> throw new IllegalArgumentException("Unexpected GenericType: " + genericType.getClass());
        };
    }



    /**
     * Mark a GenericType as inferred (compiler-resolved).
     * If it is already an {@code Inferred} instance, return as-is.
     */
    public static GenericType asInferred(GenericType gt, MetadataAccess model)
    {
        if (gt == null || gt instanceof Inferred || gt instanceof GenericTypeOperation || gt instanceof UndefinedGenericType)
        {
            return gt;
        }
        return switch (gt)
        {
            case GenericTypeValue gtv -> copyInto(gtv, new InferredGenericTypeImpl(model));
            default -> throw new IllegalArgumentException("Expected GenericTypeValue, got: " + gt.getClass());
        };
    }

    private static GenericType copyInto(GenericTypeValue source, InferredGenericTypeImpl target)
    {
        target._type(source._type());
        if (source._typeArguments() != null)
        {
            target._typeArguments(source._typeArguments());
        }
        if (source._multiplicityArguments() != null)
        {
            target._multiplicityArguments(source._multiplicityArguments());
        }
        return target;
    }

    /**
     * Resolve a source {@link GenericType} for a target type by walking
     * the generalization hierarchy. Supports both directions:
     * <ul>
     *   <li><b>Upward</b> (target is a supertype): walks up from source,
     *       substituting type and multiplicity parameters at each step.</li>
     *   <li><b>Downward</b> (target is a subtype): builds a template for the
     *       target's type parameters, resolves it UP to the source's level,
     *       collects bindings by unification, and applies them.</li>
     * </ul>
     *
     * <p>Upward example: given {@code Property<Person, String|*>} and target
     * {@code Function}, resolves to {@code Function<{Person[1]->String[*]}>}.
     *
     * <p>Downward example: given {@code Function<{Person[1]->String[*]}>} and target
     * {@code Property}, resolves to {@code Property<Person, String|*>}.
     *
     * @param sourceGenericType the concrete generic type to resolve from
     * @param targetType        the target type to resolve to (super or sub)
     * @return the resolved GenericType for the target type, or {@code null}
     *         if the target is not in the source's hierarchy
     */
    public static GenericType resolveForTarget(GenericType sourceGenericType, Type targetType, MetadataAccess model)
    {
        if (sourceGenericType == null || targetType == null)
        {
            return null;
        }

        return switch (sourceGenericType)
        {
            case GenericTypeValue src ->
            {
                Type sourceType = src._type();
                if (sourceType == null)
                {
                    yield null;
                }

                // Base case: already at the target type
                if (sourceType == targetType)
                {
                    yield sourceGenericType;
                }

                // Build bindings from the source type's declared parameters to the
                // source GenericType's concrete arguments
                ParametersBinding bindings = PlainParametersBinding.empty();

                if (sourceType instanceof Class cls)
                {
                    // Type parameter bindings: T → concrete type arg
                    cls._typeParameters().zip(src._typeArguments())
                            .forEach(pair -> bindings.typeBindings()
                                    .computeIfAbsent(pair.getOne()._name(), k -> Sets.mutable.empty())
                                    .add(pair.getTwo()));

                    // Multiplicity parameter bindings: m → concrete multiplicity arg
                    if (src._multiplicityArguments() != null)
                    {
                        cls._multiplicityParameters().zip(src._multiplicityArguments())
                                .forEach(pair -> bindings.multiplicityBindings()
                                        .computeIfAbsent(pair.getOne()._name(), k -> Sets.mutable.empty())
                                        .add(pair.getTwo()));
                    }
                }

                // Determine direction by comparing source and target in the hierarchy
                if (_Type.linearize(sourceType, model).contains(targetType))
                {
                    // Upward: target is a supertype of source — walk generalizations
                    if (sourceType._generalizations() != null)
                    {
                        for (Generalization gen : sourceType._generalizations())
                        {
                            GenericType generalGT = gen._general();
                            if (!(generalGT instanceof GenericTypeValue generalV) || generalV._type() == null)
                            {
                                continue;
                            }

                            // Pick the generalization branch that leads to the target
                            if (_Type.linearize(generalV._type(), model).contains(targetType))
                            {
                                // Substitute the current bindings into the generalization's GenericType
                                GenericType resolvedGT = makeAsConcreteAsPossible(generalGT, bindings, model);
                                // Recurse to continue up the hierarchy
                                yield resolveForTarget(resolvedGT, targetType, model);
                            }
                        }
                    }
                }
                // Downward: target is a subtype of source
                // Build a template for targetType with its type params as placeholders,
                // resolve it UP to sourceType, unify with source, apply bindings.
                else if (targetType instanceof Class targetCls
                        && _Type.linearize(targetType, model).contains(sourceType))
                {
                    MutableList<GenericType> templateTypeArgs = targetCls._typeParameters() != null
                            ? targetCls._typeParameters().collect(tp -> buildUserDefinedGenericType(tp, model))
                            : null;
                    MutableList<Multiplicity> templateMulArgs = targetCls._multiplicityParameters() != null
                            ? targetCls._multiplicityParameters().collect(mp ->
                            {
                                meta.pure.metamodel.multiplicity.UserDefinedMultiplicityParameterImpl mul =
                                        new meta.pure.metamodel.multiplicity.UserDefinedMultiplicityParameterImpl(model);
                                mul._name(mp._name());
                                return (Multiplicity) mul;
                            })
                            : null;

                    meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl templateGT =
                            new meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl(model);
                    templateGT._type(targetType);
                    templateGT._typeArguments(templateTypeArgs);
                    templateGT._multiplicityArguments(templateMulArgs);

                    // Resolve the template UP to the source's raw type
                    GenericType resolvedTemplate = resolveForTarget(templateGT, sourceType, model);
                    if (resolvedTemplate != null)
                    {
                        // Collect bindings: unify the resolved template with the source
                        ParametersBinding downBindings = PlainParametersBinding.empty();
                        collectTypeParameterBindings(resolvedTemplate, sourceGenericType, downBindings);
                        // Collect multiplicity bindings from multiplicityArguments
                        if (resolvedTemplate instanceof GenericTypeValue resolvedV
                                && resolvedV._multiplicityArguments() != null
                                && src._multiplicityArguments() != null)
                        {
                            resolvedV._multiplicityArguments().zip(src._multiplicityArguments()).forEach(pair ->
                                    _Multiplicity.collectMultiplicityParameterBindings(pair.getOne(), pair.getTwo(), downBindings));
                        }
                        // Apply bindings to produce the concrete GenericType at the target level
                        yield makeAsConcreteAsPossible(templateGT, downBindings, model);
                    }
                }

                yield null;
            }
            case UndefinedGenericType undefined -> null;
            default -> throw new IllegalArgumentException("Expected GenericTypeValue, got: " + sourceGenericType.getClass());
        };
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
        // GenericTypeOperation dispatch
        if (declared instanceof GenericTypeOperation declaredOp)
        {
            return _GenericTypeOperation.isCompatible(declaredOp, actual, contravariant, model);
        }
        if (actual instanceof GenericTypeOperation actualOp)
        {
            return _GenericTypeOperation.isCompatible(actualOp, declared, !contravariant, model);
        }

        // UndefinedGenericType matches only other UndefinedGenericType
        boolean declaredUndefined = declared instanceof UndefinedGenericType;
        boolean actualUndefined = actual instanceof UndefinedGenericType;
        if (declaredUndefined || actualUndefined)
        {
            return declaredUndefined == actualUndefined;
        }

        // Both must be GenericTypeValue at this point
        if (!(declared instanceof GenericTypeValue declaredV) || !(actual instanceof GenericTypeValue actualV))
        {
            throw new IllegalArgumentException("Expected GenericTypeValue, got declared=" + declared.getClass() + ", actual=" + actual.getClass());
        }

        // Unresolved type parameters — assume compatible
        if (declaredV._type() == null || actualV._type() == null)
        {
            return true;
        }

        Type declaredType = declaredV._type();
        Type actualType = actualV._type();

        // TypeParameter compatibility: same name = compatible; one-sided TypeParameter = assume compatible
        if (declaredType instanceof TypeParameter declaredTP)
        {
            if (actualType instanceof TypeParameter actualTP)
            {
                return declaredTP._name().equals(actualTP._name());
            }
            return true;
        }
        if (actualType instanceof TypeParameter)
        {
            return true;
        }

        // FunctionTypes are structural
        if (declaredType instanceof FunctionType declaredFT && actualType instanceof FunctionType actualFT)
        {
            return _FunctionType.isCompatible(declaredFT, actualFT, contravariant, model);
        }

        // RelationTypes are structural
        if (declaredType instanceof meta.pure.metamodel.relation.RelationType declaredRT
                && actualType instanceof meta.pure.metamodel.relation.RelationType actualRT)
        {
            return _RelationType.isCompatible(declaredRT, actualRT, contravariant, model);
        }

        if (contravariant && _Type.isBottomType(actualType))
        {
            return true;
        }

        boolean rawCompatible;
        if (!contravariant)
        {
            rawCompatible = _Type.subtypeOf(actualType, declaredType, model);
        }
        else
        {
            rawCompatible = _Type.subtypeOf(declaredType, actualType, model);
        }

        if (!rawCompatible)
        {
            return false;
        }

        GenericType resolvedActual = declaredType != actualType ? resolveForTarget(actual, declaredType, model) : actual;

        if (resolvedActual == null)
        {
            return true;
        }

        if (resolvedActual instanceof GenericTypeValue resolvedActualV
                && declaredV._typeArguments().notEmpty() && resolvedActualV._typeArguments().notEmpty())
        {
            if (declaredV._typeArguments().size() != resolvedActualV._typeArguments().size())
            {
                return false;
            }

            MutableList<TypeParameter> typeParams = (declaredType instanceof Class cls && cls._typeParameters() != null)
                    ? cls._typeParameters()
                    : null;

            for (int i = 0; i < declaredV._typeArguments().size(); i++)
            {
                boolean argContravariant = contravariant;
                if (typeParams != null && i < typeParams.size())
                {
                    Boolean isContra = typeParams.get(i)._contravariant();
                    if (Boolean.TRUE.equals(isContra))
                    {
                        argContravariant = !argContravariant;
                    }
                }

                if (!isCompatible(declaredV._typeArguments().get(i), resolvedActualV._typeArguments().get(i), argContravariant, model))
                {
                    return false;
                }
            }
        }

        // Check multiplicity arguments
        if (declaredV._multiplicityArguments() != null && declaredV._multiplicityArguments().notEmpty())
        {
            MutableList<Multiplicity> actualMulArgs = resolvedActual instanceof GenericTypeValue resolvedActualV2
                    ? resolvedActualV2._multiplicityArguments()
                    : null;
            if (actualMulArgs != null && actualMulArgs.notEmpty())
            {
                if (declaredV._multiplicityArguments().size() != actualMulArgs.size())
                {
                    return false;
                }
                return declaredV._multiplicityArguments().zip(actualMulArgs).allSatisfy(pair ->
                        _Multiplicity.subsumes(pair.getOne(), pair.getTwo()));
            }
        }

        return true;
    }


    /**
     * Walk two GenericTypes in parallel and widen any {@link Inferred} types and multiplicities
     * in {@code actual} to match {@code expected}. This reconciles stale compiler-computed
     * values (e.g. from pre-unification lambda resolution) with the expected values
     * /**
     * Reconcile inferred types by walking expected and actual generic types in parallel.
     * Returns a new GenericType if any nested FunctionType, RelationType, GenericTypeOperation,
     * or typeArgument was widened, or the same {@code actual} reference if nothing changed.
     * <p>
     * When expected and actual have different raw types (e.g. {@code Function<{FT}>} vs
     * {@code Property<Nil, Any|*>}), the expected is resolved down to the actual's raw type
     * level using {@link #resolveForTarget} so that type arguments align correctly.
     * <p>
     * Pure functional — no in-place mutation.
     * The caller is responsible for integrating the returned copy into the tree.
     */
    public static GenericType reconcileInferred(GenericType expected, GenericType actual, MetadataAccess model)
    {
        // GenericTypeOperations: recurse into left and right
        if (expected instanceof GenericTypeOperation expectedOp && actual instanceof GenericTypeOperation actualOp)
        {
            return _GenericTypeOperation.reconcileInferred(expectedOp, actualOp, model);
        }

        if (expected instanceof GenericTypeValue expectedV && actual instanceof GenericTypeValue actualV)
        {
            Type expectedType = expectedV._type();
            Type actualType = actualV._type();

            // When raw types differ (e.g. Function vs Property), resolve expected
            // to the actual's raw type level so type arguments align.
            if (expectedType != actualType)
            {
                GenericType resolvedExpected = resolveForTarget(expected, actualType, model);
                if (resolvedExpected != null)
                {
                    return reconcileInferred(resolvedExpected, actual, model);
                }
                // Can't resolve — return actual unchanged
                return actual;
            }

            Type newType = actualType;

            // FunctionTypes: reconcile param and return types/multiplicities
            if (expectedType instanceof FunctionType expectedFT && actualType instanceof FunctionType actualFT)
            {
                newType = _FunctionType.reconcileInferred(expectedFT, actualFT, model);
            }

            // RelationTypes: reconcile column types/multiplicities
            if (expectedType instanceof RelationType expectedRT && actualType instanceof RelationType actualRT)
            {
                newType = _RelationType.reconcileInferred(expectedRT, actualRT, model);
            }

            return (actual instanceof Inferred ? new InferredGenericTypeImpl(model) : new UserDefinedGenericTypeImpl(model))
                    ._type(newType)
                    ._typeArguments(expectedV._typeArguments().zip(actualV._typeArguments())
                            .collect(pair -> reconcileInferred(pair.getOne(), pair.getTwo(), model)))
                    ._multiplicityArguments(actualV._multiplicityArguments())
                    ._typeVariableValues(actualV._typeVariableValues());
        }
        return actual;
    }

}
