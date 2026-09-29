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

package org.finos.legend.pure.platform.java.execution;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Pure call stack behind a host exception, read back off the Java stack.
 *
 * <p>Translated code keeps the Pure path in the class name (the inverse of
 * {@link Execute#className}), so the FUNCTION of each frame costs nothing to
 * recover. The POSITION comes from a table the translator emits beside each
 * generated class — {@code Foo.java.pos}, mapping a generated Java line to the
 * Pure position the code on it came from — because a {@link StackTraceElement}
 * carries a line and no column, and one Pure expression is emitted as a nested
 * Java expression spanning several lines. A frame whose class has no table (a
 * function translated at run time, which is compiled in memory and has nowhere
 * to put one) is rendered unpositioned.</p>
 *
 * <p>Used at the same boundary the other hosts use — the {@code assertError}
 * catch — never at the throw site.</p>
 */
public final class PureStack
{
    /** Where the translator roots generated element classes (see Execute.className). */
    private static final String GENERATED_PREFIX = "org.finos.legend.pure.m3.";

    /**
     * Position tables by class name, parsed on first use. A miss is cached as an
     * empty map: a class without a table is the normal case for anything compiled
     * at run time, and it must not re-read the classpath on every frame.
     */
    private static final Map<String, Map<Integer, String>> TABLES = new ConcurrentHashMap<>();

    private PureStack()
    {
    }

    /**
     * {@code t}'s message with its Pure stack trace appended, or the bare
     * message when no Pure frame is recognisable. Never null.
     */
    public static String describe(Throwable t)
    {
        String message = t == null || t.getMessage() == null ? "" : t.getMessage();
        List<String> frames = frames(t);
        if (frames.isEmpty())
        {
            return message;
        }
        StringBuilder text = new StringBuilder(message).append("\nPure stack trace:");
        for (String frame : frames)
        {
            text.append("\n    at ").append(frame);
        }
        return text.toString();
    }

    /**
     * The Pure functions on {@code t}'s stack, innermost first. Empty when none
     * are recognisable — a host-only failure, which is reported as-is.
     */
    public static List<String> frames(Throwable t)
    {
        List<String> frames = new ArrayList<>();
        if (t == null)
        {
            return frames;
        }
        // The frames that matter are where the error arose, not where it was
        // rewrapped — the same innermost-cause walk Truffle's formatter does.
        Throwable inner = t;
        while (inner.getCause() != null && inner.getCause() != inner)
        {
            inner = inner.getCause();
        }
        for (StackTraceElement element : inner.getStackTrace())
        {
            String frame = pureFrame(element);
            // A single Pure call can leave several host frames (a bridge method,
            // a lambda body and its enclosing function), so collapse repeats.
            if (frame != null && (frames.isEmpty() || !frames.get(frames.size() - 1).equals(frame)))
            {
                frames.add(frame);
            }
        }
        return frames;
    }

    /** The Pure function a host frame belongs to, or null when it is not one. */
    private static String pureFrame(StackTraceElement element)
    {
        String className = element.getClassName();
        if (!className.startsWith(GENERATED_PREFIX))
        {
            return null;
        }
        int lastDot = className.lastIndexOf('.');
        String simpleName = className.substring(lastDot + 1);
        int nested = simpleName.indexOf('$');
        String outer = nested < 0 ? simpleName : simpleName.substring(0, nested);
        // Only a FUNCTION class carries a mangled signature, whose parameter and
        // return parts are always separated by `__`. That is what tells one from
        // the generated classes and interfaces of Pure TYPES, whose property
        // accessors would otherwise bury the call stack.
        if (!outer.contains("__"))
        {
            return null;
        }
        String path = className.substring(GENERATED_PREFIX.length(), lastDot + 1).replace(".", "::") + functionName(outer);
        // A lambda frame is named just `lambda`, as the other three hosts name it:
        // the enclosing function is the frame below it, so repeating it here would
        // diverge from the text they all emit.
        String named = element.getMethodName().startsWith("lambda$") ? "lambda" : path;
        String position = positionOf(className.substring(0, lastDot + 1) + outer, element.getLineNumber());
        return position == null ? named : named + " (" + position + ")";
    }

    /**
     * The Pure position the given line of a generated class came from, as
     * {@code file.pure:61c7}, or null when it is not recorded.
     */
    private static String positionOf(String className, int javaLine)
    {
        String raw = TABLES.computeIfAbsent(className, PureStack::readTable).get(javaLine);
        if (raw == null)
        {
            return null;
        }
        // The table stores `sourceId:line:column`; a frame reads `sourceId:lineCcolumn`.
        // Split from the RIGHT, because a sourceId is a path and may hold a colon.
        int column = raw.lastIndexOf(':');
        return column <= 0 ? raw : raw.substring(0, column) + 'c' + raw.substring(column + 1);
    }

    /** One class's table, empty when it has none. */
    private static Map<Integer, String> readTable(String className)
    {
        Map<Integer, String> table = new HashMap<>();
        String resource = '/' + className.replace('.', '/') + ".java.pos";
        try (InputStream in = PureStack.class.getResourceAsStream(resource))
        {
            if (in == null)
            {
                return table;
            }
            BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = lines.readLine(); line != null; line = lines.readLine())
            {
                int tab = line.indexOf('\t');
                if (tab > 0)
                {
                    table.put(Integer.parseInt(line.substring(0, tab)), line.substring(tab + 1));
                }
            }
        }
        catch (Exception e)
        {
            // A stack trace is a diagnostic: an unreadable or malformed table
            // must never replace the error being reported with its own.
            return table;
        }
        return table;
    }

    /**
     * A mangled class name without its signature:
     * {@code runPCTTests_String_1__String_1__String_1_} is {@code runPCTTests}.
     * The signature begins at the first `_` that introduces a type — either the
     * `__` before a no-parameter function's return type, or `_` then the type's
     * capital. A Pure function whose own name contained `_` followed by a capital
     * would be cut short; none does today, and a wrong name is a cosmetic fault
     * in a diagnostic, not a behavioural one.
     */
    private static String functionName(String simpleName)
    {
        for (int i = 0; i + 1 < simpleName.length(); i++)
        {
            char next = simpleName.charAt(i + 1);
            if (simpleName.charAt(i) == '_' && (next == '_' || Character.isUpperCase(next)))
            {
                return simpleName.substring(0, i);
            }
        }
        return simpleName;
    }
}
