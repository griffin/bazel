// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.cpp;

import static com.google.devtools.build.lib.rules.cpp.CppRuleClasses.DYNAMIC_LINKING_MODE;
import static com.google.devtools.build.lib.rules.cpp.CppRuleClasses.STATIC_LINKING_MODE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.MakeVariableSupplier.MapBackedMakeVariableSupplier;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.test.ExecutionInfo;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.apple.ApplePlatform;
import com.google.devtools.build.lib.rules.cpp.CcCommon.CcFlagsSupplier;
import com.google.devtools.build.lib.rules.cpp.CcCompilationHelper.CompilationInfo;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext.LinkOptions;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.DynamicMode;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.Tool;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.rules.cpp.Link.LinkingMode;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.syntax.Depset;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Sequence;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.Tuple;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * A ConfiguredTarget for <code>cc_binary</code> rules.
 */
public abstract class CcBinary implements RuleConfiguredTargetFactory {

  private final CppSemantics semantics;

  protected CcBinary(CppSemantics semantics) {
    this.semantics = semantics;
  }

  /**
   * The maximum number of inputs for any single .dwp generating action. For cases where
   * this value is exceeded, the action is split up into "batches" that fall under the limit.
   * See {@link #createDebugPackagerActions} for details.
   */
  @VisibleForTesting
  public static final int MAX_INPUTS_PER_DWP_ACTION = 100;

  /**
   * Intermediate dwps are written to this subdirectory under the main dwp's output path.
   */
  @VisibleForTesting
  public static final String INTERMEDIATE_DWP_DIR = "_dwps";

  /**
   * A string constant for the dynamic_link_test_srcs feature.
   *
   * <p>Enabling this feature forces srcs of test executables to be linked dynamically in
   * DYNAMIC_MODE=AUTO.
   */
  public static final String DYNAMIC_LINK_TEST_SRCS = "dynamic_link_test_srcs";

  /**
   * Provider that signals that rules that use launchers can use this target as the launcher.
   *
   * @deprecated This is google internal provider and it will be replaced with a more generally
   *     useful provider in Bazel. Do not use to implement support for launchers in new rules. It's
   *     only supported to be used in existing rules (PyBinary, JavaBinary, JavaTest).
   */
  @Deprecated()
  @SkylarkModule(
      name = "CcLauncherInfo",
      documented = false,
      doc =
          "Provider that signals that rules that use launchers can use this target as "
              + "the launcher.",
      category = SkylarkModuleCategory.TOP_LEVEL_TYPE)
  public static class CcLauncherInfo extends NativeInfo {
    private static final String RESTRICTION_ERROR_MESSAGE =
        "This provider is restricted to native.java_binary, native.py_binary and native.java_test. "
            + "This is a ";
    public static final String PROVIDER_NAME = "CcLauncherInfo";
    public static final Provider PROVIDER = new Provider();

    private final CcCompilationOutputs ccCompilationOutputs;
    private final CcInfo ccInfo;

    public CcLauncherInfo(CcInfo ccInfo, CcCompilationOutputs ccCompilationOutputs) {
      super(PROVIDER);
      this.ccInfo = ccInfo;
      this.ccCompilationOutputs = ccCompilationOutputs;
    }

    public CcCompilationOutputs getCcCompilationOutputs(RuleContext ruleContext) {
      checkRestrictedUsage(ruleContext);
      return ccCompilationOutputs;
    }

    public CcInfo getCcInfo(RuleContext ruleContext) {
      checkRestrictedUsage(ruleContext);
      return ccInfo;
    }

    private void checkRestrictedUsage(RuleContext ruleContext) {
      Rule rule = ruleContext.getRule();
      if (rule.getRuleClassObject().isSkylark()
          || (!rule.getRuleClass().equals("java_binary")
              && !rule.getRuleClass().equals("java_test")
              && !rule.getRuleClass().equals("py_binary")
              && !rule.getRuleClass().equals("py_test"))) {
        throw new IllegalStateException(RESTRICTION_ERROR_MESSAGE + rule.getRuleClass());
      }
    }

    /** Provider class for {@link CcLauncherInfo} objects. */
    public static class Provider extends BuiltinProvider<CcLauncherInfo> {
      private Provider() {
        super(PROVIDER_NAME, CcLauncherInfo.class);
      }
    }
  }

  private static Runfiles collectRunfiles(
      RuleContext ruleContext,
      FeatureConfiguration featureConfiguration,
      CcToolchainProvider toolchain,
      CppConfiguration cppConfiguration,
      List<LibraryToLink> libraries,
      CcLinkingOutputs ccLibraryLinkingOutputs,
      CcCompilationContext ccCompilationContext,
      Link.LinkingMode linkingMode,
      NestedSet<Artifact> transitiveArtifacts,
      Iterable<Artifact> fakeLinkerInputs,
      boolean fake,
      ImmutableSet<CppSource> cAndCppSources,
      boolean linkCompileOutputSeparately)
      throws RuleErrorException {
    Runfiles.Builder builder =
        new Runfiles.Builder(
            ruleContext.getWorkspaceName(),
            ruleContext.getConfiguration().legacyExternalRunfiles());
    Function<TransitiveInfoCollection, Runfiles> runfilesMapping =
        CppHelper.runfilesFunction(ruleContext, linkingMode != Link.LinkingMode.DYNAMIC);
    builder.addTransitiveArtifacts(transitiveArtifacts);
    // Add the shared libraries to the runfiles. This adds any shared libraries that are in the
    // srcs of this target.
    builder.addArtifacts(LibraryToLink.getDynamicLibrariesForRuntime(true, libraries));
    builder.addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES);
    // TODO(plf): Why do we need .so files produced by cc_library in data dependencies of cc_binary?
    // This can probably be removed safely.
    for (TransitiveInfoCollection transitiveInfoCollection :
        ruleContext.getPrerequisites("data", Mode.DONT_CHECK)) {
      builder.merge(
          CppHelper.runfilesFunction(ruleContext, /* linkingStatically= */ true)
              .apply(transitiveInfoCollection));
      builder.merge(
          CppHelper.runfilesFunction(ruleContext, /* linkingStatically= */ false)
              .apply(transitiveInfoCollection));
    }
    builder.add(ruleContext, runfilesMapping);
    // Add the C++ runtime libraries if linking them dynamically.
    if (linkingMode == Link.LinkingMode.DYNAMIC) {
      try {
        builder.addTransitiveArtifacts(toolchain.getDynamicRuntimeLinkInputs(featureConfiguration));
      } catch (EvalException e) {
        throw ruleContext.throwWithRuleError(e.getMessage());
      }
    }
    if (linkCompileOutputSeparately) {
      if (!ccLibraryLinkingOutputs.isEmpty()
          && ccLibraryLinkingOutputs.getLibraryToLink().getDynamicLibrary() != null) {
        builder.addArtifact(ccLibraryLinkingOutputs.getLibraryToLink().getDynamicLibrary());
      }
    }
    // For cc_binary and cc_test rules, there is an implicit dependency on
    // the malloc library package, which is specified by the "malloc" attribute.
    // As the BUILD encyclopedia says, the "malloc" attribute should be ignored
    // if linkshared=1.
    boolean linkshared = isLinkShared(ruleContext);
    if (!linkshared) {
      TransitiveInfoCollection malloc = CppHelper.mallocForTarget(ruleContext);
      builder.addTarget(malloc, RunfilesProvider.DEFAULT_RUNFILES);
      builder.addTarget(malloc, runfilesMapping);
    }

    if (fake) {
      // Add the object files, libraries, and linker scripts that are used to
      // link this executable.
      builder.addSymlinksToArtifacts(Iterables.filter(fakeLinkerInputs, Artifact.MIDDLEMAN_FILTER));
      // The crosstool inputs for the link action are not sufficient; we also need the crosstool
      // inputs for compilation. Node that these cannot be middlemen because Runfiles does not
      // know how to expand them.
      builder.addTransitiveArtifacts(toolchain.getAllFiles());
      builder.addTransitiveArtifacts(toolchain.getLibcLink(cppConfiguration));
      // Add the sources files that are used to compile the object files.
      // We add the headers in the transitive closure and our own sources in the srcs
      // attribute. We do not provide the auxiliary inputs, because they are only used when we
      // do FDO compilation, and cc_fake_binary does not support FDO.
      ImmutableSet.Builder<Artifact> sourcesBuilder = ImmutableSet.<Artifact>builder();
      for (CppSource cppSource : cAndCppSources) {
        sourcesBuilder.add(cppSource.getSource());
      }
      builder.addSymlinksToArtifacts(sourcesBuilder.build());
      builder.addSymlinksToArtifacts(ccCompilationContext.getDeclaredIncludeSrcs());
      // Add additional files that are referenced from the compile command, like module maps
      // or header modules.
      builder.addSymlinksToArtifacts(ccCompilationContext.getAdditionalInputs());
      builder.addSymlinksToArtifacts(
          ccCompilationContext.getTransitiveModules(
              usePic(ruleContext, toolchain, cppConfiguration, featureConfiguration)));
    }
    return builder.build();
  }

  @Override
  public ConfiguredTarget create(RuleContext context)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    return CcBinary.init(semantics, context, /*fake =*/ false);
  }

  public static ConfiguredTarget init(CppSemantics semantics, RuleContext ruleContext, boolean fake)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    CcCommon.checkRuleLoadedThroughMacro(ruleContext);
    semantics.validateDeps(ruleContext);
    if (ruleContext.attributes().isAttributeValueExplicitlySpecified("dynamic_deps")) {
      if (!ruleContext
          .getAnalysisEnvironment()
          .getSkylarkSemantics()
          .experimentalCcSharedLibrary()) {
        ruleContext.ruleError(
            "The attribute 'dynamic_deps' can only be used with the flag"
                + " --experimental_cc_shared_library.");
      } else if (ruleContext.attributes().isAttributeValueExplicitlySpecified("linkshared")) {
        ruleContext.ruleError(
            "Do not use `linkshared` to build a shared library. Use cc_shared_library instead.");
      }
    }
    if (ruleContext.hasErrors()) {
      return null;
    }

    ruleContext.checkSrcsSamePackage(true);

    CcCommon common = new CcCommon(ruleContext);
    common.reportInvalidOptions(ruleContext);
    CcToolchainProvider ccToolchain = common.getToolchain();

    ImmutableMap.Builder<String, String> toolchainMakeVariables = ImmutableMap.builder();
    ccToolchain.addGlobalMakeVariables(toolchainMakeVariables);
    ruleContext.initConfigurationMakeVariableContext(
        new MapBackedMakeVariableSupplier(toolchainMakeVariables.build()),
        new CcFlagsSupplier(ruleContext));

    CppConfiguration cppConfiguration = ruleContext.getFragment(CppConfiguration.class);
    PrecompiledFiles precompiledFiles = new PrecompiledFiles(ruleContext);
    LinkTargetType linkType =
        isLinkShared(ruleContext) ? LinkTargetType.DYNAMIC_LIBRARY : LinkTargetType.EXECUTABLE;

    semantics.validateAttributes(ruleContext);
    if (ruleContext.hasErrors()) {
      return null;
    }

    // if cc_binary includes "linkshared=1", then gcc will be invoked with
    // linkopt "-shared", which causes the result of linking to be a shared
    // library. In this case, the name of the executable target should end
    // in ".so" or "dylib" or ".dll".
    Artifact binary;
    PathFragment binaryPath = PathFragment.create(ruleContext.getTarget().getName());
    if (!isLinkShared(ruleContext)) {
      binary =
          CppHelper.getLinkedArtifact(
              ruleContext, ccToolchain, ruleContext.getConfiguration(), LinkTargetType.EXECUTABLE);
    } else {
      binary = ruleContext.getBinArtifact(binaryPath);
    }

    if (isLinkShared(ruleContext)
        && !CppFileTypes.SHARED_LIBRARY.matches(binary.getFilename())
        && !CppFileTypes.VERSIONED_SHARED_LIBRARY.matches(binary.getFilename())) {
      ruleContext.attributeError("linkshared", "'linkshared' used in non-shared library");
      return null;
    }

    LinkingMode linkingMode = getLinkStaticness(ruleContext, cppConfiguration);
    ImmutableSet.Builder<String> requestedFeaturesBuilder = new ImmutableSet.Builder<>();
    requestedFeaturesBuilder
        .addAll(ruleContext.getFeatures())
        .add(linkingMode == Link.LinkingMode.DYNAMIC ? DYNAMIC_LINKING_MODE : STATIC_LINKING_MODE);
    if (fake) {
      requestedFeaturesBuilder.add(CppRuleClasses.IS_CC_FAKE_BINARY);
    }

    FdoContext fdoContext = common.getFdoContext();
    FeatureConfiguration featureConfiguration =
        CcCommon.configureFeaturesOrReportRuleError(
            ruleContext,
            requestedFeaturesBuilder.build(),
            /* unsupportedFeatures= */ ruleContext.getDisabledFeatures(),
            ccToolchain);

    ImmutableList<TransitiveInfoCollection> deps =
        ImmutableList.<TransitiveInfoCollection>builder()
            .addAll(ruleContext.getPrerequisites("deps", Mode.TARGET))
            .add(CppHelper.mallocForTarget(ruleContext))
            .build();

    CppHelper.checkProtoLibrariesInDeps(ruleContext, deps);
    if (ruleContext.hasErrors()) {
      return null;
    }
    CcCompilationHelper compilationHelper =
        new CcCompilationHelper(
                ruleContext,
                ruleContext,
                ruleContext.getLabel(),
                CppHelper.getGrepIncludes(ruleContext),
                semantics,
                featureConfiguration,
                ccToolchain,
                fdoContext,
                TargetUtils.getExecutionInfo(
                    ruleContext.getRule(), ruleContext.isAllowTagsPropagation()))
            .fromCommon(common, /* additionalCopts= */ ImmutableList.of())
            .addPrivateHeaders(common.getPrivateHeaders())
            .addSources(common.getSources())
            .addCcCompilationContexts(CppHelper.getCompilationContextsFromDeps(deps))
            .addCcCompilationContexts(
                ImmutableList.of(CcCompilationHelper.getStlCcCompilationContext(ruleContext)))
            .setHeadersCheckingMode(semantics.determineHeadersCheckingMode(ruleContext))
            .setCodeCoverageEnabled(CcCompilationHelper.isCodeCoverageEnabled(ruleContext))
            .setFake(fake);
    CompilationInfo compilationInfo = compilationHelper.compile();
    CcCompilationContext ccCompilationContext = compilationInfo.getCcCompilationContext();
    CcCompilationOutputs precompiledFileObjects =
        CcCompilationOutputs.builder()
            .addObjectFiles(precompiledFiles.getObjectFiles(/* usePic= */ false))
            .addPicObjectFiles(precompiledFiles.getObjectFiles(/* usePic= */ true))
            .build();
    CcCompilationOutputs ccCompilationOutputs =
        CcCompilationOutputs.builder()
            .merge(precompiledFileObjects)
            .merge(compilationInfo.getCcCompilationOutputs())
            .build();
    Iterable<Artifact> additionalLinkerInputs = common.getAdditionalLinkerInputs();

    // Allows the dynamic library generated for code of test targets to be linked separately.
    boolean linkCompileOutputSeparately =
        ruleContext.isTestTarget()
            && linkingMode == LinkingMode.DYNAMIC
            && cppConfiguration.getDynamicModeFlag() == DynamicMode.DEFAULT
            && ruleContext.getFeatures().contains(DYNAMIC_LINK_TEST_SRCS);
    // When linking the object files directly into the resulting binary, we do not need
    // library-level link outputs; thus, we do not let CcCompilationHelper produce link outputs
    // (either shared object files or archives) for a non-library link type [*], and add
    // the object files explicitly in determineLinkerArguments.
    //
    // When linking the object files into their own library, we want CcCompilationHelper to
    // take care of creating the library link outputs for us, so we need to set the link
    // type to STATIC_LIBRARY.
    //
    // [*] The only library link type is STATIC_LIBRARY. EXECUTABLE specifies a normal
    // cc_binary output, while DYNAMIC_LIBRARY is a cc_binary rules that produces an
    // output matching a shared object, for example cc_binary(name="foo.so", ...) on linux.
    CcLinkingOutputs ccLinkingOutputs = CcLinkingOutputs.EMPTY;
    if (linkCompileOutputSeparately && !ccCompilationOutputs.isEmpty()) {
      CcLinkingHelper linkingHelper =
          new CcLinkingHelper(
                  ruleContext,
                  ruleContext.getLabel(),
                  ruleContext,
                  ruleContext,
                  semantics,
                  featureConfiguration,
                  ccToolchain,
                  fdoContext,
                  ruleContext.getConfiguration(),
                  cppConfiguration,
                  ruleContext.getSymbolGenerator(),
                  TargetUtils.getExecutionInfo(
                      ruleContext.getRule(), ruleContext.isAllowTagsPropagation()))
              .fromCommon(ruleContext, common)
              .setGrepIncludes(CppHelper.getGrepIncludes(ruleContext))
              .setIsStampingEnabled(AnalysisUtils.isStampingEnabled(ruleContext))
              .setTestOrTestOnlyTarget(ruleContext.isTestTarget() || ruleContext.isTestOnlyTarget())
              .addCcLinkingContexts(
                  CppHelper.getLinkingContextsFromDeps(
                      ImmutableList.of(CppHelper.mallocForTarget(ruleContext))))
              .emitInterfaceSharedLibraries(true)
              .setAlwayslink(false);
      ccLinkingOutputs = linkingHelper.link(ccCompilationOutputs);
    }

    boolean isStaticMode = linkingMode != LinkingMode.DYNAMIC;

    CcLinkingContext depsCcLinkingContext = collectCcLinkingContext(ruleContext);

    Artifact generatedDefFile = null;
    Artifact winDefFile = null;
    if (isLinkShared(ruleContext)) {
      if (featureConfiguration.isEnabled(CppRuleClasses.TARGETS_WINDOWS)) {
        ImmutableList.Builder<Artifact> objectFiles = ImmutableList.builder();
        objectFiles.addAll(ccCompilationOutputs.getObjectFiles(false));

        for (LibraryToLink library : depsCcLinkingContext.getLibraries()) {
          if (isStaticMode
              || (library.getDynamicLibrary() == null && library.getInterfaceLibrary() == null)) {
            if (library.getPicStaticLibrary() != null) {
              if (library.getPicObjectFiles() != null) {
                objectFiles.addAll(library.getPicObjectFiles());
              }
            } else if (library.getStaticLibrary() != null) {
              if (library.getObjectFiles() != null) {
                objectFiles.addAll(library.getObjectFiles());
              }
            }
          }
        }

        Artifact defParser = common.getDefParser();
        if (defParser != null) {
          generatedDefFile =
              CppHelper.createDefFileActions(
                  ruleContext, defParser, objectFiles.build(), binary.getFilename());
        }
        winDefFile =
            CppHelper.getWindowsDefFileForLinking(
                ruleContext, common.getWinDefFile(), generatedDefFile, featureConfiguration);
      }
    }

    boolean usePic = usePic(ruleContext, ccToolchain, cppConfiguration, featureConfiguration);

    // On Windows, if GENERATE_PDB_FILE feature is enabled
    // then a pdb file will be built along with the executable.
    Artifact pdbFile = null;
    if (featureConfiguration.isEnabled(CppRuleClasses.GENERATE_PDB_FILE)) {
      pdbFile = ruleContext.getRelatedArtifact(binary.getRootRelativePath(), ".pdb");
    }

    NestedSetBuilder<CcLinkingContext.LinkerInput> extraLinkTimeLibrariesNestedSet =
        NestedSetBuilder.linkOrder();
    NestedSetBuilder<Artifact> extraLinkTimeRuntimeLibraries = NestedSetBuilder.linkOrder();

    ExtraLinkTimeLibraries extraLinkTimeLibraries =
        depsCcLinkingContext.getExtraLinkTimeLibraries();
    if (extraLinkTimeLibraries != null) {
      ExtraLinkTimeLibrary.BuildLibraryOutput extraLinkBuildLibraryOutput =
          extraLinkTimeLibraries.buildLibraries(
              ruleContext, linkingMode != LinkingMode.DYNAMIC, isLinkShared(ruleContext));
      extraLinkTimeLibrariesNestedSet.addTransitive(extraLinkBuildLibraryOutput.getLinkerInputs());
      extraLinkTimeRuntimeLibraries.addTransitive(
          extraLinkBuildLibraryOutput.getRuntimeLibraries());
    }

    Pair<CcLinkingOutputs, CcLauncherInfo> ccLinkingOutputsAndCcLinkingInfo =
        createTransitiveLinkingActions(
            ruleContext,
            ccToolchain,
            featureConfiguration,
            fdoContext,
            common,
            precompiledFiles,
            ccCompilationOutputs,
            additionalLinkerInputs,
            ccLinkingOutputs,
            ccCompilationContext,
            fake,
            binary,
            depsCcLinkingContext,
            extraLinkTimeLibrariesNestedSet.build(),
            linkCompileOutputSeparately,
            semantics,
            linkingMode,
            cppConfiguration,
            linkType,
            pdbFile,
            winDefFile);
    if (ruleContext.hasErrors()) {
      return null;
    }

    CcLinkingOutputs ccLinkingOutputsBinary = ccLinkingOutputsAndCcLinkingInfo.first;

    CcLauncherInfo ccLauncherInfo = ccLinkingOutputsAndCcLinkingInfo.second;

    LibraryToLink ccLinkingOutputsBinaryLibrary = ccLinkingOutputsBinary.getLibraryToLink();
    Iterable<Artifact> fakeLinkerInputs =
        fake ? ccLinkingOutputsBinary.getLinkActionInputs() : ImmutableList.<Artifact>of();
    ImmutableList.Builder<LibraryToLink> librariesBuilder = ImmutableList.builder();
    if (isLinkShared(ruleContext)) {
      if (ccLinkingOutputsBinaryLibrary != null) {
        librariesBuilder.add(ccLinkingOutputsBinaryLibrary);
      }
    }
    // Also add all shared libraries from srcs.
    for (Artifact library : precompiledFiles.getSharedLibraries()) {
      Artifact symlink = common.getDynamicLibrarySymlink(library, true);
      LibraryToLink libraryToLink =
          LibraryToLink.builder()
              .setLibraryIdentifier(CcLinkingOutputs.libraryIdentifierOf(library))
              .setDynamicLibrary(symlink)
              .setResolvedSymlinkDynamicLibrary(library)
              .build();
      librariesBuilder.add(libraryToLink);
    }
    ImmutableList<LibraryToLink> libraries = librariesBuilder.build();
    NestedSet<Artifact> filesToBuild = NestedSetBuilder.create(Order.STABLE_ORDER, binary);

    // Create the stripped binary, but don't add it to filesToBuild; it's only built when requested.
    Artifact strippedFile = ruleContext.getImplicitOutputArtifact(
        CppRuleClasses.CC_BINARY_STRIPPED);
    CppHelper.createStripAction(
        ruleContext, ccToolchain, cppConfiguration, binary, strippedFile, featureConfiguration);

    NestedSet<Artifact> dwoFiles =
        collectTransitiveDwoArtifacts(
            ccCompilationOutputs,
            CppHelper.mergeCcDebugInfoContexts(
                compilationInfo.getCcCompilationOutputs(),
                AnalysisUtils.getProviders(deps, CcInfo.PROVIDER)),
            linkingMode,
            usePic,
            ccLinkingOutputsBinary.getAllLtoArtifacts());
    Artifact dwpFile =
        ruleContext.getImplicitOutputArtifact(CppRuleClasses.CC_BINARY_DEBUG_PACKAGE);
    createDebugPackagerActions(ruleContext, ccToolchain, dwpFile, dwoFiles);

    // The debug package should include the dwp file only if it was explicitly requested.
    Artifact explicitDwpFile = dwpFile;
    if (!ccToolchain.shouldCreatePerObjectDebugInfo(featureConfiguration, cppConfiguration)) {
      explicitDwpFile = null;
    } else {
      // For cc_test rules, include the dwp in the runfiles if Fission is enabled and the test was
      // built statically.
      if (TargetUtils.isTestRule(ruleContext.getRule())
          && linkingMode != Link.LinkingMode.DYNAMIC
          && cppConfiguration.buildTestDwpIsActivated()) {
        filesToBuild = NestedSetBuilder.fromNestedSet(filesToBuild).add(dwpFile).build();
      }
    }

    // If the binary is linked dynamically and COPY_DYNAMIC_LIBRARIES_TO_BINARY is enabled, collect
    // all the dynamic libraries we need at runtime. Then copy these libraries next to the binary.
    NestedSet<Artifact> copiedRuntimeDynamicLibraries = null;
    if (featureConfiguration.isEnabled(CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY)) {
      copiedRuntimeDynamicLibraries =
          createDynamicLibrariesCopyActions(
              ruleContext,
              LibraryToLink.getDynamicLibrariesForRuntime(
                  isStaticMode, depsCcLinkingContext.getLibraries()));
    }

    // TODO(bazel-team): Do we need to put original shared libraries (along with
    // mangled symlinks) into the RunfilesSupport object? It does not seem
    // logical since all symlinked libraries will be linked anyway and would
    // not require manual loading but if we do, then we would need to collect
    // their names and use a different constructor below.
    NestedSetBuilder<Artifact> transitiveArtifacts =
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(filesToBuild)
            .addTransitive(extraLinkTimeRuntimeLibraries.build());
    if (featureConfiguration.isEnabled(CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY)) {
      transitiveArtifacts.addTransitive(copiedRuntimeDynamicLibraries);
    }
    Runfiles runfiles =
        collectRunfiles(
            ruleContext,
            featureConfiguration,
            ccToolchain,
            cppConfiguration,
            libraries,
            ccLinkingOutputs,
            ccCompilationContext,
            linkingMode,
            transitiveArtifacts.build(),
            fakeLinkerInputs,
            fake,
            compilationHelper.getCompilationUnitSources(),
            linkCompileOutputSeparately);
    RunfilesSupport runfilesSupport = RunfilesSupport.withExecutable(ruleContext, runfiles, binary);

    RuleConfiguredTargetBuilder ruleBuilder = new RuleConfiguredTargetBuilder(ruleContext);
    addTransitiveInfoProviders(
        ruleContext,
        ccToolchain,
        cppConfiguration,
        featureConfiguration,
        common,
        ruleBuilder,
        filesToBuild,
        ccCompilationOutputs,
        ccCompilationContext,
        libraries,
        fake);

    // Support test execution on darwin.
    if (ApplePlatform.isApplePlatform(ccToolchain.getTargetCpu())
        && TargetUtils.isTestRule(ruleContext.getRule())) {
      ruleBuilder.addNativeDeclaredProvider(
          new ExecutionInfo(ImmutableMap.of(ExecutionRequirements.REQUIRES_DARWIN, "")));
    }

    // If PDB file is generated by the link action, we add it to pdb_file output group
    if (pdbFile != null) {
      ruleBuilder.addOutputGroup("pdb_file", pdbFile);
    }

    if (generatedDefFile != null) {
      ruleBuilder.addOutputGroup("def_file", generatedDefFile);
    }

    if (!ccLinkingOutputsBinary.isEmpty()) {
      LibraryToLink libraryToLink = ccLinkingOutputsBinary.getLibraryToLink();
      Artifact dynamicLibraryForLinking = null;
      if (libraryToLink.getInterfaceLibrary() != null) {
        if (libraryToLink.getResolvedSymlinkInterfaceLibrary() != null) {
          dynamicLibraryForLinking = libraryToLink.getResolvedSymlinkInterfaceLibrary();
        } else {
          dynamicLibraryForLinking = libraryToLink.getInterfaceLibrary();
        }
      } else if (libraryToLink.getDynamicLibrary() != null) {
        if (libraryToLink.getResolvedSymlinkDynamicLibrary() != null) {
          dynamicLibraryForLinking = libraryToLink.getResolvedSymlinkDynamicLibrary();
        } else {
          dynamicLibraryForLinking = libraryToLink.getDynamicLibrary();
        }
      }
      if (dynamicLibraryForLinking != null) {
        ruleBuilder.addOutputGroup("interface_library", dynamicLibraryForLinking);
      }
    }

    if (copiedRuntimeDynamicLibraries != null) {
      ruleBuilder.addOutputGroup("runtime_dynamic_libraries", copiedRuntimeDynamicLibraries);
    }

    CcSkylarkApiProvider.maybeAdd(ruleContext, ruleBuilder);
    return ruleBuilder
        .addProvider(RunfilesProvider.class, RunfilesProvider.simple(runfiles))
        .addProvider(
            DebugPackageProvider.class,
            new DebugPackageProvider(ruleContext.getLabel(), strippedFile, binary, explicitDwpFile))
        .setRunfilesSupport(runfilesSupport, binary)
        .addNativeDeclaredProvider(ccLauncherInfo)
        .build();
  }

  public static Pair<CcLinkingOutputs, CcLauncherInfo> createTransitiveLinkingActions(
      RuleContext ruleContext,
      CcToolchainProvider ccToolchain,
      FeatureConfiguration featureConfiguration,
      FdoContext fdoContext,
      CcCommon common,
      PrecompiledFiles precompiledFiles,
      CcCompilationOutputs ccCompilationOutputs,
      Iterable<Artifact> additionalLinkerInputs,
      CcLinkingOutputs ccLinkingOutputs,
      CcCompilationContext ccCompilationContext,
      boolean fake,
      Artifact binary,
      CcLinkingContext depsCcLinkingContext,
      NestedSet<CcLinkingContext.LinkerInput> extraLinkTimeLibraries,
      boolean linkCompileOutputSeparately,
      CppSemantics cppSemantics,
      LinkingMode linkingMode,
      CppConfiguration cppConfiguration,
      LinkTargetType linkType,
      Artifact pdbFile,
      Artifact winDefFile)
      throws InterruptedException, RuleErrorException {
    CcCompilationOutputs.Builder ccCompilationOutputsBuilder =
        CcCompilationOutputs.builder()
            .addPicObjectFiles(ccCompilationOutputs.getObjectFiles(/* usePic= */ true))
            .addObjectFiles(ccCompilationOutputs.getObjectFiles(/* usePic= */ false))
            .addLtoCompilationContext(ccCompilationOutputs.getLtoCompilationContext());
    CcCompilationOutputs ccCompilationOutputsWithOnlyObjects = ccCompilationOutputsBuilder.build();
    CcLinkingHelper ccLinkingHelper =
        new CcLinkingHelper(
                ruleContext,
                ruleContext.getLabel(),
                ruleContext,
                ruleContext,
                cppSemantics,
                featureConfiguration,
                ccToolchain,
                fdoContext,
                ruleContext.getConfiguration(),
                cppConfiguration,
                ruleContext.getSymbolGenerator(),
                TargetUtils.getExecutionInfo(
                    ruleContext.getRule(), ruleContext.isAllowTagsPropagation()))
            .setGrepIncludes(CppHelper.getGrepIncludes(ruleContext))
            .setIsStampingEnabled(AnalysisUtils.isStampingEnabled(ruleContext))
            .setTestOrTestOnlyTarget(ruleContext.isTestTarget() || ruleContext.isTestOnlyTarget())
            .addNonCodeLinkerInputs(additionalLinkerInputs);

    CcInfo depsCcInfo = CcInfo.builder().setCcLinkingContext(depsCcLinkingContext).build();

    CcLinkingContext.Builder currentCcLinkingContextBuilder = CcLinkingContext.builder();

    if (linkCompileOutputSeparately) {
      if (!ccLinkingOutputs.isEmpty()) {
        currentCcLinkingContextBuilder.addLibrary(ccLinkingOutputs.getLibraryToLink());
      }
      ccCompilationOutputsWithOnlyObjects = CcCompilationOutputs.builder().build();
    }

    // Determine the libraries to link in.
    // First libraries from srcs. Shared library artifacts here are substituted with mangled symlink
    // artifacts generated by getDynamicLibraryLink(). This is done to minimize number of -rpath
    // entries during linking process.
    ImmutableList.Builder<LibraryToLink> precompiledLibraries = ImmutableList.builder();
    for (Artifact library : precompiledFiles.getLibraries()) {
      if (Link.SHARED_LIBRARY_FILETYPES.matches(library.getFilename())) {
        LibraryToLink libraryToLink =
            LibraryToLink.builder()
                .setLibraryIdentifier(CcLinkingOutputs.libraryIdentifierOf(library))
                .setDynamicLibrary(
                    common.getDynamicLibrarySymlink(library, /* preserveName= */ true))
                .setResolvedSymlinkDynamicLibrary(library)
                .build();
        precompiledLibraries.add(libraryToLink);
      } else if (Link.LINK_LIBRARY_FILETYPES.matches(library.getFilename())) {
        LibraryToLink libraryToLink =
            LibraryToLink.builder()
                .setLibraryIdentifier(CcLinkingOutputs.libraryIdentifierOf(library))
                .setStaticLibrary(library)
                .setAlwayslink(true)
                .build();
        precompiledLibraries.add(libraryToLink);
      } else if (Link.ARCHIVE_FILETYPES.matches(library.getFilename())) {
        LibraryToLink libraryToLink =
            LibraryToLink.builder()
                .setLibraryIdentifier(CcLinkingOutputs.libraryIdentifierOf(library))
                .setStaticLibrary(library)
                .build();
        precompiledLibraries.add(libraryToLink);
      } else {
        throw new IllegalStateException();
      }
    }
    currentCcLinkingContextBuilder.addLibraries(precompiledLibraries.build());

    ImmutableList.Builder<String> userLinkflags = ImmutableList.builder();
    userLinkflags.addAll(common.getLinkopts());
    currentCcLinkingContextBuilder
        .setOwner(ruleContext.getLabel())
        .addNonCodeInputs(
            ImmutableList.<Artifact>builder()
                .addAll(ccCompilationContext.getTransitiveCompilationPrerequisites())
                .addAll(common.getLinkerScripts())
                .build())
        .addUserLinkFlags(
            ImmutableList.of(
                LinkOptions.of(userLinkflags.build(), ruleContext.getSymbolGenerator())));

    CcInfo ccInfoWithoutExtraLinkTimeLibraries =
        CcInfo.merge(
            ImmutableList.of(
                CcInfo.builder()
                    .setCcLinkingContext(currentCcLinkingContextBuilder.build())
                    .build(),
                depsCcInfo));

    CcInfo extraLinkTimeLibrariesCcInfo =
        CcInfo.builder()
            .setCcLinkingContext(
                CcLinkingContext.builder()
                    .setOwner(ruleContext.getLabel())
                    .addTransitiveLinkerInputs(extraLinkTimeLibraries)
                    .build())
            .build();
    CcInfo ccInfo =
        CcInfo.merge(
            ImmutableList.of(ccInfoWithoutExtraLinkTimeLibraries, extraLinkTimeLibrariesCcInfo));

    CcLinkingContext ccLinkingContext =
        ruleContext.attributes().isAttributeValueExplicitlySpecified("dynamic_deps")
            ? filterLibrariesThatAreLinkedDynamically(
                ruleContext, ccInfo.getCcLinkingContext(), cppSemantics)
            : ccInfo.getCcLinkingContext();
    if (ruleContext.hasErrors()) {
      return null;
    }
    ccLinkingHelper
        .addCcLinkingContexts(ImmutableList.of(ccLinkingContext))
        .setUseTestOnlyFlags(ruleContext.isTestTarget())
        .setShouldCreateStaticLibraries(false)
        .setLinkingMode(linkingMode)
        .setDynamicLinkType(linkType)
        .setLinkerOutputArtifact(binary)
        .setNeverLink(true)
        .emitInterfaceSharedLibraries(
            isLinkShared(ruleContext)
                && featureConfiguration.isEnabled(CppRuleClasses.TARGETS_WINDOWS)
                && CppHelper.useInterfaceSharedLibraries(
                    cppConfiguration, ccToolchain, featureConfiguration))
        .setPdbFile(pdbFile)
        .setFake(fake);

    ccLinkingHelper.setDefFile(winDefFile);

    return Pair.of(
        ccLinkingHelper.link(ccCompilationOutputsWithOnlyObjects),
        new CcLauncherInfo(
            ccInfoWithoutExtraLinkTimeLibraries, ccCompilationOutputsWithOnlyObjects));
  }

  /**
   * Returns "true" if the {@code linkshared} attribute exists and is set.
   */
  private static final boolean isLinkShared(RuleContext context) {
    return context.attributes().has("linkshared", Type.BOOLEAN)
        && context.attributes().get("linkshared", Type.BOOLEAN);
  }

  private static final LinkingMode getLinkStaticness(
      RuleContext context, CppConfiguration cppConfiguration) {
    if (cppConfiguration.getDynamicModeFlag() == DynamicMode.FULLY) {
      return LinkingMode.DYNAMIC;
    } else if (cppConfiguration.getDynamicModeFlag() == DynamicMode.OFF
        || context.attributes().get("linkstatic", Type.BOOLEAN)) {
      return LinkingMode.STATIC;
    } else {
      return LinkingMode.DYNAMIC;
    }
  }

  /**
   * Collects .dwo artifacts either transitively or directly, depending on the link type.
   *
   * <p>For a cc_binary, we only include the .dwo files corresponding to the .o files that are
   * passed into the link. For static linking, this includes all transitive dependencies. But for
   * dynamic linking, dependencies are separately linked into their own shared libraries, so we
   * don't need them here.
   */
  private static NestedSet<Artifact> collectTransitiveDwoArtifacts(
      CcCompilationOutputs compilationOutputs,
      CcDebugInfoContext ccDebugInfoContext,
      LinkingMode linkingMode,
      boolean usePic,
      Iterable<LtoBackendArtifacts> ltoBackendArtifacts) {
    NestedSetBuilder<Artifact> dwoFiles = NestedSetBuilder.stableOrder();
    dwoFiles.addAll(
        usePic ? compilationOutputs.getPicDwoFiles() : compilationOutputs.getDwoFiles());

    if (ltoBackendArtifacts != null) {
      for (LtoBackendArtifacts ltoBackendArtifact : ltoBackendArtifacts) {
        if (ltoBackendArtifact.getDwoFile() != null) {
          dwoFiles.add(ltoBackendArtifact.getDwoFile());
        }
      }
    }

    if (linkingMode != LinkingMode.DYNAMIC) {
      dwoFiles.addTransitive(
          usePic
              ? ccDebugInfoContext.getTransitivePicDwoFiles()
              : ccDebugInfoContext.getTransitiveDwoFiles());
    }
    return dwoFiles.build();
  }

  /**
   * Creates the actions needed to generate this target's "debug info package" (i.e. its .dwp file).
   */
  private static void createDebugPackagerActions(
      RuleContext context,
      CcToolchainProvider toolchain,
      Artifact dwpOutput,
      NestedSet<Artifact> dwoFiles)
      throws RuleErrorException {
    // No inputs? Just generate a trivially empty .dwp.
    //
    // Note this condition automatically triggers for any build where fission is disabled.
    // Because rules referencing .dwp targets may be invoked with or without fission, we need
    // to support .dwp generation even when fission is disabled. Since no actual functionality
    // is expected then, an empty file is appropriate.
    if (Iterables.isEmpty(dwoFiles)) {
      context.registerAction(FileWriteAction.create(context, dwpOutput, "", false));
      return;
    }

    // Get the tool inputs necessary to run the dwp command.
    NestedSet<Artifact> dwpFiles = toolchain.getDwpFiles();
    Preconditions.checkState(!dwpFiles.isEmpty());

    // We apply a hierarchical action structure to limit the maximum number of inputs to any
    // single action.
    //
    // While the dwp tool consumes .dwo files, it can also consume intermediate .dwp files,
    // allowing us to split a large input set into smaller batches of arbitrary size and order.
    // Aside from the parallelism performance benefits this offers, this also reduces input
    // size requirements: if a.dwo, b.dwo, c.dwo, and e.dwo are each 1 KB files, we can apply
    // two intermediate actions DWP(a.dwo, b.dwo) --> i1.dwp and DWP(c.dwo, e.dwo) --> i2.dwp.
    // When we then apply the final action DWP(i1.dwp, i2.dwp) --> finalOutput.dwp, the inputs
    // to this action will usually total far less than 4 KB.
    //
    // The actions form an n-ary tree with n == MAX_INPUTS_PER_DWP_ACTION. The tree is fuller
    // at the leaves than the root, but that both increases parallelism and reduces the final
    // action's input size.
    Packager packager =
        createIntermediateDwpPackagers(context, dwpOutput, toolchain, dwpFiles, dwoFiles, 1);
    packager.spawnAction.setMnemonic("CcGenerateDwp").addOutput(dwpOutput);
    packager.commandLine.addExecPath("-o", dwpOutput);
    context.registerAction(packager.build(context));
  }

  private static class Packager {
    SpawnAction.Builder spawnAction = new SpawnAction.Builder();
    CustomCommandLine.Builder commandLine = CustomCommandLine.builder();

    Action[] build(RuleContext context) {
      spawnAction.addCommandLine(
          commandLine.build(), ParamFileInfo.builder(ParameterFileType.UNQUOTED).build());
      return spawnAction.build(context);
    }
  }

  /**
   * Creates the intermediate actions needed to generate this target's "debug info package" (i.e.
   * its .dwp file).
   */
  private static Packager createIntermediateDwpPackagers(
      RuleContext context,
      Artifact dwpOutput,
      CcToolchainProvider toolchain,
      NestedSet<Artifact> dwpFiles,
      Iterable<Artifact> dwoFiles,
      int intermediateDwpCount)
      throws RuleErrorException {
    List<Packager> packagers = new ArrayList<>();

    // Step 1: generate our batches. We currently break into arbitrary batches of fixed maximum
    // input counts, but we can always apply more intelligent heuristics if the need arises.
    Packager currentPackager = newDwpAction(context, toolchain, dwpFiles);
    int inputsForCurrentPackager = 0;

    for (Artifact dwoFile : dwoFiles) {
      if (inputsForCurrentPackager == MAX_INPUTS_PER_DWP_ACTION) {
        packagers.add(currentPackager);
        currentPackager = newDwpAction(context, toolchain, dwpFiles);
        inputsForCurrentPackager = 0;
      }
      currentPackager.spawnAction.addInput(dwoFile);
      currentPackager.commandLine.addExecPath(dwoFile);
      inputsForCurrentPackager++;
    }
    packagers.add(currentPackager);
    // Step 2: given the batches, create the actions.
    if (packagers.size() > 1) {
      // If we have multiple batches, make them all intermediate actions, then pipe their outputs
      // into an additional level.
      List<Artifact> intermediateOutputs = new ArrayList<>();

      for (Packager packager : packagers) {
        Artifact intermediateOutput =
            getIntermediateDwpFile(context, dwpOutput, intermediateDwpCount++);
        packager.spawnAction.setMnemonic("CcGenerateIntermediateDwp").addOutput(intermediateOutput);
        packager.commandLine.addExecPath("-o", intermediateOutput);
        context.registerAction(packager.build(context));
        intermediateOutputs.add(intermediateOutput);
      }
      return createIntermediateDwpPackagers(
          context, dwpOutput, toolchain, dwpFiles, intermediateOutputs, intermediateDwpCount);
    }
    return Iterables.getOnlyElement(packagers);
  }

  /**
   * Create the actions to symlink/copy execution dynamic libraries to binary directory so that they
   * are available at runtime.
   *
   * @param dynamicLibrariesForRuntime The libraries to be copied.
   * @return The result artifacts of the copies.
   */
  private static NestedSet<Artifact> createDynamicLibrariesCopyActions(
      RuleContext ruleContext, Iterable<Artifact> dynamicLibrariesForRuntime) {
    NestedSetBuilder<Artifact> result = NestedSetBuilder.stableOrder();
    for (Artifact target : dynamicLibrariesForRuntime) {
      if (!ruleContext
          .getLabel()
          .getPackageIdentifier()
          .equals(target.getOwner().getPackageIdentifier())) {
        // SymlinkAction on file is actually copy on Windows.
        Artifact copy = ruleContext.getBinArtifact(target.getFilename());
        ruleContext.registerAction(SymlinkAction.toArtifact(
            ruleContext.getActionOwner(), target, copy, "Copying Execution Dynamic Library"));
        result.add(copy);
      } else {
        // If the target is already in the same directory as the binary, we don't need to copy it,
        // but we still add it the result.
        result.add(target);
      }
    }
    return result.build();
  }

  /**
   * Returns a new SpawnAction builder for generating dwp files, pre-initialized with standard
   * settings.
   */
  private static Packager newDwpAction(
      RuleContext ruleContext, CcToolchainProvider toolchain, NestedSet<Artifact> dwpTools)
      throws RuleErrorException {
    Packager packager = new Packager();
    packager
        .spawnAction
        .addTransitiveInputs(dwpTools)
        .setExecutable(toolchain.getToolPathFragment(Tool.DWP, ruleContext));
    return packager;
  }

  /**
   * Creates an intermediate dwp file keyed off the name and path of the final output.
   */
  private static Artifact getIntermediateDwpFile(RuleContext ruleContext, Artifact dwpOutput,
      int orderNumber) {
    PathFragment outputPath = dwpOutput.getRootRelativePath();
    PathFragment intermediatePath =
        FileSystemUtils.appendWithoutExtension(outputPath, "-" + orderNumber);
    return ruleContext.getPackageRelativeArtifact(
        PathFragment.create(INTERMEDIATE_DWP_DIR + "/" + intermediatePath.getPathString()),
        dwpOutput.getRoot());
  }

  /** Collect link parameters from the transitive closure. */
  private static CcLinkingContext collectCcLinkingContext(RuleContext context) {
    ImmutableList.Builder<CcInfo> ccInfoListBuilder = ImmutableList.builder();

    ccInfoListBuilder.addAll(context.getPrerequisites("deps", Mode.TARGET, CcInfo.PROVIDER));
    if (!isLinkShared(context)) {
      CcInfo ccInfo = CppHelper.mallocForTarget(context).get(CcInfo.PROVIDER);
      if (ccInfo != null) {
        ccInfoListBuilder.add(ccInfo);
      }
    }
    return CcInfo.merge(ccInfoListBuilder.build()).getCcLinkingContext();
  }

  private static void addTransitiveInfoProviders(
      RuleContext ruleContext,
      CcToolchainProvider toolchain,
      CppConfiguration cppConfiguration,
      FeatureConfiguration featureConfiguration,
      CcCommon common,
      RuleConfiguredTargetBuilder builder,
      NestedSet<Artifact> filesToBuild,
      CcCompilationOutputs ccCompilationOutputs,
      CcCompilationContext ccCompilationContext,
      List<LibraryToLink> libraries,
      boolean fake)
      throws RuleErrorException {
    List<Artifact> instrumentedObjectFiles = new ArrayList<>();
    instrumentedObjectFiles.addAll(ccCompilationOutputs.getObjectFiles(false));
    instrumentedObjectFiles.addAll(ccCompilationOutputs.getObjectFiles(true));
    InstrumentedFilesInfo instrumentedFilesProvider =
        common.getInstrumentedFilesProvider(
            instrumentedObjectFiles,
            !TargetUtils.isTestRule(ruleContext.getRule()) && !fake,
            ccCompilationContext.getVirtualToOriginalHeaders());

    NestedSet<Artifact> headerTokens =
        CcCompilationHelper.collectHeaderTokens(
            ruleContext, cppConfiguration, ccCompilationOutputs);

    Map<String, NestedSet<Artifact>> outputGroups =
        CcCompilationHelper.buildOutputGroupsForEmittingCompileProviders(
            ccCompilationOutputs,
            ccCompilationContext,
            cppConfiguration,
            toolchain,
            featureConfiguration,
            ruleContext);

    builder
        .setFilesToBuild(filesToBuild)
        .addNativeDeclaredProvider(
            CcInfo.builder().setCcCompilationContext(ccCompilationContext).build())
        .addProvider(
            CcNativeLibraryProvider.class,
            new CcNativeLibraryProvider(collectTransitiveCcNativeLibraries(ruleContext, libraries)))
        .addNativeDeclaredProvider(instrumentedFilesProvider)
        // For CcBinary targets, we only want to ensure that we process headers in dependencies and
        // thus only add header tokens to HIDDEN_TOP_LEVEL. If we add all HIDDEN_TOP_LEVEL artifacts
        // from dependent CcLibrary targets, we'd be building .pic.o files in nopic builds.
        .addOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL, headerTokens)
        .addOutputGroups(outputGroups);

    CppHelper.maybeAddStaticLinkMarkerProvider(builder, ruleContext);
  }

  private static NestedSet<LibraryToLink> collectTransitiveCcNativeLibraries(
      RuleContext ruleContext, List<LibraryToLink> libraries) {
    NestedSetBuilder<LibraryToLink> builder = NestedSetBuilder.linkOrder();
    builder.addAll(libraries);
    for (CcNativeLibraryProvider dep :
        ruleContext.getPrerequisites("deps", Mode.TARGET, CcNativeLibraryProvider.class)) {
      builder.addTransitive(dep.getTransitiveCcNativeLibraries());
    }
    return builder.build();
  }

  private static boolean usePic(
      RuleContext ruleContext,
      CcToolchainProvider ccToolchainProvider,
      CppConfiguration cppConfiguration,
      FeatureConfiguration featureConfiguration) {
    if (isLinkShared(ruleContext)) {
      return ccToolchainProvider.usePicForDynamicLibraries(cppConfiguration, featureConfiguration);
    } else {
      return CppHelper.usePicForBinaries(
          ccToolchainProvider, cppConfiguration, featureConfiguration);
    }
  }

  private static ImmutableList<Pair<List<String>, CcLinkingContext.LinkerInput>>
      mergeCcSharedLibraryInfos(RuleContext ruleContext, CppSemantics cppSemantics) {
    ImmutableList.Builder<Pair<List<String>, CcLinkingContext.LinkerInput>>
        directMergedCcSharedLibraryInfos = ImmutableList.builder();
    ImmutableList.Builder<Pair<List<String>, CcLinkingContext.LinkerInput>>
        transitiveMergedCcSharedLibraryInfos = ImmutableList.builder();
    for (TransitiveInfoCollection dep : ruleContext.getPrerequisites("dynamic_deps", Mode.TARGET)) {
      StructImpl ccSharedLibraryInfo = cppSemantics.getCcSharedLibraryInfo(dep);
      if (ccSharedLibraryInfo == null) {
        ruleContext.ruleError(
            String.format(
                "The dynamic dep '%s' does not have the 'CcSharedLibraryInfo' provider",
                dep.getLabel()));
        return null;
      }
      try {
        Object exportsField = ccSharedLibraryInfo.getValue("exports");
        if (exportsField == null) {
          ruleContext.ruleError(
              String.format(
                  "The cc_shared_library '%s' does not have an 'exports' field", dep.getLabel()));
          return null;
        }
        ImmutableList<String> exports =
            ImmutableList.copyOf(
                Sequence.castSkylarkListOrNoneToList(exportsField, String.class, "exports"));

        Object linkerInputField = ccSharedLibraryInfo.getValue("linker_input");
        if (linkerInputField == null) {
          ruleContext.ruleError(
              String.format(
                  "The cc_shared_library '%s' does not have a 'linker_input' field",
                  dep.getLabel()));
          return null;
        }
        CcLinkingContext.LinkerInput linkerInput = (CcLinkingContext.LinkerInput) linkerInputField;

        directMergedCcSharedLibraryInfos.add(Pair.of(exports, linkerInput));

        Object dynamicDepsField = ccSharedLibraryInfo.getValue("dynamic_deps");
        if (dynamicDepsField == null) {
          ruleContext.ruleError(
              String.format(
                  "The cc_shared_library '%s' does not have a 'dynamic_deps' field",
                  dep.getLabel()));
          return null;
        }

        @SuppressWarnings("unchecked")
        NestedSet<Tuple<Object>> dynamicDeps =
            (NestedSet<Tuple<Object>>)
                (NestedSet<?>)
                    Depset.getSetFromNoneableParam(dynamicDepsField, Tuple.class, "dynamic_deps");

        for (Tuple<Object> exportsAndLinkerInput : dynamicDeps) {
          List<String> exportsFromDynamicDep =
              Sequence.castSkylarkListOrNoneToList(
                  exportsAndLinkerInput.get(0), String.class, "exports_from_dynamic_deps");
          CcLinkingContext.LinkerInput linkerInputFromDynamicDep =
              (CcLinkingContext.LinkerInput) exportsAndLinkerInput.get(1);
          transitiveMergedCcSharedLibraryInfos.add(
              Pair.of(exportsFromDynamicDep, linkerInputFromDynamicDep));
        }
      } catch (EvalException e) {
        ruleContext.ruleError(
            String.format(
                "In the cc_shared_library rule '%s': %s", dep.getLabel(), e.getMessage()));
        return null;
      }
    }
    return ImmutableList.<Pair<List<String>, CcLinkingContext.LinkerInput>>builder()
        .addAll(directMergedCcSharedLibraryInfos.build())
        .addAll(transitiveMergedCcSharedLibraryInfos.build())
        .build();
  }

  private static ImmutableMap<String, CcLinkingContext.LinkerInput>
      buildExportsMapFromOnlyDynamicDeps(
          RuleContext ruleContext,
          ImmutableList<Pair<List<String>, CcLinkingContext.LinkerInput>>
              mergedCcSharedLibraryInfos) {
    Map<String, CcLinkingContext.LinkerInput> exportsMap = new HashMap<>();
    for (Pair<List<String>, CcLinkingContext.LinkerInput> entry : mergedCcSharedLibraryInfos) {
      List<String> exports = entry.first;
      CcLinkingContext.LinkerInput linkerInput = entry.second;
      for (String export : exports) {
        if (exportsMap.containsKey(export)) {
          ruleContext.ruleError(
              "Two shared libraries in dependencies export the same symbols. Both "
                  + exportsMap
                      .get(export)
                      .getLibraries()
                      .get(0)
                      .getDynamicLibrary()
                      .getExecPathString()
                  + " and "
                  + linkerInput.getLibraries().get(0).getDynamicLibrary().getExecPathString()
                  + " export "
                  + export);
        }
        exportsMap.put(export, linkerInput);
      }
    }
    return ImmutableMap.copyOf(exportsMap);
  }

  private static Pair<ImmutableSet<String>, ImmutableSet<String>>
      separateStaticAndDynamicLinkLibraries(
          ImmutableList<GraphNodeInfo> directChildren, Set<String> canBeLinkedDynamically) {
    GraphNodeInfo node = null;
    Queue<GraphNodeInfo> allChildren = new ArrayDeque<>(directChildren);
    ImmutableSet.Builder<String> linkStaticallyLabels = ImmutableSet.builder();
    ImmutableSet.Builder<String> linkDynamicallyLabels = ImmutableSet.builder();

    while (!allChildren.isEmpty()) {
      node = allChildren.poll();
      if (canBeLinkedDynamically.contains(node.getLabel().toString())) {
        linkDynamicallyLabels.add(node.getLabel().toString());
      } else {
        linkStaticallyLabels.add(node.getLabel().toString());
        allChildren.addAll(node.getChildren());
      }
    }

    return Pair.of(linkStaticallyLabels.build(), linkDynamicallyLabels.build());
  }

  private static ImmutableList<CcLinkingContext.LinkerInput> filterInputs(
      RuleContext ruleContext,
      CcLinkingContext ccLinkingContext,
      ImmutableMap<String, CcLinkingContext.LinkerInput> exportsMap) {
    ImmutableList.Builder<CcLinkingContext.LinkerInput> staticLinkerInputs =
        ImmutableList.builder();
    ImmutableList.Builder<GraphNodeInfo> graphStructureAspectNodes = ImmutableList.builder();
    List<CcLinkingContext.LinkerInput> linkerInputs = new ArrayList<>();

    linkerInputs.addAll(ccLinkingContext.getLinkerInputs().toList());
    for (TransitiveInfoCollection dep : ruleContext.getPrerequisites("deps", Mode.TARGET)) {
      graphStructureAspectNodes.add(dep.getProvider(GraphNodeInfo.class));
    }
    graphStructureAspectNodes.add(
        CppHelper.mallocForTarget(ruleContext).getProvider(GraphNodeInfo.class));

    Set<String> canBeLinkedDynamically = new HashSet<>();
    for (CcLinkingContext.LinkerInput linkerInput : linkerInputs) {
      String owner = linkerInput.getOwner().toString();
      if (exportsMap.containsKey(owner)) {
        canBeLinkedDynamically.add(owner);
      }
    }

    Pair<ImmutableSet<String>, ImmutableSet<String>> linkStaticallyAndDynamicallyLabels =
        separateStaticAndDynamicLinkLibraries(
            graphStructureAspectNodes.build(), canBeLinkedDynamically);

    Set<String> ownersSeen = new HashSet<>();
    for (CcLinkingContext.LinkerInput linkerInput : linkerInputs) {
      String owner = linkerInput.getOwner().toString();
      if (ownersSeen.contains(owner)) {
        continue;
      }
      ownersSeen.add(owner);
      if (!linkStaticallyAndDynamicallyLabels.second.contains(owner)
          && (linkStaticallyAndDynamicallyLabels.first.contains(owner)
              || ruleContext.getLabel().toString().equals(owner))) {
        staticLinkerInputs.add(linkerInput);
      }
    }

    return staticLinkerInputs.build();
  }

  private static CcLinkingContext createLinkingContextWithDynamicDependencies(
      ImmutableList<CcLinkingContext.LinkerInput> staticLinkerInputs,
      ImmutableList<CcLinkingContext.LinkerInput> preloadedDeps,
      ImmutableCollection<CcLinkingContext.LinkerInput> dynamicLinkerInputs) {

    return CcLinkingContext.builder()
        .addTransitiveLinkerInputs(
            NestedSetBuilder.<CcLinkingContext.LinkerInput>linkOrder()
                .addAll(dynamicLinkerInputs)
                .addAll(staticLinkerInputs)
                .addAll(preloadedDeps)
                .build())
        .build();
  }

  private static ImmutableList<CcLinkingContext.LinkerInput> getPreloadedDepsFromDynamicDeps(
      RuleContext ruleContext, CppSemantics cppSemantics) {
    ImmutableList.Builder<CcInfo> ccInfos = ImmutableList.builder();
    for (TransitiveInfoCollection dep : ruleContext.getPrerequisites("dynamic_deps", Mode.TARGET)) {
      StructImpl ccSharedLibraryInfo = cppSemantics.getCcSharedLibraryInfo(dep);
      try {
        Object preloadedDepsField = ccSharedLibraryInfo.getValue("preloaded_deps");
        if (preloadedDepsField == null) {
          ruleContext.ruleError(
              String.format(
                  "The cc_shared_library '%s' does not have an 'preloaded_deps' field",
                  dep.getLabel()));
          return null;
        }
        if (!Starlark.NONE.equals(preloadedDepsField)) {
          ccInfos.add((CcInfo) preloadedDepsField);
        }
      } catch (EvalException e) {
        ruleContext.ruleError(
            String.format(
                "In the cc_shared_library rule '%s': %s", dep.getLabel(), e.getMessage()));
        return null;
      }
    }
    return CcInfo.merge(ccInfos.build()).getCcLinkingContext().getLinkerInputs().toList();
  }

  private static CcLinkingContext filterLibrariesThatAreLinkedDynamically(
      RuleContext ruleContext, CcLinkingContext ccLinkingContext, CppSemantics cppSemantics) {
    ImmutableList<Pair<List<String>, CcLinkingContext.LinkerInput>> mergedCcSharedLibraryInfos =
        mergeCcSharedLibraryInfos(ruleContext, cppSemantics);
    ImmutableList<CcLinkingContext.LinkerInput> preloadedDeps =
        getPreloadedDepsFromDynamicDeps(ruleContext, cppSemantics);
    if (ruleContext.hasErrors()) {
      return null;
    }

    ImmutableMap<String, CcLinkingContext.LinkerInput> exportsMap =
        buildExportsMapFromOnlyDynamicDeps(ruleContext, mergedCcSharedLibraryInfos);
    ImmutableList<CcLinkingContext.LinkerInput> staticLinkerInputs =
        filterInputs(ruleContext, ccLinkingContext, exportsMap);

    return createLinkingContextWithDynamicDependencies(
        staticLinkerInputs, preloadedDeps, exportsMap.values());
  }
}
