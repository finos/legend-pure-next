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
 * Truffle-native parser extension for {@code ###File} test sections.
 * Produces a PDO at {@code meta::pure::compiler::test::TestFile} carrying
 * the raw section body. The compiler-test harness reads these via
 * {@code parsedFile.sections} and routes a multi-file compile through
 * {@code compileFiles}.
 */
public final class TruffleTestFileLanguageExtension implements TruffleParserExtension
{
    @Override
    public String sectionName()
    {
        return "File";
    }

    @Override
    public List<Object> parseSection(String content, String sourceId, int lineOffset, TruffleMetadataAccess resolver)
    {
        Object pkg = PureObjBuilder.of("meta::pure::protocol::grammar::Package_Pointer", resolver)
                .put("value", sourceId)
                .build();
        Object tf = PureObjBuilder.of("meta::pure::compiler::test::TestFile", resolver)
                .put("name", "TestFile")
                .put("value", content)
                .put("package", pkg)
                .build();
        return List.of(tf);
    }
}
