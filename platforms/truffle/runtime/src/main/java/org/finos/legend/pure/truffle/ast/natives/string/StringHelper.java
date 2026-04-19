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

package org.finos.legend.pure.truffle.ast.natives.string;

import org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.valuespecification.AtomicValue;

public final class StringHelper
{
    private StringHelper()
    {
    }

    public static String asString(Object v, String signature)
    {
        if (v instanceof String s)
        {
            return s;
        }
        if (v instanceof AtomicValue av && av._value() instanceof String s)
        {
            return s;
        }
        if (v instanceof AtomicValue av && av._value() != null)
        {
            return av._value().toString();
        }
        if (v instanceof org.finos.legend.pure.truffle.types.PureNull || v == null)
        {
            return "";
        }
        // PureSequence — join all elements as strings
        if (v instanceof org.finos.legend.pure.truffle.types.PureSequence seq)
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < seq.size(); i++)
            {
                sb.append(asString(seq.getBoxed(i), signature));
            }
            return sb.toString();
        }
        // Any other type — use toString
        return ToStringNode.pureToString(v);
    }
}
