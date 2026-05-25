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

package org.finos.legend.pure.truffle.builder;

import org.finos.legend.pure.truffle.ast.PureNode;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Registry of specialized Truffle nodes, keyed by native signature (e.g.
 * {@code plus_Integer_MANY__Integer_1_}). {@link PureASTBuilder} consults
 * this registry before falling back to {@link
 * org.finos.legend.pure.truffle.ast.BridgedNativeCallNode}.
 *
 * <p>Specialized nodes live under
 * {@code org.finos.legend.pure.truffle.ast.natives.**} and opt in by
 * registering a {@link Factory} here. Each phase of the rewrite adds a
 * batch of factories; unregistered signatures stay on the bridge.</p>
 *
 * <p>A factory receives the already-lowered argument nodes plus the
 * {@code genericType} / {@code multiplicity} from the call site. The
 * default implementation is empty — Phase 2 registers math/boolean
 * natives, Phase 4 collection natives, etc.</p>
 */
public final class NativeNodeRegistry
{
    /**
     * Factory for a specialized Truffle node covering one native signature.
     */
    @FunctionalInterface
    public interface Factory
    {
        PureNode create(PureNode[] args, Object genericType, Object multiplicity, Object fe);
    }

    private final Map<String, Factory> factories = new HashMap<>();

    /**
     * Default registry with no specializations.
     */
    public static NativeNodeRegistry empty()
    {
        return new NativeNodeRegistry();
    }

    /**
     * Creates a fully-populated registry with all standard native specializations.
     */
    public static NativeNodeRegistry createDefault()
    {
        NativeNodeRegistry registry = new NativeNodeRegistry();
        org.finos.legend.pure.truffle.ast.natives.math.MathNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.boolean_.BooleanNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.string.StringNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.collection.CollectionNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.lang.LangNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.meta.MetaNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.io.IONodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.assert_.AssertNodeFactories.registerAll(registry);
        org.finos.legend.pure.truffle.ast.natives.date.DateNodeFactories.registerAll(registry);
        // Extensions ship as separate jars (legend-pure-next-truffle-extension/*)
        // and contribute additional natives via the TruffleNativesExtension SPI.
        // Each jar's META-INF/services/org.finos.legend.pure.truffle.builder.TruffleNativesExtension
        // names the implementation(s) ServiceLoader should construct here.
        for (TruffleNativesExtension ext : ServiceLoader.load(
                TruffleNativesExtension.class,
                NativeNodeRegistry.class.getClassLoader()))
        {
            ext.registerAll(registry);
        }
        return registry;
    }

    public NativeNodeRegistry register(String signature, Factory factory)
    {
        factories.put(signature, factory);
        return this;
    }

    /**
     * Returns the specialized factory for {@code signature}, or {@code null}
     * if the signature is still on the bridge.
     */
    public Factory lookup(String signature)
    {
        return factories.get(signature);
    }

    public boolean isEmpty()
    {
        return factories.isEmpty();
    }

    public int size()
    {
        return factories.size();
    }
}
