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

import java.util.function.Supplier;

/**
 * Standard output for {@code print}, and the nesting counter {@code withSilencedPrint} uses.
 *
 * <p>{@code print}'s contract (print.pure) is that it WRITES the value and returns the formatted string.
 * {@code flush()} matters: System.out only auto-flushes on a newline, and the progress display writes a bar
 * with none, so without it the bar would not appear until some later line forced it out.</p>
 */
public final class PureOut
{
    private static int silence;

    private PureOut()
    {
    }

    public static String write(String s)
    {
        if (silence == 0)
        {
            System.out.print(s);
            System.out.flush();
        }
        return s;
    }

    /**
     * Counted, so an inner silence cannot un-silence an outer one, and the count is restored even when the
     * body throws — a failing test must not mute the runner that reports it.
     */
    public static Object silenced(Supplier<?> body)
    {
        silence++;
        try
        {
            return body.get();
        }
        finally
        {
            silence--;
        }
    }
}
