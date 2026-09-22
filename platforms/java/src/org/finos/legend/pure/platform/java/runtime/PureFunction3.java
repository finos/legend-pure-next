// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.platform.java.runtime;

/**
 * A Pure lambda of three parameters. The JDK's own functional interfaces stop
 * at two ({@code BiFunction}), and Pure does not — a section parser is
 * {@code {content, sourceId, lineOffset -> PackageableElement[*]}}.
 *
 * <p>Untyped, like the interfaces the translator casts to for the other
 * arities: every Pure value is an Object here, and a generic interface would
 * only add invariance problems at the cast sites.</p>
 */
@FunctionalInterface
public interface PureFunction3
{
    Object apply(Object first, Object second, Object third);
}
