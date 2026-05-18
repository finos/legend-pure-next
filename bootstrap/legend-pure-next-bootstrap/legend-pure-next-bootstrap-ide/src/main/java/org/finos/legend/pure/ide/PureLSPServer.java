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
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import org.finos.legend.pure.cli.CompilerNatives;
import org.finos.legend.pure.execution.PureExecution;
import org.finos.legend.pure.ide.backend.PureBackend;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.CompilationError;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.Module;
import org.finos.legend.pure.m3.module.localModule.LocalModule;
import org.finos.legend.pure.m3.module.pdbModule.PDBModule;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ElementIndex;
import org.finos.legend.pure.m3.module.pdbModule.fbs.ElementIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private static final long DEBOUNCE_DELAY_MS = 500;

    private LanguageClient client;
    private final MutableList<PDBModule> pdbModules;
    /** First PDB by convention is core — used for native-extension lookups and
     *  the element-index banner that drives the package tree. */
    private final PDBModule coreModule;
    private final MutableList<LocalModule> editableModules;
    private final PureBackend backend;
    private String currentSource = "";
    private String currentUri = "";
    private PureModel lastModel;
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pendingCompile;
    /** Set by {@code pure/cancelTests}; read by the {@code pure/runTests}
     *  loop between tests to stop running further tests. Doesn't interrupt
     *  the currently-running test (Truffle doesn't expose a clean cancel
     *  hook for in-flight evaluation), but stops the queue mid-batch. */
    private final java.util.concurrent.atomic.AtomicBoolean testsCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

    public PureLSPServer(MutableList<PDBModule> pdbModules, MutableList<LocalModule> editableModules, PureBackend backend)
    {
        if (pdbModules.isEmpty())
        {
            throw new IllegalArgumentException("PureLSPServer requires at least one PDB (core.pdb)");
        }
        this.pdbModules = pdbModules;
        this.coreModule = pdbModules.get(0);
        this.editableModules = editableModules;
        this.backend = backend;
        System.out.println("[LSP] backend = " + backend.name());
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

        // Go-to-definition (Ctrl+B / F12 / right-click → Go to Definition).
        // The cursor's position is mapped to an AST node via the compiled graph;
        // the node's `.func` / `.genericType.type` / etc. is resolved to a
        // PackageableElement whose `sourceInformation` we return as a Location.
        capabilities.setDefinitionProvider(true);

        // Execute command support for pure/execute
        ExecuteCommandOptions execOptions = new ExecuteCommandOptions(
                List.of("pure/execute", "pure/packageTree", "pure/fileTree", "pure/jumpToElement", "pure/openFile", "pure/saveFile", "pure/getPCTAdapters", "pure/discoverTests", "pure/runTests", "pure/cancelTests", "pure/search"));
        capabilities.setExecuteCommandProvider(execOptions);

        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override
    public void initialized(InitializedParams params)
    {
        // Push the welcome file content to the client
        if (client instanceof PureLanguageClient pureClient)
        {
            LocalModule welcomeModule = findModuleForSource("welcome.pure");
            if (welcomeModule != null)
            {
                String content = welcomeModule.getSourceText("welcome.pure");
                if (content != null)
                {
                    pureClient.openFile(new OpenFileParams("welcome.pure", content));
                }
            }
        }
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
        // Do NOT trigger a compile here: compileCurrentSource() persists
        // currentSource to welcome.pure, and on a freshly-connected client
        // the editor is empty at this point — which would silently wipe the
        // file on disk. The first didChange (user edit) will pick this up
        // with real content.
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
        scheduleCompile();
    }

    private void scheduleCompile()
    {
        if (pendingCompile != null && !pendingCompile.isDone())
        {
            pendingCompile.cancel(false);
        }
        pendingCompile = debounceExecutor.schedule(this::compileAndPublishDiagnostics, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
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
            String content = params.getText();
            if (content != null)
            {
                boolean saved = saveToModule(sourceId, content);
                if (saved)
                {
                    System.out.println("[LSP] Saved file: " + sourceId);
                    compileAndPublishDiagnostics();
                }
            }
        }
    }

    /**
     * textDocument/definition — Ctrl+B / F12 / right-click → Go to Definition.
     *
     * <p>Maps the cursor position to an AST node in the compiled graph
     * ({@code lastModel}), then resolves that node's reference to a
     * PackageableElement's source location:
     * <ul>
     *   <li>{@code FunctionExpression} ({@code FunctionApplication},
     *       {@code FunctionInvocation}, {@code DotApplication}) → {@code _func()}
     *       — the resolved function or property declaration.</li>
     *   <li>{@code GenericTypeValue} (type annotation) → {@code _type()} — the
     *       referenced class / enumeration / primitive type.</li>
     *   <li>{@code VariableExpression} → traverse the enclosing function's
     *       parameters and {@code let} bindings for a matching {@code _name()}.</li>
     * </ul>
     *
     * <p>If the resolved target lives in a base PDB (e.g. {@code core.pdb},
     * {@code compiler.pdb}), we show an info message rather than open the
     * compiled module — same boundary as {@code pure/jumpToElement}.
     */
    @Override
    public java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params)
    {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> resolveDefinition(params));
    }

    private org.eclipse.lsp4j.jsonrpc.messages.Either<List<? extends Location>, List<? extends LocationLink>> resolveDefinition(DefinitionParams params)
    {
        if (lastModel == null)
        {
            // First definition request before any didChange / F9 — trigger a
            // compile so we have a model to navigate against. Skips the save
            // (the didOpen handler intentionally avoided that to prevent
            // wiping welcome.pure with an empty initial buffer).
            try { compileCurrentSource(); } catch (Exception ignored) {}
            if (lastModel == null)
            {
                return org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(java.util.Collections.emptyList());
            }
        }
        String uri = params.getTextDocument().getUri();
        String sourceId = uri.startsWith("file:///") ? uri.substring(8) : uri;
        // LSP positions are 0-based; Pure SourceInformation is 1-based.
        int line = params.getPosition().getLine() + 1;
        int col = params.getPosition().getCharacter() + 1;
        System.err.println("[LSP/def] uri=" + uri + " sourceId=" + sourceId + " line=" + line + " col=" + col);

        Object exprAtCursor = findExpressionAt(sourceId, line, col);
        if (exprAtCursor == null)
        {
            System.err.println("[LSP/def] no expression at cursor.");
            System.err.println("[LSP/def]   lastModel modules:");
            for (Module mod : lastModel.modules())
            {
                System.err.println("[LSP/def]     " + mod.getClass().getSimpleName() + " " + mod.getName() + " elementCount=" + mod.elementPaths().size());
            }
            // Sample the unique sourceIds seen on top-level element SIs.
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            int distinctCount = 0;
            for (Module mod : lastModel.modules())
            {
                for (String elementPath : mod.elementPaths())
                {
                    meta.pure.metamodel.PackageableElement el = mod.getElement(elementPath);
                    if (el == null) continue;
                    meta.pure.metamodel.SourceInformation si = el._sourceInformation();
                    if (si == null) continue;
                    String sid = si._sourceId();
                    if (seen.add(sid == null ? "<null>" : sid))
                    {
                        if (distinctCount++ < 20)
                        {
                            System.err.println("[LSP/def]     sourceId: " + sid + "  (e.g. " + elementPath + ")");
                        }
                    }
                }
            }
            System.err.println("[LSP/def]   distinct sourceIds: " + seen.size());
            return org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(java.util.Collections.emptyList());
        }
        System.err.println("[LSP/def] expr at cursor: " + exprAtCursor.getClass().getName());

        TargetLocation target = resolveTarget(exprAtCursor);
        if (target == null)
        {
            return org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(java.util.Collections.emptyList());
        }
        if (findModuleForSource(target.sourceId) == null)
        {
            if (client != null)
            {
                client.showMessage(new MessageParams(MessageType.Info,
                        "Cannot navigate: definition is in a compiled module (" + target.sourceId + ")"));
            }
            return org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(java.util.Collections.emptyList());
        }

        String targetUri = "file:///" + target.sourceId;
        int startLine = Math.max(0, target.startLine - 1);
        int startCol = Math.max(0, target.startColumn - 1);
        int endLine = Math.max(startLine, target.endLine - 1);
        int endCol = Math.max(startCol, target.endColumn);
        Location loc = new Location(targetUri, new Range(
                new Position(startLine, startCol),
                new Position(endLine, endCol)));
        return org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(List.of(loc));
    }

    /** Resolved navigation target: file + 1-based line/column range. */
    private record TargetLocation(String sourceId, int startLine, int startColumn, int endLine, int endColumn) {}

    /**
     * Find the smallest expression (or PackageableElement) in {@link #lastModel}
     * whose {@code sourceInformation} contains the (sourceId, line, column)
     * cursor position. Returns the deepest match — e.g. for {@code foo(bar())}
     * with the cursor on {@code bar}, returns the inner FE rather than the outer.
     */
    private Object findExpressionAt(String sourceId, int line, int col)
    {
        if (lastModel == null) return null;
        Object[] best = new Object[]{null};
        long[] bestSize = new long[]{Long.MAX_VALUE};
        for (Module mod : lastModel.modules())
        {
            // Build elementPath → sourceId map for this module. Per-element SIs
            // have null _sourceId() (Java-direct compiler tracks the file in
            // the module's element index, not on each element's SI). Use the
            // index as the authoritative file identity for top-level entry.
            java.util.function.Function<String, String> sourceIdOf;
            if (mod instanceof LocalModule lm)
            {
                sourceIdOf = lm::getSourceIdForElement;
            }
            else
            {
                sourceIdOf = p -> null;  // PDB modules — never match user files
            }
            for (String elementPath : mod.elementPaths())
            {
                String elSourceId = sourceIdOf.apply(elementPath);
                if (elSourceId == null || !elSourceId.equals(sourceId)) continue;

                meta.pure.metamodel.PackageableElement el =
                        (meta.pure.metamodel.PackageableElement) mod.getElement(elementPath);
                if (el == null) continue;
                meta.pure.metamodel.SourceInformation si = el._sourceInformation();
                if (si == null) continue;
                // Line/column range check still applies — only line+col within
                // the element's range can be a hit. SourceId is already known
                // from the index lookup above, so don't re-check it on the SI.
                if (line < si._startLine().intValue() || line > si._endLine().intValue()) continue;
                if (line == si._startLine().intValue() && col < si._startColumn().intValue()) continue;
                if (line == si._endLine().intValue() && col > si._endColumn().intValue()) continue;
                walkForCursor(el, sourceId, line, col, best, bestSize);
            }
        }
        return best[0];
    }

    /** Recursively descend into a node looking for the smallest SI containing the cursor. */
    private void walkForCursor(Object node, String sourceId, int line, int col, Object[] best, long[] bestSize)
    {
        if (node == null) return;
        if (node instanceof Iterable<?> iter)
        {
            for (Object child : iter) walkForCursor(child, sourceId, line, col, best, bestSize);
            return;
        }
        if (!(node instanceof meta.pure.metamodel.type.Any any))
        {
            return;
        }
        meta.pure.metamodel.SourceInformation si = nodeSI(any);
        if (si != null)
        {
            if (!siContains(si, sourceId, line, col)) return;  // skip subtrees outside cursor
            long size = siSize(si);
            if (size < bestSize[0])
            {
                bestSize[0] = size;
                best[0] = any;
            }
        }
        // Recurse into AST-shaped child slots. Only descend into slots that
        // hold sub-expressions / sub-nodes; do NOT follow references like
        // `func`, `type`, `owner`, `general` — those point at other elements
        // and would explode the walk.
        if (any instanceof meta.pure.metamodel.function.FunctionDefinition fd)
        {
            walkForCursor(fd._expressionSequence(), sourceId, line, col, best, bestSize);
            walkForCursor(fd._parameters(), sourceId, line, col, best, bestSize);
        }
        if (any instanceof meta.pure.metamodel.valuespecification.FunctionExpression fe)
        {
            walkForCursor(fe._parametersValues(), sourceId, line, col, best, bestSize);
        }
        if (any instanceof meta.pure.metamodel.valuespecification.Collection c)
        {
            walkForCursor(c._values(), sourceId, line, col, best, bestSize);
        }
        if (any instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
        {
            // AtomicValue.value can hold a lambda — descend into it
            Object v = av._value();
            if (v instanceof meta.pure.metamodel.function.LambdaFunction lf)
            {
                walkForCursor(lf._expressionSequence(), sourceId, line, col, best, bestSize);
                walkForCursor(lf._parameters(), sourceId, line, col, best, bestSize);
            }
        }
        // Class properties / qualified properties are top-level: their bodies
        // become FunctionDefinition-shaped (default values, constraints, QP
        // expressionSequence). Walk those.
        if (any instanceof meta.pure.metamodel.type.Class cls)
        {
            for (Object pObj : cls._properties())
            {
                meta.pure.metamodel.function.property.Property p =
                        (meta.pure.metamodel.function.property.Property) pObj;
                walkForCursor(p, sourceId, line, col, best, bestSize);
                if (p._defaultValue() != null)
                {
                    walkForCursor(p._defaultValue()._expressionSequence(), sourceId, line, col, best, bestSize);
                }
            }
            for (Object qpObj : cls._qualifiedProperties())
            {
                meta.pure.metamodel.function.property.QualifiedProperty qp =
                        (meta.pure.metamodel.function.property.QualifiedProperty) qpObj;
                walkForCursor(qp, sourceId, line, col, best, bestSize);
                walkForCursor(qp._expressionSequence(), sourceId, line, col, best, bestSize);
                walkForCursor(qp._parameters(), sourceId, line, col, best, bestSize);
            }
        }
    }

    /** Source info for a node, or null. SourceInformation lives at the
     *  ValueSpecification level and on PackageableElement subtypes. */
    private static meta.pure.metamodel.SourceInformation nodeSI(meta.pure.metamodel.type.Any node)
    {
        if (node instanceof meta.pure.metamodel.PackageableElement pe) return pe._sourceInformation();
        if (node instanceof meta.pure.metamodel.valuespecification.ValueSpecification vs) return vs._sourceInformation();
        if (node instanceof meta.pure.metamodel.function.property.AbstractProperty p) return p._sourceInformation();
        return null;
    }

    /** Does {@code si} contain the cursor position (sourceId, line, col, 1-based)? */
    private static boolean siContains(meta.pure.metamodel.SourceInformation si, String sourceId, int line, int col)
    {
        if (si == null) return false;
        String siSource = si._sourceId();
        // null sourceId on the SI means "inherits from the enclosing element" —
        // we treat any sub-expression as in the same file as its top-level
        // PackageableElement, so don't reject on null here.
        if (siSource != null && !siSource.equals(sourceId)) return false;
        int sl = si._startLine().intValue();
        int sc = si._startColumn().intValue();
        int el = si._endLine().intValue();
        int ec = si._endColumn().intValue();
        if (line < sl || line > el) return false;
        if (line == sl && col < sc) return false;
        if (line == el && col > ec) return false;
        return true;
    }

    /** Approximate area of a SourceInformation range — used to pick the
     *  smallest containing match. Lines weighted heavily so a 3-line
     *  range never beats a 1-line one regardless of column span. */
    private static long siSize(meta.pure.metamodel.SourceInformation si)
    {
        long lines = si._endLine().longValue() - si._startLine().longValue();
        long cols = si._endColumn().longValue() - si._startColumn().longValue();
        return lines * 10000L + Math.max(cols, 0);
    }

    /** Resolve the target of an AST node — what it references — to a
     *  {@link TargetLocation} (file + range). */
    private TargetLocation resolveTarget(Object node)
    {
        if (node == null) return null;
        // FunctionExpression: jump to its resolved function (or property for
        // DotApplication, since the compiler stores the resolved Property
        // in the func slot too).
        if (node instanceof meta.pure.metamodel.valuespecification.FunctionExpression fe)
        {
            meta.pure.metamodel.function.Function func = fe._func();
            if (func == null) return null;
            Object live = derefPointer(func);
            return targetFor(live);
        }
        // GenericTypeValue: jump to the type the cursor is on.
        if (node instanceof meta.pure.metamodel.type.generics.GenericTypeValue gtv)
        {
            meta.pure.metamodel.type.Type t = gtv._type();
            if (t == null) return null;
            return targetFor(derefPointer(t));
        }
        // VariableExpression: walk enclosing function/lambda to find a
        // parameter or `let` binding with the same name.
        if (node instanceof meta.pure.metamodel.valuespecification.VariableExpression ve)
        {
            meta.pure.metamodel.SourceInformation si = resolveVariableSourceInfo(ve);
            return si == null ? null : siToTarget(si, null);
        }
        // PackageableElement itself (top-level reference like `extends Foo`).
        if (node instanceof meta.pure.metamodel.PackageableElement pe)
        {
            return targetFor(pe);
        }
        return null;
    }

    /** Build a TargetLocation from a live element's SourceInformation,
     *  falling back to {@link LocalModule#getSourceIdForElement} when the
     *  SI's {@code _sourceId()} is null (the compiler omits it on most
     *  per-element SIs — only the enclosing file SI carries it). */
    private TargetLocation targetFor(Object live)
    {
        if (!(live instanceof meta.pure.metamodel.PackageableElement pe)) return null;
        meta.pure.metamodel.SourceInformation si = pe._sourceInformation();
        if (si == null) return null;
        return siToTarget(si, qualifiedPath(pe));
    }

    /** SourceInformation + optional element path → TargetLocation. If
     *  {@code si._sourceId()} is null, look it up via
     *  {@code module.getSourceIdForElement(elementPath)}. */
    private TargetLocation siToTarget(meta.pure.metamodel.SourceInformation si, String elementPath)
    {
        String sourceId = si._sourceId();
        if (sourceId == null && elementPath != null)
        {
            for (LocalModule mod : editableModules)
            {
                String sid = mod.getSourceIdForElement(elementPath);
                if (sid != null)
                {
                    sourceId = sid;
                    break;
                }
            }
        }
        if (sourceId == null) return null;
        return new TargetLocation(
                sourceId,
                si._startLine().intValue(),
                si._startColumn().intValue(),
                si._endLine().intValue(),
                si._endColumn().intValue());
    }

    /** Compose {@code pkg::pkg::Name} from a PackageableElement using the
     *  shared compiler helper (handles empty/root package edge cases that
     *  a naive `while (pkg._name() != null)` loop gets wrong — was producing
     *  `::::meta::…` paths previously). */
    private static String qualifiedPath(meta.pure.metamodel.PackageableElement pe)
    {
        return org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
    }

    /** Dereference a pointer-like value to its live target. Handles two kinds:
     *  <ul>
     *    <li>{@code FunctionIndexEntry} — Java-direct compiler's lazy proxy
     *        for PackageableFunction; carries {@code fullPath()}.</li>
     *    <li>{@code TempCompilerPointer} — compile-pure's pointer with
     *        {@code _path()}.</li>
     *  </ul>
     *  Returns input unchanged for non-pointer values or when lookup fails. */
    private Object derefPointer(Object obj)
    {
        if (obj == null || lastModel == null) return obj;
        // Java-direct FunctionIndexEntry (UserDefinedFunctionIndexEntry,
        // NativeFunctionIndexEntry, ...): its own SourceInformation has null
        // sourceId because the entry is a synthetic proxy. Resolve via fullPath()
        // to the live PackageableFunction.
        if (obj instanceof org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry fie)
        {
            String path = fie.fullPath();
            if (path != null && !path.isEmpty())
            {
                for (Module mod : lastModel.modules())
                {
                    meta.pure.metamodel.PackageableElement el = mod.getElement(path);
                    if (el != null && el != obj) return el;
                }
            }
        }
        // compile-pure TempCompilerPointer (Class_Pointer / PropertyPointer / etc.)
        try
        {
            Class<?> tcpClass = Class.forName("meta.pure.metamodel.pointer.TempCompilerPointer");
            if (tcpClass.isInstance(obj))
            {
                java.lang.reflect.Method pathM = obj.getClass().getMethod("_path");
                Object pathObj = pathM.invoke(obj);
                if (pathObj instanceof String path && !path.isEmpty())
                {
                    for (Module mod : lastModel.modules())
                    {
                        meta.pure.metamodel.PackageableElement el = mod.getElement(path);
                        if (el != null) return el;
                    }
                }
            }
        }
        catch (ReflectiveOperationException | RuntimeException ignored)
        {
            // Fallthrough — return obj as-is
        }
        return obj;
    }


    /** Walk top-level elements in lastModel looking for the enclosing function
     *  containing {@code ve}; return the SI of a matching parameter or let
     *  binding. */
    private meta.pure.metamodel.SourceInformation resolveVariableSourceInfo(meta.pure.metamodel.valuespecification.VariableExpression ve)
    {
        if (lastModel == null || ve._name() == null) return null;
        String varName = ve._name();
        meta.pure.metamodel.SourceInformation veSI = ve._sourceInformation();
        if (veSI == null) return null;
        // Find the enclosing FunctionDefinition by source containment.
        for (Module mod : lastModel.modules())
        {
            for (String elementPath : mod.elementPaths())
            {
                Object el = mod.getElement(elementPath);
                if (!(el instanceof meta.pure.metamodel.function.FunctionDefinition fd)) continue;
                meta.pure.metamodel.SourceInformation fdSI =
                        ((meta.pure.metamodel.PackageableElement) fd)._sourceInformation();
                if (!siEncloses(fdSI, veSI)) continue;
                meta.pure.metamodel.SourceInformation found = findVarInScope(fd, varName, veSI);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Returns SI of the parameter / let-binder for {@code varName} within
     *  {@code fd}'s body, or null if not found. Prefers the nearest enclosing
     *  binding to the reference site. */
    private static meta.pure.metamodel.SourceInformation findVarInScope(
            meta.pure.metamodel.function.FunctionDefinition fd,
            String varName,
            meta.pure.metamodel.SourceInformation refSI)
    {
        for (meta.pure.metamodel.valuespecification.VariableExpression p : fd._parameters())
        {
            if (varName.equals(p._name())) return p._sourceInformation();
        }
        // `let x = ...` compiles to a letFunction(AtomicValue("x"), ...) call.
        // Find one whose first argument's value is the name AND that is
        // lexically before the reference site.
        meta.pure.metamodel.SourceInformation best = null;
        long bestLine = -1;
        for (meta.pure.metamodel.valuespecification.ValueSpecification stmt : fd._expressionSequence())
        {
            if (!(stmt instanceof meta.pure.metamodel.valuespecification.FunctionExpression fe)) continue;
            String fnName = fe._functionName();
            if (fnName == null || !fnName.startsWith("letFunction")) continue;
            if (fe._parametersValues() == null || fe._parametersValues().isEmpty()) continue;
            meta.pure.metamodel.valuespecification.ValueSpecification nameVS = fe._parametersValues().get(0);
            if (!(nameVS instanceof meta.pure.metamodel.valuespecification.AtomicValue av)) continue;
            if (!(av._value() instanceof String name) || !varName.equals(name)) continue;
            meta.pure.metamodel.SourceInformation letSI = fe._sourceInformation();
            if (letSI == null) continue;
            // Lexical scoping: prefer the latest let before refSI.
            long letLine = letSI._startLine().longValue();
            long refLine = refSI._startLine().longValue();
            if (letLine > refLine) continue;
            if (letLine > bestLine)
            {
                bestLine = letLine;
                best = letSI;
            }
        }
        return best;
    }

    /** Does {@code outer} fully enclose {@code inner}? */
    private static boolean siEncloses(meta.pure.metamodel.SourceInformation outer,
                                      meta.pure.metamodel.SourceInformation inner)
    {
        if (outer == null || inner == null) return false;
        if (outer._sourceId() != null && inner._sourceId() != null
                && !outer._sourceId().equals(inner._sourceId())) return false;
        if (inner._startLine().longValue() < outer._startLine().longValue()) return false;
        if (inner._endLine().longValue() > outer._endLine().longValue()) return false;
        if (inner._startLine().longValue() == outer._startLine().longValue()
                && inner._startColumn().longValue() < outer._startColumn().longValue()) return false;
        if (inner._endLine().longValue() == outer._endLine().longValue()
                && inner._endColumn().longValue() > outer._endColumn().longValue()) return false;
        return true;
    }

    // =========================================================================
    // WorkspaceService
    // =========================================================================

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params)
    {
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params)
    {
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params)
    {
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
                    String elementPath = String.valueOf(params.getArguments().getFirst());
                    if (elementPath.startsWith("\"") && elementPath.endsWith("\""))
                    {
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
                    String sourceId = String.valueOf(params.getArguments().getFirst());
                    if (sourceId.startsWith("\"") && sourceId.endsWith("\""))
                    {
                        sourceId = sourceId.substring(1, sourceId.length() - 1);
                    }
                    handleOpenFile(sourceId);
                }
                return null;
            });
            case "pure/getPCTAdapters" -> CompletableFuture.supplyAsync(this::handleGetPCTAdapters);
            case "pure/discoverTests" -> CompletableFuture.supplyAsync(() ->
            {
                if (params.getArguments() != null && !params.getArguments().isEmpty())
                {
                    String mode = getArgString(params.getArguments().get(0));
                    return handleDiscoverTests(mode);
                }
                return List.of();
            });
            case "pure/runTests" -> CompletableFuture.supplyAsync(() ->
            {
                // Outer guard: any uncaught error here would propagate up to
                // CompletableFuture and reach the client as an LSP error
                // response (no test results at all). Catch + return a single
                // synthesized failure so the IDE can at least render a
                // toplevel "test runner crashed" message instead of leaving
                // the test tree blank.
                try
                {
                    if (params.getArguments() != null && params.getArguments().size() >= 3)
                    {
                        String mode = getArgString(params.getArguments().get(0));
                        String adapter = getArgString(params.getArguments().get(1));
                        Object testsObj = params.getArguments().get(2);
                        List<String> tests = new ArrayList<>();
                        if (testsObj instanceof com.google.gson.JsonArray arr) {
                            for (com.google.gson.JsonElement e : arr) {
                                tests.add(e.getAsString());
                            }
                        } else if (testsObj instanceof List<?> ls) {
                            for (Object o : ls) {
                                tests.add(String.valueOf(o));
                            }
                        }
                        return handleRunTests(mode, adapter, tests);
                    }
                    return List.of();
                }
                catch (Throwable t)
                {
                    System.err.println("[LSP/runTests] hard crash in test runner: " + t);
                    t.printStackTrace(System.err);
                    Map<String, String> crash = new LinkedHashMap<>();
                    crash.put("test", "<runner>");
                    crash.put("status", "failed");
                    crash.put("error", "Test runner crashed: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getName()));
                    return List.of(crash);
                }
            });
            case "pure/cancelTests" -> CompletableFuture.supplyAsync(() ->
            {
                System.err.println("[LSP/cancelTests] received");
                testsCancelled.set(true);
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
            case "pure/search" -> CompletableFuture.supplyAsync(() ->
            {
                if (params.getArguments() != null && !params.getArguments().isEmpty())
                {
                    String query = getArgString(params.getArguments().get(0));
                    boolean caseSensitive = params.getArguments().size() >= 2
                            && Boolean.parseBoolean(String.valueOf(params.getArguments().get(1)));
                    return handleSearch(query, caseSensitive);
                }
                return List.of();
            });
            default -> CompletableFuture.completedFuture(null);
        };
    }
    
    private String getArgString(Object obj)
    {
        if (obj == null) return null;
        if (obj instanceof com.google.gson.JsonPrimitive jp && jp.isString()) return jp.getAsString();
        String str = String.valueOf(obj);
        if (str.startsWith("\"") && str.endsWith("\"")) return str.substring(1, str.length() - 1);
        return str;
    }

    private void handleSaveFile(String sourceId, String content, boolean skipCompile)
    {
        if (content != null)
        {
            boolean saved = saveToModule(sourceId, content);
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

    private CompilationResult compileCurrentSource() throws Exception
    {
        System.out.println("[LSP] Compiling source (" + currentSource.length() + " chars)");
        // Persist the live editor content to the welcome module's filesystem
        saveToModule("welcome.pure", currentSource);

        // editableModules + every PDB (core, compiler, …) — the PDBs are
        // read-only so model.compile() doesn't re-parse them per keystroke.
        MutableList<Module> modules = Lists.mutable.<Module>withAll(editableModules).withAll(pdbModules);

        PureModel model = PureModel.withModules(modules)
                .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                .build();

        CompilationResult result = model.compile();
        if (result.errors().isEmpty())
        {
            this.lastModel = model;
        }
        return result;
    }

    private void compileAndPublishDiagnostics()
    {
        if (client == null) { return; }

        CompilationResult result = null;
        Exception compileException = null;
        try
        {
            result = compileCurrentSource();
        }
        catch (Exception e)
        {
            compileException = e;
        }

        List<Diagnostic> diagnostics = new ArrayList<>();

        if (compileException != null)
        {
            String msg = compileException.getMessage();
            int line = 0;
            int col = 0;
            if (msg != null && msg.contains("at line "))
            {
                try
                {
                    String[] parts = msg.split("at line ");
                    String[] locParts = parts[1].split(" - ")[0].split(":");
                    line = Math.max(0, Integer.parseInt(locParts[0]) - 1);
                    col = Math.max(0, Integer.parseInt(locParts[1])); // Monaco expects 1-based but in LSP it's 0-based
                }
                catch (Exception ignored) {}

                diagnostics.add(new Diagnostic(
                        new Range(new Position(line, col), new Position(line, col + 1)),
                        msg,
                        DiagnosticSeverity.Error, "pure"));
            }
            else
            {
                diagnostics.add(new Diagnostic(
                        new Range(new Position(0, 0), new Position(0, 1)),
                        msg != null ? msg : "Internal compilation error",
                        DiagnosticSeverity.Error, "pure"));
            }
        }
        else if (result != null && !result.errors().isEmpty())
        {
            for (CompilationError error : result.errors())
            {
                int line = 0, col = 0, endLine, endCol;
                meta.pure.metamodel.SourceInformation si = error.sourceInformation();
                if (si != null)
                {
                    line = si._startLine() != null ? Math.max(0, si._startLine().intValue() - 1) : 0;
                    col = si._startColumn() != null ? Math.max(0, si._startColumn().intValue() - 1) : 0;
                    endLine = si._endLine() != null ? Math.max(0, si._endLine().intValue() - 1) : line;
                    endCol = si._endColumn() != null ? si._endColumn().intValue() : col + 1;
                }
                else
                {
                    endLine = line;
                    endCol = col + 1;
                }
                Diagnostic diag = new Diagnostic(
                        new Range(new Position(line, col), new Position(endLine, endCol)),
                        error.message(),
                        DiagnosticSeverity.Error, "pure");
                diagnostics.add(diag);
            }
        }

        client.publishDiagnostics(new PublishDiagnosticsParams(currentUri, diagnostics));

        // Refresh the package tree so newly compiled (or removed) editable
        // elements show up without the client having to re-request it. Even
        // when compile fails, LocalModule.state holds whatever parsed/compiled
        // before the error — so the tree reflects current source.
        sendPackageTree();
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
            // Persist the live editor content to the welcome module's filesystem
            saveToModule("welcome.pure", currentSource);

            MutableList<Module> modules = Lists.mutable.<Module>withAll(editableModules).withAll(pdbModules);

            PureModel model = PureModel.withModules(modules)
                    .withExtensions(Lists.mutable.with(new PureLanguageExtension()))
                    .build();

            CompilationResult result = model.compile();

            if (!result.errors().isEmpty())
            {
                StringBuilder sb = new StringBuilder();
                sb.append("Compilation errors:\n");
                for (CompilationError error : result.errors())
                {
                    sb.append("  ").append(error.message()).append("\n");
                }
                sendExecuteResult(sb.toString(), true);
                return;
            }

            // Make this successful model available to other LSP features
            // (textDocument/definition). Without this, Ctrl+B is broken after
            // an F9 run that succeeded but before any subsequent didChange.
            this.lastModel = model;

            // Find and execute go():Any[*]
            Module welcomeMod = model.getModule("welcome");
            FunctionDefinition goFunc = null;
            for (String candidate : List.of("go__Any_MANY_", "go__String_1_", "go__String_MANY_",
                    "go__Integer_1_", "go__Boolean_1_"))
            {
                try
                {
                    Object element = welcomeMod.getElement(candidate);
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

            // Route execution through the selected backend (java-direct or
            // truffle). The backend captures stdout so Pure print()/println()
            // output flows back to the IDE terminal.
            PureBackend.ExecutionResult execResult = backend.execute(editableModules, model, result, goFunc);
            if (execResult.ok())
            {
                sendExecuteResult(execResult.capturedStdout(), false, execResult.compileStats());
            }
            else
            {
                Throwable err = execResult.error();
                // Include any captured stdout (println debug output the user
                // wrote before the throw) so it doesn't get lost on the error
                // path — otherwise debugging via println is impossible when the
                // last call throws.
                String stdout = execResult.capturedStdout();
                String body = (stdout != null && !stdout.isEmpty() ? stdout + "\n" : "")
                        + "Execution error: " + err.getMessage();
                sendExecuteResult(body, true, execResult.compileStats());
            }
        }
        catch (Exception e)
        {
            sendExecuteResult("Execution error: " + e.getMessage(), true, null);
        }
    }

    // =========================================================================
    // Test Runner
    // =========================================================================

    private boolean isTestStereotype(meta.pure.metamodel.PackageableElement element, String expectedProfile, String expectedValue)
    {
        if (element instanceof meta.pure.metamodel.extension.ElementWithStereotypes ews)
        {
            for (meta.pure.metamodel.extension.Stereotype s : ews._stereotypes())
            {
                if (s != null && expectedValue.equals(s._value()) && s._profile() != null && expectedProfile.equals(s._profile()._name()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> handleGetPCTAdapters()
    {
        List<String> adapters = new ArrayList<>();
        if (lastModel == null)
        {
            try { compileCurrentSource(); } catch (Exception ignored) {}
        }
        if (lastModel != null)
        {
            for (Module m : lastModel.modules())
            {
                for (String path : m.elementPaths())
                {
                    try
                    {
                        meta.pure.metamodel.PackageableElement element = m.getElement(path);
                        if (element instanceof FunctionDefinition && isTestStereotype(element, "PCT", "adapter"))
                        {
                            adapters.add(path);
                        }
                    }
                    catch (Exception ignored) {}
                }
            }
        }
        return adapters;
    }

    private List<String> handleDiscoverTests(String mode)
    {
        List<String> tests = new ArrayList<>();
        if (lastModel == null)
        {
            try { compileCurrentSource(); } catch (Exception ignored) {}
        }
        if (lastModel != null)
        {
            String profile = "pct".equals(mode) ? "PCT" : "test";
            String value = "test"; // wait, is simple test 'test' value? Usually it's <<test.Test>>. Let's accept 'test' or 'Test'.
            for (Module m : lastModel.modules())
            {
                for (String path : m.elementPaths())
                {
                    try
                    {
                        meta.pure.metamodel.PackageableElement element = m.getElement(path);
                        if (element instanceof FunctionDefinition && (isTestStereotype(element, profile, "test") || isTestStereotype(element, profile, "Test") || ("simple".equals(mode) && isTestStereotype(element, "test", "Test"))))
                        {
                            tests.add(path);
                        }
                    }
                    catch (Exception ignored) {}
                }
            }
        }
        return tests;
    }

    private Object handleRunTests(String mode, String adapterPath, List<String> testPaths)
    {
        System.err.println("[LSP/runTests] mode=" + mode + " adapter=" + adapterPath + " tests=" + testPaths.size());
        // Reset cancel flag at the start of every new batch — a previous
        // batch's cancel must not bleed into the next.
        testsCancelled.set(false);
        List<Map<String, String>> results = new ArrayList<>();
        if (lastModel == null)
        {
            try { compileCurrentSource(); }
            catch (Exception e)
            {
                System.err.println("[LSP/runTests] compileCurrentSource threw: " + e);
            }
        }
        if (lastModel == null)
        {
            // Compile failed (either threw, or produced errors so lastModel
            // stayed null). Synthesize a failed entry for every requested
            // test so the IDE's test tree shows red instead of spinning.
            for (String testPath : testPaths)
            {
                Map<String, String> result = new LinkedHashMap<>();
                result.put("test", testPath);
                result.put("status", "failed");
                result.put("error", "Cannot run: source did not compile cleanly (lastModel is null). Fix compile errors and re-run.");
                streamTestResult(result);
                results.add(result);
            }
            return results;
        }

        ValueSpecification adapterArg = null;
        if ("pct".equals(mode) && adapterPath != null && !adapterPath.isEmpty())
        {
            meta.pure.metamodel.PackageableElement adapterElem = null;
            for (Module m : lastModel.modules()) {
                if ((adapterElem = m.getElement(adapterPath)) != null) break;
            }
            if (adapterElem instanceof FunctionDefinition fd) {
                adapterArg = org.finos.legend.pure.execution._E_ValueSpecification.wrap(
                        fd, org.finos.legend.pure.execution.PureTypeResolver.getClassifierGenericType(fd, coreModule), null, coreModule);
            }
        }

        for (String testPath : testPaths)
        {
            if (testsCancelled.get())
            {
                System.err.println("[LSP/runTests] cancelled; remaining " + (testPaths.size() - results.size()) + " tests");
                for (int i = results.size(); i < testPaths.size(); i++)
                {
                    String remaining = testPaths.get(i);
                    Map<String, String> r = new LinkedHashMap<>();
                    r.put("test", remaining);
                    r.put("status", "cancelled");
                    r.put("error", "Cancelled by user");
                    streamTestResult(r);
                    results.add(r);
                }
                break;
            }
            // Notify the IDE that this test is about to start so its row can
            // light up as "running" — the user sees which test the runner
            // is currently on rather than every spinner looking identical.
            {
                Map<String, String> running = new LinkedHashMap<>();
                running.put("test", testPath);
                running.put("status", "running");
                streamTestResult(running);
            }
            Map<String, String> result = new LinkedHashMap<>();
            result.put("test", testPath);

            meta.pure.metamodel.PackageableElement testElem = null;
            for (Module m : lastModel.modules()) {
                if ((testElem = m.getElement(testPath)) != null) break;
            }

            if (testElem instanceof FunctionDefinition fd)
            {
                // Tests reuse the previously-compiled model, so no fresh
                // CompilationResult to pass through — test output focuses on
                // pass/fail, not compile stats.
                // Hard-crash safety: a test that throws inside the Truffle
                // runtime (e.g. unmatched `match` arms, NPE in a native, …)
                // must NOT abort the loop — otherwise the IDE's test tree
                // gets zero feedback for every subsequent test. Catch every
                // Throwable here and record a failed entry per test.
                try
                {
                    PureBackend.ExecutionResult execResult = "pct".equals(mode) && adapterArg != null
                            ? backend.execute(editableModules, lastModel, null, fd, adapterArg)
                            : backend.execute(editableModules, lastModel, null, fd);
                    if (execResult.ok())
                    {
                        result.put("status", "passed");
                    }
                    else
                    {
                        Throwable err = execResult.error();
                        result.put("status", "failed");
                        result.put("error", formatTestError(err));
                    }
                    result.put("output", execResult.capturedStdout());
                }
                catch (Throwable t)
                {
                    result.put("status", "failed");
                    result.put("error", formatTestError(t));
                    // Per-test crash log so the user can correlate stderr to a
                    // specific test row without scrolling through stacks.
                    System.err.println("[LSP/runTests] test crashed: " + testPath + " — " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            else
            {
                result.put("status", "failed");
                result.put("error", "Test function not found.");
            }
            // Stream this test's result so the IDE can update its row icon
            // immediately — don't wait for the whole batch to complete.
            streamTestResult(result);
            results.add(result);
        }
        System.err.println("[LSP/runTests] returning " + results.size() + " results");
        return results;
    }

    /** Send a {@code pure/testResult} notification to the IDE for live
     *  per-test feedback. Safe to call from any thread; the LSP4J launcher
     *  serializes outbound messages. */
    private void streamTestResult(Map<String, String> result)
    {
        if (!(client instanceof PureLanguageClient pureClient)) return;
        try
        {
            pureClient.testResult(new TestResultParams(
                    result.get("test"),
                    result.get("status"),
                    result.get("error"),
                    result.get("output")));
        }
        catch (Throwable t)
        {
            System.err.println("[LSP/runTests] streamTestResult send failed: " + t);
        }
    }

    /** Render a test failure error consistently. PureAssertionError is the
     *  expected "test failed an assertion" case; everything else is treated
     *  as a hard error and the stack-tail is captured so the IDE can show
     *  WHY the test crashed without the user needing the LSP server logs. */
    private static String formatTestError(Throwable err)
    {
        if (err == null) return "Error: <unknown>";
        if (err instanceof org.finos.legend.pure.execution.PureAssertionError)
        {
            return "Assertion Failed: " + err.getMessage();
        }
        String msg = err.getMessage() != null ? err.getMessage() : err.getClass().getName();
        // Include the Pure stack frames if present in the message — those are
        // far more useful to the user than the Java stack.
        return "Error: " + msg;
    }



    private List<Map<String, Object>> handleSearch(String query, boolean caseSensitive)
    {
        List<Map<String, Object>> results = new ArrayList<>();
        if (query == null || query.isEmpty())
        {
            return results;
        }
        String needle = caseSensitive ? query : query.toLowerCase(java.util.Locale.ROOT);
        for (LocalModule module : editableModules)
        {
            for (String sourceId : module.sourceFiles())
            {
                String content = module.getSourceText(sourceId);
                if (content == null) { continue; }
                String[] lines = content.split("\n", -1);
                for (int i = 0; i < lines.length; i++)
                {
                    String line = lines[i];
                    String haystack = caseSensitive ? line : line.toLowerCase(java.util.Locale.ROOT);
                    int col = haystack.indexOf(needle);
                    if (col >= 0)
                    {
                        Map<String, Object> match = new LinkedHashMap<>();
                        match.put("sourceId", sourceId);
                        match.put("line", i + 1);
                        match.put("col", col + 1);
                        match.put("text", line);
                        results.add(match);
                    }
                }
            }
        }
        return results;
    }

    private void sendExecuteResult(String result, boolean isError)
    {
        sendExecuteResult(result, isError, null);
    }

    private void sendExecuteResult(String result, boolean isError, PureBackend.CompileStats compileStats)
    {
        if (client instanceof PureLanguageClient pureClient)
        {
            pureClient.executeResult(new ExecuteResultParams(result, isError, compileStats));
        }
    }

    // =========================================================================
    // Tree Data
    // =========================================================================

    private void sendPackageTree()
    {
        if (client == null) { return; }

        // Each entry: path -> [type, displayName, hasSource]
        Map<String, String[]> elementEntries = new LinkedHashMap<>();

        // Build function signatures from PDB metadata
        Map<String, String> functionSignatures = buildFunctionDisplayNames();

        // Add elements from editable LocalModules directly. We don't gate this
        // on lastModel because LocalModule populates its own state during
        // compile() — so as soon as the user's source has been parsed once
        // (even if a later phase errored), the elements show up in the tree.
        // The PDB loop below will skip any path the LocalModule already owns,
        // so editable elements win precedence over PDB shadows.
        for (LocalModule module : editableModules)
        {
            for (String path : module.elementPaths())
            {
                if (elementEntries.containsKey(path)) { continue; }
                meta.pure.metamodel.PackageableElement element = module.getElement(path);
                String type = "UserDefined";
                String displayName = null;
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
                    if (element instanceof FunctionIndexEntry fie)
                    {
                        displayName = fie.signatureWithoutPackage();
                    }
                    else if (element instanceof meta.pure.metamodel.function.PackageableFunction fn)
                    {
                        displayName = buildFunctionSignature(fn);
                    }
                }
                elementEntries.put(path, new String[]{type, displayName, "true"});
            }
        }

        // Read elementIndex from EVERY PDB (core, compiler, …) so the
        // package tree surfaces all loaded PDB elements, not just core's.
        for (PDBModule pdb : pdbModules)
        {
            byte[] indexBytes = pdb.archive().readSection("elementIndex");
            if (indexBytes == null)
            {
                throw new IllegalStateException(
                        "PDB '" + pdb.getName() + "' has no elementIndex section — "
                                + "this is required for the package tree. Rebuild the PDB.");
            }
            ElementIndex index = ElementIndex.getRootAsElementIndex(ByteBuffer.wrap(indexBytes));
            for (int i = 0; i < index.elementsLength(); i++)
            {
                ElementIndexEntry entry = index.elements(i);
                String path = entry.elementPath();
                if (elementEntries.containsKey(path)) { continue; }
                String type = entry.elementType();
                String displayName = functionSignatures.get(path);
                elementEntries.put(path, new String[]{type, displayName, "false"});
            }
        }

        String json = buildPackageTreeJson(elementEntries);
        sendTreeData("packageTree", json);
    }

    /**
     * Build a map of function path → display signature using the PureLanguage metadata.
     */
    private Map<String, String> buildFunctionDisplayNames()
    {
        Map<String, String> result = new LinkedHashMap<>();
        try
        {
            org.finos.legend.pure.m3.module.MetadataAccessExtension ext =
                    new PureLanguageExtension().buildMetadataExtensionForModule(coreModule);
            if (ext instanceof org.finos.legend.pure.m3.pureLanguage.metadata.PureLanguageMetadataAccess metadata)
            {
                for (FunctionIndexEntry entry : metadata.getAllFunctionHeaders())
                {
                    result.put(entry.fullPath(), entry.signatureWithoutPackage());
                }
            }
        }
        catch (Exception e)
        {
            // Fall through — no display names
        }
        return result;
    }

    private void sendTreeData(String treeId, String json)
    {
        if (json != null && client instanceof PureLanguageClient pureClient)
        {
            pureClient.treeData(new TreeDataParams(treeId, json));
        }
    }

    /**
     * Build a JSON tree from :: separated element paths.
     * Each node: {"name":"x","type":"Package","children":[...]}
     */
    private String buildPackageTreeJson(Map<String, String[]> elementEntries)
    {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("__children", new LinkedHashMap<String, Object>());

        for (Map.Entry<String, String[]> entry : elementEntries.entrySet())
        {
            String path = entry.getKey();
            String type = entry.getValue()[0];
            String displayName = entry.getValue()[1];
            String hasSource = entry.getValue().length > 2 ? entry.getValue()[2] : "true";
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
                    if (displayName != null)
                    {
                        nodeMap.put("__displayName", displayName);
                    }
                    nodeMap.put("__hasSource", hasSource);
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

    @SuppressWarnings("unchecked")
    private String renderTreeJson(Map<String, Object> nodes)
    {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, Object> entry : nodes.entrySet())
        {
            if (!first) { sb.append(","); }
            first = false;

            Map<String, Object> node = (Map<String, Object>) entry.getValue();
            Map<String, Object> children = (Map<String, Object>) node.get("__children");
            String type = node.containsKey("__type") ? (String) node.get("__type") : "Package";
            String displayName = node.containsKey("__displayName") ? (String) node.get("__displayName") : null;
            String hasSource = node.containsKey("__hasSource") ? (String) node.get("__hasSource") : null;

            sb.append("{\"name\":\"").append(escapeJson(entry.getKey())).append("\"");
            sb.append(",\"type\":\"").append(type).append("\"");
            if (displayName != null)
            {
                sb.append(",\"displayName\":\"").append(escapeJson(displayName)).append("\"");
            }
            if ("false".equals(hasSource))
            {
                sb.append(",\"hasSource\":false");
            }
            if (children != null && !children.isEmpty())
            {
                sb.append(",\"children\":").append(renderTreeJson(children));
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    // =========================================================================
    // File Tree
    // =========================================================================

    private void sendFileTree()
    {
        if (client == null) { return; }

        String json = buildFileTreeJson();
        if (json != null && client instanceof PureLanguageClient pureClient)
        {
            pureClient.treeData(new TreeDataParams("fileTree", json));
        }
    }

    private String buildFileTreeJson()
    {
        // Each editable LocalModule is a top-level folder named by the
        // module; its source files sit under it (preserving any directory
        // structure inside the module's source folder). welcome.pure lands at
        // the top of the "welcome" folder rather than nested under a
        // redundant package path.
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("__children", new LinkedHashMap<>());

        for (LocalModule module : editableModules)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> rootChildren = (Map<String, Object>) root.get("__children");
            Map<String, Object> moduleNode = new LinkedHashMap<>();
            moduleNode.put("__type", "Module");
            moduleNode.put("__children", new LinkedHashMap<>());
            rootChildren.put(module.getName(), moduleNode);

            for (String sourceId : module.sourceFiles())
            {
                String[] parts = sourceId.split("/");
                @SuppressWarnings("unchecked")
                Map<String, Object> current = (Map<String, Object>) moduleNode.get("__children");

                for (int i = 0; i < parts.length; i++)
                {
                    if (!current.containsKey(parts[i]))
                    {
                        Map<String, Object> node = new LinkedHashMap<>();
                        node.put("__children", new LinkedHashMap<>());
                        current.put(parts[i], node);
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nodeMap = (Map<String, Object>) current.get(parts[i]);
                    if (i == parts.length - 1)
                    {
                        nodeMap.put("__type", "File");
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nextChildren = (Map<String, Object>) nodeMap.get("__children");
                    current = nextChildren;
                }
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> rootChildren = (Map<String, Object>) root.get("__children");
        return renderTreeJson(rootChildren);
    }

    private void handleJumpToElement(String elementPath)
    {
        System.out.println("[LSP] Received pure/jumpToElement for: " + elementPath);
        if (client == null) { 
            System.out.println("[LSP] jumpToElement abort: client is null");
            return; 
        }
        
        // Search all editable modules for the element
        for (LocalModule module : editableModules)
        {
            String sourceId = module.getSourceIdForElement(elementPath);
            if (sourceId != null)
            {
                System.out.println("[LSP] Found in module " + module.getName() + " with sourceId " + sourceId);
                String content = module.getSourceText(sourceId);
                if (content != null)
                {
                    try 
                    {
                        meta.pure.metamodel.PackageableElement element = module.getElement(elementPath);
                        if (element != null && element._sourceInformation() != null)
                        {
                            Long lLine = element._sourceInformation()._startLine();
                            Long lCol = element._sourceInformation()._startColumn();
                            System.out.println("[LSP] Found element source info: line " + lLine);
                            sendOpenFile(sourceId, content, lLine != null ? lLine.intValue() : null, lCol != null ? lCol.intValue() : null);
                        }
                        else
                        {
                            System.out.println("[LSP] Element source info null, opening base file");
                            sendOpenFile(sourceId, content);
                        }
                    }
                    catch (Exception ex)
                    {
                        System.out.println("[LSP] Exception resolving " + elementPath + ": " + ex.getMessage());
                        sendOpenFile(sourceId, content);
                    }
                    return;
                }
            }
        }
        System.out.println("[LSP] jumpToElement failed: elementPath '" + elementPath + "' not found in any editable module index.");
        if (client != null)
        {
            client.showMessage(new MessageParams(MessageType.Error, "Cannot open source for '" + elementPath + "'. Associated source files are not present in your editable modules."));
        }
    }

    private void handleOpenFile(String sourceId)
    {
        if (client == null) { return; }

        // Search all editable modules for the file
        for (LocalModule module : editableModules)
        {
            String content = module.getSourceText(sourceId);
            if (content != null)
            {
                sendOpenFile(sourceId, content);
                return;
            }
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

    // =========================================================================
    // Module helpers
    // =========================================================================

    /**
     * Build a display signature for a locally-compiled PackageableFunction.
     */
    private String buildFunctionSignature(meta.pure.metamodel.function.PackageableFunction fn)
    {
        try
        {
            StringBuilder sb = new StringBuilder();
            sb.append(fn._functionName() != null ? fn._functionName() : "?");
            sb.append('(');
            if (fn._parameters() != null)
            {
                boolean first = true;
                for (meta.pure.metamodel.valuespecification.VariableExpression param : fn._parameters())
                {
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.print(param._genericType(), false));
                    sb.append(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity.print(param._multiplicity()));
                }
            }
            sb.append("): ");
            sb.append(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType.print(fn._returnGenericType(), false));
            sb.append(org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity.print(fn._returnMultiplicity()));
            return sb.toString();
        }
        catch (Exception e)
        {
            return fn._functionName();
        }
    }

    private static String escapeJson(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Find the editable module that owns the given source file.
     */
    private LocalModule findModuleForSource(String sourceId)
    {
        for (LocalModule module : editableModules)
        {
            if (module.getSourceText(sourceId) != null)
            {
                return module;
            }
        }
        return null;
    }

    /**
     * Save source content to the appropriate editable module's filesystem.
     */
    private boolean saveToModule(String sourceId, String content)
    {
        for (LocalModule module : editableModules)
        {
            if (module.saveSourceText(sourceId, content))
            {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // LSP extension interface
    // =========================================================================

    public interface PureLanguageClient extends LanguageClient
    {
        @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("pure/executeResult")
        void executeResult(ExecuteResultParams params);

        @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("pure/treeData")
        void treeData(TreeDataParams params);

        @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("pure/openFile")
        void openFile(OpenFileParams params);

        /** Streamed per-test result so the IDE can update icons live as each
         *  test finishes, instead of waiting for the whole batch. The IDE
         *  also receives the full list as the {@code pure/runTests} response
         *  for compatibility, but the streaming notification is the source
         *  of truth for incremental UI updates. */
        @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("pure/testResult")
        void testResult(TestResultParams params);
    }

    public record ExecuteResultParams(String result, boolean error, PureBackend.CompileStats compileStats) {}
    public record TreeDataParams(String treeId, String json) {}
    public record TestResultParams(String test, String status, String error, String output) {}

    public record OpenFileParams(String sourceId, String content, Integer line, Integer column)
    {
        public OpenFileParams(String sourceId, String content)
        {
            this(sourceId, content, null, null);
        }
    }
}
