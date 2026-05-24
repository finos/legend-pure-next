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
import org.finos.legend.pure.truffle.runtime.helper._GenericType;
import org.finos.legend.pure.truffle.runtime.helper._PackageableElement;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * {@code copy(T[1], KeyExpression[*]) : T[1]}
 * -- lazy native: deep copy + construction stack + key overrides.
 * Delegates to StandaloneEvaluator for the complex copy logic.
 */
@NodeInfo(shortName = "copyWithKeys")
public final class CopyWithKeysNode extends PureNode
{

    private static final int SLOT_CLASSIFIER_GENERIC_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("classifierGenericType");
    private static final int SLOT_DEFAULT_VALUE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("defaultValue");
    private static final int SLOT_EXPRESSION_SEQUENCE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("expressionSequence");
    private static final int SLOT_GENERAL = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("general");
    private static final int SLOT_GENERALIZATIONS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("generalizations");
    private static final int SLOT_MULTIPLICITY_ARGUMENTS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("multiplicityArguments");
    private static final int SLOT_MULTIPLICITY_PARAMETERS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("multiplicityParameters");
    private static final int SLOT_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    private static final int SLOT_OWNER = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("owner");
    private static final int SLOT_PROPERTIES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("properties");
    private static final int SLOT_PROPERTIES_FROM_ASSOCIATIONS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("propertiesFromAssociations");
    private static final int SLOT_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("type");
    private static final int SLOT_TYPE_PARAMETERS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("typeParameters");
    private static final int SLOT_VALUE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("value");
    @CompilationFinal
    private final String signature;

    @Child
    private PureNode sourceNode;

    @Children
    private org.finos.legend.pure.truffle.ast.PropertyAssignNode[] assignments;

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyReadNode deepReader = new org.finos.legend.pure.truffle.ast.PropertyReadNode();

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyWriteNode deepWriter = new org.finos.legend.pure.truffle.ast.PropertyWriteNode();

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyReadNode topPropReader = new org.finos.legend.pure.truffle.ast.PropertyReadNode();

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyReadNode subCopyReader = new org.finos.legend.pure.truffle.ast.PropertyReadNode();

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyReadNode appendReader = new org.finos.legend.pure.truffle.ast.PropertyReadNode();

    @Child
    private org.finos.legend.pure.truffle.ast.PropertyWriteNode appendWriter = new org.finos.legend.pure.truffle.ast.PropertyWriteNode();

    public CopyWithKeysNode(String signature, PureNode sourceNode, org.finos.legend.pure.truffle.ast.PropertyAssignNode[] assignments)
    {
        this.signature = signature;
        this.sourceNode = sourceNode;
        this.assignments = assignments;
    }

    @Override
    @com.oracle.truffle.api.nodes.ExplodeLoop
    public Object executeGeneric(VirtualFrame frame)
    {
        return invoke(frame);
    }

    /**
     * Memoise per Class — `getInterfaces()` is reflection, `getName()` walks
     * the constant pool, and `replace(".", "::")` is a String allocation;
     * the result is purely a function of the input class so we cache it.
     * 72 of 72 StringLatin1.replace JFR samples on the metamodel_factories
     * compile traced back to this method's String.replace call.
     *
     * Identity-keyed because Class equality is identity. Synchronised so
     * concurrent compile threads don't race on the put; the read path is
     * a plain {@code get} and dominates by orders of magnitude.
     */
    /**
     * Resolve the Pure path of a copy source. PDOs carry it in the Shape's
     * dynamicType; the legacy non-PDO {@link PropertyAccessor} fallback walks
     * {@code Class.getInterfaces()[0]} (only reached for hand-written collection
     * types like {@link org.finos.legend.pure.truffle.ast.natives.collection.MapImpl}
     * — never for Pure metamodel instances post-PDO-migration).
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String classPathFromInstance(Object instance)
    {
        if (instance instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo)
        {
            return pdo.classInfo.purePath;
        }
        Class<?>[] interfaces = instance.getClass().getInterfaces();
        return interfaces.length > 0 ? interfaces[0].getName().replace(".", "::") : null;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String headBeforeDot(String s)
    {
        int idx = s.indexOf('.');
        return idx < 0 ? s : s.substring(0, idx);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object finalizeCopy(Object copy, String classPath,
                                java.util.List<java.util.Map.Entry<String, Object>> keyValues,
                                org.finos.legend.pure.truffle.PureContext eval)
    {
        // Set reverse association pointers (bidirectional binding)
        // For deep property paths, add the top-level property to enable reverse binding
        java.util.Set<String> topLevelDeepProps = new java.util.LinkedHashSet<>();
        for (java.util.Map.Entry<String, Object> kv : keyValues)
        {
            if (kv.getKey().contains("."))
            {
                topLevelDeepProps.add(headBeforeDot(kv.getKey()));
            }
        }
        for (String topProp : topLevelDeepProps)
        {
            boolean alreadyPresent = keyValues.stream().anyMatch(kv -> topProp.equals(kv.getKey()));
            if (!alreadyPresent)
            {
                Object topValue = topPropReader.execute(copy, topProp);
                if (topValue != null && !(topValue instanceof org.finos.legend.pure.truffle.types.PureSequence ps2 && ps2.isEmpty()))
                {
                    keyValues.add(java.util.Map.entry(topProp, topValue));
                }
            }
        }
        java.util.Set<String> keyPropNames = new java.util.HashSet<>();
        for (var kv : keyValues) keyPropNames.add(kv.getKey());
        addCopiedAssociationProps(copy, classPath, keyPropNames, keyValues, eval);

        // Immutability check (mirrors Java copy native): every assoc-prop
        // value on the copy must have been instantiated within this copy
        // expression. Runs after deep-property paths have been resolved
        // (and deep-copied intermediates registered as fresh).
        NewWithKeysNode.verifyAssocPropsAreFresh(classPath, keyValues, eval);

        NewWithKeysNode.setReverseAssociationPointers(copy, classPath, keyValues, eval, appendReader, appendWriter);

        for (String topProp : topLevelDeepProps)
        {
            Object subCopy = subCopyReader.execute(copy, topProp);
            Object subCgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(subCopy, SLOT_CLASSIFIER_GENERIC_TYPE);
            if (subCgt != null)
            {
                var subType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(subCgt);
                if (subType != null)
                {
                    String subPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(subType, eval.resolver());
                    java.util.List<java.util.Map.Entry<String, Object>> subKvs = new java.util.ArrayList<>();
                    addCopiedAssociationProps(subCopy, subPath, new java.util.HashSet<>(), subKvs, eval);
                    NewWithKeysNode.setReverseAssociationPointers(subCopy, subPath, subKvs, eval, appendReader, appendWriter);
                }
            }
        }
        return copy;
    }

    @com.oracle.truffle.api.nodes.ExplodeLoop
    private Object invoke(VirtualFrame frame)
    {
        org.finos.legend.pure.truffle.PureContext eval = getContext();
        // Open fresh-instance scope (mirror Java MetaNatives copy lazy
        // native). Inner new/copy results register here; the verify in
        // finalizeCopy enforces that assoc-prop values originated within
        // this expression.
        eval.pushFreshScope();
        Object copyResult = null;
        try
        {

        // Step 1: Evaluate the source object (first arg) — pre-compiled as child node
        Object original = sourceNode.executeGeneric(frame);

        if (original == null)
        {
            throw new RuntimeException("Cannot copy: null");
        }
        // Every Pure metamodel value is an Any. Single PureObj-driven path
        // works for both legacy XPDBHelper and the future PureDynamicObject.
        Object cgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(original, SLOT_CLASSIFIER_GENERIC_TYPE);
        String classPath = classPathFromInstance(original);
        if ((classPath == null || classPath.isEmpty()) && cgt != null)
        {
            classPath = resolveClassPathFromCGT(cgt, eval.resolver());
        }

        // Post-flip: original is always a PureDynamicObject. The typed
        // `Any._copy()` fast path was a relic of the pre-flip world; no
        // user-visible value is a typed XPDBHelper anymore.
        Object copy = org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory.createInstance(classPath, getResolver());
        shallowCopyProperties(original, copy, cgt);
        // No self-reference rewriting — copy is a literal shallow copy. Any slot
        // that the user wants pointing at the copy (classifierGenericType,
        // Property.owner, TypeParameter.owner, …) must be set explicitly via
        // `~.~` in the copy expression. Letting the runtime do it implicitly
        // would silently change graph identity in surprising ways.
        Object copyCgt = cgt;
        // Platform-level canonical anchor: preserve canonical GenericType_<TypeName>
        // UDPGT-PE references through copy operations. Symmetric to NewWithKeysNode's
        // preferCanonicalAnchor — without this, copies of canonical-classified
        // values can lose their canonical reference if any earlier step diverged.
        if (copyCgt != null)
        {
            copyCgt = NewWithKeysNode.preferCanonicalAnchor(copyCgt, eval.resolver());
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(copy, "classifierGenericType", copyCgt);
        }

        // Step 3: Push onto construction stack, evaluate and set key properties
        var ctx = org.finos.legend.pure.truffle.PureLanguage.get(this);
        ctx.pushConstruction(copy);
        try
        {
            java.util.List<java.util.Map.Entry<String, Object>> keyValues = new java.util.ArrayList<>();
            for (int i = 0; i < assignments.length; i++)
            {
                String propName = assignments[i].propertyName();
                if (propName.contains("."))
                {
                    // Deep property path: evaluate value, handle add, then navigate and copy sub-objects
                    Object propValue = assignments[i].execute(frame, copy);
                    setDeepProperty(copy, propName, propValue, deepReader, deepWriter);
                    if (propValue != null)
                    {
                        keyValues.add(java.util.Map.entry(propName, propValue));
                    }
                }
                else
                {
                    Object propValue = assignments[i].execute(frame, copy);
                    if (propValue != null)
                    {
                        keyValues.add(java.util.Map.entry(propName, propValue));
                    }
                }
            }

            copyResult = copy;
            return finalizeCopy(copy, classPath, keyValues, eval);
        }
        finally
        {
            ctx.popConstruction();
        }
        }
        finally
        {
            eval.popFreshScopeAndRegister(copyResult);
        }
    }

    /**
     * Resolve a class path from a truffle GenericTypeValue.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String resolveClassPathFromCGT(Object cgt, org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        Object rawType = _GenericType.type(cgt);
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
     * Shallow-copy all properties from source to target via reflection.
     * Copies all _xxx() getter values to the corresponding _xxx(value) setter.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void shallowCopyProperties(Object source, Object target, Object cgt)
    {
        // Use the truffle type to gather property names, then copy via reflection
        Object sourceType = cgt != null ? _GenericType.type(cgt) : null;
        if (sourceType == null)
        {
            // Fallback: try interface methods directly
            copyViaReflection(source, target);
            return;
        }
        // Use interface method scanning — PDB metadata may not include association properties
        copyViaReflection(source, target);
    }

    /**
     * Collect all property names from a truffle type and its supertypes.
     * Cached by type identity — types are singletons per resolver, and the
     * walk is a pure function of the type's structure, so re-walking on
     * every copy was wasted work.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static java.util.List<String> collectAllPropertyNames(Object type, org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        java.util.List<String> cached = PROPERTY_NAMES_CACHE.get(type);
        if (cached != null)
        {
            return cached;
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        collectPropertyNamesRecursive(type, names, seen, visited, resolver);
        java.util.List<String> result = java.util.List.copyOf(names);
        synchronized (PROPERTY_NAMES_CACHE_LOCK)
        {
            if (!PROPERTY_NAMES_CACHE.containsKey(type))
            {
                java.util.IdentityHashMap<Object, java.util.List<String>> next =
                        new java.util.IdentityHashMap<>(PROPERTY_NAMES_CACHE);
                next.put(type, result);
                PROPERTY_NAMES_CACHE = next;
            }
        }
        return result;
    }

    /**
     * Volatile copy-on-write IdentityHashMap. Reads (cache hits) are
     * unsynchronized; writes copy the map under PROPERTY_NAMES_CACHE_LOCK.
     * Identity-keyed because types are singletons per resolver and
     * structural equals on generated metamodel types can recurse through
     * cyclic generalisations and blow the stack.
     */
    private static volatile java.util.IdentityHashMap<Object, java.util.List<String>> PROPERTY_NAMES_CACHE =
            new java.util.IdentityHashMap<>();
    private static final Object PROPERTY_NAMES_CACHE_LOCK = new Object();

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void collectPropertyNamesRecursive(Object type, java.util.List<String> names,
                                                       java.util.Set<String> seen,
                                                       java.util.Set<String> visited,
                                                       org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        String typeId = type != null
                ? _PackageableElement.path(type, resolver)
                : null;
        if (typeId == null) typeId = String.valueOf(System.identityHashCode(type));
        if (!visited.add(typeId))
        {
            return;
        }
        Object propsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(type, SLOT_PROPERTIES);
        if (propsObj instanceof PureSequence ps) addPropertyNamesFromSeq(ps, names, seen);
        Object pfaObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(type, SLOT_PROPERTIES_FROM_ASSOCIATIONS);
        if (pfaObj instanceof PureSequence pfa) addPropertyNamesFromSeq(pfa, names, seen);
        Object gens = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(type, SLOT_GENERALIZATIONS);
        if (gens instanceof PureSequence seq)
        {
            for (Object gen : seq.toBoxedArray())
            {
                Object general = gen != null
                        ? org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(gen, SLOT_GENERAL) : null;
                if (general != null)
                {
                    Object superType = _GenericType.type(general);
                    if (superType != null)
                    {
                        collectPropertyNamesRecursive(superType, names, seen, visited, resolver);
                    }
                }
            }
        }
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void addPropertyNamesFromSeq(PureSequence seq, java.util.List<String> names, java.util.Set<String> seen)
    {
        if (seq == null)
        {
            return;
        }
        for (Object prop : seq.toBoxedArray())
        {
            if (prop == null) continue;
            Object n = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(prop, SLOT_NAME);
            if (n instanceof String name && seen.add(name))
            {
                names.add(name);
            }
        }
    }

    /**
     * Shallow-copy all materialised properties from source to target.
     * Post-PDO-flip the source is always a {@link
     * org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject} and
     * the target is a {@link
     * org.finos.legend.pure.truffle.runtime.PropertyAccessor} (i.e. another
     * PDO). The previous typed-reflection fallback ({@code
     * source.getClass().getInterfaces()[0].getMethods()}) was a relic of
     * the typed-XPDBHelper era — no live caller produces a non-PDO source.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void copyViaReflection(Object source, Object target)
    {
        if (!(source instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdoSrc)
                || !(target instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdoTgt))
        {
            return;
        }
        // Source and target have identical classInfo (copy preserves class),
        // so slot indices line up. Iterate the raw slots array directly to
        // skip the per-name {@code slotByName.get} + {@code writeProperty}
        // coercion lookup. Materialise lazy FB entries via {@code readSlot}
        // only when we hit the LAZY sentinel; everything else is a direct
        // array load + store. Coercion is unnecessary because the value
        // came from a slot of the same class.
        Object[] srcSlots = pdoSrc.slots;
        Object[] tgtSlots = pdoTgt.slots;
        int n = Math.min(srcSlots.length, tgtSlots.length);
        for (int i = 0; i < n; i++)
        {
            Object v = srcSlots[i];
            if (v == null) continue;
            if (v == org.finos.legend.pure.truffle.runtime.dynobj.PureFbDecoder.LAZY)
            {
                v = pdoSrc.readSlot(i);
                if (v == null) continue;
            }
            tgtSlots[i] = v;
        }
    }

    /**
     * Fix self-referential classifierGenericType during copy.
     * If the original's CGT has a typeArgument whose rawType IS the original instance itself
     * (e.g., Class&lt;self&gt;), create a new CGT with the typeArgument pointing to the copy.
     * Returns the updated CGT, or the original if no self-reference was found.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object fixSelfReferentialCGT(Object cgt, Object original, Object copy,
                                                          org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        if (cgt == null)
        {
            return null;
        }
        PureSequence typeArgs = _GenericType.typeArguments(cgt);
        if (typeArgs != null && typeArgs.size() > 0)
        {
            boolean hasSelfRef = false;
            for (Object arg : typeArgs.toBoxedArray())
            {
                // _GenericType.type is Object-tolerant — handles both typed
                // GenericTypeValue and PDO uniformly.
                if (arg != null)
                {
                    Object argType = _GenericType.type(arg);
                    if (argType != null && argType == original)
                    {
                        hasSelfRef = true;
                        break;
                    }
                }
            }
            if (hasSelfRef)
            {
                Object copyType = copy;
                // Build a new CGT with updated self-references
                Object cgtType = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(cgt, SLOT_TYPE);
                var newCgt = _GenericType.buildUserDefinedGenericType(cgtType, resolver);
                org.finos.legend.pure.truffle.types.ObjectSequence newArgs =
                        new org.finos.legend.pure.truffle.types.ObjectSequence(
                                java.util.Arrays.stream(typeArgs.toBoxedArray())
                                        .map(arg ->
                                        {
                                            // _GenericType.type / .typeArguments work on PDO too.
                                            if (arg != null)
                                            {
                                                Object argType = _GenericType.type(arg);
                                                if (argType != null && argType == original)
                                                {
                                                    var selfRef = _GenericType.buildUserDefinedGenericType(copyType, resolver);
                                                    // Carry over inner type arguments (e.g., TypeParameter GTs)
                                                    PureSequence innerTA = _GenericType.typeArguments(arg);
                                                    if (innerTA != null && !innerTA.isEmpty())
                                                    {
                                                        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(selfRef, "typeArguments", innerTA);
                                                    }
                                                    Object innerMAObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(arg, SLOT_MULTIPLICITY_ARGUMENTS);
                                                    if (innerMAObj instanceof PureSequence innerMA && !innerMA.isEmpty())
                                                    {
                                                        org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(selfRef, "multiplicityArguments", innerMA);
                                                    }
                                                    return (Object) selfRef;
                                                }
                                            }
                                            return arg;
                                        })
                                        .toArray());
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(newCgt, "typeArguments", newArgs);
                Object mulArgsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(cgt, SLOT_MULTIPLICITY_ARGUMENTS);
                if (mulArgsObj != null)
                {
                    org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(newCgt, "multiplicityArguments", mulArgsObj);
                }
                return newCgt;
            }
        }
        return cgt;
    }

    /**
     * Handle dotted property paths like "address.name" or "firm.employees".
     * Navigates to each sub-object, creating copies as needed, and sets the leaf property.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void setDeepProperty(Object root, String dottedPath, Object value,
                                         org.finos.legend.pure.truffle.ast.PropertyReadNode reader,
                                         org.finos.legend.pure.truffle.ast.PropertyWriteNode writer)
    {
        String[] parts = dottedPath.split("\\.");
        Object current = root;
        // Navigate to the parent of the leaf, copying sub-objects along the way
        for (int i = 0; i < parts.length - 1; i++)
        {
            Object child = reader.execute(current, parts[i]);
            if (child == null || (child instanceof org.finos.legend.pure.truffle.types.PureSequence ps3 && ps3.isEmpty()))
            {
                return; // Sub-object doesn't exist — nothing to set
            }
            // Copy the sub-object so we don't mutate the original. All Pure
            // values are PDOs post-flip; the typed Any._copy() fast path was
            // removed when FB-decode stopped handing back typed XPDBHelpers.
            Object childCopy = (child instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject)
                    ? CopySimpleNode.pdoCopyPublic(child) : null;
            if (childCopy != null)
            {
                Object cgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(child, SLOT_CLASSIFIER_GENERIC_TYPE);
                if (cgt != null)
                {
                    org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(childCopy, "classifierGenericType", cgt);
                }
                writer.execute(current, parts[i], childCopy);
                current = childCopy;
            }
            else
            {
                current = child;
            }
        }
        // Set the leaf property
        writer.execute(current, parts[parts.length - 1], value);
    }

    /**
     * Add elements from {@code value} to {@code target} list, preserving
     * original types (no AtomicValue unwrapping). Handles empty sequence (skip),
     * PureSequence (flatten), MutableList (flatten), and scalar (add as-is).
     */
    /**
     * After shallow copy, find all association properties on the copy that have non-null values
     * and add them to keyValues for reverse pointer binding.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void addCopiedAssociationProps(Object copy, String classPath,
                                                   java.util.Set<String> existingKeys,
                                                   java.util.List<java.util.Map.Entry<String, Object>> keyValues,
                                                   org.finos.legend.pure.truffle.PureContext eval)
    {
        // Walk property names via the type metadata + PropertyAccessor —
        // mirrors the {@code copyViaReflection} → {@code _copy()} migration
        // we did for the property-copy hot path. Removes the last
        // {@code Class.getMethods()} call in the copy chain (~21 samples in
        // JFR after the {@code _copy} fix).
        if (!(copy instanceof org.finos.legend.pure.truffle.runtime.PropertyAccessor pa))
        {
            return;
        }
        Object cgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(copy, SLOT_CLASSIFIER_GENERIC_TYPE);
        if (cgt == null)
        {
            return;
        }
        var type = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(cgt);
        if (type == null)
        {
            return;
        }
        // Pre-filter the loop: most types have 0-2 properties with a reverse
        // association, but the original loop iterated ALL properties (often
        // 20+) and called findReverseAssociationProperty per-property. The
        // result is purely a function of (type, classPath) — cache the
        // pre-filtered list once per type.
        java.util.List<String> assocProps = associationPropsForType(type, classPath, eval.resolver());
        for (String propName : assocProps)
        {
            if (existingKeys.contains(propName))
            {
                continue;
            }
            Object propValue = pa.readProperty(propName);
            if (propValue == null
                    || propValue == org.finos.legend.pure.truffle.runtime.PropertyAccessor.ABSENT
                    || (propValue instanceof PureSequence seq && seq.isEmpty()))
            {
                continue;
            }
            keyValues.add(java.util.Map.entry(propName, propValue));
        }
    }

    /**
     * Cache of "properties of {@code type} that have a reverse association
     * targeting {@code classPath}" — the only ones {@link #addCopiedAssociationProps}
     * actually emits. Pre-computed once per type, makes the per-copy loop
     * iterate only the association-relevant properties (typically 0-2 instead
     * of 20+) and skips the per-property findReverseAssociationProperty call
     * + framework-name string-equals chain.
     *
     * <p>Identity-keyed on Type. Volatile copy-on-write for unsynchronized
     * reads — same pattern as the other static caches in this module.</p>
     */
    private static volatile java.util.IdentityHashMap<Object, java.util.List<String>> ASSOC_PROPS_CACHE =
            new java.util.IdentityHashMap<>();
    private static final Object ASSOC_PROPS_CACHE_LOCK = new Object();

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static java.util.List<String> associationPropsForType(Object type, String classPath,
                                                                   org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        java.util.List<String> cached = ASSOC_PROPS_CACHE.get(type);
        if (cached != null)
        {
            return cached;
        }
        java.util.List<String> propertyNames = collectAllPropertyNames(type, resolver);
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String propName : propertyNames)
        {
            if ("classifierGenericType".equals(propName)
                    || "sourceInformation".equals(propName)
                    || "elementOverride".equals(propName))
            {
                continue;
            }
            String reversePropName = NewWithKeysNode.findReverseAssociationProperty(propName, classPath, resolver);
            if (reversePropName != null)
            {
                result.add(propName);
            }
        }
        java.util.List<String> immut = java.util.List.copyOf(result);
        synchronized (ASSOC_PROPS_CACHE_LOCK)
        {
            if (!ASSOC_PROPS_CACHE.containsKey(type))
            {
                java.util.IdentityHashMap<Object, java.util.List<String>> next =
                        new java.util.IdentityHashMap<>(ASSOC_PROPS_CACHE);
                next.put(type, immut);
                ASSOC_PROPS_CACHE = next;
            }
        }
        return immut;
    }

    /**
     * After copying a SimplePropertyOwner, update property owners and nested
     * enum value CGTs to point to the copy instead of the original.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void fixPropertyOwners(Object original, Object copy,
                                          org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess resolver)
    {
        Object propsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(copy, SLOT_PROPERTIES);
        if (!(propsObj instanceof PureSequence props))
        {
            return;
        }
        // Only build a self-referencing GT for actual Types — Associations,
        // Properties, etc. flow through this path during copy but aren't
        // valid `_type` slot values for a GenericType. Downstream the
        // `if (copyGT == null) continue;` guard at line 782 handles the null.
        // Use pureTypeIs because post loader-flip Type instances may be PDOs.
        Object copyGT = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(copy,
                "meta::pure::metamodel::type::Type", resolver)
                ? org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(copy, resolver)
                : null;

        for (int i = 0; i < props.size(); i++)
        {
            Object prop = props.getBoxed(i);
            if (prop == null) continue;
            // Fix owner reference
            if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(prop, SLOT_OWNER) == original)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(prop, "owner", copy);
            }
            // Fix enum value CGT inside defaultValue lambda
            if (copyGT == null) continue;
            Object dv = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(prop, SLOT_DEFAULT_VALUE);
            if (dv == null) continue;
            Object dvExprObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(dv, SLOT_EXPRESSION_SEQUENCE);
            if (!(dvExprObj instanceof PureSequence dvExpr) || dvExpr.isEmpty()) continue;
            Object vs = dvExpr.getBoxed(0);
            if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(vs,
                    "meta::pure::metamodel::valuespecification::AtomicValue")) continue;
            Object enumVal = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(vs, SLOT_VALUE);
            // Only PDOs carry an instance-level CGT — primitive defaults like
            // {@code Integer[1] = 1} have a Long value with no CGT to rewire.
            if (!(enumVal instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject)) continue;
            Object enumCgt = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(enumVal, SLOT_CLASSIFIER_GENERIC_TYPE);
            if (enumCgt == null) continue;
            var enumCgtType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(enumCgt);
            if (enumCgtType == original)
            {
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(enumVal, "classifierGenericType", copyGT);
            }
        }
    }

    /**
     * After copying a TypeAndMultiplicityParametersOwner (e.g. Class, Function),
     * update the owner back-reference on its TypeParameters and MultiplicityParameters
     * to point to the copy instead of the original.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void fixTypeParameterOwners(Object copy)
    {
        Object typeParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(copy, SLOT_TYPE_PARAMETERS);
        if (typeParamsObj instanceof PureSequence typeParams)
        {
            for (int i = 0; i < typeParams.size(); i++)
            {
                Object tp = typeParams.getBoxed(i);
                if (tp != null)
                {
                    org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(tp, "owner", copy);
                }
            }
        }
        Object mulParamsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(copy, SLOT_MULTIPLICITY_PARAMETERS);
        if (mulParamsObj instanceof PureSequence mulParams)
        {
            for (int i = 0; i < mulParams.size(); i++)
            {
                Object mp = mulParams.getBoxed(i);
                if (mp != null)
                {
                    org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(mp, "owner", copy);
                }
            }
        }
    }
}
