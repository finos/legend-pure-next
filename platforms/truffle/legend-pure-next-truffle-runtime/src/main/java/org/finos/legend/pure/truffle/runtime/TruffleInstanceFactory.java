package org.finos.legend.pure.truffle.runtime;

/**
 * Creates truffle-namespaced Impl instances from Pure class paths.
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
     * Create an instance of the truffle Impl class for the given Pure class path.
     * e.g. "meta::pure::functions::collection::tests::fold::FO_Person" →
     *       org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.tests.fold.FO_PersonImpl
     */
    public static Object createInstance(String classPath)
    {
        // Strip leading :: separators and the truffle prefix if already present
        classPath = classPath.replaceAll("^:+", "");
        if (classPath.startsWith("org.finos.legend.pure.truffle.pdb.") || classPath.startsWith("org::finos::legend::pure::truffle::pdb::"))
        {
            classPath = classPath.replace("org::finos::legend::pure::truffle::pdb::", "")
                    .replace("org.finos.legend.pure.truffle.pdb.", "");
        }
        String javaClassName = TRUFFLE_PREFIX + escapeJavaKeywords(classPath.replace("::", ".")) + "Impl";
        try
        {
            Class<?> implClass = Class.forName(javaClassName);
            return implClass.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            // Fallback: try without truffle prefix (for bootstrap-generated classes still on classpath)
            try
            {
                String fallback = classPath.replace("::", ".") + "Impl";
                Class<?> implClass = Class.forName(fallback);
                return implClass.getDeclaredConstructor().newInstance();
            }
            catch (Exception e2)
            {
                throw new RuntimeException("No Impl class found for: " + classPath
                        + " (tried " + javaClassName + " and " + classPath.replace("::", ".") + "Impl)", e2);
            }
        }
    }
}
