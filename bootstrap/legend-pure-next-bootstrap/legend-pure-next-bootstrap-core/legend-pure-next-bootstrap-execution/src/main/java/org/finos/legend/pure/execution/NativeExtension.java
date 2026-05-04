package org.finos.legend.pure.execution;

import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.m3.module.MetadataAccess;
import java.util.Map;

/**
 * Service provider interface for extending the execution engine with external native functions.
 */
public interface NativeExtension
{
    void register(Map<String, NativeImpl> natives, Map<String, LazyNativeImpl> lazyNatives, MetadataAccess resolver);
}
