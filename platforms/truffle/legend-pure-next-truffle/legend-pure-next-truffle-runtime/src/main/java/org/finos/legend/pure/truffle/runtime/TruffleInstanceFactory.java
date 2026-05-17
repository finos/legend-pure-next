package org.finos.legend.pure.truffle.runtime;

/**
 * Creates truffle-namespaced PDBHelper instances from Pure class paths.
 */
public final class TruffleInstanceFactory
{
    private static final String TRUFFLE_PREFIX = "org.finos.legend.pure.truffle.pdb.";

    private static final java.util.Set<String> JAVA_KEYWORDS = java.util.Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while");

    private TruffleInstanceFactory() {}

    private static String escapeJavaKeywords(String dottedPath)
    {
        String[] parts = dottedPath.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++)
        {
            if (i > 0) sb.append('.');
            sb.append(JAVA_KEYWORDS.contains(parts[i]) ? parts[i] + "_" : parts[i]);
        }
        return sb.toString();
    }

    /**
     * Create an instance of the truffle PDBHelper class for the given Pure class path.
     * e.g. "meta::pure::functions::collection::tests::fold::FO_Person" →
     *       org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.tests.fold.FO_PersonPDBHelper
     *
     * <p>Cached path: pass the resolver so {@link TruffleTypeCache#classForPath}
     * memoises {@code Class.forName} (the dominant {@code String.replace} hot
     * caller before this overload existed).</p>
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object createInstance(String classPath, TruffleMetadataAccess resolver)
    {
        return new org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject(
                org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.classInfoFor(classPath, resolver),
                /*fb=*/ null,
                resolver,
                /*parent=*/ null);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object createInstance(String classPath)
    {
        return new org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject(
                org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.classInfoFor(classPath, null),
                /*fb=*/ null,
                /*resolver=*/ null,
                /*parent=*/ null);
    }

    /**
     * Resolve a Pure class path to its runtime Java {@code PDBHelper} class.
     * Public entry point for {@link TruffleTypeCache} to use as the cache
     * compute function.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Class<?> resolveClass(String classPath)
    {
        // Strip leading :: separators and the truffle prefix if already present
        String stripped = classPath.replaceAll("^:+", "");
        if (stripped.startsWith("org.finos.legend.pure.truffle.pdb.") || stripped.startsWith("org::finos::legend::pure::truffle::pdb::"))
        {
            stripped = stripped.replace("org::finos::legend::pure::truffle::pdb::", "")
                    .replace("org.finos.legend.pure.truffle.pdb.", "");
        }
        String javaClassName = TRUFFLE_PREFIX + escapeJavaKeywords(stripped.replace("::", ".")) + "PDBHelper";
        try
        {
            return Class.forName(javaClassName);
        }
        catch (ClassNotFoundException e)
        {
            // Fallback: try without truffle prefix (for bootstrap-generated classes still on classpath)
            String fallback = stripped.replace("::", ".") + "PDBHelper";
            try
            {
                return Class.forName(fallback);
            }
            catch (ClassNotFoundException e2)
            {
                throw new RuntimeException("No PDBHelper class found for: " + classPath
                        + " (tried " + javaClassName + " and " + fallback + ")", e2);
            }
        }
    }
}
