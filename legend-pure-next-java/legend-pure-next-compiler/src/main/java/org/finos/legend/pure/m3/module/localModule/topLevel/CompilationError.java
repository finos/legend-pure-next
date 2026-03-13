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

package org.finos.legend.pure.m3.module.localModule.topLevel;

import meta.pure.metamodel.SourceInformation;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

/**
 * A compilation error with a descriptive message, source location,
 * and progressively enriched context.
 *
 * <p>Created at the lowest compilation layer with a base message,
 * then enriched with additional context as the call stack unwinds.
 * For example:</p>
 * <ul>
 *   <li>{@code GenericTypeCompiler} creates: "The type 'X' can't be found"</li>
 *   <li>{@code PropertyCompiler} adds context: "property 'other'"</li>
 *   <li>{@code ClassHandler} adds context: "class 'pack::MyClass'"</li>
 * </ul>
 * <p>Formatted message: "The type 'X' can't be found in property 'other' in class 'pack::MyClass' (at 4:11-4:20)"</p>
 */
public class CompilationError
{
    private final String message;
    private final SourceInformation sourceInformation;
    private final MutableList<String> contexts = Lists.mutable.empty();

    public CompilationError(String message, SourceInformation sourceInformation)
    {
        this.message = message;
        this.sourceInformation = sourceInformation;
    }

    /**
     * Add context describing where this error occurred.
     * Context is added going up the call stack, so the
     * innermost context is added first.
     */
    public void addContext(String context)
    {
        contexts.add(context);
    }

    public String message()
    {
        return message;
    }

    public SourceInformation sourceInformation()
    {
        return sourceInformation;
    }

    /**
     * Return a formatted message including all context entries
     * and source location.
     */
    public String formatMessage()
    {
        StringBuilder sb = new StringBuilder(message);
        for (String context : contexts)
        {
            sb.append(" in ").append(context);
        }
        if (sourceInformation != null)
        {
            sb.append(" (at ");
            if (sourceInformation._sourceId() != null && !sourceInformation._sourceId().isEmpty())
            {
                sb.append(sourceInformation._sourceId()).append(' ');
            }
            sb.append(sourceInformation._startLine()).append(':').append(sourceInformation._startColumn());
            sb.append('-');
            sb.append(sourceInformation._endLine()).append(':').append(sourceInformation._endColumn());
            sb.append(')');
        }
        return sb.toString();
    }

    @Override
    public String toString()
    {
        return formatMessage();
    }
}
