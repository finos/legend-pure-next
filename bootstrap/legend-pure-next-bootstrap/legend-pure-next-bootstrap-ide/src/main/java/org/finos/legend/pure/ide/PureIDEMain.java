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
 * <p>Usage: java -cp ... org.finos.legend.pure.ide.PureIDEMain
 *     [path-to-core.pdb] [compiler-pure-path] [welcome-path]</p>
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
        run(List.of(args), corePdb -> new JavaDirectBackend());
    }

    /**
     * Boot the IDE with the given backend factory. The factory receives the
     * resolved {@code core.pdb} path so backends that need their own resolver
     * (e.g. Truffle) can load the same bootstrap without re-resolving it.
     *
     * <p>Positional args (in order): {@code [path-to-core.pdb]
     * [compiler-pure-path] [welcome-path]}. Callers (e.g. a Truffle launcher)
     * should strip their own flags before invoking this.</p>
     */
    public static void run(List<String> args, Function<Path, PureBackend> backendFactory) throws Exception
    {
        // Resolve core.pdb path (CLI arg or classpath-bundled core.pdb)
        Path pdbPath = args.size() > 0
                ? Path.of(args.get(0))
                : BootstrapModule.locateCorePdb();

        System.out.println("Loading core.pdb from: " + pdbPath);
        PDBModule coreModule = new PDBModule(pdbPath, PDBModule.Mode.COMPILATION);
        System.out.println("Core PDB loaded (" + coreModule.elementPaths().size() + " elements)");

        // Add LocalModule for compiler-pure. Resolve relative to the launch
        // cwd OR the project root — IntelliJ run configs default cwd to the
        // project root, command-line `mvn exec`-from-bootstrap-ide module
        // defaults to the module dir. The default candidates cover both.
        Path compilerPurePath = args.size() > 1
                ? Path.of(args.get(1))
                : resolveExisting("compiler-pure",
                        Path.of("../../../pure/compiler-pure"),
                        Path.of("pure/compiler-pure"),
                        Path.of("legend-pure-next/pure/compiler-pure"));
        System.out.println("Loading compiler pure from: " + compilerPurePath);
        LocalModule compilerPureModule = new LocalModule(
                "next-compiler-pure", "*",
                java.util.List.of(coreModule.getName()),
                compilerPurePath
        );

        // Add LocalModule for welcome scratch pad (same cwd-resilience).
        Path welcomePath = args.size() > 2
                ? Path.of(args.get(2))
                : resolveExisting("welcome",
                        Path.of("src/main/resources/welcome"),
                        Path.of("bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-ide/src/main/resources/welcome"),
                        Path.of("legend-pure-next/bootstrap/legend-pure-next-bootstrap/legend-pure-next-bootstrap-ide/src/main/resources/welcome"));
        System.out.println("Loading welcome from: " + welcomePath);
        LocalModule welcomeModule = new LocalModule(
                "welcome", "*",
                java.util.List.of(coreModule.getName(), compilerPureModule.getName()),
                welcomePath
        );

        MutableList<LocalModule> editableModules = Lists.mutable.with(welcomeModule, compilerPureModule);

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

        PureBackend backend = backendFactory.apply(pdbPath);
        System.out.println("Backend: " + backend.name());

        // Start LSP WebSocket server
        PureLSPWebSocketServer lspServer = new PureLSPWebSocketServer(LSP_PORT, coreModule, editableModules, backend);
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
}
