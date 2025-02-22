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

package com.google.devtools.build.lib.rules.java;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.EmptyRunfilesSupplier;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.RunfilesSupplier;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.actions.extra.JavaCompileInfo;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.StrictDepsMode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.rules.java.JavaCompileAction.ProgressMessage;
import com.google.devtools.build.lib.rules.java.JavaConfiguration.JavaClasspathMode;
import com.google.devtools.build.lib.rules.java.JavaPluginInfoProvider.JavaPluginInfo;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.StringCanonicalizer;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/** Java compilation action builder. */
public final class JavaCompileActionBuilder {

  private static final String JACOCO_INSTRUMENTATION_PROCESSOR = "jacoco";

  /** Environment variable that sets the UTF-8 charset. */
  static final ImmutableMap<String, String> UTF8_ENVIRONMENT =
      ImmutableMap.of("LC_CTYPE", "en_US.UTF-8");

  static final String MNEMONIC = "Javac";

  /** Returns true if this is a Java compile action. */
  public static boolean isJavaCompileAction(ActionAnalysisMetadata action) {
    return action != null && action.getMnemonic().equals(MNEMONIC);
  }

  @ThreadCompatible
  @Immutable
  @AutoCodec
  static class JavaCompileExtraActionInfoSupplier
      implements JavaCompileAction.ExtraActionInfoSupplier {

    private final Artifact outputJar;

    /** The list of classpath entries to specify to javac. */
    private final NestedSet<Artifact> classpathEntries;

    /** The list of bootclasspath entries to specify to javac. */
    private final NestedSet<Artifact> bootclasspathEntries;

    /** The list of classpath entries to search for annotation processors. */
    private final NestedSet<Artifact> processorPath;

    /** The list of annotation processor classes to run. */
    private final NestedSet<String> processorNames;

    /** Set of additional Java source files to compile. */
    private final ImmutableList<Artifact> sourceJars;

    /** The set of explicit Java source files to compile. */
    private final ImmutableSet<Artifact> sourceFiles;

    /** The compiler options to pass to javac. */
    private final ImmutableList<String> javacOpts;

    JavaCompileExtraActionInfoSupplier(
        Artifact outputJar,
        NestedSet<Artifact> classpathEntries,
        NestedSet<Artifact> bootclasspathEntries,
        NestedSet<Artifact> processorPath,
        NestedSet<String> processorNames,
        ImmutableList<Artifact> sourceJars,
        ImmutableSet<Artifact> sourceFiles,
        ImmutableList<String> javacOpts) {
      this.outputJar = outputJar;
      this.classpathEntries = classpathEntries;
      this.bootclasspathEntries = bootclasspathEntries;
      this.processorPath = processorPath;
      this.processorNames = processorNames;
      this.sourceJars = sourceJars;
      this.sourceFiles = sourceFiles;
      this.javacOpts = javacOpts;
    }

    @Override
    public void extend(ExtraActionInfo.Builder builder, ImmutableList<String> arguments) {
      JavaCompileInfo.Builder info =
          JavaCompileInfo.newBuilder()
              .addAllSourceFile(Artifact.toExecPaths(sourceFiles))
              .addAllClasspath(Artifact.toExecPaths(classpathEntries))
              .addAllBootclasspath(Artifact.toExecPaths(bootclasspathEntries))
              .addAllSourcepath(Artifact.toExecPaths(sourceJars))
              .addAllJavacOpt(javacOpts)
              .addAllProcessor(processorNames)
              .addAllProcessorpath(Artifact.toExecPaths(processorPath))
              .setOutputjar(outputJar.getExecPathString());
      info.addAllArgument(arguments);
      builder.setExtension(JavaCompileInfo.javaCompileInfo, info.build());
    }
  }

  private final ActionOwner owner;
  private final BuildConfiguration configuration;
  private final JavaToolchainProvider toolchain;
  private PathFragment javaExecutable;
  private List<Artifact> javabaseInputs = ImmutableList.of();
  private ImmutableSet<Artifact> additionalOutputs = ImmutableSet.of();
  private Artifact coverageArtifact;
  private ImmutableSet<Artifact> sourceFiles = ImmutableSet.of();
  private ImmutableList<Artifact> sourceJars = ImmutableList.of();
  private StrictDepsMode strictJavaDeps = StrictDepsMode.ERROR;
  private String fixDepsTool = "add_dep";
  private NestedSet<Artifact> directJars = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER);
  private NestedSet<Artifact> compileTimeDependencyArtifacts =
      NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  private List<String> javacOpts = new ArrayList<>();
  private ImmutableList<String> javacJvmOpts = ImmutableList.of();
  private ImmutableMap<String, String> executionInfo = ImmutableMap.of();
  private boolean compressJar;
  private NestedSet<Artifact> classpathEntries = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER);
  private NestedSet<Artifact> bootclasspathEntries =
      NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER);
  private ImmutableList<Artifact> sourcePathEntries = ImmutableList.of();
  private ImmutableList<Artifact> extdirInputs = ImmutableList.of();
  private FilesToRunProvider javaBuilder;
  private NestedSet<Artifact> toolsJars = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER);
  private PathFragment sourceGenDirectory;
  private PathFragment tempDirectory;
  private PathFragment classDirectory;
  private JavaPluginInfo plugins = JavaPluginInfo.empty();
  private ImmutableSet<String> builtinProcessorNames = ImmutableSet.of();
  private NestedSet<Artifact> extraData = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER);
  private Label targetLabel;
  @Nullable private String injectingRuleKind;
  private JavaCompileOutputs<Artifact> outputs;

  public JavaCompileActionBuilder(
      ActionOwner owner, BuildConfiguration configuration, JavaToolchainProvider toolchain) {
    this.owner = owner;
    this.configuration = configuration;
    this.toolchain = toolchain;
  }

  public JavaCompileAction build() {
    // TODO(bazel-team): all the params should be calculated before getting here, and the various
    // aggregation code below should go away.
    ImmutableList<String> internedJcopts =
        javacOpts.stream().map(StringCanonicalizer::intern).collect(toImmutableList());

    // Invariant: if strictJavaDeps is OFF, then directJars and
    // dependencyArtifacts are ignored
    if (strictJavaDeps == StrictDepsMode.OFF) {
      directJars = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER);
      compileTimeDependencyArtifacts = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    // Invariant: if java_classpath is set to 'off', dependencyArtifacts are ignored
    JavaConfiguration javaConfiguration = configuration.getFragment(JavaConfiguration.class);
    JavaClasspathMode classpathMode = javaConfiguration.getReduceJavaClasspath();
    if (!Collections.disjoint(
        plugins.processorClasses().toSet(),
        toolchain.getReducedClasspathIncompatibleProcessors())) {
      classpathMode = JavaClasspathMode.OFF;
    }
    if (classpathMode == JavaClasspathMode.OFF) {
      compileTimeDependencyArtifacts = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    Preconditions.checkState(javaExecutable != null, owner);

    CustomCommandLine.Builder executableLine = CustomCommandLine.builder();
    NestedSetBuilder<Artifact> toolsBuilder = NestedSetBuilder.compileOrder();

    RunfilesSupplier runfilesSupplier = EmptyRunfilesSupplier.INSTANCE;

    // The actual params-file-based command line executed for a compile action.
    Artifact javaBuilderJar = checkNotNull(javaBuilder.getExecutable());
    if (!javaBuilderJar.getExtension().equals("jar")) {
      // JavaBuilder is a non-deploy.jar executable.
      executableLine.addPath(javaBuilder.getExecutable().getExecPath());
      runfilesSupplier = javaBuilder.getRunfilesSupplier();
      toolsBuilder.addTransitive(javaBuilder.getFilesToRun());
    } else {
      toolsBuilder.add(javaBuilderJar);
      executableLine
          .addPath(javaExecutable)
          .addAll(javacJvmOpts)
          .add("-jar")
          .addPath(javaBuilderJar.getExecPath());
    }
    toolsBuilder.addTransitive(toolsJars);

    ActionEnvironment actionEnvironment =
        configuration.getActionEnvironment().addFixedVariables(UTF8_ENVIRONMENT);

    NestedSetBuilder<Artifact> mandatoryInputs = NestedSetBuilder.stableOrder();
    mandatoryInputs
        .addTransitive(compileTimeDependencyArtifacts)
        .addTransitive(plugins.processorClasspath())
        .addTransitive(plugins.data())
        .addTransitive(extraData)
        .addAll(sourceJars)
        .addAll(sourceFiles)
        .addAll(javabaseInputs)
        .addTransitive(bootclasspathEntries)
        .addAll(sourcePathEntries)
        .addAll(extdirInputs);
    if (coverageArtifact != null) {
      mandatoryInputs.add(coverageArtifact);
    }

    JavaCompileExtraActionInfoSupplier extraActionInfoSupplier =
        new JavaCompileExtraActionInfoSupplier(
            outputs.output(),
            classpathEntries,
            bootclasspathEntries,
            plugins.processorClasspath(),
            plugins.processorClasses(),
            sourceJars,
            sourceFiles,
            internedJcopts);

    // TODO(b/123076347): outputDepsProto should never be null if SJD is enabled
    if (strictJavaDeps == StrictDepsMode.OFF || outputs.depsProto() == null) {
      classpathMode = JavaClasspathMode.OFF;
    }

    if (outputs.depsProto() != null) {
      if (javaConfiguration.inmemoryJdepsFiles()) {
        executionInfo =
            ImmutableMap.<String, String>builderWithExpectedSize(this.executionInfo.size() + 1)
                .putAll(this.executionInfo)
                .put(
                    ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS,
                    outputs.depsProto().getExecPathString())
                .build();
      }
    }

    NestedSet<Artifact> tools = toolsBuilder.build();
    mandatoryInputs.addTransitive(tools);
    return new JavaCompileAction(
        /* compilationType= */ JavaCompileAction.CompilationType.JAVAC,
        /* owner= */ owner,
        /* env= */ actionEnvironment,
        /* tools= */ tools,
        /* runfilesSupplier= */ runfilesSupplier,
        /* progressMessage= */ new ProgressMessage(
            /* prefix= */ "Building",
            /* output= */ outputs.output(),
            /* sourceFiles= */ sourceFiles,
            /* sourceJars= */ sourceJars,
            /* plugins= */ plugins),
        /* mandatoryInputs= */ mandatoryInputs.build(),
        /* transitiveInputs= */ classpathEntries,
        /* directJars= */ directJars,
        /* outputs= */ allOutputs(),
        /* executionInfo= */ executionInfo,
        /* extraActionInfoSupplier= */ extraActionInfoSupplier,
        /* executableLine= */ executableLine.build(),
        /* flagLine= */ buildParamFileContents(internedJcopts),
        /* configuration= */ configuration,
        /* dependencyArtifacts= */ compileTimeDependencyArtifacts,
        /* outputDepsProto= */ outputs.depsProto(),
        /* classpathMode= */ classpathMode);
  }

  private NestedSet<Artifact> allOutputs() {
    return NestedSetBuilder.<Artifact>stableOrder()
        .addTransitive(outputs.toNestedSet())
        .addAll(additionalOutputs)
        .build();
  }

  private CustomCommandLine buildParamFileContents(Collection<String> javacOpts) {
    checkNotNull(classDirectory, "classDirectory should not be null");
    checkNotNull(tempDirectory, "tempDirectory should not be null");

    CustomCommandLine.Builder result = CustomCommandLine.builder();

    result.addPath("--classdir", classDirectory);
    result.addPath("--tempdir", tempDirectory);
    result.addExecPath("--output", outputs.output());
    result.addExecPath("--native_header_output", outputs.nativeHeader());
    result.addPath("--sourcegendir", sourceGenDirectory);
    result.addExecPath("--generated_sources_output", outputs.genSource());
    result.addExecPath("--output_manifest_proto", outputs.manifestProto());
    if (compressJar) {
      result.add("--compress_jar");
    }
    result.addExecPath("--output_deps_proto", outputs.depsProto());
    result.addExecPaths("--extclasspath", extdirInputs);
    result.addExecPaths("--bootclasspath", bootclasspathEntries);
    result.addExecPaths("--sourcepath", sourcePathEntries);
    result.addExecPaths("--processorpath", plugins.processorClasspath());
    result.addAll("--processors", plugins.processorClasses());
    result.addAll(
        "--builtin_processors",
        Sets.intersection(plugins.processorClasses().toSet(), builtinProcessorNames));
    result.addExecPaths("--source_jars", ImmutableList.copyOf(sourceJars));
    result.addExecPaths("--sources", sourceFiles);
    if (!javacOpts.isEmpty()) {
      result.addAll("--javacopts", ImmutableList.copyOf(javacOpts));
      // terminate --javacopts with `--` to support javac flags that start with `--`
      result.add("--");
    }
    if (targetLabel != null) {
      result.add("--target_label");
      if (targetLabel.getPackageIdentifier().getRepository().isDefault()
          || targetLabel.getPackageIdentifier().getRepository().isMain()) {
        result.addLabel(targetLabel);
      } else {
        // @-prefixed strings will be assumed to be filenames and expanded by
        // {@link JavaLibraryBuildRequest}, so add an extra &at; to escape it.
        result.addPrefixedLabel("@", targetLabel);
      }
    }
    result.add("--injecting_rule_kind", injectingRuleKind);
    // strict_java_deps controls whether the mapping from jars to targets is
    // written out and whether we try to minimize the compile-time classpath.
    if (strictJavaDeps != StrictDepsMode.OFF) {
      result.add("--strict_java_deps", strictJavaDeps.toString());
      result.addExecPaths("--direct_dependencies", directJars);
    }
    result.add("--experimental_fix_deps_tool", fixDepsTool);

    // Chose what artifact to pass to JavaBuilder, as input to jacoco instrumentation processor.
    // metadata should be null when --experimental_java_coverage is true.
    if (coverageArtifact != null) {
      result.add("--post_processor");
      result.addExecPath(JACOCO_INSTRUMENTATION_PROCESSOR, coverageArtifact);
      result.addPath(
          configuration
              .getCoverageMetadataDirectory(targetLabel.getPackageIdentifier().getRepository())
              .getExecPath());
      result.add("-*Test");
      result.add("-*TestCase");
    }
    return result.build();
  }

  public JavaCompileActionBuilder setJavaExecutable(PathFragment javaExecutable) {
    this.javaExecutable = javaExecutable;
    return this;
  }

  public JavaCompileActionBuilder setJavaBaseInputs(Iterable<Artifact> javabaseInputs) {
    this.javabaseInputs = ImmutableList.copyOf(javabaseInputs);
    return this;
  }

  public JavaCompileActionBuilder setAdditionalOutputs(ImmutableSet<Artifact> outputs) {
    this.additionalOutputs = outputs;
    return this;
  }

  public JavaCompileActionBuilder setSourceFiles(ImmutableSet<Artifact> sourceFiles) {
    this.sourceFiles = sourceFiles;
    return this;
  }

  public JavaCompileActionBuilder setSourceJars(ImmutableList<Artifact> sourceJars) {
    checkState(this.sourceJars.isEmpty());
    this.sourceJars = checkNotNull(sourceJars, "sourceJars must not be null");
    return this;
  }

  /** Sets the strictness of Java dependency checking, see {@link StrictDepsMode}. */
  public JavaCompileActionBuilder setStrictJavaDeps(StrictDepsMode strictDeps) {
    strictJavaDeps = strictDeps;
    return this;
  }

  /** Sets the tool with which to fix dependency errors. */
  public JavaCompileActionBuilder setFixDepsTool(String depsTool) {
    fixDepsTool = depsTool;
    return this;
  }

  /** Accumulates the given jar artifacts as being provided by direct dependencies. */
  public JavaCompileActionBuilder setDirectJars(NestedSet<Artifact> directJars) {
    this.directJars = checkNotNull(directJars, "directJars must not be null");
    return this;
  }

  public JavaCompileActionBuilder setCompileTimeDependencyArtifacts(
      NestedSet<Artifact> dependencyArtifacts) {
    checkNotNull(compileTimeDependencyArtifacts, "dependencyArtifacts must not be null");
    this.compileTimeDependencyArtifacts = dependencyArtifacts;
    return this;
  }

  public JavaCompileActionBuilder setJavacOpts(Iterable<String> copts) {
    this.javacOpts = ImmutableList.copyOf(copts);
    return this;
  }

  public JavaCompileActionBuilder setJavacJvmOpts(ImmutableList<String> opts) {
    this.javacJvmOpts = opts;
    return this;
  }

  public JavaCompileActionBuilder setJavacExecutionInfo(
      ImmutableMap<String, String> executionInfo) {
    this.executionInfo = executionInfo;
    return this;
  }

  public JavaCompileActionBuilder setCompressJar(boolean compressJar) {
    this.compressJar = compressJar;
    return this;
  }

  public JavaCompileActionBuilder setClasspathEntries(NestedSet<Artifact> classpathEntries) {
    this.classpathEntries = classpathEntries;
    return this;
  }

  public JavaCompileActionBuilder setBootclasspathEntries(
      NestedSet<Artifact> bootclasspathEntries) {
    this.bootclasspathEntries = bootclasspathEntries;
    return this;
  }

  public JavaCompileActionBuilder setSourcePathEntries(Iterable<Artifact> sourcePathEntries) {
    this.sourcePathEntries = ImmutableList.copyOf(sourcePathEntries);
    return this;
  }

  public JavaCompileActionBuilder setExtdirInputs(Iterable<Artifact> extdirEntries) {
    this.extdirInputs = ImmutableList.copyOf(extdirEntries);
    return this;
  }

  /** Sets the directory where source files generated by annotation processors should be stored. */
  public JavaCompileActionBuilder setSourceGenDirectory(PathFragment sourceGenDirectory) {
    this.sourceGenDirectory = sourceGenDirectory;
    return this;
  }

  public JavaCompileActionBuilder setTempDirectory(PathFragment tempDirectory) {
    this.tempDirectory = tempDirectory;
    return this;
  }

  public JavaCompileActionBuilder setClassDirectory(PathFragment classDirectory) {
    this.classDirectory = classDirectory;
    return this;
  }

  public JavaCompileActionBuilder setPlugins(JavaPluginInfo plugins) {
    checkNotNull(plugins, "plugins must not be null");
    checkState(this.plugins.isEmpty());
    this.plugins = plugins;
    return this;
  }

  public JavaCompileActionBuilder setBuiltinProcessorNames(
      ImmutableSet<String> builtinProcessorNames) {
    this.builtinProcessorNames =
        checkNotNull(builtinProcessorNames, "builtinProcessorNames must not be null");
    return this;
  }

  public void setExtraData(NestedSet<Artifact> extraData) {
    checkNotNull(extraData, "extraData must not be null");
    checkState(this.extraData.isEmpty());
    this.extraData = extraData;
  }

  /** Sets the tools jars. */
  public JavaCompileActionBuilder setToolsJars(NestedSet<Artifact> toolsJars) {
    checkNotNull(toolsJars, "toolsJars must not be null");
    this.toolsJars = toolsJars;
    return this;
  }

  public JavaCompileActionBuilder setJavaBuilder(FilesToRunProvider javaBuilder) {
    this.javaBuilder = javaBuilder;
    return this;
  }

  public JavaCompileActionBuilder setCoverageArtifact(Artifact coverageArtifact) {
    this.coverageArtifact = coverageArtifact;
    return this;
  }

  public JavaCompileActionBuilder setTargetLabel(Label targetLabel) {
    this.targetLabel = targetLabel;
    return this;
  }

  public JavaCompileActionBuilder setInjectingRuleKind(@Nullable String injectingRuleKind) {
    this.injectingRuleKind = injectingRuleKind;
    return this;
  }

  public void setOutputs(JavaCompileOutputs<Artifact> outputs) {
    this.outputs = outputs;
  }
}
