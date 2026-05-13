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

import org.eclipse.collections.api.list.MutableList;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.finos.legend.pure.ide.backend.PureBackend;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * WebSocket server that bridges each WebSocket connection to an LSP4J launcher.
 *
 * <p>Each WebSocket client gets its own {@link PureLSPServer} instance and
 * LSP4J launcher. WebSocket text messages are forwarded to the LSP4J input
 * stream as LSP JSON-RPC messages (with Content-Length headers), and LSP4J
 * output is forwarded back to the WebSocket client.</p>
 */
public class PureLSPWebSocketServer extends WebSocketServer
{
    private final MutableList<PDBModule> pdbModules;
    private final MutableList<LocalModule> editableModules;
    private final PureBackend backend;
    private final Map<WebSocket, ConnectionState> connections = new ConcurrentHashMap<>();

    public PureLSPWebSocketServer(int port, MutableList<PDBModule> pdbModules,
                                  MutableList<LocalModule> editableModules,
                                  PureBackend backend)
    {
        super(new InetSocketAddress(port));
        this.pdbModules = pdbModules;
        this.editableModules = editableModules;
        this.backend = backend;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake)
    {
        System.out.println("[LSP] Client connected: " + conn.getRemoteSocketAddress());
        try
        {
            // Create piped streams for the LSP4J launcher
            PipedInputStream serverInput = new PipedInputStream(65536);
            PipedOutputStream clientToServer = new PipedOutputStream(serverInput);

            // Create the LSP server
            PureLSPServer server = new PureLSPServer(pdbModules, editableModules, backend);

            // Output stream that writes back to the WebSocket
            OutputStream serverOutput = new WebSocketOutputStream(conn);

            // Launch LSP4J
            Launcher<PureLSPServer.PureLanguageClient> launcher = new Launcher.Builder<PureLSPServer.PureLanguageClient>()
                    .setLocalService(server)
                    .setRemoteInterface(PureLSPServer.PureLanguageClient.class)
                    .setInput(serverInput)
                    .setOutput(serverOutput)
                    .create();

            // Connect the client proxy to the server
            server.connect(launcher.getRemoteProxy());

            // Start listening on the input stream in a background thread
            Future<Void> future = launcher.startListening();

            connections.put(conn, new ConnectionState(clientToServer, future));
        }
        catch (IOException e)
        {
            System.err.println("[LSP] Failed to set up connection: " + e.getMessage());
            conn.close();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message)
    {
        ConnectionState state = connections.get(conn);
        if (state == null) { return; }

        try
        {
            // Write LSP Content-Length header + body to the piped stream
            byte[] content = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String header = "Content-Length: " + content.length + "\r\n\r\n";
            synchronized (state.outputToServer)
            {
                state.outputToServer.write(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                state.outputToServer.write(content);
                state.outputToServer.flush();
            }
        }
        catch (IOException e)
        {
            System.err.println("[LSP] Error writing to server: " + e.getMessage());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote)
    {
        System.out.println("[LSP] Client disconnected: " + conn.getRemoteSocketAddress());
        ConnectionState state = connections.remove(conn);
        if (state != null)
        {
            try { state.outputToServer.close(); } catch (IOException ignored) {}
            state.listenerFuture.cancel(true);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex)
    {
        System.err.println("[LSP] WebSocket error: " + ex.getMessage());
    }

    @Override
    public void onStart()
    {
        System.out.println("[LSP] WebSocket server started on port " + getPort());
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private record ConnectionState(PipedOutputStream outputToServer, Future<Void> listenerFuture) {}

    /**
     * OutputStream that writes LSP JSON-RPC messages back to a WebSocket connection.
     *
     * <p>Buffers bytes until a complete LSP message (Content-Length header + body) is
     * available, then sends the JSON body as a WebSocket text message.</p>
     */
    private static class WebSocketOutputStream extends OutputStream
    {
        private final WebSocket conn;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        WebSocketOutputStream(WebSocket conn)
        {
            this.conn = conn;
        }

        @Override
        public void write(int b) throws IOException
        {
            buffer.write(b);
            trySend();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
            buffer.write(b, off, len);
            trySend();
        }

        private void trySend()
        {
            byte[] data = buffer.toByteArray();
            String dataStr = new String(data, java.nio.charset.StandardCharsets.UTF_8);

            // Look for the Content-Length header separator
            int headerEnd = dataStr.indexOf("\r\n\r\n");
            if (headerEnd < 0) { return; }

            // Parse Content-Length
            String header = dataStr.substring(0, headerEnd);
            int contentLength = -1;
            for (String line : header.split("\r\n"))
            {
                if (line.toLowerCase().startsWith("content-length:"))
                {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                    break;
                }
            }
            if (contentLength < 0) { return; }

            int bodyStart = headerEnd + 4; // after \r\n\r\n
            byte[] bodyStartBytes = dataStr.substring(0, bodyStart).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int bodyStartByteCount = bodyStartBytes.length;

            if (data.length < bodyStartByteCount + contentLength) { return; }

            // Extract the JSON body
            String jsonBody = new String(data, bodyStartByteCount, contentLength, java.nio.charset.StandardCharsets.UTF_8);

            // Send via WebSocket
            if (conn.isOpen())
            {
                conn.send(jsonBody);
            }

            // Keep remaining bytes in buffer
            int consumed = bodyStartByteCount + contentLength;
            buffer.reset();
            if (consumed < data.length)
            {
                buffer.write(data, consumed, data.length - consumed);
            }
        }
    }
}
