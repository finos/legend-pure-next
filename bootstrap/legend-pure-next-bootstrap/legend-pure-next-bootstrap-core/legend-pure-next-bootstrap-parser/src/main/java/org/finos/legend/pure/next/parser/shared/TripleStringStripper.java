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

package org.finos.legend.pure.next.parser.shared;

public final class TripleStringStripper
{
    private TripleStringStripper()
    {
    }

    // Java text-block-style stripping (JEP 378):
    //   1. Drop the 3-char opening/closing `'''` delimiters.
    //   2. If body opens with a single `\n` (newline right after `'''`),
    //      drop it — the closing-delimiter column then determines indentation.
    //   3. Compute the minimum count of leading-whitespace characters across
    //      continuation lines (everything after the first `\n` in the body).
    //      Blank lines are excluded EXCEPT the very last line — that one
    //      always contributes, even if it's only whitespace, because it's
    //      the line on which the closing `'''` sits. Positioning the closing
    //      delimiter is how the author chooses the strip column.
    //   4. Strip that many leading whitespace characters from every
    //      continuation line. First-line content (on the opening-delimiter
    //      line) is preserved as-is.
    // Tabs and spaces are compared as raw characters — no tab-stop expansion.
    public static String strip(String tripleQuoted)
    {
        String body = tripleQuoted.substring(3, tripleQuoted.length() - 3);
        if (body.indexOf('\n') < 0)
        {
            return body;
        }
        boolean leadingNewline = body.charAt(0) == '\n';
        String contentAfterFirstLine = leadingNewline ? body.substring(1) : null;
        int firstNewline = leadingNewline ? -1 : body.indexOf('\n');

        String firstLine = leadingNewline ? null : body.substring(0, firstNewline);
        String rest = leadingNewline ? contentAfterFirstLine : body.substring(firstNewline + 1);

        String[] continuationLines = rest.split("\n", -1);

        int minLeading = Integer.MAX_VALUE;
        for (int i = 0; i < continuationLines.length; i++)
        {
            String line = continuationLines[i];
            boolean isLast = (i == continuationLines.length - 1);
            if (!isLast && isBlank(line))
            {
                continue;
            }
            int n = countLeadingWhitespace(line);
            if (n < minLeading)
            {
                minLeading = n;
            }
        }
        if (minLeading == Integer.MAX_VALUE)
        {
            minLeading = 0;
        }

        StringBuilder sb = new StringBuilder(body.length());
        if (!leadingNewline)
        {
            sb.append(firstLine);
        }
        for (int i = 0; i < continuationLines.length; i++)
        {
            if (i > 0 || !leadingNewline)
            {
                sb.append('\n');
            }
            String line = continuationLines[i];
            sb.append(stripUpTo(line, minLeading));
        }
        return sb.toString();
    }

    private static boolean isBlank(String s)
    {
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t')
            {
                return false;
            }
        }
        return true;
    }

    private static int countLeadingWhitespace(String s)
    {
        int n = 0;
        while (n < s.length())
        {
            char c = s.charAt(n);
            if (c != ' ' && c != '\t')
            {
                break;
            }
            n++;
        }
        return n;
    }

    private static String stripUpTo(String s, int n)
    {
        int strip = Math.min(n, countLeadingWhitespace(s));
        return s.substring(strip);
    }
}
