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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.relation.GenericTypeOperationImpl;
import meta.pure.metamodel.relation.GenericTypeOperationType;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.TypeParameter;
import meta.pure.metamodel.type.generics.TypeParameterImpl;
import meta.pure.protocol.grammar.relation.GenericTypeOperation;
import meta.pure.protocol.grammar.type.Type_Pointer;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Unit;

/**
 * Compiles a grammar-level {@link meta.pure.protocol.grammar.type.generics.GenericType}
 * into a metamodel-level {@link GenericType}, resolving pointer references
 * via a {@link PureModel} and a list of imports.
 */
public final class GenericTypeCompiler
{
    private GenericTypeCompiler()
    {
    }

    /**
     * Compile a grammar GenericType into a metamodel GenericType.
     *
     * <p>
     * Each {@link Type_Pointer} reference in the grammar tree is resolved
     * to the actual {@link Type} instance held in the model.  Resolution
     * tries the pointer value as a fully-qualified path first, then falls
     * back to prepending each import package.
     * </p>
     *
     * @param grammarGenericType the grammar-level generic type to compile
     * @param imports            import package paths from the enclosing section
     * @param model              the compiled PureModel used for element lookup
     * @param context            the compilation context for error collection
     * @return a fully resolved metamodel GenericType, or null if the type can't be resolved
     */
    public static GenericType compile(meta.pure.protocol.grammar.type.generics.GenericType grammarGenericType, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        // GenericTypeOperation (type algebra: T+R, T-Z, Z=K, Z⊆T)
        if (grammarGenericType instanceof GenericTypeOperation gto)
        {
            return compileGenericTypeOperation(gto, imports, model, context);
        }

        // Type parameter reference (e.g., T) — no raw type to resolve
        if (grammarGenericType._rawType() == null)
        {
            return new ConcreteGenericTypeImpl()
                    ._typeParameter(compileTypeParameter(grammarGenericType._typeParameter()));
        }

        Type rawType = resolveType(grammarGenericType._rawType(), imports, model, context);
        if (rawType == null)
        {
            return null;
        }

        return new ConcreteGenericTypeImpl()
                ._rawType(rawType)
                ._typeArguments(grammarGenericType._typeArguments().collect(arg -> compile(arg, imports, model, context)))
                ._multiplicityArguments(grammarGenericType._multiplicityArguments().collect(m -> MultiplicityCompiler.compile(m, model)))
                ._typeVariableValues(grammarGenericType._typeVariableValues().collect(vs -> ValueSpecificationCompiler.compile(vs, imports, model, context)))
                ._typeParameter(compileTypeParameter(grammarGenericType._typeParameter()));
    }

    private static GenericType compileGenericTypeOperation(GenericTypeOperation gto, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        GenericType left = compile(gto._left(), imports, model, context);
        GenericType right = compile(gto._right(), imports, model, context);
        GenericTypeOperationType opType = switch (gto._type())
        {
            case UNION -> GenericTypeOperationType.UNION;
            case DIFFERENCE -> GenericTypeOperationType.DIFFERENCE;
            case SUBSET -> GenericTypeOperationType.SUBSET;
            case EQUAL -> GenericTypeOperationType.EQUAL;
        };
        return new GenericTypeOperationImpl()
                ._left(left)
                ._right(right)
                ._type(opType);
    }

    private static Type resolveType(meta.pure.protocol.grammar.type.Type_Protocol rawType, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        // FunctionType (e.g., {K[1]->Boolean[1]}) — compile into a metamodel FunctionType
        if (rawType instanceof meta.pure.protocol.grammar.type.FunctionType ft)
        {
            return FunctionTypeCompiler.compile(ft, imports, model, context);
        }

        // RelationType (e.g., (name:String[1], age:Integer[1])) — compile into a metamodel RelationType
        if (rawType instanceof meta.pure.protocol.grammar.relation.RelationType rt)
        {
            return RelationTypeCompiler.compile(rt, imports, model, context);
        }

        Type_Pointer pointer = (Type_Pointer) rawType;
        String pointerValue = pointer._pointerValue();

        int checkpoint = context.currentErrorCount();
        SourceInformation sourceInfo = SourceInformationCompiler.compile(pointer._sourceInformation());
        PackageableElement element = _PackageableElement.findElementOrReportError(pointerValue, imports, model, context, sourceInfo);
        if (element instanceof Type type)
        {
            return type;
        }

        // Unit type reference: MeasurePath~UnitName
        if (pointerValue.indexOf('~') >= 0)
        {
            meta.pure.metamodel.type.Unit unit = _Unit.findUnit(pointerValue, imports, model, context, sourceInfo);
            if (unit != null)
            {
                return unit;
            }
        }
        else if (context.currentErrorCount() == checkpoint)
        {
            context.addError(new CompilationError("The type '" + pointerValue + "' can't be found", sourceInfo));
        }
        return null;
    }


    public static TypeParameter compileTypeParameter(meta.pure.protocol.grammar.type.generics.TypeParameter grammarTypeParameter)
    {
        if (grammarTypeParameter == null)
        {
            return null;
        }
        return new TypeParameterImpl()
                ._name(grammarTypeParameter._name())
                ._contravariant(grammarTypeParameter._contravariant());
    }
}
