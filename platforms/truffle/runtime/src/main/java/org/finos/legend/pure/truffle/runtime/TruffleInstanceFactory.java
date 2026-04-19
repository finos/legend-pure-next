package org.finos.legend.pure.truffle.runtime;

/**
 * Creates truffle-namespaced Impl instances from Pure class paths.
 */
public final class TruffleInstanceFactory
{
    private static final String TRUFFLE_PREFIX = "org.finos.legend.pure.truffle.pdb.";

    private TruffleInstanceFactory() {}

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
        String javaClassName = TRUFFLE_PREFIX + classPath.replace("::", ".") + "Impl";
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
                // Last resort: DynamicInstance for classes without generated Impls
                return new org.finos.legend.pure.execution.DynamicInstance(classPath);
            }
        }
    }
}
