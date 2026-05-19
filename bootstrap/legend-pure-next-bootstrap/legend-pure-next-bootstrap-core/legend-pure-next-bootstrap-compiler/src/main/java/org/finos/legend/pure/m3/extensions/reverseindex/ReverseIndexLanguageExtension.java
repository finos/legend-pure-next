// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3.extensions.reverseindex;

import meta.pure.metamodel.PackageableElement;
import meta.pure.protocol.grammar.Package_PointerImpl;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilerContextExtension;
import org.finos.legend.pure.m3.module.localModule.topLevel.IndexEntry;

import java.util.List;

/**
 * Language extension for the {@code ###ReverseIndex} test section.
 *
 * <p>The section captures the expected reverse reference index produced
 * during compilation. Built by {@code RecordingMetadataAccess} as a side
 * effect of every {@code getElement} lookup, the index records every
 * {@code (targetPath, callerPath)} pair the compiler resolved. Tests
 * declare the expected pairs in {@code ###ReverseIndex} and the assertion
 * compares them against {@code CompilationResult.referencedBy()}.</p>
 *
 * <p>Mirrors {@code CompilerStatsLanguageExtension}'s 3-class shape:
 * grammar element + impl + extension hooks for first/second/third pass.</p>
 */
public final class ReverseIndexLanguageExtension implements LanguageExtension
{
    @Override
    public String sectionName()
    {
        return "ReverseIndex";
    }

    @Override
    public CompilerContextExtension buildCompilerContextExtension()
    {
        return null;
    }

    @Override
    public String resolveType(Object protocolObject)
    {
        if (protocolObject instanceof ReverseIndex)
        {
            return "meta::pure::compiler::test::ReverseIndex";
        }
        return null;
    }

    @Override
    public List<meta.pure.protocol.grammar.PackageableElement> parseSection(String content, String sourceId, int lineOffset)
    {
        return List.of(
                new ReverseIndex()
                        ._value(content)
                        ._package(new Package_PointerImpl()._value(sourceId)));
    }

    @Override
    public PackageableElement firstPass(meta.pure.protocol.grammar.PackageableElement grammar, MetadataAccess model, CompilationContext context)
    {
        if (grammar instanceof ReverseIndex ri)
        {
            return new ReverseIndexImpl()
                    ._name(ri._name())
                    ._value(ri._value());
        }
        return null;
    }

    @Override
    public PackageableElement secondPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        if (entry.grammarElement() instanceof ReverseIndex)
        {
            return entry.element();
        }
        return null;
    }

    @Override
    public PackageableElement thirdPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        if (entry.grammarElement() instanceof ReverseIndex)
        {
            return entry.element();
        }
        return null;
    }
}
