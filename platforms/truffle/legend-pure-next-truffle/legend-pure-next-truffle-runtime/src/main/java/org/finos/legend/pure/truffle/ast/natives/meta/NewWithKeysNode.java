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

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawLambdaCallNode;
import org.finos.legend.pure.truffle.runtime.helper._GenericType;
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * {@code new(GenericTypeAndMultiplicityHolder[1], KeyExpression[*]) : T[1]}
 * -- lazy native: construction stack + key expression evaluation.
 * Delegates to StandaloneEvaluator for the complex construction logic.
 */
@NodeInfo(shortName = "newWithKeys")
public final class NewWithKeysNode extends PureNode
{

    private static final int SLOT_CLASSIFIER_GENERIC_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("classifierGenericType");
    private static final int SLOT_EXPRESSION = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("expression");
    private static final int SLOT_GENERIC_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("genericType");
    private static final int SLOT_MULTIPLICITY_ARGUMENTS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("multiplicityArguments");
    private static final int SLOT_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    private static final int SLOT_PROPERTIES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("properties");
    @CompilationFinal
    private final String signature;

    @Child
    private PureNode typeHolderNode;

    @Children
    private org.finos.legend.pure.truffle.ast.PropertyAssignNode[] assignments;

    /**
     * Original {@code KeyExpression[*]} arg PureNode. Drives the dynamic
     * path — used when the parser couldn't decompose {@code assignments[]}
     * at parse time. Two examples:
     *   - {@code [^KeyExpression(name=…, expression=…)]} (literal form, the
     *     KeyExpressions are runtime instances)
     *   - {@code $keyVar} (variable holding a list built earlier)
     * The static path (assignments[]) is the 99% case; the dynamic path
     * lets the remaining cases work.
     */
    @Child
    private PureNode keysNode;

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyWriteNode dynamicKeyWriter = new org.finos.legend.pure.truffle.ast.PropertyWriteNode();

    @Child
    private RawLambdaCallNode constraintCallNode = new RawLambdaCallNode();

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyReadNode appendReader = new org.finos.legend.pure.truffle.ast.PropertyReadNode();

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyWriteNode appendWriter = new org.finos.legend.pure.truffle.ast.PropertyWriteNode();

    public NewWithKeysNode(String signature, PureNode typeHolderNode, org.finos.legend.pure.truffle.ast.PropertyAssignNode[] assignments)
    {
        this(signature, typeHolderNode, assignments, null);
    }

    public NewWithKeysNode(String signature, PureNode typeHolderNode, org.finos.legend.pure.truffle.ast.PropertyAssignNode[] assignments, PureNode keysNode)
    {
        this.signature = signature;
        this.typeHolderNode = typeHolderNode;
        this.assignments = assignments;
        this.keysNode = keysNode;
    }

    @Override
    @com.oracle.truffle.api.nodes.ExplodeLoop
    public Object executeGeneric(VirtualFrame frame)
    {
        return invoke(frame);
    }

    @com.oracle.truffle.api.nodes.ExplodeLoop
    private Object invoke(VirtualFrame frame)
    {
        // Split into three phases:
        //   1. createAndInitInstance — @TruffleBoundary, no frame: typeHolder
        //      → instance creation + classifier setup.
        //   2. The @ExplodeLoop body — frame-touching: evaluate each
        //      assignment's value via PropertyAssignNode.execute.
        //   3. finalizeInstance — @TruffleBoundary, no frame: reverse
        //      association pointers + constraint validation.
        // Without the boundaries, Graal PE inlined the entire instance-creation
        // tree + post-loop validation into every NewWithKeysNode call site,
        // blowing the inlining budget on generic functions like `pair<U,V>`
        // whose root nodes also inline bindQpTypeVariablesStatic. The
        // boundaries let PE stay shallow at the call site and reach the
        // actual KeyExpression evaluations without exhausting depth.
        org.finos.legend.pure.truffle.PureContext eval = getContext();
        Object typeHolder = typeHolderNode.executeGeneric(frame);
        Object[] holder = createAndInitInstance(typeHolder, eval);
        Object instance = holder[0];
        String classPath = (String) holder[1];

        // Push onto construction stack for parentReference() access across call boundaries
        var ctx = org.finos.legend.pure.truffle.PureLanguage.get(this);
        ctx.pushConstruction(instance);
        try
        {
            java.util.List<java.util.Map.Entry<String, Object>> keyValues = new java.util.ArrayList<>();
            for (int i = 0; i < assignments.length; i++)
            {
                if ("classifierGenericType".equals(assignments[i].propertyName()))
                {
                    // Evaluate the value WITHOUT writing it to the instance —
                    // PropertyAssignNode.execute() would write before we get
                    // a chance to compare against the system-set classifier,
                    // making validation a no-op.
                    Object propValue = assignments[i].evaluateValue(frame);
                    validateClassifierOverride(propValue, instance, eval.resolver());
                    // Widened to non-null check: post-loader-flip propValue may
                    // be a PureDynamicObject (pureType=GenericTypeValue) rather
                    // than the typed XPDBHelper. Validation above already enforces
                    // the raw-type compatibility constraint.
                    if (propValue != null)
                    {
                        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(instance, "classifierGenericType", propValue);
                    }
                    continue;
                }
                Object propValue = assignments[i].execute(frame, instance);
                if (propValue != null)
                {
                    keyValues.add(java.util.Map.entry(assignments[i].propertyName(), propValue));
                }
            }

            // Dynamic path: when the parser couldn't decompose KeyExpressions
            // at parse time (literal-form `^KeyExpression(name=…, expression=…)`
            // or a variable-bound list), evaluate the keys arg now and walk
            // the resulting KeyExpression instances to set properties.
            if (assignments.length == 0 && keysNode != null)
            {
                applyDynamicKeyExpressions(frame, instance, keyValues);
            }

            finalizeInstance(instance, classPath, keyValues, eval);
            return instance;
        }
        finally
        {
            ctx.popConstruction();
        }
    }

    /**
     * Resolve classPath from the type holder, create the instance, and set
     * its classifierGenericType. Returns {@code {instance, classPath}}.
     *
     * <p>Marked @TruffleBoundary: this is the largest non-frame chunk of
     * NewWithKeysNode.invoke, and inlining it into the caller's PE graph
     * is what tips generic-function root nodes (which also inline
     * bindQpTypeVariablesStatic) past Graal's inlining budget.</p>
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object[] createAndInitInstance(Object typeHolder, org.finos.legend.pure.truffle.PureContext eval)
    {
        String classPath = "Unknown";
        Object hoistedGt = null;
        PureSequence hoistedTypeArgs = null;
        if (typeHolder != null)
        {
            Object holderGT = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(typeHolder, SLOT_GENERIC_TYPE);
            if (holderGT != null)
            {
                hoistedGt = holderGT;
                hoistedTypeArgs = _GenericType.typeArguments(hoistedGt);
                if (hoistedTypeArgs != null && hoistedTypeArgs.size() > 0)
                {
                    classPath = resolveClassPathFromTypeArg(hoistedTypeArgs.getBoxed(0), eval.resolver());
                }
                if ("Unknown".equals(classPath))
                {
                    var type = _GenericType.type(hoistedGt);
                    if (type != null)
                    {
                        String path = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(type, eval.resolver());
                        if (path != null && !path.isEmpty())
                        {
                            classPath = path;
                        }
                    }
                    if ("Unknown".equals(classPath))
                    {
                        throw new RuntimeException("[NEW] Cannot resolve class path: gt=" + hoistedGt.getClass().getName()
                                + " typeArgs=" + (hoistedTypeArgs != null ? hoistedTypeArgs.size() : "null")
                                + " type=" + (_GenericType.type(hoistedGt) != null ? _GenericType.type(hoistedGt).getClass().getName() : "null"));
                    }
                }
            }
        }

        Object instance = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(classPath, getResolver());

        if (hoistedGt != null && instance != null)
        {
            PureSequence typeArgs = hoistedTypeArgs;
            if (typeArgs != null && typeArgs.size() > 0)
            {
                Object firstArg = typeArgs.getBoxed(0);
                if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(firstArg,
                        "meta::pure::metamodel::type::generics::GenericTypeValue", eval.resolver()))
                {
                    Object resolved = preferCanonicalAnchor(firstArg, eval.resolver());
                    org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(instance, "classifierGenericType", resolved);
                }
            }
            else if (!"Unknown".equals(classPath))
            {
                Object typeElement = eval.resolver().getElement(classPath);
                if (typeElement != null)
                {
                    org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(instance, "classifierGenericType",
                            org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(typeElement, eval.resolver()));
                }
            }
        }

        if (instance != null
                && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(instance, SLOT_CLASSIFIER_GENERIC_TYPE) == null)
        {
            Object hgt = typeHolder != null
                    ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(typeHolder, SLOT_GENERIC_TYPE) : null;
            throw new RuntimeException("[NEW] classifierGenericType is NULL after creation of " + classPath
                    + " (instance=" + instance.getClass().getName()
                    + ", typeHolder=" + (typeHolder != null ? typeHolder.getClass().getName() : "null")
                    + ", gt=" + (hgt != null ? _GenericType.print(hgt, eval.resolver()) : "n/a")
                    + ")");
        }
        return new Object[]{instance, classPath};
    }

    /**
     * Post-loop validation: reverse association pointers + constraint walk.
     *
     * <p>Marked @TruffleBoundary: same rationale as
     * {@link #createAndInitInstance} — keeps PE shallow at the call site.</p>
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void finalizeInstance(Object instance, String classPath,
                                   java.util.List<java.util.Map.Entry<String, Object>> keyValues,
                                   org.finos.legend.pure.truffle.PureContext eval)
    {
        setReverseAssociationPointers(instance, classPath, keyValues, eval, appendReader, appendWriter);
        if (instance != null)
        {
            Object cgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(instance, SLOT_CLASSIFIER_GENERIC_TYPE);
            if (cgt != null)
            {
                Object type = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
                if (type != null)
                {
                    CastNode.validateConstraints(type, cgt, instance, eval.resolver(), constraintCallNode);
                }
            }
        }
    }

    /**
     * Dynamic path: evaluate the keys arg and walk the resulting
     * {@code KeyExpression} instances, setting each one's named property
     * on the instance.
     *
     * <p>Each {@code KeyExpression} carries:
     *   - {@code _name()} — target property name on the new instance
     *   - {@code _expression()} — value to assign (already evaluated when
     *     the KeyExpression was constructed via {@code ^KeyExpression(…)})
     *   - {@code _add()} — true means append-to-list semantics
     * </p>
     */
    private void applyDynamicKeyExpressions(VirtualFrame frame, Object instance,
                                            java.util.List<java.util.Map.Entry<String, Object>> keyValues)
    {
        Object keys = keysNode.executeGeneric(frame);
        int len = org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper.size(keys);
        for (int i = 0; i < len; i++)
        {
            Object item = org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper.at(keys, i);
            if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(item,
                    "meta::pure::functions::lang::KeyExpression"))
            {
                throw new RuntimeException(
                        "new(...) expected KeyExpression in keys list at index " + i
                                + ", got " + (item == null ? "null" : item.getClass().getName()));
            }
            Object propNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(item, SLOT_NAME);
            if (!(propNameObj instanceof String propName))
            {
                throw new RuntimeException("KeyExpression at index " + i + " has no name");
            }
            Object value = unwrapSingleton(org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(item, SLOT_EXPRESSION));
            dynamicKeyWriter.execute(instance, propName, value);
            if (value != null)
            {
                keyValues.add(java.util.Map.entry(propName, value));
            }
        }
    }

    /**
     * Unwrap a single-element {@link PureSequence} to its element. Many
     * setters expect the unwrapped value (e.g. a {@code String}, not a
     * one-element sequence containing a String). Multi-element sequences
     * pass through as-is — the receiving setter will accept the list.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object unwrapSingleton(Object value)
    {
        if (value instanceof PureSequence ps)
        {
            if (ps.size() == 1)
            {
                return ps.getBoxed(0);
            }
        }
        return value;
    }

    /**
     * Resolve a class path from a type argument (Object from PureSequence.getBoxed).
     * Uses truffle-namespaced helpers.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String resolveClassPathFromTypeArg(Object typeArg,
                                                       org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        Object rawType = _GenericType.type(typeArg);
        if (rawType != null)
        {
            String path = _PackageableElement.path(rawType, resolver);
            if (path != null && !path.isEmpty())
            {
                return path;
            }
        }
        return "Unknown";
    }

    /**
     * Reject a user-supplied {@code classifierGenericType=...} key expression
     * whose raw type doesn't match the system-derived classifier. The raw type
     * is the metaclass identity ({@code LA_Person}, {@code RelationType}, ...)
     * and is system-managed — changing it would corrupt type integrity
     * (covered by spec test {@code testCantSetClassifierGenericType}).
     * Type-arguments, multiplicity-arguments and type-variable values can be
     * user-supplied without changing identity, so an override whose raw type
     * matches the system one is allowed (this is what the
     * {@code RelationTypeCompiler} self-classifier wiring relies on).
     */
    private static void validateClassifierOverride(Object proposed, Object instance, org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        Object currentCgt = instance != null
                ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(instance, SLOT_CLASSIFIER_GENERIC_TYPE) : null;
        Object expected = currentCgt != null ? _GenericType.type(currentCgt) : null;
        // _GenericType.type is Object-tolerant (reads `type` via PureObj.read)
        // so it handles both typed GenericTypeValue and PDO uniformly.
        Object proposedType = proposed != null ? _GenericType.type(proposed) : null;
        if (expected != null && proposedType != null && samePackageableElement(expected, proposedType, resolver))
        {
            return;
        }
        // Allow proposed to be a *subtype* of expected — covers the
        // enum-value pattern, where an {@code ^Enum(...)} instance is
        // intentionally re-classified as a specific user enumeration
        // (e.g. {@code ^Enum(classifierGenericType = MyEnum<self>)}). The
        // safety invariant — preventing arbitrary metaclass swaps like
        // {@code ^LA_Person(classifierGenericType = ^...(type = Any))} —
        // still holds since {@code Any} is a supertype of LA_Person, not a
        // subtype, and the supertype path remains rejected.
        //
        // We can't go through {@link _Type#subtypeOf}: it queries the resolver's
        // {@code TypeCache} which is populated lazily and doesn't see freshly
        // constructed types (compile-pure pass-1 emits enums whose ancestors
        // set hasn't been computed yet). Walk {@code .generalizations}
        // directly, dereferencing pointer types as we go — the chain is short
        // (user enum → Enum → PackageableElement → …) so the walk is cheap.
        if (expected != null && proposedType != null && isSubtypeViaGeneralizations(proposedType, expected, resolver))
        {
            return;
        }
        // Compile-pure pass-1 pattern: the proposed classifier's type is a
        // {@link TempCompilerPointer} whose target is being built right now
        // and isn't yet in the resolver (e.g. {@code buildEnumerationSkeleton}
        // wires an enum value's classifier as a pointer to the enum it's
        // about to register). The pointer carries the path the producer
        // intends, and {@code PointerGraphResolver} canonicalises it at
        // module construction. Accept — a pointer is by construction
        // compile-pure-internal, not a user-supplied raw type swap.
        if (proposedType != null
                && org.finos.legend.pure.truffle.runtime.helper._PackageableElement.pointerPath(proposedType) != null)
        {
            return;
        }
        String expectedName = expected != null ? _PackageableElement.path(expected, resolver) : "<unknown>";
        String proposedName = proposedType != null ? _PackageableElement.path(proposedType, resolver) : "<unknown>";
        throw new RuntimeException("Cannot change classifierGenericType.type from '" + expectedName + "' to '" + proposedName
                + "'. The classifier's raw type is system-managed (derived from the instance's metaclass)"
                + " — only typeArguments, multiplicityArguments and typeVariableValues are user-customizable."
                + " Use meta::pure::functions::lang::new(GenericType[1]) to construct an instance with a different metaclass.");
    }

    private static boolean samePackageableElement(Object a, Object b, org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (a == b)
        {
            return true;
        }
        String pathA = _PackageableElement.path(a, resolver);
        String pathB = _PackageableElement.path(b, resolver);
        return pathA != null && pathA.equals(pathB);
    }

    private static final int SLOT_GENERAL = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("general");
    private static final int SLOT_GENERALIZATIONS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("generalizations");

    /**
     * Walk {@code sub}'s generalization chain (dereferencing pointer types
     * along the way) and return true when {@code sup} is reached by path
     * comparison. Bounded by a max depth so a pathological cycle in the
     * graph terminates cleanly rather than spinning.
     */
    private static boolean isSubtypeViaGeneralizations(Object sub, Object sup, org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (samePackageableElement(sub, sup, resolver))
        {
            return true;
        }
        Object current = sub;
        for (int depth = 0; depth < 32 && current != null; depth++)
        {
            // Pointer → live before reading .generalizations (pointers carry only .path)
            String ptrPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.pointerPath(current);
            if (ptrPath != null && resolver != null)
            {
                Object live = resolver.getElement(ptrPath);
                if (live != null) current = live;
            }
            Object gens = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(current, SLOT_GENERALIZATIONS);
            if (!(gens instanceof PureSequence seq) || seq.size() == 0) return false;
            Object next = null;
            for (int i = 0; i < seq.size(); i++)
            {
                Object g = seq.getBoxed(i);
                Object generalGT = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(g, SLOT_GENERAL);
                Object parent = generalGT != null ? _GenericType.type(generalGT) : null;
                if (parent == null) continue;
                if (samePackageableElement(parent, sup, resolver)) return true;
                if (next == null) next = parent;
            }
            current = next;
        }
        return false;
    }

    /**
     * For a parser-built classifier UDGT, prefer the canonical
     * {@code GenericType_<TypeName>} anchor from core.pdb when one exists and
     * the UDGT has no type/multiplicity arguments. Mirrors the bootstrap
     * MetaNatives.preferCanonicalAnchor — keeps {@code ^Type(...)} classifier
     * chains identical between Pure runtime construction and Java's
     * {@code new XxxImpl(model)} ctor pattern.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object preferCanonicalAnchor(
            Object gtv,
            org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        PureSequence typeArgs = _GenericType.typeArguments(gtv);
        if (typeArgs != null && typeArgs.size() > 0)
        {
            return gtv;
        }
        Object mulArgs = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(gtv, SLOT_MULTIPLICITY_ARGUMENTS);
        if (mulArgs instanceof PureSequence ms && ms.size() > 0)
        {
            return gtv;
        }
        Object rawType = _GenericType.type(gtv);
        if (rawType == null)
        {
            return gtv;
        }
        // Cache by Type identity — avoids string concatenation per call
        // and amortises the resolver lookup across `^X(...)` operations.
        // ABSENT_GTV sentinel caches "no canonical anchor for this type"
        // so repeated lookups for non-anchored types stay O(1) too.
        Object cached = CANONICAL_ANCHOR_CACHE.get(rawType);
        if (cached == ABSENT_GTV)
        {
            return gtv;
        }
        if (cached != null)
        {
            return cached;
        }
        // The canonical anchor key encodes the type's FULL Pure path so user
        // modules whose simple names collide with platform types (e.g. a user
        // module declaring its own `Package` class) don't pick up the wrong
        // anchor and end up classified as the platform type. The pre-built
        // anchors in core.pdb live at
        //   meta::pure::metamodel::type::generics::optimization::GenericType_<path>
        // with `::` in `<path>` replaced by `_`. Only types that have such
        // an anchor (most Pure-platform classes; none from user modules) ever
        // resolve to a non-null canonical here.
        String typePath = _PackageableElement.path(rawType, resolver);
        if (typePath == null || typePath.isEmpty())
        {
            CANONICAL_ANCHOR_CACHE.put(rawType, ABSENT_GTV);
            return gtv;
        }
        String canonicalKey = "meta::pure::metamodel::type::generics::optimization::GenericType_"
                + typePath.replace("::", "_");
        Object canonical = resolver.getElement(canonicalKey);
        // Treat any non-null resolved element as the canonical anchor — the
        // typed `instanceof GenericTypeValue` guard fails post-loader-flip
        // because resolver.getElement now returns PureDynamicObject. The
        // path is curated (only GenericTypeValue elements live under the
        // optimization::GenericType_ prefix), so the type check was always
        // a belt-and-braces.
        if (canonical != null)
        {
            CANONICAL_ANCHOR_CACHE.put(rawType, canonical);
            return canonical;
        }
        CANONICAL_ANCHOR_CACHE.put(rawType, ABSENT_GTV);
        return gtv;
    }

    /**
     * Per-Type cache of canonical anchor lookups. Synchronised because
     * IdentityHashMap is not thread-safe; reads dominate writes after warmup
     * so the lock contention is negligible. Identity-keyed because PDB Type
     * classes have structural equals that recurse through generalizations.
     */
    private static final Object ABSENT_GTV = new Object();
    private static final java.util.Map<Object, Object> CANONICAL_ANCHOR_CACHE =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /**
     * After constructing an instance, set reverse association pointers.
     * If person.firm = firmX, then firmX.employees should include person.
     */
    static void setReverseAssociationPointers(Object instance, String classPath,
                                              java.util.List<java.util.Map.Entry<String, Object>> keyValues,
                                              org.finos.legend.pure.truffle.PureContext eval,
                                              org.finos.legend.pure.truffle.ast.PropertyReadNode reader,
                                              org.finos.legend.pure.truffle.ast.PropertyWriteNode writer)
    {
        if (keyValues.isEmpty())
        {
            return;
        }
        for (java.util.Map.Entry<String, Object> kv : keyValues)
        {
            String propName = kv.getKey();
            Object propValue = kv.getValue();
            if (propValue == null || (propValue instanceof org.finos.legend.pure.truffle.types.PureSequence ps2 && ps2.isEmpty()))
            {
                continue;
            }

            String reversePropName = findReverseAssociationProperty(propName, classPath, eval.resolver());
            if (reversePropName != null)
            {
                appendToProperty(propValue, reversePropName, instance, reader, writer);
            }
        }
    }

    /**
     * Look up the reverse association property name for {@code propName} when
     * setting it on an instance of {@code classPath}. Returns the other-side
     * property name (e.g. {@code "employees"} when binding {@code firm} on a
     * {@code Person}), or {@code null} if no association involves this name.
     *
     * <p>Delegates the index to the resolver — see
     * {@link org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry#reverseAssociationCandidates}.
     * Pre-existing static cache on this class was invalid as soon as a JVM
     * saw more than one resolver (e.g. a surefire fork where one test
     * class loaded core+core-tests and another loaded only core), so the
     * cache moved onto the registry and is invalidated on register/unregister.</p>
     */
    static String findReverseAssociationProperty(String propName, String classPath,
                                                          org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (!(resolver instanceof org.finos.legend.pure.truffle.runtime.TruffleModuleRegistry registry))
        {
            throw new IllegalStateException(
                    "Reverse-association lookup requires a TruffleModuleRegistry resolver, got "
                            + (resolver == null ? "null" : resolver.getClass().getName())
                            + ". The reverse-association index lives on the registry so it can be"
                            + " invalidated on register/unregister; bare TruffleMetadataAccess impls"
                            + " can't host that lifecycle.");
        }
        java.util.List<String[]> candidates = registry.reverseAssociationCandidates(propName);
        if (candidates.isEmpty())
        {
            return null;
        }

        for (String[] pair : candidates)
        {
            String otherPropName = pair[0];
            String targetPath = pair[1];
            if (classPath.equals(targetPath))
            {
                return otherPropName;
            }
            // Check subtype
            Object classElement = resolver.getElement(classPath);
            if (classElement != null)
            {
                var mro = org.finos.legend.pure.truffle.runtime.helper._Type.linearize(classElement, resolver);
                for (var ancestor : mro)
                {
                    if (ancestor != null
                            && targetPath.equals(org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(ancestor, resolver)))
                    {
                        return otherPropName;
                    }
                }
            }
        }
        return null;
    }

    // @TruffleBoundary — walks target.getClass().getMethods() to detect
    // multi-valued setter shape. Class.getMethodsRecursive PE was inlining
    // 990 deep. No frame here, so the boundary is safe.
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static void appendToProperty(Object target, String propName, Object value,
                                          org.finos.legend.pure.truffle.ast.PropertyReadNode reader,
                                          org.finos.legend.pure.truffle.ast.PropertyWriteNode writer)
    {
        if (target instanceof java.util.List<?> targets)
        {
            for (Object t : targets)
            {
                appendToProperty(t, propName, value, reader, writer);
            }
            return;
        }
        if (target instanceof PureSequence seq)
        {
            for (int i = 0; i < seq.size(); i++)
            {
                appendToProperty(seq.getBoxed(i), propName, value, reader, writer);
            }
            return;
        }
        // Determine if the property is multi-valued. The PDO's
        // PropertyMetadataRegistry holds per-property Java types populated
        // either by an XPDBHelper static{} block (for codegen'd metamodel
        // classes) or by `ensurePopulated` walking the Class element (for
        // user/compiler/protocol/functions classes). The previous reflective
        // fallback over `target.getClass().getMethods()` covered typed
        // XPDBHelpers — post-PDO-flip no caller produces one.
        try
        {
            boolean isMulti = false;
            if (target instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo)
            {
                Class<?> paramType = pdo.classInfo.propTypes().get(propName);
                if (paramType != null
                        && (org.finos.legend.pure.truffle.types.PureSequence.class.isAssignableFrom(paramType)
                                || org.eclipse.collections.api.RichIterable.class.isAssignableFrom(paramType)
                                || java.util.Collection.class.isAssignableFrom(paramType)))
                {
                    isMulti = true;
                }
            }

            if (!isMulti)
            {
                // [0..1] or [1] property — replace
                writer.execute(target, propName, value);
            }
            else
            {
                // [*] or [1..*] property — append to existing
                Object current = reader.execute(target, propName);
                boolean isEmpty = current == null
                        || (current instanceof org.finos.legend.pure.truffle.types.PureSequence ps3 && ps3.isEmpty())
                        || (current instanceof PureSequence seq && seq.isEmpty());
                if (isEmpty)
                {
                    writer.execute(target, propName, value);
                }
                else if (current instanceof PureSequence seq)
                {
                    Object[] items = new Object[seq.size() + 1];
                    for (int j = 0; j < seq.size(); j++)
                    {
                        items[j] = seq.getBoxed(j);
                    }
                    items[seq.size()] = value;
                    writer.execute(target, propName, new org.finos.legend.pure.truffle.types.ObjectSequence(items));
                }
                else if (current instanceof org.eclipse.collections.api.list.MutableList<?> list)
                {
                    @SuppressWarnings("unchecked")
                    org.eclipse.collections.api.list.MutableList<Object> mlist =
                            (org.eclipse.collections.api.list.MutableList<Object>) list;
                    mlist.add(value);
                }
                else
                {
                    writer.execute(target, propName,
                            new org.finos.legend.pure.truffle.types.ObjectSequence(new Object[]{current, value}));
                }
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to set reverse association property '" + propName + "' on " + target.getClass().getName(), e);
        }
    }

}
