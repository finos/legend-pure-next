// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime;

import meta.pure.protocol.grammar.Package_PointerImpl;
import meta.pure.protocol.grammar.PackageableElement;
import org.finos.legend.pure.next.parser.ParserExtension;

import java.util.List;

/**
 * Truffle-native parser extension for {@code ###CompilerStats} test sections.
 *
 * <p>Mirrors {@link TruffleCompiledGraphLanguageExtension} but for the
 * resolver-telemetry section. Creates grammar elements that
 * {@code ProtocolTranslator} routes to the PDB-generated
 * {@code org.finos.legend.pure.truffle.pdb.meta.pure.compiler.test.CompilerStatsImpl}.</p>
 */
public final class TruffleCompilerStatsLanguageExtension implements ParserExtension
{
    @Override
    public String sectionName()
    {
        return "CompilerStats";
    }

    @Override
    public List<PackageableElement> parseSection(String content, String sourceId, int lineOffset)
    {
        meta.pure.compiler.test.CompilerStatsImpl cs = new meta.pure.compiler.test.CompilerStatsImpl();
        cs._name("CompilerStats");
        cs._value(content);
        cs._package(new Package_PointerImpl()._value(sourceId));
        return List.of(cs);
    }
}
