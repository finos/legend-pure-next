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

package org.finos.legend.pure.cli;

import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.SpecificationBinaryBuilder;
import org.finos.legend.pure.m3.extensions.compiledgraph.CompiledGraphLanguageExtension;
import org.finos.legend.pure.m3.extensions.compilerstats.CompilerStatsLanguageExtension;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.ModelMetadataAccess;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PureCLI
{
    public static void main(String[] args) throws Exception
    {
        if (args.length == 0)
        {
            printUsage();
            System.exit(1);
        }

        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (command)
        {
            case "compile-spec" -> compileSpec(rest);
            case "compile" -> compile(rest);
            case "compile-via-pure" -> compileViaPure(rest);
            case "execute" -> execute(rest);
            case "diff-pdb" -> diffPdb(rest);
            case "inspect-pdb" -> inspectPdb(rest);
            default ->
            {
                System.err.println("Unknown command: " + command);
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void printUsage()
    {
        System.err.println("Usage: pure-bootstrap <command> [options]");
        System.err.println();
        System.err.println("Commands:");
        System.err.println("  compile-spec --m3-ttl <file> --output-dir <dir> [--tests <mode>] <sourceDir...>   Compile specification Pure sources into <dir>/core.pdb");
        System.err.println("  compile --base-pdb <file> --source <dir> --output-dir <dir> [--tests <mode>]   Compile Pure sources against a base PDB (Java compiler)");
        System.err.println("  compile-via-pure --base-pdb <file>... --source <dir> --output-dir <dir> [--tests <mode>]   Compile Pure sources by running compiler-pure on the Java runtime");
        System.err.println();
        System.err.println("  All compile commands auto-discover module.json by walking up from the source dir.");
        System.err.println("  Output filename comes from the module manifest 'name' field.");
        System.err.println("  --tests {none|with|only|split} controls which elements end up in which PDB (default 'none' = lean).");
        System.err.println("  execute --pdb <file>... --function <path> [--args <arg>...]   Execute a Pure function");
        System.err.println("  diff-pdb [--deep] <a.pdb> <b.pdb>                          Compare two PDB archives (path-set + per-element byte hash; --deep walks typed properties)");
        System.err.println("  inspect-pdb --pdb <file>... <elementPath>                  Load PDB(s) and dump the named element's slots (properties, propertiesFromAssociations, generalizations)");
    }

    private static void diffPdb(String[] args) throws Exception
    {
        boolean deep = false;
        Path corePdb = null;
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++)
        {
            String arg = args[i];
            if ("--deep".equals(arg)) deep = true;
            else if ("--core".equals(arg) && i + 1 < args.length) corePdb = Path.of(args[++i]);
            else positional.add(arg);
        }
        if (positional.size() != 2)
        {
            System.err.println("Usage: pure-bootstrap diff-pdb [--deep] [--core <core.pdb>] <a.pdb> <b.pdb>");
            System.exit(1);
        }
        Path a = Path.of(positional.get(0));
        Path b = Path.of(positional.get(1));
        boolean clean;
        if (deep)
        {
            clean = org.finos.legend.pure.m3.module.pdbModule.diff.PdbDeepDiffer
                    .diff(a, b, corePdb, System.out).isClean();
        }
        else
        {
            clean = org.finos.legend.pure.m3.module.pdbModule.diff.PdbDiffer
                    .diff(a, b, System.out).isClean();
        }
        // Exit 0 if PDBs are identical, 1 otherwise — useful for CI gates.
        System.exit(clean ? 0 : 1);
    }

    /**
     * {@code inspect-pdb} — load one or more PDB archives, resolve a named
     * element, and dump the slots most useful for diagnosing association /
     * inheritance / cross-PDB-reference bugs. Reads through the standard
     * Java PDB loader (same path the bootstrap compiler uses), so what's
     * printed is what the runtime actually sees after PDB round-trip.
     *
     * <p>Pass every PDB the target element transitively depends on (e.g.
     * a test element needs its lean companion + core); cross-PDB pointer
     * derefs return {@code null} otherwise and mask real diagnostic data.</p>
     */
    private static void inspectPdb(String[] args) throws Exception
    {
        List<String> pdbPaths = new ArrayList<>();
        String elementPath = null;
        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--pdb" -> pdbPaths.add(args[++i]);
                default ->
                {
                    if (elementPath != null)
                    {
                        throw new IllegalArgumentException("Unexpected positional arg '" + args[i]
                                + "' (already have element path '" + elementPath + "')");
                    }
                    elementPath = args[i];
                }
            }
        }
        if (pdbPaths.isEmpty() || elementPath == null)
        {
            System.err.println("Usage: pure-bootstrap inspect-pdb --pdb <file>... <elementPath>");
            System.err.println("  Pass every PDB the target element depends on (lean + tests + core)");
            System.err.println("  so cross-PDB pointer references resolve.");
            System.exit(1);
        }

        List<PDBModule> modules = new ArrayList<>();
        List<String> loadedNames = new ArrayList<>();
        for (String pdbPath : pdbPaths)
        {
            PDBModule module = new PDBModule(Path.of(pdbPath), PDBModule.Mode.EXECUTION);
            modules.add(module);
            loadedNames.add(module.getName());
        }
        PureModel model = PureModel.withModules(Lists.mutable.withAll(modules))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        meta.pure.metamodel.PackageableElement element = null;
        String foundIn = null;
        for (PDBModule mod : modules)
        {
            meta.pure.metamodel.PackageableElement candidate = mod.getElement(elementPath);
            if (candidate != null)
            {
                element = candidate;
                foundIn = mod.getName();
                break;
            }
        }
        if (element == null)
        {
            throw new IllegalArgumentException("Element '" + elementPath + "' not found in any loaded PDB "
                    + "(loaded: " + loadedNames + "). Check the path and that all dependency PDBs are passed.");
        }

        System.out.println("Element: " + elementPath);
        System.out.println("  Found in module: " + foundIn);
        System.out.println("  Java class:      " + element.getClass().getName());

        if (element instanceof meta.pure.metamodel.type.Class cls)
        {
            dumpClass(cls);
        }
        else if (element instanceof meta.pure.metamodel.relationship.Association assoc)
        {
            dumpAssociation(assoc);
        }
        else
        {
            System.out.println("  (no specialised dump for this element type — only path resolution checked)");
        }
    }

    private static void dumpClass(meta.pure.metamodel.type.Class cls)
    {
        org.eclipse.collections.api.list.MutableList<? extends meta.pure.metamodel.function.property.Property> props = cls._properties();
        System.out.println("  properties (" + (props == null ? "null slot" : props.size() + " entries") + "):");
        if (props != null)
        {
            for (meta.pure.metamodel.function.property.Property p : props)
            {
                System.out.println("    - " + p._name() + " : " + typeOfProperty(p));
            }
        }

        org.eclipse.collections.api.list.MutableList<? extends meta.pure.metamodel.function.property.Property> pfa = cls._propertiesFromAssociations();
        System.out.println("  propertiesFromAssociations (" + (pfa == null ? "null slot" : pfa.size() + " entries") + "):");
        if (pfa != null)
        {
            for (meta.pure.metamodel.function.property.Property p : pfa)
            {
                System.out.println("    - " + p._name() + " : " + typeOfProperty(p) + "   (owner: " + ownerPath(p) + ")");
            }
        }

        org.eclipse.collections.api.list.MutableList<? extends meta.pure.metamodel.relationship.Generalization> gens = cls._generalizations();
        System.out.println("  generalizations (" + (gens == null ? "null slot" : gens.size() + " entries") + "):");
        if (gens != null)
        {
            for (meta.pure.metamodel.relationship.Generalization g : gens)
            {
                meta.pure.metamodel.type.Type t = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(g._general());
                String supertype = t instanceof meta.pure.metamodel.PackageableElement pe
                        ? org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe)
                        : (t == null ? "null" : t.getClass().getName());
                System.out.println("    -> " + supertype);
            }
        }
    }

    private static void dumpAssociation(meta.pure.metamodel.relationship.Association assoc)
    {
        org.eclipse.collections.api.list.MutableList<? extends meta.pure.metamodel.function.property.Property> props = assoc._properties();
        System.out.println("  properties (" + (props == null ? "null slot" : props.size() + " entries") + "):");
        if (props != null)
        {
            for (meta.pure.metamodel.function.property.Property p : props)
            {
                System.out.println("    - " + p._name() + " : " + typeOfProperty(p));
            }
        }
    }

    private static String typeOfProperty(meta.pure.metamodel.function.property.Property p)
    {
        meta.pure.metamodel.type.generics.GenericType gt = p._genericType();
        if (gt == null) return "null genericType";
        meta.pure.metamodel.type.Type t = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.type(gt);
        if (t instanceof meta.pure.metamodel.PackageableElement pe)
        {
            return org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
        }
        return t == null ? "null type" : t.getClass().getName();
    }

    private static String ownerPath(meta.pure.metamodel.function.property.Property p)
    {
        meta.pure.metamodel.SimplePropertyOwner owner = p._owner();
        if (owner instanceof meta.pure.metamodel.PackageableElement pe)
        {
            return org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
        }
        return owner == null ? "null" : owner.getClass().getName();
    }

    private static void compileSpec(String[] args) throws Exception
    {
        Path m3TtlPath = null;
        Path outputDir = null;
        org.finos.legend.pure.m3.module.TestElementFilter.Mode mode =
                org.finos.legend.pure.m3.module.TestElementFilter.Mode.NONE;
        List<String> sourceDirArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--m3-ttl" -> m3TtlPath = Path.of(args[++i]);
                case "--output-dir" -> outputDir = Path.of(args[++i]);
                case "--tests" -> mode = org.finos.legend.pure.m3.module.TestElementFilter.Mode.parse(args[++i]);
                default -> sourceDirArgs.add(args[i]);
            }
        }
        if (m3TtlPath == null || outputDir == null || sourceDirArgs.isEmpty())
        {
            System.err.println("Usage: pure-bootstrap compile-spec --m3-ttl <file> --output-dir <dir> [--tests {none|with|only|split}] <sourceDir...>");
            System.err.println("  Output filename comes from the module manifest 'name' field.");
            System.err.println("  --tests filter (default 'none'):");
            System.err.println("    none  : lean PDB                -> <dir>/<name>.pdb");
            System.err.println("    with  : full PDB                -> <dir>/<name>-with-tests.pdb");
            System.err.println("    only  : tests-only PDB          -> <dir>/<name>-tests.pdb");
            System.err.println("    split : lean + tests-only PDBs  -> <dir>/<name>.pdb + <dir>/<name>-tests.pdb");
            System.exit(1);
        }
        List<Path> sourceDirs = new ArrayList<>();
        for (String s : sourceDirArgs)
        {
            sourceDirs.add(Path.of(s));
        }
        SpecificationBinaryBuilder.compile(m3TtlPath, sourceDirs, outputDir, mode);
    }

    private static void compile(String[] args) throws Exception
    {
        String basePdb = null;
        String source = null;
        String outputDir = null;
        org.finos.legend.pure.m3.module.TestElementFilter.Mode mode =
                org.finos.legend.pure.m3.module.TestElementFilter.Mode.NONE;

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--base-pdb" -> basePdb = args[++i];
                case "--source" -> source = args[++i];
                case "--output-dir" -> outputDir = args[++i];
                case "--tests" -> mode = org.finos.legend.pure.m3.module.TestElementFilter.Mode.parse(args[++i]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (basePdb == null || source == null || outputDir == null)
        {
            System.err.println("Usage: pure-bootstrap compile --base-pdb <file> --source <dir> --output-dir <dir> [--tests {none|with|only|split}]");
            System.err.println("  Output filename comes from the module manifest 'name' field.");
            System.exit(1);
        }

        CompilerBinaryBuilder.compile(Path.of(basePdb), Path.of(source), Path.of(outputDir), mode);
    }

    /**
     * {@code compile-via-pure} — run the Pure-language compiler on the Java
     * runtime to produce a PDB. Mirrors the Truffle CLI's {@code compile}
     * subcommand structurally (multi-{@code --base-pdb}, {@code --source},
     * {@code --output}) but runs through {@link PureRuntimeCompilerBinaryBuilder}.
     */
    private static void compileViaPure(String[] args) throws Exception
    {
        List<String> basePdbs = new ArrayList<>();
        String source = null;
        String outputDir = null;
        org.finos.legend.pure.m3.module.TestElementFilter.Mode mode =
                org.finos.legend.pure.m3.module.TestElementFilter.Mode.NONE;

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--base-pdb" -> basePdbs.add(args[++i]);
                case "--source" -> source = args[++i];
                case "--output-dir" -> outputDir = args[++i];
                case "--tests" -> mode = org.finos.legend.pure.m3.module.TestElementFilter.Mode.parse(args[++i]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (basePdbs.isEmpty() || source == null || outputDir == null)
        {
            System.err.println("Usage: pure-bootstrap compile-via-pure --base-pdb <file>... --source <dir> --output-dir <dir> [--tests {none|with|only|split}]");
            System.err.println("  Pass at least core.pdb plus an existing compiler.pdb so the");
            System.err.println("  Pure compile_PureFile_… function is on the resolver.");
            System.err.println("  Output filename comes from the module manifest 'name' field.");
            System.exit(1);
        }

        List<Path> basePaths = new ArrayList<>();
        for (String b : basePdbs)
        {
            basePaths.add(Path.of(b));
        }
        PureRuntimeCompilerBinaryBuilder.compile(basePaths, Path.of(source), Path.of(outputDir), mode);
    }

    private static void execute(String[] args) throws Exception
    {
        List<String> pdbPaths = new ArrayList<>();
        String function = null;
        List<String> fnArgs = new ArrayList<>();

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--pdb" -> pdbPaths.add(args[++i]);
                case "--function" -> function = args[++i];
                case "--args" ->
                {
                    while (i + 1 < args.length && !args[i + 1].startsWith("--"))
                    {
                        fnArgs.add(args[++i]);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (pdbPaths.isEmpty() || function == null)
        {
            System.err.println("Usage: pure-bootstrap execute --pdb <file>... --function <path> [--args <arg>...]");
            System.exit(1);
        }

        // Load PDB modules — each one carries its identity in its embedded manifest.
        List<PDBModule> modules = new ArrayList<>();
        List<String> loadedNames = new ArrayList<>();
        for (String pdbPath : pdbPaths)
        {
            PDBModule module = new PDBModule(Path.of(pdbPath), PDBModule.Mode.EXECUTION);
            modules.add(module);
            loadedNames.add(module.getName());
        }
        // Validate that every declared dep is among the loaded modules.
        for (PDBModule module : modules)
        {
            for (String dep : module.getDependencies())
            {
                if (!loadedNames.contains(dep))
                {
                    throw new IllegalArgumentException("Module '" + module.getName()
                            + "' declares dependency '" + dep + "' but it was not loaded (loaded: " + loadedNames + ")");
                }
            }
        }

        PureModel model = PureModel.withModules(Lists.mutable.withAll(modules))
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();
        model.compile();

        // Resolve function from last module
        PDBModule lastModule = modules.get(modules.size() - 1);
        // Registry-wide resolver: every loaded PDB is in scope. Necessary for
        // runtime test discovery — runTests walks `Package.children`, whose
        // serialised PointerRefs point at test functions in companion PDBs
        // that the lean PDB's manifest doesn't declare as a direct dep.
        MetadataAccess resolver = new ModelMetadataAccess(model);

        PureExecution execution = PureExecution.builder()
                .withResolver(resolver)
                .withNativeExtensions(Lists.mutable.with(new CompilerNatives()))
                .withParserExtensions(List.of(
                        new CompiledGraphLanguageExtension(),
                        new CompilerStatsLanguageExtension(),
                        new org.finos.legend.pure.m3.extensions.reverseindex.ReverseIndexLanguageExtension()))
                .build();

        FunctionDefinition fd = (FunctionDefinition) lastModule.getElement(function);
        if (fd == null)
        {
            // Try searching all modules
            for (PDBModule mod : modules)
            {
                fd = (FunctionDefinition) mod.getElement(function);
                if (fd != null) break;
            }
        }
        if (fd == null)
        {
            System.err.println("Function not found: " + function);
            System.exit(1);
        }

        Object result;
        if (fnArgs.isEmpty())
        {
            result = execution.execute(fd);
        }
        else
        {
            result = execution.execute(fd, fnArgs.toArray());
        }

        if (result != null)
        {
            System.out.println(result);
        }
    }
}
