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
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.TypeParameter;
import meta.pure.metamodel.type.generics.TypeParameterImpl;
import meta.pure.protocol.grammar.relation.GenericTypeOperation;
import meta.pure.protocol.grammar.type.Type_Pointer;
import meta.pure.protocol.grammar.type.generics.GenericTypeValue;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Enumeration;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

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
        return switch (grammarGenericType)
        {
            case meta.pure.protocol.grammar.type.generics.UndefinedGenericType ignored -> new meta.pure.metamodel.type.generics.UndefinedGenericTypeImpl(model);
            case GenericTypeOperation gto -> compileGenericTypeOperation(gto, imports, model, context);
            case GenericTypeValue gtv ->
            {
                // Type parameter reference (e.g., T) — rawType is a protocol TypeParameter
                if (gtv._type() instanceof meta.pure.protocol.grammar.type.generics.TypeParameter grammarTP)
                {
                    yield _GenericType.buildUserDefinedGenericType(resolveOrCompileTypeParameter(grammarTP, context, model), model);
                }

                Type rawType = resolveType(gtv._type(), imports, model, context);
                if (rawType == null)
                {
                    yield null;
                }

                yield _GenericType.buildUserDefinedGenericType(rawType, model)
                        ._typeArguments(gtv._typeArguments().collect(arg -> compile(arg, imports, model, context)))
                        ._multiplicityArguments(gtv._multiplicityArguments().collect(m -> MultiplicityCompiler.compile(m, model, context)))
                        ._typeVariableValues(gtv._typeVariableValues().collect(vs -> ValueSpecificationCompiler.compile(vs, imports, model, context)));
            }
            default -> throw new IllegalArgumentException("Unexpected GenericType: " + grammarGenericType.getClass());
        };
    }

    private static GenericType compileGenericTypeOperation(GenericTypeOperation gto, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        GenericType left = compile(gto._left(), imports, model, context);
        GenericType right = compile(gto._right(), imports, model, context);
        // Read enum value from Enum_Pointer and resolve from graph
        String opName = gto._operationType()._extraPointerValues().getFirst()._value();
        meta.pure.metamodel.relation.GenericTypeOperationType opType = (meta.pure.metamodel.relation.GenericTypeOperationType) _Enumeration.resolveEnumValue("meta::pure::metamodel::relation::GenericTypeOperationType", opName, model);
        return new GenericTypeOperationImpl(model)
                ._left(left)
                ._right(right)
                ._operationType(opType);
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
        SourceInformation sourceInfo = SourceInformationCompiler.compile(pointer._sourceInformation(), model);
        PackageableElement element = _PackageableElement.findElementOrReportError(pointerValue, imports, model, context, sourceInfo);
        if (element instanceof Type type)
        {
            return type;
        }

        if (context.currentErrorCount() == checkpoint)
        {
            context.addError(new CompilationError("The type '" + pointerValue + "' can't be found", sourceInfo));
        }
        return null;
    }


    public static TypeParameter compileTypeParameter(meta.pure.protocol.grammar.type.generics.TypeParameter grammarTypeParameter,
                                                      meta.pure.metamodel.type.generics.TypeAndMultiplicityParametersOwner owner,
                                                      MetadataAccess model)
    {
        if (grammarTypeParameter == null)
        {
            return null;
        }
        TypeParameterImpl tp = new TypeParameterImpl(model)
                ._name(grammarTypeParameter._name())
                ._contravariant(grammarTypeParameter._contravariant() != null ? grammarTypeParameter._contravariant() : false)
                ._owner(owner);
        tp._classifierGenericType(_GenericType.buildUserDefinedGenericType(
                (meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::type::generics::TypeParameter"), model));
        return tp;
    }

    /**
     * Resolve a type parameter reference by looking up the declared TypeParameter from
     * the compilation scope. Falls back to creating a fresh TypeParameter if not in scope
     * (e.g. during bootstrap or when compiling type arguments in class definitions).
     */
    private static TypeParameter resolveOrCompileTypeParameter(meta.pure.protocol.grammar.type.generics.TypeParameter grammarTypeParameter,
                                                                CompilationContext context, MetadataAccess model)
    {
        if (grammarTypeParameter == null)
        {
            return null;
        }
        PureLanguageCompilerContext plcc = context.compilerContextExtensions(PureLanguageCompilerContext.class);
        if (plcc != null)
        {
            TypeParameter scoped = plcc.lookupTypeParameter(grammarTypeParameter._name());
            if (scoped != null)
            {
                return scoped;
            }
        }
        return new TypeParameterImpl(model)
                        ._name(grammarTypeParameter._name())
                        ._contravariant(grammarTypeParameter._contravariant() != null ? grammarTypeParameter._contravariant() : false)
                        ._classifierGenericType(_GenericType.buildUserDefinedGenericType(
                                (meta.pure.metamodel.type.Type) model.getElement("meta::pure::metamodel::type::generics::TypeParameter"), model)
                        );

    }
}
