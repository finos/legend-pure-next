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

package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.next.parser.PureParser;
import org.finos.legend.pure.truffle.PureContext;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;
import org.finos.legend.pure.truffle.runtime.ProtocolTranslator;

/**
 * {@code parse(sourceId:String[1], content:String[1]) : PureFile[1]}.
 *
 * <p>Invokes the Pure parser and converts the resulting protocol
 * objects to {@code DynamicInstance} trees via
 * {@code ProtocolToDynamicInstance}. This is needed because the
 * compiled-graph test runner uses {@code match} / {@code instanceOf}
 * on the parsed elements, which requires {@code classifierGenericType}
 * metadata that only DynamicInstance provides.</p>
 */
@NodeInfo(shortName = "parse")
public final class ParseNode extends PureNode
{
    @Child
    private PureNode sourceIdNode;
    @Child
    private PureNode contentNode;

    public ParseNode(PureNode sourceIdNode, PureNode contentNode)
    {
        this.sourceIdNode = sourceIdNode;
        this.contentNode = contentNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        String sourceId = StringHelper.asString(sourceIdNode.executeGeneric(frame), "parse");
        String content = StringHelper.asString(contentNode.executeGeneric(frame), "parse");
        return doParse(getContext(), sourceId, content);
    }

    private static Object doParse(PureContext eval, String sourceId, String content)
    {
        PureParser parser = eval.pureParser();
        if (parser == null)
        {
            throw new RuntimeException("parse native: no PureParser configured on PureContext");
        }
        Object bootstrapResult = parser.parse(sourceId, content);
        return new ProtocolTranslator(eval.resolver()).translate(bootstrapResult);
    }
}
