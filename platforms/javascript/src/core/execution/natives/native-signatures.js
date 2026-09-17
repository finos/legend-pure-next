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

// Mangled signatures of the natives the JavaScript host implements — the keys
// of NativeRegistry, identical to the JVM hosts' native keys and to the element
// ids the pdbs store (package omitted). A leaf module so the natives and their
// extensions can share them without an import cycle.

export const COMPILE_SOURCE = "compileSource_PureFile_1__String_MANY__CompileSourceResult_1_";
export const EVALUATE = "evaluate_Function_1__List_MANY__Any_MANY_";
export const JS_COMPILE_MODULE = "compileModule_String_1__Any_1_";
export const JS_EXECUTE = "execute_Any_1__String_1__Any_MANY__GenericType_$0_1$__Multiplicity_$0_1$__Any_$0_1$__Any_MANY_";
export const JS_DRAIN_COMPILED_SOURCES = "drainCompiledSources__String_MANY_";
export const READ_FILE = "readFile_String_1__String_1_";
export const READ_FILE_BYTES = "readFileBytes_String_1__Integer_MANY_";
export const DIRECTORY_TREE = "directoryTree_String_1__String_MANY_";
export const WRITE_FILE = "writeFile_String_1__String_1__String_1_";

// meta::pure::functions::meta::antlr natives: [name, signature]. The translator emits `__<name>(...)`.
export const ANTLR_NATIVES = [
    ["parseAntlr", "parseAntlr_String_1__String_1__String_1__Integer_1__AntlrContext_1_"],
    ["getText", "getText_AntlrContext_1__String_1_"],
    ["grammarRuleName", "grammarRuleName_AntlrContext_1__String_1_"],
    ["getChild", "getChild_AntlrContext_1__String_1__AntlrContext_$0_1$_"],
    ["getChildren", "getChildren_AntlrContext_1__String_1__AntlrContext_MANY_"],
    ["getTopLevelChildren", "getTopLevelChildren_AntlrContext_1__AntlrContext_MANY_"],
    ["getChildTextAt", "getChildTextAt_AntlrContext_1__Integer_1__String_$0_1$_"],
    ["getTokenText", "getTokenText_AntlrContext_1__String_1__String_$0_1$_"],
    ["getTokenTexts", "getTokenTexts_AntlrContext_1__String_1__String_MANY_"],
    ["hasChild", "hasChild_AntlrContext_1__String_1__Boolean_1_"],
    ["hasToken", "hasToken_AntlrContext_1__String_1__Boolean_1_"],
    ["getStartLine", "getStartLine_AntlrContext_1__Integer_1_"],
    ["getStartColumn", "getStartColumn_AntlrContext_1__Integer_1_"],
    ["getStopLine", "getStopLine_AntlrContext_1__Integer_1_"],
    ["getStopColumn", "getStopColumn_AntlrContext_1__Integer_1_"],
    ["stripTripleQuotesDedented", "stripTripleQuotesDedented_String_1__String_1_"],
    ["computeFirstNonNewlineLine", "computeFirstNonNewlineLine_AntlrContext_$0_1$__Boolean_1__Integer_1_"],
];
