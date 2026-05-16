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
import org.finos.legend.pure.m3.module.bootstrapModule.BootstrapModule;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Main entry point for the Pure IDE on the Java-direct backend.
 *
 * <p>Starts:
 * <ul>
 *   <li>HTTP server on port 9090 serving the Monaco web UI</li>
 *   <li>WebSocket LSP server on port 9091 for LSP communication</li>
 * </ul>
 *
 * <p>Usage: java -cp ... org.finos.legend.pure.ide.PureIDEMain
 *     [path-to-core.pdb] [compiler-pure-path] [welcome-path]</p>
 *
 * <p>Other backends live in their own modules (e.g.
 * {@code legend-pure-next-truffle-ide}) and reuse this launcher via
 * {@link #run(List, BiFunction)}. Bootstrap must not depend on platform
 * runtimes, so any Truffle-specific main lives outside this module.</p>
 */
public class PureIDEMain
{
    private static final int HTTP_PORT = 9090;
    private static final int LSP_PORT = 9091;

    public static void main(String[] args) throws Exception
    {
        run(List.of(args), (corePdb, compilerPdb) -> new JavaDirectBackend());
    }

    /**
     * Boot the IDE with the given backend factory. The factory receives both
     * resolved PDB paths so backends that need their own resolver (e.g.
     * Truffle) load the exact same files the package tree was built from.
     *
     * <p>Positional args (in order): {@code [path-to-core.pdb]
     * [path-to-compiler.pdb] [welcome-path]}. Callers should strip their own
     * flags before invoking this.</p>
     */
    public static void run(List<String> args, BiFunction<Path, Path, PureBackend> backendFactory) throws Exception
    {
        // Resolve core.pdb path (CLI arg or classpath-bundled core.pdb)
        Path pdbPath = args.size() > 0
                ? Path.of(args.get(0))
                : BootstrapModule.locateCorePdb();

        System.out.println("Loading core.pdb from: " + pdbPath);
        PDBModule coreModule = new PDBModule(pdbPath, PDBModule.Mode.COMPILATION);
        System.out.println("Core PDB loaded (" + coreModule.elementPaths().size() + " elements)");

        // Load compiler.pdb as a PDB (read-only, pre-compiled) so the IDE
        // doesn't re-compile compiler-pure on every keystroke and so the
        // package tree picks up its elements via the same elementIndex path
        // it uses for core. The file is built by `just bootstrap::build-compiler-pdb`
        // and staged into shared/.
        Path compilerPdbPath = args.size() > 1
                ? Path.of(args.get(1))
                : resolveExistingFile("compiler.pdb",
                        Path.of("../../../shared/compiler.pdb"),
                        Path.of("shared/compiler.pdb"),
                        Path.of("legend-pure-next/shared/compiler.pdb"));
        System.out.println("Loading compiler.pdb from: " + compilerPdbPath);
        PDBModule compilerModule = new PDBModule(compilerPdbPath, PDBModule.Mode.COMPILATION,
                "compiler", "*", java.util.List.of(coreModule.getName()));
        System.out.println("Compiler PDB loaded (" + compilerModule.elementPaths().size() + " elements)");

        // Add LocalModule for welcome scratch pad — the only user-editable
        // module. Depends on both PDBs so user code can reference any
        // metamodel or compiler-pure element.
        Path welcomePath = args.size() > 2
                ? Path.of(args.get(2))
                : resolveExisting("welcome",
                        Path.of("src/main/resources/welcome"),
                        Path.of("bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-ide/src/main/resources/welcome"),
                        Path.of("legend-pure-next/bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-ide/src/main/resources/welcome"));
        System.out.println("Loading welcome from: " + welcomePath);
        LocalModule welcomeModule = new LocalModule(
                "welcome", "*",
                java.util.List.of(coreModule.getName(), compilerModule.getName()),
                welcomePath
        );

        MutableList<LocalModule> editableModules = Lists.mutable.with(welcomeModule);
        MutableList<PDBModule> pdbModules = Lists.mutable.with(coreModule, compilerModule);

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

        PureBackend backend = backendFactory.apply(pdbPath, compilerPdbPath);
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
            if (java.nio.file.Files.isDirectory(p))
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
                        + "\nPass an explicit path as a positional CLI arg, or set the run "
                        + "config's working directory to a parent of the bootstrap-ide module.");
    }

    /** Same shape as {@link #resolveExisting} but for a regular file (not a directory). */
    private static Path resolveExistingFile(String label, Path... candidates)
    {
        for (Path p : candidates)
        {
            if (java.nio.file.Files.isRegularFile(p))
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
                "Cannot locate " + label + " file. cwd=" + Path.of("").toAbsolutePath()
                        + ". Tried:" + tried
                        + "\nRun `just bootstrap::build-compiler-pdb` (or `just build`) to "
                        + "produce shared/compiler.pdb, or pass an explicit path as a "
                        + "positional CLI arg.");
    }
}
