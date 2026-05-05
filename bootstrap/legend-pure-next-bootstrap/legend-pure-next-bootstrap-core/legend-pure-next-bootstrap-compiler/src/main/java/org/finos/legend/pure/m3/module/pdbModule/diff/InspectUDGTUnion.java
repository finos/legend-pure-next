// Copyright 2026 Goldman Sachs
package org.finos.legend.pure.m3.module.pdbModule.diff;

import com.google.flatbuffers.Table;
import org.finos.legend.pure.m3.module.pdbModule.fbs.PointerRef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.AncestorRef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.UserDefinedFunctionDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.UserDefinedGenericTypeDef;
import org.finos.legend.pure.m3.module.pdbModule.fbs.InferredGenericTypeDef;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Diagnostic: scans every UserDefinedGenericTypeDef + InferredGenericTypeDef
 * inside a UserDefinedFunction blob and counts what union code is used for
 * each classifierGenericType. 1=PointerRef, 2=AncestorRef, 3=Inferred,
 * 4=UDGT, 0=missing.
 */
public class InspectUDGTUnion
{
    static final Map<Byte, Integer> udgtCounts = new HashMap<>();
    static final Map<Byte, Integer> infCounts = new HashMap<>();
    static final Map<String, Integer> pointerRefPaths = new HashMap<>();

    public static void main(String[] args) throws IOException
    {
        try (ZipFile zf = new ZipFile(args[0]))
        {
            ZipEntry e = zf.getEntry(args[1]);
            byte[] data = zf.getInputStream(e).readAllBytes();
            UserDefinedFunctionDef def = UserDefinedFunctionDef.getRootAsUserDefinedFunctionDef(ByteBuffer.wrap(data));

            // Walk the function's classifier
            byte funType = def.classifierGenericTypeType();
            System.out.println("UDF.classifierGenericTypeType=" + funType);
            udgtCounts.merge(funType, 1, Integer::sum);
            if (funType == 4)
            {
                walkUDGT((UserDefinedGenericTypeDef) def.classifierGenericType(new UserDefinedGenericTypeDef()));
            }
            // Walk first expressionSequence entry's genericType chain
            if (def.expressionSequenceTypeLength() > 0)
            {
                System.out.println("expressionSequence[0] union=" + def.expressionSequenceType(0));
            }

            // Walk expressionSequence — each is a ValueSpecification, classifier appears at multiple union indices
            // For now just print top-level result
            System.out.println("UDGT classifier counts: " + udgtCounts);
            System.out.println("Inferred classifier counts: " + infCounts);
            System.out.println("Top PointerRef paths:");
            pointerRefPaths.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(10)
                    .forEach(en -> System.out.println("  " + en.getValue() + "x  " + en.getKey()));
            System.out.println("blob size=" + data.length);
        }
    }

    static void walkUDGT(UserDefinedGenericTypeDef gt)
    {
        byte t = gt.classifierGenericTypeType();
        udgtCounts.merge(t, 1, Integer::sum);
        if (t == 1)
        {
            PointerRef pr = (PointerRef) gt.classifierGenericType(new PointerRef());
            if (pr != null)
            {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pr.pathLength(); i++) { if (i > 0) sb.append("::"); sb.append(pr.path(i)); }
                pointerRefPaths.merge(sb.toString(), 1, Integer::sum);
            }
        }
        else if (t == 4)
        {
            UserDefinedGenericTypeDef sub = (UserDefinedGenericTypeDef) gt.classifierGenericType(new UserDefinedGenericTypeDef());
            if (sub != null) walkUDGT(sub);
        }
    }
}
