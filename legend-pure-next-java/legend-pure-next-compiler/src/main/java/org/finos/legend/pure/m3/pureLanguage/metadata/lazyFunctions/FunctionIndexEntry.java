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

package org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions;

import meta.pure.metamodel.Package;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.constraint.Constraint;
import meta.pure.metamodel.extension.Stereotype;
import meta.pure.metamodel.extension.TaggedValue;
import meta.pure.metamodel.function.PackageableFunction;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.multiplicity.MultiplicityParameter;
import meta.pure.metamodel.multiplicity.UserDefinedMultiplicityParameterImpl;
import meta.pure.metamodel.type.ElementOverride;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.TypeParameter;
import meta.pure.metamodel.type.generics.TypeParameterImpl;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._FunctionType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;

import java.util.Comparator;

/**
 * An entry in the function index that implements {@link PackageableFunction}
 * as a lightweight, lazy proxy.
 * <p>
 * Most properties are answered directly from the {@link FunctionType} and
 * path information stored at index construction time. Properties that
 * require the full compiled element (e.g., {@code _package()},
 * {@code _stereotypes()}) are lazily resolved via
 * {@code model.getElement(fullPath)} only when actually accessed at
 * execution time — never during compilation.
 * </p>
 */
public abstract class FunctionIndexEntry implements PackageableFunction
{
    /**
     * When true, any call to {@link #resolve()} will throw an {@link AssertionError}.
     * Enable this in tests to verify that compilation never triggers function element loading.
     */
    private static volatile boolean failOnResolve = false;

    public static void setFailOnResolve(boolean fail)
    {
        failOnResolve = fail;
    }

    public static boolean isFailOnResolve()
    {
        return failOnResolve;
    }

    private final FunctionType functionType;
    private final String fullPath;
    private final String functionName;
    private final MetadataAccess model;
    private final int typeParamCount;
    private final int multiplicityParamCount;
    private final MutableList<TypeParameter> typeParameters;
    private final MutableList<MultiplicityParameter> multiplicityParameters;

    private volatile PackageableFunction resolved;

    protected FunctionIndexEntry(String fullPath, String functionName, FunctionType functionType, MetadataAccess model)
    {
        this.functionType = functionType;
        this.fullPath = fullPath;
        this.functionName = functionName;
        this.model = model;

        MutableSet<String> typeParamNames = _FunctionType.collectReferencedTypeParameterNames(functionType);
        this.typeParamCount = typeParamNames.size();
        this.typeParameters = typeParamNames.collect(name ->
                                {
                                    TypeParameterImpl tp = new TypeParameterImpl(model)._name(name)._owner(this);
                                    tp._classifierGenericType(_GenericType.buildUserDefinedGenericType((Type) model.getElement("meta::pure::metamodel::type::generics::TypeParameter"), model));
                                    return (TypeParameter) tp;
                                }).toList();

        MutableSet<String> mulParamNames = _FunctionType.collectReferencedMultiplicityParameterNames(functionType);
        this.multiplicityParamCount = mulParamNames.size();
        this.multiplicityParameters = mulParamNames.collect(name ->
                (MultiplicityParameter) new UserDefinedMultiplicityParameterImpl(model)._name(name)._owner(this)).toList();
    }

    // ========================================================================
    // Direct accessors (no lazy resolution needed)
    // ========================================================================

    public FunctionType functionType()
    {
        return functionType;
    }

    public String fullPath()
    {
        return fullPath;
    }

    public int typeParamCount()
    {
        return typeParamCount;
    }

    public int multiplicityParamCount()
    {
        return multiplicityParamCount;
    }

    // ========================================================================
    // PackageableFunction — answered from existing data
    // ========================================================================

    @Override
    public String _functionName()
    {
        return functionName;
    }

    @Override
    public MutableList<VariableExpression> _parameters()
    {
        return functionType._parameters();
    }

    @Override
    public GenericType _returnGenericType()
    {
        return functionType._returnType();
    }

    @Override
    public Multiplicity _returnMultiplicity()
    {
        return functionType._returnMultiplicity();
    }

    @Override
    public MutableList<TypeParameter> _typeParameters()
    {
        return typeParameters;
    }

    @Override
    public MutableList<MultiplicityParameter> _multiplicityParameters()
    {
        return multiplicityParameters;
    }

    // ========================================================================
    // PackageableElement — answered from path data
    // ========================================================================

    @Override
    public String _name()
    {
        // The fullPath is the function ID like "meta::pure::functions::boolean::greaterThan_Number_1__Number_1__Boolean_1_"
        // _name() should return just the element name (last segment)
        int sep = fullPath.lastIndexOf("::");
        return sep >= 0 ? fullPath.substring(sep + 2) : fullPath;
    }

    // ========================================================================
    // Any — classifierGenericType built from FunctionType
    // ========================================================================

    @Override
    public GenericType _classifierGenericType()
    {
        // Build PackageableFunction<{FunctionType}> from the stored FunctionType
        Type pfType = model != null ? (Type) model.getElement("meta::pure::metamodel::function::PackageableFunction") : null;
        return _GenericType.buildUserDefinedGenericType(pfType, model)
                ._typeArguments(Lists.mutable.with(_GenericType.buildUserDefinedGenericType(functionType, model)));
    }

    // ========================================================================
    // Lazy-resolved properties (delegate to real PackageableFunction)
    // ========================================================================

    protected PackageableFunction resolve()
    {
        if (resolved == null)
        {
            if (failOnResolve)
            {
                throw new AssertionError("FunctionIndexEntry.resolve() was called during compilation for: " + fullPath);
            }
            resolved = (PackageableFunction) model.getElement(fullPath);
        }
        return resolved;
    }

    @Override
    public Package _package()
    {
        // Derive the package from the fullPath prefix — no lazy resolution needed
        int sep = fullPath.lastIndexOf("::");
        if (sep > 0 && model != null)
        {
            String pkgPath = fullPath.substring(0, sep);
            return (Package) model.getElement(pkgPath);
        }
        return null;
    }

    @Override
    public MutableList<Stereotype> _stereotypes()
    {
        return resolve()._stereotypes();
    }

    @Override
    public MutableList<TaggedValue> _taggedValues()
    {
        return resolve()._taggedValues();
    }

    @Override
    public MutableList<Constraint> _preConstraints()
    {
        return resolve()._preConstraints();
    }

    @Override
    public MutableList<Constraint> _postConstraints()
    {
        return resolve()._postConstraints();
    }

    @Override
    public ElementOverride _elementOverride()
    {
        return null;
    }

    @Override
    public SourceInformation _sourceInformation()
    {
        return resolve()._sourceInformation();
    }

    // ========================================================================
    // Read-only setters — throw UnsupportedOperationException
    // ========================================================================

    @Override
    public PackageableFunction _functionName(String value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public PackageableFunction _returnMultiplicity(Multiplicity value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public PackageableFunction _returnGenericType(GenericType value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public PackageableFunction _typeParameters(MutableList<TypeParameter> value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public PackageableFunction _multiplicityParameters(MutableList<MultiplicityParameter> value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public PackageableFunction _preConstraints(MutableList<Constraint> value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public PackageableFunction _postConstraints(MutableList<Constraint> value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public meta.pure.metamodel.function.FunctionWithParameters _parameters(MutableList<VariableExpression> value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public PackageableElement _package(Package value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public PackageableElement _name(String value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public meta.pure.metamodel.extension.ElementWithTaggedValues _taggedValues(MutableList<TaggedValue> value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public meta.pure.metamodel.extension.ElementWithStereotypes _stereotypes(MutableList<Stereotype> value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public meta.pure.metamodel.type.Any _elementOverride(ElementOverride value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public meta.pure.metamodel.type.Any _classifierGenericType(GenericType value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public meta.pure.metamodel.type.Any _sourceInformation(SourceInformation value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    @Override
    public FunctionIndexEntry _copy()
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }

    // ========================================================================
    // Specificity comparator
    // ========================================================================

    /**
     * Comparator that orders entries from most specific to least specific.
     * Concrete parameter types are considered more specific than type parameters.
     * When types are equal, tighter multiplicities (e.g. [1] vs [*]) are more specific.
     * When parameter types are equal, fewer type parameters = more constrained = more specific.
     */
    public static Comparator<FunctionIndexEntry> mostSpecificFirst(MetadataAccess model)
    {
        return (e1, e2) ->
        {
            int cmp = compareFunctionTypes(e1.functionType(), e2.functionType(), model);
            if (cmp != 0)
            {
                return cmp;
            }
            // Tiebreaker: fewer type parameters = more constrained = more specific
            int typeParamCmp = Integer.compare(e1.typeParamCount(), e2.typeParamCount());
            if (typeParamCmp != 0)
            {
                return typeParamCmp;
            }
            // Tiebreaker: fewer multiplicity parameters = more constrained = more specific
            return Integer.compare(e1.multiplicityParamCount(), e2.multiplicityParamCount());
        };
    }

    /**
     * Compare two FunctionTypes by parameter specificity.
     */
    public static int compareFunctionTypes(FunctionType f1, FunctionType f2, MetadataAccess model)
    {
        MutableList<VariableExpression> params1 = f1._parameters();
        MutableList<VariableExpression> params2 = f2._parameters();
        int count = Math.min(params1.size(), params2.size());

        for (int i = 0; i < count; i++)
        {
            Type t1 = resolvedType(params1.get(i));
            Type t2 = resolvedType(params2.get(i));

            // Type parameter (e.g. T) vs concrete type:
            // - T is MORE specific than Any (the top type), because T binds to
            //   the exact type of the actual argument.
            // - T is LESS specific than any other concrete type (e.g. String, Integer).
            if (t1 != null && t2 == null)
            {
                return _Type.isTopType(t1) ? 1 : -1;
            }
            if (t1 == null && t2 != null)
            {
                return _Type.isTopType(t2) ? -1 : 1;
            }

            if (t1 != null && t1 != t2)
            {
                MutableList<Type> lin1 = _Type.linearize(t1, model);
                if (lin1.contains(t2))
                {
                    return -1;
                }

                MutableList<Type> lin2 = _Type.linearize(t2, model);
                if (lin2.contains(t1))
                {
                    return 1;
                }

                String name1 = (t1 instanceof PackageableElement pe1) ? pe1._name() : "";
                String name2 = (t2 instanceof PackageableElement pe2) ? pe2._name() : "";
                int nameCmp = name1.compareTo(name2);
                if (nameCmp != 0)
                {
                    return nameCmp;
                }
            }

            Multiplicity m1 = params1.get(i)._multiplicity();
            Multiplicity m2 = params2.get(i)._multiplicity();

            // Multiplicity parameter (e.g. [m]) vs concrete:
            // - [m] is MORE specific than [*] (the universal wildcard), because
            //   [m] binds to the exact multiplicity of the actual argument.
            // - [m] is LESS specific than any other concrete multiplicity (e.g. [1], [0..1]).
            boolean m1IsParam = m1 instanceof MultiplicityParameter;
            boolean m2IsParam = m2 instanceof MultiplicityParameter;
            if (m1IsParam && !m2IsParam)
            {
                return _Multiplicity.isUniversal(m2) ? -1 : 1;
            }
            if (!m1IsParam && m2IsParam)
            {
                return _Multiplicity.isUniversal(m1) ? 1 : -1;
            }

            if (!m1IsParam && !m2IsParam)
            {
                boolean m1SubsumesM2 = _Multiplicity.subsumes(m1, m2);
                boolean m2SubsumesM1 = _Multiplicity.subsumes(m2, m1);
                if (m1SubsumesM2 && !m2SubsumesM1)
                {
                    return 1;
                }
                if (m2SubsumesM1 && !m1SubsumesM2)
                {
                    return -1;
                }
            }
        }

        return 0;
    }

    private static Type resolvedType(ValueSpecification vs)
    {
        GenericType gt = vs._genericType();
        return gt != null ? _GenericType.type(gt) : null;
    }

    // ========================================================================
    // Signature
    // ========================================================================

    /**
     * Build a human-readable signature from the FunctionType and path,
     * e.g. {@code "pack::myFunc(param1: String[1], param2: Integer[*]): Boolean[1]"}.
     * This avoids loading the full PackageableFunction element.
     */
    public String signature()
    {
        StringBuilder sb = new StringBuilder();

        // Use the human-readable function name with package prefix
        int lastSep = fullPath.lastIndexOf("::");
        String pkgPrefix = lastSep > 0 ? fullPath.substring(0, lastSep) + "::" : "";
        sb.append(pkgPrefix).append(functionName);

        // Parameters
        sb.append('(');
        if (functionType._parameters() != null)
        {
            boolean first = true;
            for (VariableExpression param : functionType._parameters())
            {
                if (!first)
                {
                    sb.append(", ");
                }
                first = false;
                sb.append(param._name());
                sb.append(": ");
                sb.append(_GenericType.print(param._genericType(), false));
                sb.append(formatMultiplicity(param._multiplicity()));
            }
        }
        sb.append(')');

        // Return type
        sb.append(": ");
        sb.append(_GenericType.print(functionType._returnType(), false));
        sb.append(formatMultiplicity(functionType._returnMultiplicity()));

        return sb.toString();
    }

    private static String formatMultiplicity(Multiplicity m)
    {
        if (m == null)
        {
            return "[*]";
        }
        if (m instanceof MultiplicityParameter mp)
        {
            return "[" + mp._name() + "]";
        }
        long lower = 0;
        Long upper = null;
        if (m instanceof meta.pure.metamodel.multiplicity.ConcreteMultiplicity cm)
        {
            lower = cm._lowerBound() != null ? cm._lowerBound()._value() : 0;
            upper = cm._upperBound() != null ? cm._upperBound()._value() : null;
        }
        if (upper == null)
        {
            return lower == 0 ? "[*]" : "[" + lower + "..*]";
        }
        if (lower == upper)
        {
            return "[" + lower + "]";
        }
        return "[" + lower + ".." + upper + "]";
    }
}
