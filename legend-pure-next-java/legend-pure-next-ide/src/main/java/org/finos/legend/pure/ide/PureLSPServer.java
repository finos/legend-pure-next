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

import meta.pure.metamodel.function.FunctionDefinition;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationError;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.localModule.PureContent;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ElementIndex;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ElementIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * LSP server for the Pure language.
 *
 * <p>Supports:
 * <ul>
 *   <li>textDocument/didOpen, didChange → compile → publishDiagnostics</li>
 *   <li>workspace/executeCommand "pure/execute" → compile + execute go():Any[*]</li>
 * </ul>
 */
public class PureLSPServer implements LanguageServer, TextDocumentService, WorkspaceService
{
    private LanguageClient client;
    private final PDBModule coreModule;
    private final LocalModule compilerPureModule;
    private String currentSource = "";
    private String currentUri = "";
    private PureModel lastModel;

    public PureLSPServer(PDBModule coreModule, LocalModule compilerPureModule)
    {
        this.coreModule = coreModule;
        this.compilerPureModule = compilerPureModule;
    }

    public void connect(LanguageClient client)
    {
        this.client = client;
    }

    // =========================================================================
    // LanguageServer
    // =========================================================================

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params)
    {
        ServerCapabilities capabilities = new ServerCapabilities();

        // Full text sync — client sends entire document on each change
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);

        // Execute command support for pure/execute
        ExecuteCommandOptions execOptions = new ExecuteCommandOptions(
                List.of("pure/execute", "pure/packageTree", "pure/fileTree", "pure/jumpToElement", "pure/openFile", "pure/saveFile"));
        capabilities.setExecuteCommandProvider(execOptions);

        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override
    public CompletableFuture<Object> shutdown()
    {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit()
    {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService()
    {
        return this;
    }

    @Override
    public WorkspaceService getWorkspaceService()
    {
        return this;
    }

    // =========================================================================
    // TextDocumentService
    // =========================================================================

    @Override
    public void didOpen(DidOpenTextDocumentParams params)
    {
        currentUri = params.getTextDocument().getUri();
        currentSource = params.getTextDocument().getText();
        compileAndPublishDiagnostics();
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params)
    {
        currentUri = params.getTextDocument().getUri();
        // Full sync — take the last change (which is the full text)
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
        if (!changes.isEmpty())
        {
            currentSource = changes.get(changes.size() - 1).getText();
        }
        compileAndPublishDiagnostics();
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params)
    {
        // Clear diagnostics when document is closed
        if (client != null)
        {
            client.publishDiagnostics(new PublishDiagnosticsParams(
                    params.getTextDocument().getUri(), List.of()));
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params)
    {
        String uri = params.getTextDocument().getUri();
        if (uri.startsWith("file:///"))
        {
            String sourceId = uri.substring(8);
            if (!"user.pure".equals(sourceId))
            {
                String content = params.getText();
                if (content != null)
                {
                    boolean saved = compilerPureModule.saveSourceText(sourceId, content);
                    if (saved)
                    {
                        System.out.println("[LSP] Saved file: " + sourceId);
                        compileAndPublishDiagnostics();
                    }
                }
            }
        }
    }

    // =========================================================================
    // WorkspaceService
    // =========================================================================

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params)
    {
        // No-op
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params)
    {
        // No-op
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params)
    {
        System.out.println("[LSP] executeCommand: " + params.getCommand());
        return switch (params.getCommand())
        {
            case "pure/execute" -> CompletableFuture.supplyAsync(() ->
            {
                executeGoFunction();
                return null;
            });
            case "pure/packageTree" -> CompletableFuture.supplyAsync(() ->
            {
                sendPackageTree();
                return null;
            });
            case "pure/fileTree" -> CompletableFuture.supplyAsync(() ->
            {
                sendFileTree();
                return null;
            });
            case "pure/jumpToElement" -> CompletableFuture.supplyAsync(() ->
            {
                if (params.getArguments() != null && !params.getArguments().isEmpty())
                {
                    String elementPath = String.valueOf(params.getArguments().get(0));
                    if (elementPath.startsWith("\"") && elementPath.endsWith("\"")) {
                        elementPath = elementPath.substring(1, elementPath.length() - 1);
                    }
                    handleJumpToElement(elementPath);
                }
                return null;
            });
            case "pure/openFile" -> CompletableFuture.supplyAsync(() ->
            {
                if (params.getArguments() != null && !params.getArguments().isEmpty())
                {
                    String sourceId = String.valueOf(params.getArguments().get(0));
                    if (sourceId.startsWith("\"") && sourceId.endsWith("\"")) {
                        sourceId = sourceId.substring(1, sourceId.length() - 1);
                    }
                    handleOpenFile(sourceId);
                }
                return null;
            });
            case "pure/saveFile" -> CompletableFuture.supplyAsync(() ->
            {
                if (params.getArguments() != null && params.getArguments().size() >= 2)
                {
                    String sourceId = String.valueOf(params.getArguments().get(0));
                    if (sourceId.startsWith("\"") && sourceId.endsWith("\"")) {
                        sourceId = sourceId.substring(1, sourceId.length() - 1);
                    }
                    Object contentObj = params.getArguments().get(1);
                    String content = contentObj instanceof com.google.gson.JsonPrimitive 
                        ? ((com.google.gson.JsonPrimitive) contentObj).getAsString() 
                        : String.valueOf(contentObj);
                    
                    boolean skipCompile = false;
                    if (params.getArguments().size() >= 3) {
                        Object skipObj = params.getArguments().get(2);
                        if (skipObj instanceof com.google.gson.JsonPrimitive) {
                            skipCompile = ((com.google.gson.JsonPrimitive) skipObj).getAsBoolean();
                        } else {
                            skipCompile = Boolean.parseBoolean(String.valueOf(skipObj));
                        }
                    }
                    handleSaveFile(sourceId, content, skipCompile);
                }
                return null;
            });
            default -> CompletableFuture.completedFuture(null);
        };
    }

    private void handleSaveFile(String sourceId, String content, boolean skipCompile)
    {
        if (content != null && !"user.pure".equals(sourceId))
        {
            boolean saved = compilerPureModule.saveSourceText(sourceId, content);
            if (saved)
            {
                System.out.println("[LSP] Saved file via command: " + sourceId);
                if (!skipCompile) {
                    compileAndPublishDiagnostics();
                }
            }
            else
            {
                System.err.println("[LSP] Failed to save file via command: " + sourceId);
            }
        }
    }

    // =========================================================================
    // Compilation
    // =========================================================================

    private CompilationResult compileCurrentSource()
    {
        System.out.println("[LSP] Compiling source (" + currentSource.length() + " chars)");
        try
        {
            LocalModule userModule = new LocalModule("user", "*",
                    Lists.mutable.with(coreModule.getName(), compilerPureModule.getName()),
                    Lists.mutable.with(new PureContent(currentSource, "user.pure")));

            PureModel model = PureModel.withModules(Lists.mutable.with(userModule, compilerPureModule, coreModule))
                    .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                    .build();

            CompilationResult result = model.compile();
            if (result.errors().isEmpty())
            {
                this.lastModel = model;
            }
            return result;
        }
        catch (Exception e)
        {
            System.err.println("[LSP] Compilation exception: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private void compileAndPublishDiagnostics()
    {
        if (client == null || currentUri.isEmpty())
        {
            return;
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        CompilationResult result = compileCurrentSource();

        if (result == null)
        {
            diagnostics.add(new Diagnostic(
                    new Range(new Position(0, 0), new Position(0, 1)),
                    "Internal compilation error",
                    DiagnosticSeverity.Error, "pure"));
        }
        else if (!result.errors().isEmpty())
        {
            for (CompilationError error : result.errors())
            {
                int line = 0;
                int col = 0;
                int endLine = 0;
                int endCol = 1;
                if (error.sourceInformation() != null)
                {
                    var si = error.sourceInformation();
                    line = si._startLine() != null ? Math.max(0, si._startLine().intValue() - 1) : 0;
                    col = si._startColumn() != null ? Math.max(0, si._startColumn().intValue() - 1) : 0;
                    endLine = si._endLine() != null ? Math.max(0, si._endLine().intValue() - 1) : line;
                    endCol = si._endColumn() != null ? si._endColumn().intValue() : col + 1;
                }
                Diagnostic diag = new Diagnostic(
                        new Range(new Position(line, col), new Position(endLine, endCol)),
                        error.message(),
                        DiagnosticSeverity.Error, "pure");
                diagnostics.add(diag);
            }
        }

        client.publishDiagnostics(new PublishDiagnosticsParams(currentUri, diagnostics));
    }

    // =========================================================================
    // Execution
    // =========================================================================

    private void executeGoFunction()
    {
        if (client == null)
        {
            return;
        }

        try
        {
            // Compile
            LocalModule userModule = new LocalModule("user", "*",
                    Lists.mutable.with(coreModule.getName(), compilerPureModule.getName()),
                    Lists.mutable.with(new PureContent(currentSource, "user.pure")));

            PureModel model = PureModel.withModules(Lists.mutable.with(userModule, compilerPureModule, coreModule))
                    .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                    .build();

            CompilationResult result = model.compile();

            if (!result.errors().isEmpty())
            {
                StringBuilder sb = new StringBuilder("Compilation errors:\n");
                for (CompilationError err : result.errors())
                {
                    sb.append("  ").append(err.message()).append("\n");
                }
                sendExecuteResult(sb.toString(), true);
                return;
            }

            // Resolve go():Any[*]
            org.finos.legend.pure.m3.module.Module testModule = model.getModule("user");
            FunctionDefinition goFunc = null;

            // Try common mangled names
            for (String candidate : List.of("go__Any_MANY_", "go__String_1_", "go__String_MANY_",
                    "go__Integer_1_", "go__Boolean_1_"))
            {
                try
                {
                    Object element = testModule.getElement(candidate);
                    if (element instanceof FunctionDefinition fd)
                    {
                        goFunc = fd;
                        break;
                    }
                }
                catch (Exception ignored) {}
            }

            if (goFunc == null)
            {
                sendExecuteResult("Error: Could not find function go().\nDefine: function go():Any[*] { ... }", true);
                return;
            }

            // Execute — use a scoped MetadataAccess that covers both user and core modules
            PureExecution execution = new PureExecution(
                    new org.finos.legend.pure.m3.module.ScopedMetadataAccess(testModule, model));
            Object output = execution.execute(goFunc);

            String resultStr = formatResult(output);
            sendExecuteResult(resultStr, false);
        }
        catch (Exception e)
        {
            sendExecuteResult("Execution error: " + e.getMessage(), true);
        }
    }

    private String formatResult(Object output)
    {
        if (output == null) { return "null"; }
        if (output instanceof List<?> list)
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++)
            {
                if (i > 0) { sb.append("\n"); }
                sb.append(formatResult(list.get(i)));
            }
            return sb.toString();
        }
        return String.valueOf(output);
    }

    private void sendExecuteResult(String text, boolean isError)
    {
        if (client instanceof PureLanguageClient pureClient)
        {
            pureClient.executeResult(new ExecuteResultParams(text, isError));
        }
    }

    // =========================================================================
    // Tree Data
    // =========================================================================

    private void sendPackageTree()
    {
        if (client == null) { return; }

        // Read elementIndex from PDB archive
        Map<String, String> elementTypes = new LinkedHashMap<>();
        byte[] indexBytes = coreModule.archive().readSection("elementIndex");
        if (indexBytes != null)
        {
            ElementIndex index = ElementIndex.getRootAsElementIndex(ByteBuffer.wrap(indexBytes));
            for (int i = 0; i < index.elementsLength(); i++)
            {
                ElementIndexEntry entry = index.elements(i);
                elementTypes.put(entry.elementPath(), entry.elementType());
            }
        }

        // Also add elements from any local modules in the last compiled model
        if (lastModel != null)
        {
            for (Module m : lastModel.modules())
            {
                if (m instanceof LocalModule)
                {
                    for (String path : m.elementPaths())
                    {
                        if (!elementTypes.containsKey(path))
                        {
                            meta.pure.metamodel.PackageableElement element = m.getElement(path);
                            String type = "UserDefined";
                            if (element != null)
                            {
                                if (element instanceof meta.pure.metamodel.type.Class) { type = "Class"; }
                                else if (element instanceof meta.pure.metamodel.type.Enumeration) { type = "Enumeration"; }
                                else if (element instanceof meta.pure.metamodel.relationship.Association) { type = "Association"; }
                                else if (element instanceof meta.pure.metamodel.extension.Profile) { type = "Profile"; }
                                else if (element instanceof meta.pure.metamodel.function.UserDefinedFunction) { type = "UserDefinedFunction"; }
                                else if (element instanceof meta.pure.metamodel.function.NativeFunction) { type = "NativeFunction"; }
                                else if (element instanceof meta.pure.metamodel.type.PrimitiveType) { type = "PrimitiveType"; }
                                else if (element instanceof meta.pure.metamodel.Package) { type = "Package"; }
                            }
                            elementTypes.put(path, type);
                        }
                    }
                }
            }
        }

        String json = buildPackageTreeJson(elementTypes);
        sendTreeData("packageTree", json);
    }

    private void sendFileTree()
    {
        if (client == null) { return; }

        // Collect source files from all modules in the last compiled model
        Set<String> allFiles = new LinkedHashSet<>();
        if (lastModel != null)
        {
            for (Module m : lastModel.modules())
            {
                allFiles.addAll(m.sourceFiles());
            }
        }

        String json = buildFileTreeJson(allFiles);
        sendTreeData("fileTree", json);
    }

    /**
     * Build a JSON tree from :: separated element paths.
     * Each node: {"name":"x","type":"Package","children":[...]}
     */
    private String buildPackageTreeJson(Map<String, String> elementTypes)
    {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("__children", new LinkedHashMap<String, Object>());

        for (Map.Entry<String, String> entry : elementTypes.entrySet())
        {
            String path = entry.getKey();
            String type = entry.getValue();
            String[] parts = path.split("::");

            @SuppressWarnings("unchecked")
            Map<String, Object> current = (Map<String, Object>) root.get("__children");
            for (int i = 0; i < parts.length; i++)
            {
                String part = parts[i];
                if (!current.containsKey(part))
                {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("__children", new LinkedHashMap<String, Object>());
                    current.put(part, node);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> nodeMap = (Map<String, Object>) current.get(part);
                if (i == parts.length - 1)
                {
                    nodeMap.put("__type", type);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> nextChildren = (Map<String, Object>) nodeMap.get("__children");
                current = nextChildren;
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> rootChildren = (Map<String, Object>) root.get("__children");
        return renderTreeJson(rootChildren);
    }

    /**
     * Build a JSON tree from source file paths (split by /).     * Each file is a leaf node with type "File".
     */
    private String buildFileTreeJson(Set<String> filePaths)
    {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("__children", new LinkedHashMap<String, Object>());

        for (String filePath : filePaths)
        {
            String[] parts = filePath.split("/");
            @SuppressWarnings("unchecked")
            Map<String, Object> current = (Map<String, Object>) root.get("__children");
            for (int i = 0; i < parts.length; i++)
            {
                String part = parts[i];
                if (!current.containsKey(part))
                {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("__children", new LinkedHashMap<String, Object>());
                    current.put(part, node);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> nodeMap = (Map<String, Object>) current.get(part);
                if (i == parts.length - 1)
                {
                    nodeMap.put("__type", "File");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> nextChildren = (Map<String, Object>) nodeMap.get("__children");
                current = nextChildren;
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> rootChildren = (Map<String, Object>) root.get("__children");
        return renderTreeJson(rootChildren);
    }

    private void handleJumpToElement(String elementPath)
    {
        if (client == null) { return; }
        
        if (lastModel != null)
        {
            Module userModule = lastModel.getModule("user");
            if (userModule instanceof LocalModule lu)
            {
                String sourceId = lu.getSourceIdForElement(elementPath);
                if (sourceId != null && "user.pure".equals(sourceId))
                {
                    meta.pure.metamodel.PackageableElement element = lu.getElement(elementPath);
                    if (element != null && element._sourceInformation() != null)
                    {
                        Long lLine = element._sourceInformation()._startLine();
                        Long lCol = element._sourceInformation()._startColumn();
                        sendOpenFile(sourceId, currentSource, lLine != null ? lLine.intValue() : null, lCol != null ? lCol.intValue() : null);
                    }
                    else
                    {
                        sendOpenFile(sourceId, currentSource);
                    }
                    return;
                }
            }
        }

        String sourceId = compilerPureModule.getSourceIdForElement(elementPath);
        if (sourceId != null)
        {
            String content = compilerPureModule.getSourceText(sourceId);
            if (content != null)
            {
                meta.pure.metamodel.PackageableElement element = compilerPureModule.getElement(elementPath);
                if (element != null && element._sourceInformation() != null)
                {
                    Long lLine = element._sourceInformation()._startLine();
                    Long lCol = element._sourceInformation()._startColumn();
                    sendOpenFile(sourceId, content, lLine != null ? lLine.intValue() : null, lCol != null ? lCol.intValue() : null);
                }
                else
                {
                    sendOpenFile(sourceId, content);
                }
            }
        }
    }

    private void handleOpenFile(String sourceId)
    {
        if (client == null) { return; }

        if ("user.pure".equals(sourceId))
        {
            sendOpenFile(sourceId, currentSource);
            return;
        }

        String content = compilerPureModule.getSourceText(sourceId);
        if (content != null)
        {
            sendOpenFile(sourceId, content);
        }
    }

    private void sendOpenFile(String sourceId, String content)
    {
        if (client instanceof PureLanguageClient pureClient)
        {
            pureClient.openFile(new OpenFileParams(sourceId, content));
        }
    }

    private void sendOpenFile(String sourceId, String content, Integer line, Integer column)
    {
        if (client instanceof PureLanguageClient pureClient)
        {
            pureClient.openFile(new OpenFileParams(sourceId, content, line, column));
        }
    }

    @SuppressWarnings("unchecked")
    private String renderTreeJson(Map<String, Object> children)
    {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;

        // Sort: packages (nodes with children) first, then leaves
        List<Map.Entry<String, Object>> sorted = new ArrayList<>(children.entrySet());
        sorted.sort((a, b) ->
        {
            Map<String, Object> aNode = (Map<String, Object>) a.getValue();
            Map<String, Object> bNode = (Map<String, Object>) b.getValue();
            boolean aHasChildren = !((Map<?, ?>) aNode.get("__children")).isEmpty();
            boolean bHasChildren = !((Map<?, ?>) bNode.get("__children")).isEmpty();
            if (aHasChildren != bHasChildren) { return aHasChildren ? -1 : 1; }
            return a.getKey().compareToIgnoreCase(b.getKey());
        });

        for (Map.Entry<String, Object> entry : sorted)
        {
            if (!first) { sb.append(","); }
            first = false;
            Map<String, Object> node = (Map<String, Object>) entry.getValue();
            Map<String, Object> nodeChildren = (Map<String, Object>) node.get("__children");
            String type = (String) node.getOrDefault("__type", "Package");

            sb.append("{\"name\":\"").append(escapeJson(entry.getKey())).append("\"");
            sb.append(",\"type\":\"").append(escapeJson(type)).append("\"");
            if (!nodeChildren.isEmpty())
            {
                sb.append(",\"children\":").append(renderTreeJson(nodeChildren));
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void sendTreeData(String treeId, String json)
    {
        if (client instanceof PureLanguageClient pureClient)
        {
            pureClient.treeData(new TreeDataParams(treeId, json));
        }
    }

    // =========================================================================
    // Custom notification types
    // =========================================================================

    /**
     * Extended client interface that supports our custom notifications.
     */
    public interface PureLanguageClient extends LanguageClient
    {
        @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("pure/executeResult")
        void executeResult(ExecuteResultParams params);

        @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("pure/treeData")
        void treeData(TreeDataParams params);

        @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("pure/openFile")
        void openFile(OpenFileParams params);
    }

    public static class ExecuteResultParams
    {
        private String result;
        private boolean isError;

        public ExecuteResultParams() {}

        public ExecuteResultParams(String result, boolean isError)
        {
            this.result = result;
            this.isError = isError;
        }

        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public boolean isError() { return isError; }
        public void setError(boolean error) { isError = error; }
    }

    public static class TreeDataParams
    {
        private String treeId;
        private String json;

        public TreeDataParams() {}

        public TreeDataParams(String treeId, String json)
        {
            this.treeId = treeId;
            this.json = json;
        }

        public String getTreeId() { return treeId; }
        public void setTreeId(String treeId) { this.treeId = treeId; }
        public String getJson() { return json; }
        public void setJson(String json) { this.json = json; }
    }

    public static class OpenFileParams
    {
        private String sourceId;
        private String content;
        private Integer line;
        private Integer column;

        public OpenFileParams() {}

        public OpenFileParams(String sourceId, String content)
        {
            this.sourceId = sourceId;
            this.content = content;
        }

        public OpenFileParams(String sourceId, String content, Integer line, Integer column)
        {
            this.sourceId = sourceId;
            this.content = content;
            this.line = line;
            this.column = column;
        }

        public Integer getLine() { return line; }
        public void setLine(Integer line) { this.line = line; }
        public Integer getColumn() { return column; }
        public void setColumn(Integer column) { this.column = column; }

        public String getSourceId() { return sourceId; }
        public void setSourceId(String sourceId) { this.sourceId = sourceId; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}
