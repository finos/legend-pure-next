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

package org.finos.legend.pure.truffle.extension.typescriptcompile;

import org.finos.legend.pure.truffle.types.PureSequence;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Compile-and-invoke implementation for the TypeScript runtime bridge.
 *
 * <p>Holds a singleton GraalJS {@link Context} with the official
 * {@code typescript.js} compiler pre-loaded. Each call:</p>
 * <ol>
 *   <li>Calls {@code ts.transpileModule(<src>, {...})} to get JavaScript.</li>
 *   <li>Wraps the JS in an IIFE that exposes a CommonJS {@code exports}
 *       object, evaluates it, and reads the named property off {@code exports}.</li>
 *   <li>Invokes that function with the user-supplied args.</li>
 * </ol>
 *
 * <p>Return values map back to Pure following the same {@code Any[*]} pattern
 * as the Java {@code compileAndExecute} bridge: scalars flow as-is,
 * null/undefined map to {@link PureSequence#EMPTY}, unsupported types throw.</p>
 *
 * <p>Thread safety: {@link Context} is not safe for concurrent use, so all
 * entry points synchronize on the singleton instance. A spike-grade tradeoff
 * &mdash; if contention becomes real, switch to a pooled {@code Context}
 * sharing one {@code Engine}.</p>
 */
final class TypeScriptCompileNatives
{
    private static final String TYPESCRIPT_JS_RESOURCE = "/typescript.js";

    private static final Object LOCK = new Object();
    private static Context jsContext;

    private TypeScriptCompileNatives() {}

    static Object compileAndInvoke(String source, String fnName, Object[] args)
    {
        synchronized (LOCK)
        {
            Context ctx = context();
            ctx.getBindings("js").putMember("__pureTsSource", source);
            String js = transpile(ctx);
            Value fn  = evalAndExtract(ctx, js, fnName);
            Value ret = fn.execute(args);
            return toPureValue(ret);
        }
    }

    private static Context context()
    {
        if (jsContext == null)
        {
            Context c = Context.newBuilder("js")
                    .allowHostAccess(HostAccess.ALL)
                    .allowAllAccess(true)
                    .build();
            c.eval(typescriptCompilerSource());
            jsContext = c;
        }
        return jsContext;
    }

    private static Source typescriptCompilerSource()
    {
        try (InputStream in = TypeScriptCompileNatives.class.getResourceAsStream(TYPESCRIPT_JS_RESOURCE))
        {
            if (in == null)
            {
                throw new RuntimeException(
                        "typescript.js not found on classpath at '" + TYPESCRIPT_JS_RESOURCE +
                        "'. Build pre-req: run `pnpm install` in platforms/typescript so " +
                        "node_modules/typescript/lib/typescript.js exists before building this extension.");
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Source.newBuilder("js", body, "typescript.js").buildLiteral();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to read bundled typescript.js", e);
        }
    }

    // Calls ts.transpileModule on __pureTsSource and returns the emitted JS as
    // a String. Throws on diagnostics so failures surface with file+line info
    // rather than producing nonsense JS that fails on eval.
    private static String transpile(Context ctx)
    {
        String wrapper =
                "(function() {\n" +
                "  var result = ts.transpileModule(__pureTsSource, {\n" +
                "    compilerOptions: { module: 'commonjs', target: 'es2020', strict: false },\n" +
                // `reportDiagnostics: true` — without this the diagnostics
                // array is omitted entirely even on syntax errors, and we
                // would silently get truncated JS that fails later at
                // execution as a misleading ReferenceError / undefined export.
                "    reportDiagnostics: true\n" +
                "  });\n" +
                "  if (result.diagnostics && result.diagnostics.length > 0) {\n" +
                "    var msgs = result.diagnostics.map(function(d) {\n" +
                "      return ts.flattenDiagnosticMessageText(d.messageText, '\\n');\n" +
                "    });\n" +
                "    throw new Error('TypeScript compilation failed:\\n  ' + msgs.join('\\n  '));\n" +
                "  }\n" +
                "  return result.outputText;\n" +
                "})()";
        Value out = ctx.eval(Source.newBuilder("js", wrapper, "<transpile>").buildLiteral());
        if (!out.isString())
        {
            throw new RuntimeException("ts.transpileModule did not return a string");
        }
        return out.asString();
    }

    // Wraps the transpiled JS in an IIFE that provides a CommonJS-style
    // `exports` object, evaluates it, and reads the named property.
    private static Value evalAndExtract(Context ctx, String js, String fnName)
    {
        ctx.getBindings("js").putMember("__pureTsModuleSource", js);
        ctx.getBindings("js").putMember("__pureTsExportName", fnName);
        String wrapper =
                "(function() {\n" +
                "  var exports = {};\n" +
                "  var module  = { exports: exports };\n" +
                "  (new Function('exports', 'module', __pureTsModuleSource))(exports, module);\n" +
                "  var resolved = module.exports[__pureTsExportName];\n" +
                "  if (typeof resolved !== 'function') {\n" +
                "    throw new Error(\"Export '\" + __pureTsExportName + \"' is not a function (got \" + typeof resolved + \")\");\n" +
                "  }\n" +
                "  return resolved;\n" +
                "})()";
        return ctx.eval(Source.newBuilder("js", wrapper, "<extract>").buildLiteral());
    }

    private static Object toPureValue(Value v)
    {
        if (v == null || v.isNull())
        {
            return PureSequence.EMPTY;
        }
        if (v.isBoolean())
        {
            return v.asBoolean();
        }
        if (v.isString())
        {
            return v.asString();
        }
        if (v.isNumber())
        {
            // Mirror Pure's Long vs Double split: integer-valued numbers
            // come back as Long so they line up with Pure Integer slots;
            // anything fractional becomes Double.
            if (v.fitsInLong())
            {
                double d = v.asDouble();
                if (d == Math.floor(d) && !Double.isInfinite(d))
                {
                    return v.asLong();
                }
            }
            return v.asDouble();
        }
        throw new RuntimeException("Unsupported TypeScript return type: " + v + " (spike supports scalars only)");
    }
}
