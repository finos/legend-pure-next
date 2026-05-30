// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime;

import org.finos.legend.pure.truffle.parser.topLevel.TruffleParserExtension;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObjBuilder;

import java.util.List;

/**
 * Truffle-native parser extension for {@code ###CompilerStats} test sections.
 * Produces a PDO at {@code meta::pure::compiler::test::CompilerStats}.
 */
public final class TruffleCompilerStatsLanguageExtension implements TruffleParserExtension
{
    @Override
    public String sectionName()
    {
        return "CompilerStats";
    }

    @Override
    public List<Object> parseSection(String content, String sourceId, int lineOffset, TruffleMetadataAccess resolver)
    {
        Object pkg = PureObjBuilder.of("meta::pure::protocol::grammar::Package_Pointer", resolver)
                .put("value", sourceId)
                .build();
        Object cs = PureObjBuilder.of("meta::pure::compiler::test::CompilerStats", resolver)
                .put("name", "CompilerStats")
                .put("value", content)
                .put("package", pkg)
                .build();
        return List.of(cs);
    }
}
