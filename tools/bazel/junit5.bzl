# ©2026 JP Morgan Chase & Co. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""JUnit 5 tests, run with the JUnit console launcher."""

load("@rules_java//java:defs.bzl", "java_binary", "java_library")
load("@rules_shell//shell:sh_test.bzl", "sh_test")

def junit5_test(name, srcs, deps = [], runtime_deps = [], data = [], resources = [], jvm_flags = [], plugins = [], visibility = None, tags = [], **kwargs):
    """The test classes of a module: every class of its test jar that holds tests, whatever its name, run with the JUnit console
    launcher, from a jar of their own.

    Two targets: '<name>_lib', the compiled test classes (the test jar, for a module whose tests
    other modules reuse), and 'test_<name>', the test, which scans that jar only, so the tests of a test jar on the
    classpath are never run twice.

    Args:
      name: the test.
      srcs: the test sources.
      deps: what the test sources compile against.
      runtime_deps: what they need only to run.
      data: files the tests read at run time.
      resources: test resources, on the classpath.
      jvm_flags: flags of the test JVM.
      plugins: annotation processors of the test sources.
      visibility: the visibility of '<name>_lib' and '<name>_runner' (a command that runs some of the tests, as
        //tools/bazel:update_pdb_goldens__compiler).
      **kwargs: passed to the java_test (tags, size, timeout, shard_count, env).
    """
    java_library(
        name = name + "_lib",
        testonly = True,
        srcs = srcs,
        plugins = plugins,
        resources = resources,
        visibility = visibility,
        deps = deps + [
            "@maven//:org_junit_jupiter_junit_jupiter_api",
            "@maven//:org_junit_jupiter_junit_jupiter_params",
            "@maven//:org_opentest4j_opentest4j",
        ],
        runtime_deps = runtime_deps,
    )
    # The launcher, run by run-suite.sh so that its JUnit XML (what the test
    # dashboard imports) goes among the test outputs, at junit/<package>/<name>.
    java_binary(
        name = name + "_runner",
        testonly = True,
        jvm_flags = jvm_flags,
        main_class = "org.junit.platform.console.ConsoleLauncher",
        visibility = visibility,
        runtime_deps = [
            ":%s_lib" % name,
            "@maven//:org_junit_platform_junit_platform_console_standalone",
        ],
    )
    sh_test(
        name = "test_" + name,
        srcs = ["//tools/bazel:run-suite.sh"],
        args = [
            "junit/%s/%s" % (native.package_name(), name),
            "$(rootpath :%s_runner)" % name,
            "execute",
            "--scan-classpath=$(rootpath :%s_lib)" % name,
            "--include-classname=.*",
            "--fail-if-no-tests",
            "--disable-banner",
            "--reports-dir={report}",
        ],
        data = data + [":%s_lib" % name, ":%s_runner" % name],
        tags = tags + ["kind:junit"],
        **kwargs
    )
