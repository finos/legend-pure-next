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

// Full ids of the natives the JavaScript host implements — package and mangled
// signature, the keys of NativeRegistry as on every host, and the element paths
// the pdbs store. A leaf module so the natives and their extensions can share
// them without an import cycle.

export const COMPILE_SOURCE = "platform::pure::functions::meta::compileSource_PureFile_1__String_MANY__CompileSourceResult_1_";
export const EVALUATE = "platform::pure::functions::lang::evaluate_Function_1__List_MANY__Any_MANY_";
export const JS_COMPILE_MODULE = "platform::extensions::language::javascript::compileModule_String_1__Any_1_";
export const JS_EXECUTE = "platform::extensions::language::javascript::execute_Any_1__String_1__Any_MANY__GenericType_$0_1$__Multiplicity_$0_1$__Any_$0_1$__Any_MANY_";
export const JS_DRAIN_COMPILED_SOURCES = "platform::extensions::language::javascript::drainCompiledSources__String_MANY_";
export const READ_FILE_BYTES = "platform::pure::functions::io::readFileBytes_String_1__Binary_1_";
export const ENTRY_NAMES = "platform::pure::compiler::pdb::archive::entryNames_Binary_1__String_MANY_";
export const ENTRY_BYTES = "platform::pure::compiler::pdb::archive::entryBytes_Binary_1__String_1__Binary_1_";
export const DIRECTORY_TREE = "platform::pure::functions::io::directoryTree_String_1__String_MANY_";
export const WRITE_FILE = "platform::pure::functions::io::writeFile_String_1__String_1__String_1_";
export const WRITE_FILE_BYTES = "platform::pure::functions::io::writeFileBytes_String_1__Binary_1__String_1_";

// platform::pure::functions::meta::antlr natives: [name, id]. The translator emits `__<name>(...)`.
export const ANTLR_NATIVES = [
    ["parseAntlr", "platform::pure::functions::meta::antlr::parseAntlr_String_1__String_1__String_1__Integer_1__AntlrContext_1_"],
    ["getText", "platform::pure::functions::meta::antlr::getText_AntlrContext_1__String_1_"],
    ["grammarRuleName", "platform::pure::functions::meta::antlr::grammarRuleName_AntlrContext_1__String_1_"],
    ["getChild", "platform::pure::functions::meta::antlr::getChild_AntlrContext_1__String_1__AntlrContext_$0_1$_"],
    ["getChildren", "platform::pure::functions::meta::antlr::getChildren_AntlrContext_1__String_1__AntlrContext_MANY_"],
    ["getTopLevelChildren", "platform::pure::functions::meta::antlr::getTopLevelChildren_AntlrContext_1__AntlrContext_MANY_"],
    ["getChildTextAt", "platform::pure::functions::meta::antlr::getChildTextAt_AntlrContext_1__Integer_1__String_$0_1$_"],
    ["getTokenText", "platform::pure::functions::meta::antlr::getTokenText_AntlrContext_1__String_1__String_$0_1$_"],
    ["getTokenTexts", "platform::pure::functions::meta::antlr::getTokenTexts_AntlrContext_1__String_1__String_MANY_"],
    ["hasChild", "platform::pure::functions::meta::antlr::hasChild_AntlrContext_1__String_1__Boolean_1_"],
    ["hasToken", "platform::pure::functions::meta::antlr::hasToken_AntlrContext_1__String_1__Boolean_1_"],
    ["getStartLine", "platform::pure::functions::meta::antlr::getStartLine_AntlrContext_1__Integer_1_"],
    ["getStartColumn", "platform::pure::functions::meta::antlr::getStartColumn_AntlrContext_1__Integer_1_"],
    ["getStopLine", "platform::pure::functions::meta::antlr::getStopLine_AntlrContext_1__Integer_1_"],
    ["getStopColumn", "platform::pure::functions::meta::antlr::getStopColumn_AntlrContext_1__Integer_1_"],
    ["stripTripleQuotesDedented", "platform::pure::functions::meta::antlr::stripTripleQuotesDedented_String_1__String_1_"],
    ["computeFirstNonNewlineLine", "platform::pure::functions::meta::antlr::computeFirstNonNewlineLine_AntlrContext_$0_1$__Integer_1_"],
];
