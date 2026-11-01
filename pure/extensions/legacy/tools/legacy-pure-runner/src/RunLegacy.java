// ©2026 JP Morgan Chase & Co. All rights reserved.
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

import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.finos.legend.pure.m3.navigation.Instance;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.serialization.filesystem.repository.CodeRepository;
import org.finos.legend.pure.m3.serialization.filesystem.repository.CodeRepositoryProviderHelper;
import org.finos.legend.pure.m3.serialization.filesystem.repository.GenericCodeRepository;
import org.finos.legend.pure.m3.serialization.filesystem.usercodestorage.classpath.ClassLoaderCodeStorage;
import org.finos.legend.pure.m3.serialization.filesystem.usercodestorage.composite.CompositeCodeStorage;
import org.finos.legend.pure.m3.serialization.filesystem.usercodestorage.fs.MutableFSCodeStorage;
import org.finos.legend.pure.m3.serialization.runtime.Message;
import org.finos.legend.pure.m3.serialization.runtime.PureRuntime;
import org.finos.legend.pure.m3.serialization.runtime.PureRuntimeBuilder;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.runtime.java.interpreted.FunctionExecutionInterpreted;

// Compiles a directory of legacy Pure with legend-pure itself, then runs functions and prints what they return:
//   RunLegacy <source dir> <repository name> <function id>...
// Each line: "<function id> => <value>|<value>..." or "<function id> !! <failure>".
public class RunLegacy
{
    public static void main(String[] args)
    {
        Path source = Paths.get(args[0]);
        CodeRepository repository = GenericCodeRepository.build(args[1], args[1] + "(::.*)?", "platform");
        RichIterable<CodeRepository> platform = CodeRepositoryProviderHelper.findCodeRepositories();
        CompositeCodeStorage storage = new CompositeCodeStorage(new ClassLoaderCodeStorage(platform), new MutableFSCodeStorage(repository, source));
        FunctionExecutionInterpreted execution = new FunctionExecutionInterpreted();
        PureRuntime runtime = new PureRuntimeBuilder(storage).setTransactionalByDefault(false).build();
        execution.init(runtime, new Message(""));
        runtime.initialize();
        Lists.immutable.with(args).drop(2).forEach(id ->
        {
            try
            {
                CoreInstance result = execution.start(runtime.getFunction(id), Lists.immutable.empty());
                ListIterable<? extends CoreInstance> values = Instance.getValueForMetaPropertyToManyResolved(result, M3Properties.values, runtime.getProcessorSupport());
                System.out.println(id + " => " + values.collect(CoreInstance::getName).makeString("|"));
            }
            catch (Throwable t)
            {
                System.out.println(id + " !! " + String.valueOf(t.getMessage()).replace('\n', ' '));
            }
        });
    }
}
