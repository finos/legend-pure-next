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
import org.finos.legend.pure.truffle.StandaloneEvaluator;
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
        return doParse(sourceId, content);
    }

    private static Object doParse(String sourceId, String content)
    {
        StandaloneEvaluator eval = StandaloneEvaluator.INSTANCE;
        PureParser parser = eval.pureParser();
        if (parser == null)
        {
            throw new RuntimeException("parse native: no PureParser configured on StandaloneEvaluator");
        }
        Object bootstrapResult = parser.parse(sourceId, content);
        // DEBUG: trace stereotypes on parsed properties
        if (bootstrapResult instanceof meta.pure.protocol.PureFile pf && pf._sections() != null)
        {
            for (var section : pf._sections())
            {
                if (section._elements() != null)
                {
                    for (var elem : section._elements())
                    {
                        // Use reflection to find _properties() on any element
                        try
                        {
                            java.lang.reflect.Method propsMethod = elem.getClass().getMethod("_properties");
                            Object propsResult = propsMethod.invoke(elem);
                            if (propsResult instanceof org.eclipse.collections.api.list.MutableList<?> propsList)
                            {
                                for (Object prop : propsList)
                                {
                                    try
                                    {
                                        java.lang.reflect.Method stereoMethod = prop.getClass().getMethod("_stereotypes");
                                        Object stereos = stereoMethod.invoke(prop);
                                        if (stereos instanceof org.eclipse.collections.api.list.MutableList<?> stList && !stList.isEmpty())
                                        {
                                            java.lang.reflect.Method nameMethod = prop.getClass().getMethod("_name");
                                            System.out.println("PARSE-DEBUG elem=" + elem._name()
                                                    + " prop=" + nameMethod.invoke(prop)
                                                    + "@" + System.identityHashCode(prop)
                                                    + " stereotypes=" + stList.size());
                                        }
                                    }
                                    catch (NoSuchMethodException ignored) {}
                                }
                            }
                        }
                        catch (NoSuchMethodException ignored) {}
                        catch (Exception e) { System.err.println("PARSE-DEBUG error: " + e); }
                    }
                }
            }
        }
        return new ProtocolTranslator(eval.resolver()).translate(bootstrapResult);
    }
}
