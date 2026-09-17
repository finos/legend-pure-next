package org.finos.legend.pure.execution.natives;

/**
 * Contributes natives to a {@link NativeRegistry} — the bootstrap counterpart of
 * Truffle's and JavaScript's {@code NativesExtension.registerAll(NativeRegistry)}.
 * The registry already holds the metadata resolver ({@link NativeRegistry#resolver()})
 * when extensions register.
 */
public interface NativesExtension
{
    void registerAll(NativeRegistry registry);
}
