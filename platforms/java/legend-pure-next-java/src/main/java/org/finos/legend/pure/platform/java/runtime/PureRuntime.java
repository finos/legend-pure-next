// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.platform.java.runtime;

import org.finos.legend.pure.m3.PdbTypes;
import org.finos.legend.pure.platform.java.compiler.module.CompilationResult;
import org.finos.legend.pure.platform.java.compiler.module.Module;
import org.finos.legend.pure.platform.java.compiler.module.ModuleRegistry;
import org.finos.legend.pure.platform.java.compiler.module.inMemoryModule.InMemoryModule;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.LazyObject;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.Metadata;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.PdbModule;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.PdbRuntime;
import org.finos.legend.pure.platform.java.execution.Execute;
import org.finos.legend.pure.platform.java.grammar.PureParsing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The host API on the Java platform: register modules, put Pure source into an in-memory
 * one, compile, execute what it defines. The same shape as bootstrap's and Truffle's
 * {@code PureRuntime}, so a program that drives one drives all three.
 *
 * <p>Relationship to {@link Execute}: that is the CLI, which loads PDBs and calls one
 * function. This is the programmatic entry point, and it adds the part the CLI has no use
 * for — compiling source at run time and executing the result.</p>
 *
 * <p>How executing compiled source works here, which differs from the other hosts: this
 * platform runs translated Java, and a function the compiler just built has no translated
 * class. {@link #execute} therefore goes through {@code Metadata.invoke}, which translates
 * the function and compiles it with javac in memory. Elements the compile produced are
 * contributed to the decoder by path, so translated code that resolves one of them — a
 * call to a sibling function, a {@code ^Class(...)} of a new class — finds it.</p>
 */
public final class PureRuntime
{
    private final ModuleRegistry registry;
    private final PdbRuntime runtime;

    private PureRuntime(ModuleRegistry registry, Path schema) throws IOException
    {
        this.registry = registry;
        registry.validate();
        List<Path> archives = new ArrayList<>();
        for (Module module : registry.modules())
        {
            if (module instanceof PdbModule pdb)
            {
                archives.add(pdb.path());
            }
        }
        this.runtime = schema == null
                ? PdbRuntime.open(PdbTypes::create, archives.toArray(new Path[0]))
                : PdbRuntime.open(schema, PdbTypes::create, archives.toArray(new Path[0]));
        registry.attach(runtime);
        // Translated code reaches the metadata through the static Metadata, so a runtime
        // built here becomes the one it reads. One runtime at a time, as on the CLI.
        Metadata.install(runtime);
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static final class Builder
    {
        private ModuleRegistry registry;
        private Path schema;

        public Builder withRegistry(ModuleRegistry registry)
        {
            this.registry = registry;
            return this;
        }

        /**
         * Decode with the schema at {@code schema} instead of the one the platform ships as a
         * classpath resource. Only for a caller that deliberately wants a different schema —
         * the archives and the schema that describes them have to match.
         */
        public Builder withSchema(Path schema)
        {
            this.schema = schema;
            return this;
        }

        public PureRuntime build()
        {
            if (registry == null)
            {
                throw new IllegalStateException("PureRuntime needs withRegistry(...)");
            }
            try
            {
                return new PureRuntime(registry, schema);
            }
            catch (IOException e)
            {
                throw new RuntimeException("Cannot open the registered archives: " + e.getMessage(), e);
            }
        }

    }

    /** The registry this runtime resolves through. */
    public ModuleRegistry registry()
    {
        return registry;
    }

    /** Add or replace source {@code sourceId} in the registered in-memory module {@code moduleName}. */
    public void setSource(String moduleName, String sourceId, String content)
    {
        inMemoryModule(moduleName).setSource(sourceId, content);
    }

    /** Remove source {@code sourceId} from the registered in-memory module {@code moduleName}. */
    public void removeSource(String moduleName, String sourceId)
    {
        inMemoryModule(moduleName).removeSource(sourceId);
    }

    /**
     * Compile, in registration order, every in-memory module that has code: parse its
     * sources, run compiler-pure over them, and contribute the elements so later modules
     * and {@link #execute} see them. Stops at the first module that fails; that module
     * keeps the elements it had.
     */
    public CompilationResult compile()
    {
        List<Object> compiled = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Module module : registry.modules())
        {
            if (module instanceof InMemoryModule inMemory && inMemory.hasCode()
                    && !compileModule(inMemory, compiled, errors))
            {
                break;
            }
        }
        return new CompilationResult(List.copyOf(compiled), List.copyOf(errors));
    }

    private boolean compileModule(InMemoryModule module, List<Object> compiled, List<String> errors)
    {
        List<Object> files = new ArrayList<>();
        try
        {
            for (Map.Entry<String, String> source : module.sources().entrySet())
            {
                files.add(PureParsing.parse(source.getKey(), source.getValue()));
            }
        }
        catch (RuntimeException e)
        {
            errors.add(module.name() + ": " + e.getMessage());
            return false;
        }

        Object result;
        try
        {
            // silent: compiling inside a program should not draw a progress bar.
            result = Execute.call(COMPILE, files, Execute.call(NEW_CONTEXT, false), false, true);
        }
        catch (Exception e)
        {
            errors.add(module.name() + ": " + e.getMessage());
            return false;
        }

        List<Object> reported = values(result, "errors");
        if (!reported.isEmpty())
        {
            reported.forEach(error -> errors.add(String.valueOf(error)));
            return false;
        }

        // The previous compile's elements go first, or one deleted from the source would
        // keep resolving.
        runtime.undefineElements(module.elements().keySet());
        Map<String, Object> defined = new LinkedHashMap<>();
        for (Object element : values(result, "elements"))
        {
            defined.put(Metadata.path(element, "::"), element);
        }
        defined.forEach(runtime::defineElement);
        module.setElements(defined);
        compiled.addAll(defined.values());
        return true;
    }

    /**
     * Execute a compiled function with the given raw Java arguments. Translating and
     * compiling it happens here, on first call, because the platform has no generated
     * class for a function the compiler just built.
     */
    public Object execute(Object function, Object... args)
    {
        return Metadata.invoke(function, List.of(args));
    }

    /** Nothing to release: the archives are read into memory when they are opened. */
    public void close()
    {
    }

    private InMemoryModule inMemoryModule(String name)
    {
        if (registry.module(name) instanceof InMemoryModule module)
        {
            return module;
        }
        throw new IllegalArgumentException("no in-memory module '" + name + "' is registered");
    }

    private static List<Object> values(Object result, String property)
    {
        return new ArrayList<>(((LazyObject) result).__values(property));
    }

    private static final String COMPILE =
            "meta::pure::compiler::compile_PureFile_MANY__CompilerContext_1__Boolean_1__Boolean_1__CompilationResult_1_";
    private static final String NEW_CONTEXT = "meta::pure::compiler::newCompilerContext_Boolean_1__CompilerContext_1_";
}
