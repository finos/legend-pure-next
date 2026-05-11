// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.runtime.dynobj;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;
import org.finos.legend.pure.m3.module.pdbModule.fbs.PropertyDef;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry;
import org.finos.legend.pure.truffle.runtime.TrufflePdbLoader;
import org.finos.legend.pure.truffle.types.PureSequence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Spike validation for the DynamicObject + lazy-FB approach.
 *
 * <p>Loads a real {@code Property} from {@code core.pdb}, mirrors it as a
 * {@link PureDynamicObject}, and compares values + measures post-warmup
 * read cost vs the existing {@code PropertyImpl} path.</p>
 */
class PureDynamicObjectSpikeTest
{
    private static TrufflePdbLoader coreLoader;
    private static TruffleModuleRegistry resolver;
    // Post-FB-decode flip: sampled Property is a PureDynamicObject backed by
    // the raw FB sub-table. The typed PropertyImpl path went away when FB
    // decode stopped creating typed XImpls for nested refs.
    private static Object sampleFbw;
    private static PropertyDef samplePdef;
    private static Shape propertyShape;
    private static org.graalvm.polyglot.Engine engine;
    private static org.graalvm.polyglot.Context polyglotCtx;

    @BeforeAll
    static void setUp() throws Exception
    {
        // Engine + Context with Graal compilation eagerly enabled, so the
        // microbench's CallTargets actually get PE'd. Mirrors the wiring
        // in TrufflePureTestRunner — the only way DOL constant-folds the
        // Shape is to run under Truffle's compiler, not stock HotSpot.
        engine = org.graalvm.polyglot.Engine.newBuilder()
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        System.out.println("[spike] Truffle runtime = " + com.oracle.truffle.api.Truffle.getRuntime().getName());
        polyglotCtx = org.graalvm.polyglot.Context.newBuilder()
                .engine(engine)
                .allowAllAccess(true)
                .build();

        Path corePdb = Path.of("../../../../shared/core.pdb");
        coreLoader = new TrufflePdbLoader(corePdb, "core", List.of());
        resolver = new TruffleModuleRegistry();
        resolver.register(coreLoader);
        coreLoader.setResolver(resolver);

        // Find any Class with at least one Property; use its first property
        // as the sample. We pick PackageableElement because it's small and
        // stable.
        // Post-loader-flip resolver.getElement returns a PureDynamicObject —
        // read via PureObj.read instead of the typed (Class) cast.
        Object cls = resolver.getElement("meta::pure::metamodel::PackageableElement");
        assertNotNull(cls, "PackageableElement should be loaded");
        PureSequence props = (PureSequence) PureObj.read(cls, "properties");
        assertNotNull(props, "PackageableElement.properties should be non-null");

        // Find the first Property (vs QualifiedProperty). FB-decoded entries
        // may be typed PropertyImpl (legacy path) or PureDynamicObject with
        // Shape's dynamicType set to ::Property (post-flip).
        for (Object p : props.toBoxedArray())
        {
            if ("meta::pure::metamodel::function::property::Property".equals(PureObj.pureTypeOf(p)))
            {
                sampleFbw = p;
                break;
            }
        }
        assertNotNull(sampleFbw, "expected at least one Property on PackageableElement");

        // The new universal decoder uses PropertyAccessor.readProperty as its
        // backend, so PureDynamicObject.fb is the FBW itself (not the raw
        // PropertyDef).
        samplePdef = null;

        propertyShape = PureShapeRegistry.shapeFor("meta::pure::metamodel::function::property::Property");

        System.out.println("[spike] sample property name=" + PureObj.read(sampleFbw, "name")
                + "  owner=" + PureObj.read(sampleFbw, "owner"));
    }

    @AfterAll
    static void tearDown()
    {
        if (polyglotCtx != null) polyglotCtx.close();
        if (engine != null) engine.close();
    }

    private PureDynamicObject freshSampleDo()
    {
        // fb = the FBW itself; the universal decoder dispatches via
        // PropertyAccessor.readProperty.
        return new PureDynamicObject(propertyShape, sampleFbw, resolver, /*parent*/ null);
    }

    @Test
    void dolReadsMatchFbwReads()
    {
        PureDynamicObject sampleDo = freshSampleDo();
        DynamicObjectLibrary dol = DynamicObjectLibrary.getUncached();

        // First read: this DO instance hasn't materialized "name" → returns
        // LAZY (whether or not the shared Shape has the slot — fresh DOs
        // start at the base Shape; transitions are per-instance via put).
        Object firstName = dol.getOrDefault(sampleDo, "name", PureFbDecoder.LAZY);
        assertSame(PureFbDecoder.LAZY, firstName, "fresh DO should miss");
        Object decoded = PureFbDecoder.decode(sampleDo, "name");
        dol.put(sampleDo, "name", decoded);

        // Second read: hit the materialized slot
        Object secondName = dol.getOrDefault(sampleDo, "name", PureFbDecoder.LAZY);
        assertEquals(PureObj.read(sampleFbw, "name"), secondName, "DOL hit must match FBW read");

        // Repeat for a more interesting field — owner — to confirm the
        // decoder works for cross-element resolution too. Compare via
        // PureObj.read instead of the typed `_owner()` getter: post-loader-flip
        // the FBW's typed getter can return null when the stored value is a
        // PureDynamicObject (cast-safe getter), while the DOL holds the raw
        // PDO. PureObj.read goes through readProperty and returns the raw value
        // on both sides — equivalent comparison.
        Object decodedOwner = PureFbDecoder.decode(sampleDo, "owner");
        dol.put(sampleDo, "owner", decodedOwner);
        assertEquals(PureObj.read(sampleFbw, "owner"), dol.getOrDefault(sampleDo, "owner", null),
                "owner DOL hit must match FBW read");
    }

    @Test
    void pureObjReadMatchesAcrossTypes()
    {
        // End-to-end Stage 1 bridge: load real elements via the new
        // getElementAsDynamic path, read various property kinds via PureObj.read,
        // verify each matches the legacy FBW typed-getter result.
        Object pe = resolver.getElement("meta::pure::metamodel::PackageableElement");
        Object cls = resolver.getElement("meta::pure::metamodel::type::Class");
        Object t = resolver.getElement("meta::pure::metamodel::type::Type");
        assertNotNull(pe, "PackageableElement must be loaded");
        assertNotNull(cls, "Class must be loaded");
        assertNotNull(t, "Type must be loaded");

        // Property: PE -> get Class via dynamic path, compare via PureObj.read.
        // Post-loader-flip, `pe` is itself already a PureDynamicObject — the
        // separate dynamic-wrapping path is only needed if the loader returned
        // typed XImpl. getElementAsDynamic still works (it wraps if needed
        // or returns the existing PDO).
        PureDynamicObject peDyn = coreLoader.getElementAsDynamic("meta::pure::metamodel::PackageableElement");
        PureDynamicObject clsDyn = coreLoader.getElementAsDynamic("meta::pure::metamodel::type::Class");
        assertNotNull(peDyn, "dynamic PE wrapping must succeed");
        assertNotNull(clsDyn, "dynamic Class wrapping must succeed");

        // Pure-type-of: Shape's dynamic type must match Pure's underlying type
        assertEquals("meta::pure::metamodel::type::Class", PureObj.pureTypeOf(peDyn),
                "PackageableElement is itself a Class — Shape's dynamic type encodes that");

        // Read primitive String property — name
        assertEquals(PureObj.read(pe, "name"),
                PureObj.read(peDyn, "name"),
                "PureObj.read of name must agree between resolver-returned and freshly-wrapped PDO");

        // Read a sequence property — properties (PureSequence of Property FBWs)
        Object resolverProps = PureObj.read(pe, "properties");
        Object dynProps = PureObj.read(peDyn, "properties");
        assertNotNull(dynProps, "properties should not be null");
        // Same identity — both wrappers delegate to the same FBW-cached PureSequence
        assertSame(resolverProps, dynProps, "PureObj.read of properties must return the FBW-cached sequence");
    }

    @Test
    void microbench_readName_postWarmup()
    {
        // Pre-materialize the name slot on the DO so the bench measures the
        // hot-path "Shape hit" — this is the post-warmup state of every
        // call site after first access.
        PureDynamicObject sampleDo = freshSampleDo();
        DynamicObjectLibrary dolUncached = DynamicObjectLibrary.getUncached();
        Object decoded = PureFbDecoder.decode(sampleDo, "name");
        dolUncached.put(sampleDo, "name", decoded);

        // Enter the polyglot context so CallTargets get PE'd by Graal.
        polyglotCtx.enter();
        try
        {
            runBench(sampleDo);
        }
        finally
        {
            polyglotCtx.leave();
        }
    }

    private void runBench(PureDynamicObject sampleDo)
    {
        // Build CallTargets so the Truffle compiler PEs both paths.
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        CallTarget fbwTarget = new FbwReadRoot(sampleFbw, fd).getCallTarget();
        CallTarget doTarget = new DoReadRoot(sampleDo, fd).getCallTarget();
        CallTarget doRawTarget = new DoRawReadRoot(sampleDo, fd).getCallTarget();

        // Warm
        for (int i = 0; i < 200_000; i++)
        {
            fbwTarget.call();
            doTarget.call();
            doRawTarget.call();
        }

        long iters = 5_000_000L;
        long fbwBest = Long.MAX_VALUE;
        long doBest = Long.MAX_VALUE;
        long doRawBest = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++)
        {
            long t0 = System.nanoTime();
            for (long i = 0; i < iters; i++)
            {
                fbwTarget.call();
            }
            long fbwNs = System.nanoTime() - t0;

            long t1 = System.nanoTime();
            for (long i = 0; i < iters; i++)
            {
                doTarget.call();
            }
            long doNs = System.nanoTime() - t1;

            long t2 = System.nanoTime();
            for (long i = 0; i < iters; i++)
            {
                doRawTarget.call();
            }
            long doRawNs = System.nanoTime() - t2;

            fbwBest = Math.min(fbwBest, fbwNs);
            doBest = Math.min(doBest, doNs);
            doRawBest = Math.min(doRawBest, doRawNs);
            System.out.printf("[spike round %d] fbw=%.1f  do(node)=%.1f  do(raw)=%.1f  ns/op%n",
                    round,
                    fbwNs / (double) iters,
                    doNs / (double) iters,
                    doRawNs / (double) iters);
        }
        System.out.printf("[spike best] fbw=%.1f  do(node)=%.1f  do(raw)=%.1f  ns/op  "
                + "node-vs-fbw=%.2fx  raw-vs-fbw=%.2fx%n",
                fbwBest / (double) iters,
                doBest / (double) iters,
                doRawBest / (double) iters,
                doBest / (double) fbwBest,
                doRawBest / (double) fbwBest);
    }

    /** Single-property read via {@link PureObj#read} — the path that works
     *  for both typed XImpl and PureDynamicObject backings. */
    private static final class FbwReadRoot extends RootNode
    {
        private final Object property;

        FbwReadRoot(Object property, FrameDescriptor fd)
        {
            super(null, fd);
            this.property = property;
        }

        @Override
        public Object execute(VirtualFrame frame)
        {
            return PureObj.read(property, "name");
        }
    }

    /** DOL-based read via the new node — the proposed path. */
    private static final class DoReadRoot extends RootNode
    {
        private final PureDynamicObject receiver;
        @Child private PurePropertyReadNode read = PurePropertyReadNodeGen.create();

        DoReadRoot(PureDynamicObject receiver, FrameDescriptor fd)
        {
            super(null, fd);
            this.receiver = receiver;
        }

        @Override
        public Object execute(VirtualFrame frame)
        {
            return read.execute(receiver, "name");
        }
    }

    /** Raw DOL — bypasses the Node specialization machinery. */
    private static final class DoRawReadRoot extends RootNode
    {
        private final PureDynamicObject receiver;
        @Child private com.oracle.truffle.api.object.DynamicObjectLibrary dol =
                com.oracle.truffle.api.object.DynamicObjectLibrary.getFactory().createDispatched(3);

        DoRawReadRoot(PureDynamicObject receiver, FrameDescriptor fd)
        {
            super(null, fd);
            this.receiver = receiver;
        }

        @Override
        public Object execute(VirtualFrame frame)
        {
            return dol.getOrDefault(receiver, "name", null);
        }
    }
}
