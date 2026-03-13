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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.extension.Profile;
import meta.pure.metamodel.function.NativeFunction;
import meta.pure.metamodel.function.PackageableFunction;
import meta.pure.metamodel.function.UserDefinedFunction;
import meta.pure.metamodel.relationship.Association;
import meta.pure.metamodel.type.Class;
import meta.pure.metamodel.type.Enumeration;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.Measure;
import meta.pure.metamodel.type.PrimitiveType;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilerContextExtension;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilerExtension;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements.AssociationHandler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements.ClassHandler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements.EnumerationHandler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements.MeasureHandler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements.NativeFunctionHandler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements.PrimitiveTypeHandler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements.ProfileHandler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements.UserDefinedFunctionHandler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Function;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import org.finos.legend.pure.m3.module.localModule.topLevel.IndexEntry;
import org.finos.legend.pure.m3.pureLanguage.metadata.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.metadata.PureLanguageMetadata;

/**
 * Core compiler extension for the Pure language.
 *
 * <p>Handles all built-in M3 element types (Class, Enumeration,
 * Function, Profile, Association, Measure, PrimitiveType) across
 * the three compilation passes. Also owns the function index.</p>
 */
public final class PureLanguageCompilerExtension implements CompilerExtension
{
    private final MutableList<PackageableElement> elements = Lists.mutable.empty();

    @Override
    public CompilerContextExtension buildCompilerContextExtension()
    {
        return new PureLanguageCompilerContext();
    }

    @Override
    public PackageableElement firstPass(meta.pure.protocol.grammar.PackageableElement grammar)
    {
        PackageableElement element = switch (grammar)
        {
            case meta.pure.protocol.grammar.type.Class c -> ClassHandler.firstPass(c);
            case meta.pure.protocol.grammar.type.Enumeration e -> EnumerationHandler.firstPass(e);
            case meta.pure.protocol.grammar.function.NativeFunction f -> NativeFunctionHandler.firstPass(f);
            case meta.pure.protocol.grammar.function.UserDefinedFunction f -> UserDefinedFunctionHandler.firstPass(f);
            case meta.pure.protocol.grammar.extension.Profile p -> ProfileHandler.firstPass(p);
            case meta.pure.protocol.grammar.relationship.Association a -> AssociationHandler.firstPass(a);
            case meta.pure.protocol.grammar.type.Measure m -> MeasureHandler.firstPass(m);
            case meta.pure.protocol.grammar.type.PrimitiveType p -> PrimitiveTypeHandler.firstPass(p);
            default -> null;
        };
        elements.add(element);
        return element;
    }

    @Override
    public PackageableElement secondPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        MutableList<String> imports = context.imports();
        return switch (entry.grammarElement())
        {
            case meta.pure.protocol.grammar.type.Class c ->
                    ClassHandler.secondPass((meta.pure.metamodel.type.ClassImpl) entry.element(), c, imports, model, context);
            case meta.pure.protocol.grammar.type.Enumeration e ->
                    EnumerationHandler.secondPass((meta.pure.metamodel.type.EnumerationImpl) entry.element(), e, model);
            case meta.pure.protocol.grammar.function.NativeFunction f ->
                    NativeFunctionHandler.secondPass((meta.pure.metamodel.function.NativeFunctionImpl) entry.element(), f, imports, model, context);
            case meta.pure.protocol.grammar.function.UserDefinedFunction f ->
                    UserDefinedFunctionHandler.secondPass((meta.pure.metamodel.function.UserDefinedFunctionImpl) entry.element(), f, imports, model, context);
            case meta.pure.protocol.grammar.extension.Profile p ->
                    ProfileHandler.secondPass((meta.pure.metamodel.extension.ProfileImpl) entry.element(), p, model);
            case meta.pure.protocol.grammar.relationship.Association a ->
                    AssociationHandler.secondPass((meta.pure.metamodel.relationship.AssociationImpl) entry.element(), a, imports, model, context);
            case meta.pure.protocol.grammar.type.Measure m ->
                    MeasureHandler.secondPass((meta.pure.metamodel.type.MeasureImpl) entry.element(), m, imports, model, context);
            case meta.pure.protocol.grammar.type.PrimitiveType p ->
                    PrimitiveTypeHandler.secondPass((meta.pure.metamodel.type.PrimitiveTypeImpl) entry.element(), p, imports, model, context);
            default -> null;
        };
    }

    @Override
    public void preThirdPass(MetadataAccess localModule, MetadataAccess model)
    {
        localModule.getMetadataAccessExtension(PureLanguageMetadata.class).getFirst().setFunctionIndex(this.buildFunctionIndex(elements, model));
    }

    @Override
    public PackageableElement thirdPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        return switch (entry.element())
        {
            case UserDefinedFunction f -> UserDefinedFunctionHandler.thirdPass(f, model, context);
            case Class cls -> ClassHandler.thirdPass(cls,
                    (meta.pure.protocol.grammar.type.Class) entry.grammarElement(),
                    context.imports(),
                    model,
                    context
            );
            case Association assoc -> AssociationHandler.thirdPass(assoc, model, context);
            case NativeFunction nf -> NativeFunctionHandler.thirdPass(nf, model, context);
            case Profile pr -> ProfileHandler.thirdPass(pr, model, context);
            case Measure me -> MeasureHandler.thirdPass(me, model, context);
            case Enumeration e -> EnumerationHandler.thirdPass(e, (meta.pure.protocol.grammar.type.Enumeration) entry.grammarElement(), context);
            case PrimitiveType pt -> PrimitiveTypeHandler.thirdPass(pt,
                    (meta.pure.protocol.grammar.type.PrimitiveType) entry.grammarElement(),
                    context.imports(),
                    model,
                    context
            );
            default -> null;
        };
    }

    /**
     * Build the function index from all compiled elements.
     * Called at the beginning of the third pass.
     */
    public MutableMap<String, MutableMap<Integer, MutableList<FunctionIndexEntry>>> buildFunctionIndex(MutableList<PackageableElement> elements, MetadataAccess model)
    {
        MutableMap<String, MutableMap<Integer, MutableList<FunctionIndexEntry>>> functionIndex = Maps.mutable.empty();
        functionIndex.clear();
        for (PackageableElement entry : elements)
        {
            if (entry instanceof PackageableFunction fn)
            {
                String shortName = fn._functionName();
                if (shortName == null || shortName.isEmpty())
                {
                    continue;
                }
                int paramCount = fn._parameters() != null ? fn._parameters().size() : 0;
                String path = _PackageableElement.path(fn);
                FunctionType functionType = _Function.resolveFunctionType(fn._classifierGenericType(), model);

                functionIndex
                        .getIfAbsentPut(shortName, Maps.mutable::empty)
                        .getIfAbsentPut(paramCount, Lists.mutable::empty)
                        .add(new FunctionIndexEntry(path, shortName, functionType));
            }
        }

        return functionIndex;
    }
}
