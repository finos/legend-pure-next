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

package org.finos.legend.pure.runtime.compilation;

import org.eclipse.collections.impl.factory.Maps;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m3.module.ElementStatistics;
import org.finos.legend.pure.m3.module.CompilationStatistics;
import org.finos.legend.pure.m3.module.sourceModule.PureContent;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.function.FunctionWithParameters;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.PureMap;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.compilation.Compilation;
import org.finos.legend.pure.m3.compilation.CompilationContext;
import org.finos.legend.pure.m3.compilation.CompilationUnavailableException;
import org.finos.legend.pure.m3.compilation.FunctionExecutor;
import org.finos.legend.pure.m3.module.CompilationError;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.sourceModule.SourceModule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Pure compiler (compiler-pure, loaded from compiler.pdb) as a compilation strategy:
 * {@code parseDirs} then {@code compile}, executed through the runtime. The same compiler
 * every host runs.
 */
public final class PureCompilation implements Compilation
{
    private static final String PARSE_FN = "meta::pure::functions::meta::parse_String_1__String_1__PureFile_1_";
    private static final String PARSE_DIRS_FN = "meta::pure::compiler::parseDirs_String_MANY__PureFile_MANY_";
    private static final String COMPILE_FN = "meta::pure::compiler::compile_PureFile_MANY__CompilationResult_1_";

    private final MetadataAccess registry;
    private final FunctionExecutor executor;

    private PureCompilation(CompilationContext context)
    {
        this.registry = context.registry();
        this.executor = context.executor();
    }

    public static Builder builder()
    {
        return new Builder();
    }

    @Override
    public CompilationResult compile(SourceModule module)
    {
        List<PureContent> contents = module.sources();
        if (contents != null)
        {
            FunctionWithParameters parse = compilerFunction(PARSE_FN);
            List<Object> files = new ArrayList<>();
            for (PureContent content : contents)
            {
                files.add(this.executor.execute(parse, content.sourceId(), content.content()));
            }
            return compileParsed(files);
        }
        List<Path> folders = module.sourceFolders();
        if (folders == null || folders.isEmpty())
        {
            throw new IllegalArgumentException("module '" + module.name() + "' has no sources");
        }
        FunctionWithParameters parseDirs = compilerFunction(PARSE_DIRS_FN);
        Object files = this.executor.execute(parseDirs, folders.stream().map(f -> f.toAbsolutePath().toString()).toList());
        return compileParsed(files instanceof List<?> list ? list : files == null ? List.of() : List.of(files));
    }

    @Override
    public CompilationResult compileParsed(List<?> pureFiles)
    {
        Object result = this.executor.execute(compilerFunction(COMPILE_FN), pureFiles);
        if (!(result instanceof DynamicInstance compilationResult))
        {
            throw new RuntimeException("compile did not return a CompilationResult (got "
                    + (result == null ? "null" : result.getClass().getName()) + ")");
        }
        List<CompilationError> errors = new ArrayList<>();
        for (Object error : asList(compilationResult.get("errors")))
        {
            errors.add(new CompilationError(String.valueOf(error), null));
        }
        List<PackageableElement> elements = new ArrayList<>();
        for (Object element : asList(compilationResult.get("elements")))
        {
            if (element instanceof PackageableElement packageableElement)
            {
                elements.add(packageableElement);
            }
        }
        return new CompilationResult(elements, errors, statistics(compilationResult.get("statistics")), referencedBy(compilationResult));
    }

    private FunctionWithParameters compilerFunction(String path)
    {
        Object function = this.registry.getElement(path);
        if (function == null)
        {
            throw new CompilationUnavailableException("the compiler module is not loaded");
        }
        return (FunctionWithParameters) function;
    }

    /**
     * compiler-pure's {@code CompilationStatistics} (milliseconds) as the Java record (nanoseconds).
     * compiler-pure measures no memory delta, and only each element's total time — recorded here
     * as its first-pass time.
     */
    private static CompilationStatistics statistics(Object value)
    {
        if (!(value instanceof DynamicInstance s))
        {
            return null;
        }
        MutableMap<String, ElementStatistics> elementStatistics = Maps.mutable.empty();
        for (Object element : asList(s.get("elementStatistics")))
        {
            if (element instanceof DynamicInstance e)
            {
                String path = String.valueOf(e.get("elementPath"));
                elementStatistics.put(path, new ElementStatistics(path, String.valueOf(e.get("elementType")),
                        millisToNanos(e.get("totalMillis")), 0, 0, intValue(e.get("inferenceRollbacks")), intValue(e.get("candidateEvaluations"))));
            }
        }
        MutableMap<String, Integer> rollbackSites = Maps.mutable.empty();
        if (s.get("rollbackSites") instanceof PureMap sites)
        {
            sites.getMap().forEach((k, v) -> rollbackSites.put(String.valueOf(_E_ValueSpecification.unwrap(k)), intValue(_E_ValueSpecification.unwrap(v))));
        }
        return new CompilationStatistics(
                millisToNanos(s.get("totalMillis")), millisToNanos(s.get("parsingMillis")),
                millisToNanos(s.get("firstPassMillis")), millisToNanos(s.get("secondPassMillis")), millisToNanos(s.get("thirdPassMillis")),
                intValue(s.get("elementCount")), intValue(s.get("sourceFileCount")), 0,
                intValue(s.get("inferenceRollbackCount")), intValue(s.get("candidateEvaluationCount")),
                elementStatistics, rollbackSites);
    }

    private static long millisToNanos(Object millis)
    {
        return millis instanceof Number n ? n.longValue() * 1_000_000L : 0L;
    }

    private static int intValue(Object value)
    {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static List<?> asList(Object value)
    {
        return value instanceof List<?> list ? list : value == null ? List.of() : List.of(value);
    }

    /**
     * The reverse reference index from {@code CompilationResult.context.referencedBy}: a
     * {@code PureMap<String, List<String>>} whose values are {@code List} instances with a
     * {@code values: String[*]} field. Empty on any shape mismatch, so a missing index
     * doesn't fail a PDB write.
     */
    private static Map<String, Set<String>> referencedBy(DynamicInstance compilationResult)
    {
        LinkedHashMap<String, Set<String>> out = new LinkedHashMap<>();
        if (!(compilationResult.get("context") instanceof DynamicInstance context)
                || !(context.get("referencedBy") instanceof PureMap map))
        {
            return out;
        }
        for (Map.Entry<meta.pure.metamodel.valuespecification.ValueSpecification,
                meta.pure.metamodel.valuespecification.ValueSpecification> entry : map.getMap().entrySet())
        {
            Object key = _E_ValueSpecification.unwrap(entry.getKey());
            Object value = _E_ValueSpecification.unwrap(entry.getValue());
            if (!(key instanceof String targetPath) || !(value instanceof DynamicInstance list))
            {
                continue;
            }
            LinkedHashSet<String> callers = new LinkedHashSet<>();
            for (Object caller : asList(list.get("values")))
            {
                if (caller instanceof String s)
                {
                    callers.add(s);
                }
            }
            out.put(targetPath, callers);
        }
        return out;
    }

    public static final class Builder implements Compilation.Builder
    {
        private Builder()
        {
        }

        @Override
        public PureCompilation build(CompilationContext context)
        {
            return new PureCompilation(context);
        }
    }
}
