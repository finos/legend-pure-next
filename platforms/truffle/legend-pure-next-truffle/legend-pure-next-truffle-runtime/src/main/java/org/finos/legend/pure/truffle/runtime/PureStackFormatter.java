// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.runtime;

import com.oracle.truffle.api.TruffleStackTrace;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

import java.util.List;

/**
 * Renders the Pure-language call stack from a Truffle-side exception.
 *
 * <p>{@code PureException extends AbstractTruffleException} attaches the
 * throwing {@link Node} as the exception's location, and Truffle records
 * polyglot frames as the exception unwinds. Without invoking
 * {@link TruffleStackTrace#getStackTrace}, the JVM's default uncaught-exception
 * handler only prints Java frames — losing the {@code file:line:col} of the
 * Pure call chain that actually produced the error. Centralising the
 * formatting here means every Pure-throws-during-execute path (compile,
 * test runner, …) renders the stack identically.</p>
 */
public final class PureStackFormatter
{
    private PureStackFormatter()
    {
    }

    /**
     * Walk to the innermost cause, ask Truffle for its polyglot stack, and
     * format each frame as {@code "  at <fnName> (<sourceFile>:<line>:<col>)"}.
     * Returns an empty string when the throwable has no recorded Truffle
     * frames (e.g. a plain Java RuntimeException with no Pure context).
     */
    public static String format(Throwable e)
    {
        Throwable inner = e;
        while (inner.getCause() != null && inner.getCause() != inner)
        {
            inner = inner.getCause();
        }

        try
        {
            List<TruffleStackTraceElement> frames = TruffleStackTrace.getStackTrace(inner);
            if (frames == null || frames.isEmpty())
            {
                return "";
            }
            // Match the bootstrap (Java direct) runtime's stack format exactly
            // so the IDE renders both backends identically:
            //  - header `\nPure stack trace:`
            //  - each frame `\n    at <fn> (<sourceId>:<line>c<col>)`  (4-space indent)
            //  - innermost frame first (the throwing site)
            //
            // Bootstrap's ValueSpecificationEvaluator.getCallStackTrace iterates
            // its ArrayDeque<FunctionExpression> in stack-order (top-first =
            // innermost-first). Truffle's TruffleStackTrace.getStackTrace also
            // returns frames innermost-first, so we iterate in natural order.
            StringBuilder sb = new StringBuilder("\nPure stack trace:");
            for (TruffleStackTraceElement frame : frames)
            {
                RootNode rootNode = frame.getTarget().getRootNode();
                String name = rootNode != null ? rootNode.getName() : "?";
                // Normalize anonymous-lambda names. Truffle's RootNode for a
                // lambda is auto-named `lambda@<sourceId>:<line>:<col>` —
                // bootstrap renders the same frame as just `lambda`. Strip the
                // synthetic suffix so the two engines emit identical names;
                // the per-frame location (rendered separately below) still
                // pins where the lambda lives.
                //
                // (PureContext.getFunctionName now emits the unmangled
                // `package::functionName` form directly, so the older
                // first-`__` strip is no longer needed.)
                if (name != null && name.startsWith("lambda@"))
                {
                    name = "lambda";
                }

                // Prefer the location node's source section (the actual call
                // site or expression), walking up the AST until we hit one;
                // fall back to the root node's section if none is found.
                SourceSection sourceSection = null;
                Node location = frame.getLocation();
                if (location != null)
                {
                    sourceSection = location.getSourceSection();
                    Node node = location;
                    while (sourceSection == null && node != null)
                    {
                        sourceSection = node.getSourceSection();
                        node = node.getParent();
                    }
                }
                if (sourceSection == null && rootNode != null)
                {
                    sourceSection = rootNode.getSourceSection();
                }

                // 4-space indent matches bootstrap's `\n    at` exactly.
                sb.append("\n    at ").append(name != null ? name : "?");
                if (sourceSection != null && sourceSection.getSource() != null)
                {
                    // Column separator `c` matches both bootstrap's format and
                    // the IDE's stack-link regex (`at <fn> (<sourceId>:<line>c<col>)`).
                    sb.append(" (").append(sourceSection.getSource().getName())
                            .append(":").append(sourceSection.getStartLine())
                            .append("c").append(sourceSection.getStartColumn()).append(")");
                }
            }
            return sb.toString();
        }
        catch (Exception ignored)
        {
            return "";
        }
    }
}
