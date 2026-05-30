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

package org.finos.legend.pure.truffle.builder;

/**
 * Service-loader SPI for contributing specialized Truffle native nodes from
 * extension jars. {@link NativeNodeRegistry#createDefault()} registers all
 * implementations discovered on the classpath via
 * {@code ServiceLoader.load(TruffleNativesExtension.class)}.
 *
 * <p>Extensions ship as separate jars (see {@code platforms/truffle/legend-pure-next-truffle-extension/}).
 * The launcher (see {@code bin/pure-truffle}) puts the extension jars on the
 * classpath alongside the runtime fat jar so ServiceLoader picks them up.</p>
 */
public interface TruffleNativesExtension
{
    /**
     * Register one or more native-signature → Truffle-node factories on the
     * shared {@link NativeNodeRegistry}. Called once at runtime startup.
     */
    void registerAll(NativeNodeRegistry registry);
}
