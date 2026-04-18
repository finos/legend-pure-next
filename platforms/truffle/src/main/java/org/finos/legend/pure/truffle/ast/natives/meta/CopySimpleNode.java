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

package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.Any;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.GenericTypeValue;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.execution.natives.meta.MetaNatives;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code copy(T[1]) : T[1]}.
 *
 * <p>Shallow copy with no property overrides. Creates a new instance of the
 * same type, copies all properties, and fixes self-referential
 * classifierGenericType pointers.</p>
 */
@NodeInfo(shortName = "copySimple")
public final class CopySimpleNode extends PureNode
{
    @Child
    private PureNode child;

    private final GenericType genericType;
    private final Multiplicity multiplicity;

    public CopySimpleNode(PureNode child, GenericType genericType, Multiplicity multiplicity)
    {
        this.child = child;
        this.genericType = genericType;
        this.multiplicity = multiplicity;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object result = child.executeGeneric(frame);
        return doCopy(result, genericType, multiplicity);
    }

    @TruffleBoundary
    private static ValueSpecification doCopy(Object result, GenericType genericType, Multiplicity multiplicity)
    {
        ValueSpecification vs = ValueAdapter.ensureVS(result);
        MetadataAccess resolver = EvaluatorHolder.current().natives().resolver();

        Object original = _E_ValueSpecification.unwrap(vs);
        String classPath;
        GenericTypeValue cgt;
        if (original instanceof DynamicInstance di)
        {
            classPath = di.getClassPath();
            cgt = di.getClassifierGenericType();
        }
        else if (original instanceof PackageableElement pe)
        {
            cgt = pe._classifierGenericType();
            classPath = pe.getClass().getInterfaces()[0].getName().replace(".", "::");
        }
        else if (original instanceof Any any)
        {
            cgt = any._classifierGenericType();
            classPath = any.getClass().getInterfaces()[0].getName().replace(".", "::");
        }
        else
        {
            throw new RuntimeException("Cannot copy: " + (original == null ? "null" : original.getClass().getSimpleName()));
        }

        // If path is empty, derive from classifierGenericType
        if ((classPath == null || classPath.isEmpty()) && cgt != null)
        {
            classPath = MetaNatives.resolveClassPathFromCGT(cgt);
        }

        Object copy = MetaNatives.createInstanceByPath(classPath);
        // First copy all properties
        MetaNatives.shallowCopyProperties(original, copy, cgt, resolver);
        // Then fix and set the self-referential classifierGenericType
        GenericTypeValue copyCgt = MetaNatives.fixSelfReferentialCGT(cgt, original, copy, resolver);
        if (copy instanceof Any anyC && copyCgt != null)
        {
            anyC._classifierGenericType(copyCgt);
        }
        else if (copy instanceof DynamicInstance diC && copyCgt != null)
        {
            diC.setClassifierGenericType(copyCgt);
        }
        return _E_ValueSpecification.wrap(copy, genericType, multiplicity, resolver);
    }
}
