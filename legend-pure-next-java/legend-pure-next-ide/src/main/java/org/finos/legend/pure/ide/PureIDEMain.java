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
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Main entry point for the Pure IDE.
 *
 * <p>Starts:
 * <ul>
 *   <li>HTTP server on port 9090 serving the Monaco web UI</li>
 *   <li>WebSocket LSP server on port 9091 for LSP communication</li>
 * </ul>
 *
 * <p>Usage: java -cp ... org.finos.legend.pure.ide.PureIDEMain [path-to-core.pdb]</p>
 */
public class PureIDEMain
{
    private static final int HTTP_PORT = 9090;
    private static final int LSP_PORT = 9091;

    public static void main(String[] args) throws Exception
    {
        // Resolve core.pdb path
        String pdbPath = args.length > 0
                ? args[0]
                : "../legend-pure-next-compiler/target/core.pdb";

        System.out.println("Loading core.pdb from: " + pdbPath);
        PDBModule coreModule = new PDBModule(Path.of(pdbPath), PDBModule.Mode.COMPILATION);
        System.out.println("Core PDB loaded (" + coreModule.elementPaths().size() + " elements)");

        // Add LocalModule for next-compiler-pure
        String compilerPurePathStr = args.length > 1
                ? args[1]
                : "../../legend-pure-next-compiler-pure/src/main/resources";
        System.out.println("Loading compiler pure from: " + compilerPurePathStr);
        LocalModule compilerPureModule = new LocalModule(
                "next-compiler-pure", "*",
                java.util.List.of(coreModule.getName()),
                Path.of(compilerPurePathStr)
        );

        // Add LocalModule for welcome scratch pad
        String welcomePathStr = args.length > 2
                ? args[2]
                : "src/main/resources/welcome";
        System.out.println("Loading welcome from: " + welcomePathStr);
        LocalModule welcomeModule = new LocalModule(
                "welcome", "*",
                java.util.List.of(coreModule.getName(), compilerPureModule.getName()),
                Path.of(welcomePathStr)
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

        // Start LSP WebSocket server
        PureLSPWebSocketServer lspServer = new PureLSPWebSocketServer(LSP_PORT, coreModule, editableModules);
        lspServer.start();
        System.out.println("LSP WebSocket server started: ws://localhost:" + LSP_PORT);

        System.out.println("\n  Open http://localhost:" + HTTP_PORT + " in your browser\n");

        // Keep main thread alive
        Thread.currentThread().join();
    }
}
