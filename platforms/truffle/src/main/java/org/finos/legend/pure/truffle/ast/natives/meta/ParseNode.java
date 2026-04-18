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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.execution.ProtocolToDynamicInstance;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.next.parser.PureParser;
import org.finos.legend.pure.truffle.StandaloneEvaluator;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.string.StringHelper;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;

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
        return doParse(sourceId, content);
    }

    @TruffleBoundary
    private static Object doParse(String sourceId, String content)
    {
        StandaloneEvaluator eval = StandaloneEvaluatorHolder.current();
        PureParser parser = eval.pureParser();
        if (parser == null)
        {
            throw new RuntimeException("parse native: no PureParser configured on StandaloneEvaluator");
        }
        MetadataAccess resolver = eval.resolver();
        Object pureFile = parser.parse(sourceId, content);
        ProtocolToDynamicInstance translator = new ProtocolToDynamicInstance(resolver);
        return translator.convert(pureFile);
    }
}
