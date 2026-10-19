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
 * <p>Usage: {@code PureJava [--root <dir>] [--pdb a.pdb ...] --function <path> [--args v ...]}. The
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
 * <p>Those five modules used to be spelled out by every caller: twenty lines across the Justfile naming
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

    public static void main(String[] args) throws Exception
    {
        Path schema = null;
        Path root = Path.of("");
        MutableList<Path> archives = Lists.mutable.empty();
        String function = null;
        MutableList<String> functionArgs = Lists.mutable.empty();
        for (int i = 0; i < args.length; i++)
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
                default:
                    throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if (function == null)
        {
            System.err.println("usage: PureJava [--root <dir>] [--pdb a.pdb ...] --function <path> "
                    + "[--args v ...] [--schema m3.fbs]");
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
            System.out.println(Execute.call(function, functionArgs.toArray()));
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
