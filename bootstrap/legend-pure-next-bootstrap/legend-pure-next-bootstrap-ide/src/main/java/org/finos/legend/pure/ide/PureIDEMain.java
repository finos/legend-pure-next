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

package org.finos.legend.pure.ide;

import com.sun.net.httpserver.HttpServer;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.ide.backend.JavaDirectBackend;
import org.finos.legend.pure.ide.backend.PureBackend;
import org.finos.legend.pure.m3.module.ModuleManifest;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/**
 * Main entry point for the Pure IDE on the Java-direct backend.
 *
 * <p>Starts:
 * <ul>
 *   <li>HTTP server on port 9090 serving the Monaco web UI</li>
 *   <li>WebSocket LSP server on port 9091 for LSP communication</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>
 *   pure-bootstrap ide [--module &lt;sourceDir&gt;]... [--pdb-dir &lt;dir&gt;] [--welcome &lt;dir&gt;]
 * </pre>
 *
 * <p>Each {@code --module} arg points at a Pure module's source root (a
 * directory containing {@code module.json}). The IDE reads each manifest,
 * computes the transitive dependency closure, and auto-loads the
 * corresponding {@code <name>.pdb} files from {@code --pdb-dir} (default:
 * {@code <repo>/shared/}). A synthetic in-memory {@code welcome} scratch-pad
 * is always added with read access to every loaded PDB.</p>
 *
 * <p>Other backends live in their own modules (e.g.
 * {@code legend-pure-next-truffle-ide}) and reuse this launcher via
 * {@link #run(List, Function)}. Bootstrap must not depend on platform
 * runtimes, so any Truffle-specific main lives outside this module.</p>
 */
public class PureIDEMain
{
    private static final int HTTP_PORT = 9090;
    private static final int LSP_PORT = 9091;

    public static void main(String[] args) throws Exception
    {
        run(List.of(args), pdbDir -> new JavaDirectBackend());
    }

    /**
     * Boot the IDE with the given backend factory.
     *
     * <p>The factory receives only the resolved PDB directory (where
     * {@code core.pdb}, {@code compiler.pdb}, etc. live). Backends manage
     * their own compilation graph independently of the editor's edit graph
     * — e.g. Truffle always loads {@code core.pdb + compiler.pdb} into its
     * own runtime regardless of which module the user is editing, so that
     * {@code compileDir} is always available for execution.</p>
     */
    public static void run(List<String> args, Function<Path, PureBackend> backendFactory) throws Exception
    {
        ParsedArgs parsed = ParsedArgs.parse(args);

        // Read each local module's manifest, collect transitive dep names in
        // load order (deps first). A name appears once even if reached by
        // multiple paths in the graph.
        List<ModuleManifest> localManifests = new ArrayList<>();
        for (Path moduleDir : parsed.modules)
        {
            if (!Files.isDirectory(moduleDir))
            {
                throw new IllegalArgumentException("--module " + moduleDir + " is not a directory");
            }
            ModuleManifest m = ModuleManifest.locate(moduleDir);
            localManifests.add(m);
        }

        LinkedHashSet<String> depClosure = new LinkedHashSet<>();
        LinkedHashSet<String> visiting = new LinkedHashSet<>();
        for (ModuleManifest m : localManifests)
        {
            for (String dep : m.dependencies())
            {
                collectTransitiveDeps(dep, parsed.pdbDir, depClosure, visiting);
            }
        }
        // Default: no --module given → still load core.pdb so the welcome
        // scratch-pad has something to reference.
        if (localManifests.isEmpty() && depClosure.isEmpty())
        {
            collectTransitiveDeps("core", parsed.pdbDir, depClosure, visiting);
        }

        // Load each transitive PDB dep. Order matters — a dependent must see
        // its deps already in the resolver chain.
        MutableList<PDBModule> pdbModules = Lists.mutable.empty();
        for (String depName : depClosure)
        {
            Path pdbPath = parsed.pdbDir.resolve(depName + ".pdb");
            System.out.println("Loading PDB: " + depName + " (" + pdbPath + ")");
            PDBModule mod = new PDBModule(pdbPath, PDBModule.Mode.COMPILATION);
            pdbModules.add(mod);
        }

        // Local modules the user is editing.
        MutableList<LocalModule> editableModules = Lists.mutable.empty();
        for (int i = 0; i < parsed.modules.size(); i++)
        {
            Path moduleDir = parsed.modules.get(i);
            ModuleManifest m = localManifests.get(i);
            System.out.println("Loading local module: " + m.name() + " (" + moduleDir + ")");
            editableModules.add(new LocalModule(
                    m.name(), m.packagePattern(), m.dependencies(), moduleDir));
        }

        // Synthetic welcome scratch-pad — always present, can reference any
        // loaded PDB. Even when no --module is passed this gives the user
        // somewhere to type Pure code against the loaded library.
        Path welcomePath = parsed.welcome != null
                ? parsed.welcome
                : resolveExisting("welcome",
                        Path.of("src/main/resources/welcome"),
                        Path.of("bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-ide/src/main/resources/welcome"),
                        Path.of("legend-pure-next/bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-ide/src/main/resources/welcome"));
        LinkedHashSet<String> welcomeDepSet = new LinkedHashSet<>(depClosure);
        for (LocalModule lm : editableModules)
        {
            welcomeDepSet.add(lm.getName());
        }
        List<String> welcomeDeps = new ArrayList<>(welcomeDepSet);
        System.out.println("Loading welcome scratch-pad from: " + welcomePath + " (deps=" + welcomeDeps + ")");
        LocalModule welcomeModule = new LocalModule("welcome", "*", welcomeDeps, welcomePath);
        editableModules.add(welcomeModule);

        // Start HTTP server for static files
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/", exchange ->
        {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) { path = "/index.html"; }

            // Serve from classpath
            InputStream resource = PureIDEMain.class.getResourceAsStream("/web" + path);
            if (resource == null)
            {
                String notFound = "404 Not Found";
                exchange.sendResponseHeaders(404, notFound.length());
                exchange.getResponseBody().write(notFound.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
                return;
            }

            // Determine content type
            String contentType = "text/html";
            if (path.endsWith(".js")) { contentType = "application/javascript"; }
            else if (path.endsWith(".css")) { contentType = "text/css"; }
            else if (path.endsWith(".json")) { contentType = "application/json"; }

            exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            byte[] content = resource.readAllBytes();
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.getResponseBody().close();
        });
        httpServer.start();
        System.out.println("HTTP server started: http://localhost:" + HTTP_PORT);

        PureBackend backend = backendFactory.apply(parsed.pdbDir);
        System.out.println("Backend: " + backend.name());

        // Start LSP WebSocket server
        PureLSPWebSocketServer lspServer = new PureLSPWebSocketServer(LSP_PORT, pdbModules, editableModules, backend);
        lspServer.start();
        System.out.println("LSP WebSocket server started: ws://localhost:" + LSP_PORT);

        System.out.println("\n  Open http://localhost:" + HTTP_PORT + " in your browser\n");

        // Release the backend cleanly on JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(backend::close, "pure-backend-close"));

        // Keep main thread alive
        Thread.currentThread().join();
    }

    /**
     * DFS-based transitive collection that preserves dep-first ordering and
     * detects cycles (which the manifest validation should already prevent,
     * but we double-check rather than spin forever on a bad input).
     */
    private static void collectTransitiveDeps(String depName, Path pdbDir,
                                              LinkedHashSet<String> result,
                                              LinkedHashSet<String> visiting)
    {
        if (result.contains(depName)) return;
        if (!visiting.add(depName))
        {
            throw new IllegalStateException("Dependency cycle through module '" + depName + "'");
        }
        try
        {
            Path pdbPath = pdbDir.resolve(depName + ".pdb");
            if (!Files.isRegularFile(pdbPath))
            {
                throw new IllegalStateException("Cannot resolve module dependency '" + depName
                        + "': expected " + pdbPath + ". Build it first (e.g. `just bootstrap::build`).");
            }
            // Peek at the dep's own manifest to recurse — load and discard.
            // The actual PDB load happens later in dep order.
            PDBModule peek;
            try
            {
                peek = new PDBModule(pdbPath, PDBModule.Mode.COMPILATION);
            }
            catch (java.io.IOException e)
            {
                throw new RuntimeException("Failed to read manifest of " + pdbPath, e);
            }
            for (String inner : peek.getDependencies())
            {
                collectTransitiveDeps(inner, pdbDir, result, visiting);
            }
            result.add(depName);
        }
        finally
        {
            visiting.remove(depName);
        }
    }

    private static final class ParsedArgs
    {
        final List<Path> modules;
        final Path pdbDir;
        final Path welcome;

        private ParsedArgs(List<Path> modules, Path pdbDir, Path welcome)
        {
            this.modules = modules;
            this.pdbDir = pdbDir;
            this.welcome = welcome;
        }

        static ParsedArgs parse(List<String> args)
        {
            List<Path> modules = new ArrayList<>();
            Path pdbDir = null;
            Path welcome = null;
            for (int i = 0; i < args.size(); i++)
            {
                switch (args.get(i))
                {
                    case "--module" -> modules.add(Path.of(args.get(++i)));
                    case "--pdb-dir" -> pdbDir = Path.of(args.get(++i));
                    case "--welcome" -> welcome = Path.of(args.get(++i));
                    default -> throw new IllegalArgumentException("Unknown option: " + args.get(i));
                }
            }
            if (pdbDir == null)
            {
                pdbDir = resolvePdbDir();
            }
            return new ParsedArgs(modules, pdbDir, welcome);
        }
    }

    private static Path resolvePdbDir()
    {
        Path[] candidates = {
                Path.of("shared"),
                Path.of("../../../shared"),
                Path.of("legend-pure-next/shared"),
        };
        for (Path p : candidates)
        {
            if (Files.isDirectory(p)) return p;
        }
        StringBuilder tried = new StringBuilder();
        for (Path p : candidates)
        {
            tried.append("\n  - ").append(p.toAbsolutePath().normalize());
        }
        throw new IllegalStateException(
                "Cannot locate PDB output directory. cwd=" + Path.of("").toAbsolutePath()
                        + ". Tried:" + tried
                        + "\nPass --pdb-dir <path> explicitly, or run from a directory under the repo root.");
    }

    /**
     * Return the first candidate that resolves to an existing directory.
     * Throws with the resolved (absolute) paths of every candidate tried so
     * the user can see exactly what was searched relative to their cwd and
     * either fix the run config's working directory or pass an explicit
     * positional arg.
     */
    private static Path resolveExisting(String label, Path... candidates)
    {
        for (Path p : candidates)
        {
            if (Files.isDirectory(p))
            {
                return p;
            }
        }
        StringBuilder tried = new StringBuilder();
        for (Path p : candidates)
        {
            tried.append("\n  - ").append(p.toAbsolutePath().normalize());
        }
        throw new IllegalStateException(
                "Cannot locate " + label + " directory. cwd=" + Path.of("").toAbsolutePath()
                        + ". Tried:" + tried
                        + "\nPass --welcome <path> to override.");
    }
}
