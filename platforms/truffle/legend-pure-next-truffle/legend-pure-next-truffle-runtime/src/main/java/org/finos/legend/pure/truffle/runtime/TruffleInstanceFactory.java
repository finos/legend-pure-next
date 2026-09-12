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

package org.finos.legend.pure.truffle.runtime;

import org.finos.legend.pure.truffle.runtime.module.TruffleMetadataAccess;

/**
 * Creates {@link org.finos.legend.pure.truffle.runtime.pdo.PureDynamicObject}
 * instances from Pure class paths.
 */
public final class TruffleInstanceFactory
{
    private TruffleInstanceFactory() {}

    /**
     * Create a fresh, slot-backed PDO for the given Pure class path — the
     * runtime's `new` for both metamodel and user classes.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object createInstance(String classPath, TruffleMetadataAccess resolver)
    {
        return new org.finos.legend.pure.truffle.runtime.pdo.PureDynamicObject(
                org.finos.legend.pure.truffle.runtime.module.PureClassRegistry.classInfoFor(classPath, resolver),
                /*fb=*/ null,
                resolver,
                /*parent=*/ null);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object createInstance(String classPath)
    {
        return new org.finos.legend.pure.truffle.runtime.pdo.PureDynamicObject(
                org.finos.legend.pure.truffle.runtime.module.PureClassRegistry.classInfoFor(classPath, null),
                /*fb=*/ null,
                /*resolver=*/ null,
                /*parent=*/ null);
    }
}
