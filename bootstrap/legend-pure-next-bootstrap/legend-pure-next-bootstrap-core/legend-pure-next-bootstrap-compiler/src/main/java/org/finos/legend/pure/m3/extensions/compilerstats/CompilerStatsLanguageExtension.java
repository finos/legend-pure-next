// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3.extensions.compilerstats;

import meta.pure.metamodel.PackageableElement;
import meta.pure.protocol.grammar.Package_PointerImpl;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilerContextExtension;
import org.finos.legend.pure.m3.module.localModule.topLevel.IndexEntry;

import java.util.List;

/**
 * Language extension for the {@code ###CompilerStats} test section.
 *
 * <p>The section captures the deterministic subset of
 * {@link org.finos.legend.pure.m3.module.CompilationStatistics} (total
 * rollback / candidate counts plus the per-call-site rollback histogram). The
 * test harness compares the parsed text to what the compiler actually
 * produced, so a divergence in resolver shape between the Java and Pure
 * compilers shows up as a regression in the spec suite.</p>
 */
public final class CompilerStatsLanguageExtension implements LanguageExtension
{
    @Override
    public String sectionName()
    {
        return "CompilerStats";
    }

    @Override
    public CompilerContextExtension buildCompilerContextExtension()
    {
        return null;
    }

    @Override
    public String resolveType(Object protocolObject)
    {
        if (protocolObject instanceof CompilerStats)
        {
            return "meta::pure::compiler::test::CompilerStats";
        }
        return null;
    }

    @Override
    public List<meta.pure.protocol.grammar.PackageableElement> parseSection(String content, String sourceId, int lineOffset)
    {
        return List.of(
                new CompilerStats()
                        ._value(content)
                        ._package(new Package_PointerImpl()._value(sourceId)));
    }

    @Override
    public PackageableElement firstPass(meta.pure.protocol.grammar.PackageableElement grammar, MetadataAccess model, org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext context)
    {
        if (grammar instanceof CompilerStats cs)
        {
            return new CompilerStatsImpl()
                    ._name(cs._name())
                    ._value(cs._value());
        }
        return null;
    }

    @Override
    public PackageableElement secondPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        if (entry.grammarElement() instanceof CompilerStats)
        {
            return entry.element();
        }
        return null;
    }

    @Override
    public PackageableElement thirdPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        if (entry.grammarElement() instanceof CompilerStats)
        {
            return entry.element();
        }
        return null;
    }
}
