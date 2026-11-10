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

package org.finos.legend.pure.platform.java.cli;

import org.finos.legend.pure.platform.java.compiler.module.ModuleRegistry;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.PdbModule;
import org.finos.legend.pure.platform.java.execution.Execute;
import org.finos.legend.pure.platform.java.execution.PureStack;
import org.finos.legend.pure.platform.java.extensions.diagram.DiagramLanguage;
import org.finos.legend.pure.platform.java.extensions.tds.TdsLanguage;
import org.finos.legend.pure.platform.java.runtime.PureRuntime;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.block.factory.Functions;

import java.nio.file.Path;

/**
 * Runs a translated Pure function on the Java platform, the counterpart of {@code pure-truffle
 * execute}: installs the languages, opens the PDBs lazily, installs them as the metadata translated
 * code reads, and calls the function's class.
 *
 * <p>Usage: {@code PureJava execute [--root <dir>] [--pdb a.pdb ...] --function <path> [--args v ...]}, or
 * {@code PureJava compile} with the flags of {@code pure-truffle compile} (see {@link #USAGE}). The
 * function path is the element path with its signature
 * ({@code platform::pure::functions::string::joinStrings_String_MANY__String_1__String_1_}); arguments are
 * passed as Strings and the result is printed. {@code --schema m3.fbs} overrides the schema the platform
 * ships; it is not needed.</p>
 *
 * <p>THE LANGUAGES AND THE PLATFORM'S OWN MODULES ARE BUILT IN — a caller names neither. The Pure
 * language and every extension this CLI was linked against are installed here, and the runtime registers
 * what each language declares in its own manifest plus the Java metamodel and translator. {@code --pdb}
 * is for modules of the CALLER's own (test content, an extension's module), and {@code --root} is only
 * what the manifests' repo-relative paths resolve against — the working directory by default.</p>
 *
 * <p>Those five modules used to be spelled out by every caller: twenty lines of build scripts naming
 * core.pdb, compiler.pdb and the three the platform brings, each needing to be kept in step with
 * PlatformModules by hand.</p>
 *
 * <p>THE COMPOSITION ROOT: the one place that names every language, because it is the one module that
 * depends on the engine and on each extension. It was {@code Execute.main} in the engine, which could
 * not reach an extension — the dependency runs the other way — so no JVM host could parse
 * {@code ###Diagram}.</p>
 */
public final class PureJava
{
    private PureJava()
    {
    }

    private static final String COMPILE_MODULE =
            "platform::pure::compiler::archive::compileModule_String_MANY__String_1__String_1__CompilerExtension_MANY__String_MANY_";

    private static final String USAGE = "usage: PureJava execute [--root <dir>] [--pdb a.pdb ...] --function <path> [--args v ...] [--schema m3.fbs]\n"
            + "       PureJava compile [--root <dir>] --base-pdb a.pdb ... --source <dir> ... --output-dir <dir> [--tests {none|split|with}]";

    public static void main(String[] args) throws Exception
    {
        if (args.length == 0 || !(args[0].equals("execute") || args[0].equals("compile")))
        {
            System.err.println(USAGE);
            System.exit(2);
        }
        Path schema = null;
        Path root = Path.of("");
        MutableList<Path> archives = Lists.mutable.empty();
        String function = null;
        MutableList<String> functionArgs = Lists.mutable.empty();
        MutableList<String> sources = Lists.mutable.empty();
        String outputDir = null;
        String tests = "none";
        for (int i = 1; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--schema":
                    schema = Path.of(args[++i]);
                    break;
                case "--root":
                    root = Path.of(args[++i]);
                    break;
                case "--pdb":
                case "--base-pdb":
                    archives.add(Path.of(args[++i]));
                    break;
                case "--function":
                    function = args[++i];
                    break;
                case "--args":
                    while (i + 1 < args.length && !args[i + 1].startsWith("--"))
                    {
                        functionArgs.add(args[++i]);
                    }
                    break;
                case "--source":
                    sources.add(Path.of(args[++i]).toAbsolutePath().normalize().toString());
                    break;
                case "--output-dir":
                    outputDir = args[++i];
                    break;
                case "--tests":
                    tests = args[++i];
                    break;
                default:
                    throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        boolean compile = args[0].equals("compile");
        if (compile ? (sources.isEmpty() || outputDir == null) : function == null)
        {
            System.err.println(USAGE);
            System.exit(2);
        }

        // The --pdb archives go into the registry, as on every host; build() still opens what the languages
        // declare first, so core.pdb leads whatever order these come in. The languages: ###Pure, then each
        // extension this CLI was linked against — inert until its own module is loaded, so naming one
        // costs nothing when it is not used.
        PureRuntime.Builder builder = PureRuntime.builder()
                .withRegistry(new ModuleRegistry().registerAll(archives.collect(Functions.throwing(PdbModule::open))))
                .withLanguages(DiagramLanguage.all().newWithAll(TdsLanguage.all()))
                .withModuleRoot(root);
        if (schema != null)
        {
            // Only for a caller that deliberately wants a different schema; otherwise the one this
            // platform ships as a classpath resource is used.
            builder = builder.withSchema(schema);
        }
        // build() installs the metadata translated code reads, which is what `call` resolves through —
        // the same path the embedding example takes.
        PureRuntime runtime = builder.build();
        try
        {
            System.out.println(compile
                    ? Execute.call(COMPILE_MODULE, sources, outputDir, tests, runtime.compilerExtensions())
                    : Execute.call(function, functionArgs.toArray()));
        }
        catch (Throwable t)
        {
            // Anything that escapes tryEval lands here, where the host stack is all the JVM would
            // print. Name the Pure functions it came through first, then rethrow unchanged so the exit
            // code and the Java trace stay exactly as they were.
            java.util.List<String> frames = PureStack.frames(t);
            if (!frames.isEmpty())
            {
                System.err.println("Pure stack trace:");
                frames.forEach(frame -> System.err.println("    at " + frame));
            }
            throw t;
        }
        finally
        {
            runtime.close();
        }
    }
}
