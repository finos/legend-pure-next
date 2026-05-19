// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3.extensions.testfile;

import meta.pure.metamodel.PackageableElement;
import meta.pure.protocol.PureFile;
import meta.pure.protocol.grammar.Package_PointerImpl;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilerContextExtension;
import org.finos.legend.pure.m3.module.localModule.topLevel.IndexEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Language extension for the {@code ###File} test section.
 *
 * <p>The harness uses ###File chunks to encode an additional in-memory
 * source file alongside the primary ###Pure section. The compiler treats
 * the section as a regular {@link PackageableElement} pass-through — Pure
 * code reads the parsed elements (via {@code parsedFile.sections}) and
 * routes the multi-file compile through {@code compileFiles}.</p>
 */
public final class TestFileLanguageExtension implements LanguageExtension
{
    @Override
    public String sectionName()
    {
        return "File";
    }

    @Override
    public CompilerContextExtension buildCompilerContextExtension()
    {
        return null;
    }

    @Override
    public String resolveType(Object protocolObject)
    {
        if (protocolObject instanceof TestFile)
        {
            return "meta::pure::compiler::test::TestFile";
        }
        return null;
    }

    @Override
    public List<meta.pure.protocol.grammar.PackageableElement> parseSection(String content, String sourceId, int lineOffset)
    {
        return List.of(
                new TestFile()
                        ._value(content)
                        ._package(new Package_PointerImpl()._value(sourceId)));
    }

    @Override
    public PackageableElement firstPass(meta.pure.protocol.grammar.PackageableElement grammar, MetadataAccess model, org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext context)
    {
        if (grammar instanceof TestFile tf)
        {
            return new TestFileImpl()
                    ._name(tf._name())
                    ._value(tf._value());
        }
        return null;
    }

    @Override
    public PackageableElement secondPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        if (entry.grammarElement() instanceof TestFile)
        {
            return entry.element();
        }
        return null;
    }

    @Override
    public PackageableElement thirdPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        if (entry.grammarElement() instanceof TestFile)
        {
            return entry.element();
        }
        return null;
    }

    /**
     * Splits a compiler test source into a {@link PureContent} per file: the
     * primary ###Pure section under {@code testName} plus one PureContent per
     * ###File chunk (sourceId from the chunk's first non-empty line).
     *
     * <p>Used by {@code CompilerCompiledGraphTest} / {@code CompilerErrorTest}
     * to drive multi-file repros through the bootstrap compiler the same way
     * {@code compileFiles} drives them through compile-pure on Truffle.</p>
     */
    public static List<PureContent> splitTestFiles(String rawContent, String testName, PureFile parsedFile)
    {
        List<PureContent> result = new ArrayList<>();
        result.add(new PureContent(slicePrimaryPureContent(rawContent), testName));
        parsedFile._sections().forEach(section ->
        {
            if (!"File".equals(section._parserName()))
            {
                return;
            }
            section._elements().forEach(elem ->
            {
                if (!(elem instanceof TestFile tf))
                {
                    return;
                }
                String raw = tf._value().strip();
                int nl = raw.indexOf('\n');
                if (nl < 0)
                {
                    result.add(new PureContent("###Pure\n", raw));
                    return;
                }
                String name = raw.substring(0, nl).strip();
                String body = raw.substring(nl + 1);
                result.add(new PureContent("###Pure\n" + body, name));
            });
        });
        return result;
    }

    private static String slicePrimaryPureContent(String rawContent)
    {
        int pureStart = rawContent.indexOf("###Pure");
        if (pureStart < 0)
        {
            return rawContent;
        }
        String fromPure = rawContent.substring(pureStart);
        int end = fromPure.length();
        for (String terminator : new String[]{"\n###File", "\n###Error", "\n###CompiledGraph", "\n###CompilerStats"})
        {
            int idx = fromPure.indexOf(terminator);
            if (idx >= 0 && idx < end)
            {
                end = idx;
            }
        }
        return fromPure.substring(0, end);
    }
}
