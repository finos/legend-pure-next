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
    public static GenericType compile(meta.pure.protocol.grammar.type.generics.GenericType_Protocol grammarGenericType, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        if (grammarGenericType instanceof meta.pure.protocol.grammar.type.generics.GenericType_Pointer ptr)
        {
            // GenericType_Pointer is a PDB-serialization construct — it should
            // never appear in compile-time grammar. If we see one here, the
            // parser or some upstream layer is leaking the PDB form into
            // compilation. Throw with enough context to track it down.
            throw new IllegalStateException("GenericType_Pointer reached compiler (path='" + ptr._value()
                    + "'). This is a PDB-only concept; grammar values should always be inline GenericType subtypes. "
                    + "Check parser/protocol-generator output.");
        }
        meta.pure.protocol.grammar.type.generics.GenericType inline = (meta.pure.protocol.grammar.type.generics.GenericType) grammarGenericType;
        return switch (inline)
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

                // Scope-aware Type_Pointer resolution: if the pointer's value matches
                // a type parameter in scope, promote it to TypeParameter. This lets
                // the parser stay scope-free — it always emits Type_Pointer and the
                // compiler disambiguates here. Symmetric to compiler-pure.
                if (gtv._type() instanceof Type_Pointer asPtr)
                {
                    PureLanguageCompilerContext plcc = context.compilerContextExtensions(PureLanguageCompilerContext.class);
                    if (plcc != null)
                    {
                        TypeParameter scoped = plcc.lookupTypeParameter(asPtr._value());
                        if (scoped != null)
                        {
                            yield _GenericType.buildUserDefinedGenericType(scoped, model);
                        }
                    }
                }

                Type rawType = resolveType(gtv._type(), imports, model, context);
                if (rawType == null)
                {
                    yield null;
                }

                meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl built = _GenericType.buildUserDefinedGenericType(rawType, model)
                        ._typeArguments(gtv._typeArguments().collect(arg -> compile(arg, imports, model, context)))
                        ._multiplicityArguments(gtv._multiplicityArguments().collect(m -> MultiplicityCompiler.compile(m, model, context)))
                        ._typeVariableValues(gtv._typeVariableValues().collect(vs -> ValueSpecificationCompiler.compile(vs, imports, model, context)));
                // Canonical-anchor preservation: when the compiled GT is a leaf
                // (no type/mult/typeVar args) wrapping a PE rawType with a
                // `GenericType_<fullPath>` UDPGT-PE in core.pdb, return the
                // canonical anchor instead of the fresh inline UDGT. Anchor
                // key encodes the FULL path so user modules whose simple
                // names collide with platform types don't pick up the wrong
                // anchor. Mirrors the platform-level preferCanonicalAnchor
                // applied by `new()`/`copy()` natives.
                if ((built._typeArguments() == null || built._typeArguments().isEmpty())
                        && (built._multiplicityArguments() == null || built._multiplicityArguments().isEmpty())
                        && (built._typeVariableValues() == null || built._typeVariableValues().isEmpty())
                        && rawType instanceof PackageableElement rawPE)
                {
                    String typePath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(rawPE);
                    if (typePath != null && !typePath.isEmpty())
                    {
                        Object canonical = model.getElement("meta::pure::metamodel::type::generics::optimization::GenericType_"
                                + typePath.replace("::", "_"));
                        if (canonical instanceof meta.pure.metamodel.type.generics.GenericTypeValue canonicalGT && canonicalGT._type() == rawType)
                        {
                            yield canonicalGT;
                        }
                    }
                }
                yield built;
            }
            default -> throw new IllegalArgumentException("Unexpected GenericType: " + inline.getClass());
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
        String pointerValue = pointer._value();

        int checkpoint = context.currentErrorCount();
        SourceInformation sourceInfo = SourceInformationCompiler.compile(pointer._p_sourceInformation(), context.getSourceId(), model);
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
        return new TypeParameterImpl(model)
                ._name(grammarTypeParameter._name())
                ._contravariant(grammarTypeParameter._contravariant() != null ? grammarTypeParameter._contravariant() : false)
                ._owner(owner);
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
                        ._contravariant(grammarTypeParameter._contravariant() != null ? grammarTypeParameter._contravariant() : false);

    }
}
