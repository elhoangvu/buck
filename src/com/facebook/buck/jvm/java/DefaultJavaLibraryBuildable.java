/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesAction;
import com.facebook.buck.jvm.java.version.JavaVersion;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.PipelinedBuildable;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.rules.modern.impl.ModernBuildableSupport;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.java.MakeMissingOutputsStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Buildable for DefaultJavaLibrary. */
class DefaultJavaLibraryBuildable implements PipelinedBuildable<JavacPipelineState> {

  @AddToRuleKey private final int buckJavaVersion = JavaVersion.getMajorVersion();
  @AddToRuleKey private final JarBuildStepsFactory jarBuildStepsFactory;
  @AddToRuleKey private final UnusedDependenciesAction unusedDependenciesAction;
  @AddToRuleKey private final Optional<NonHashableSourcePathContainer> sourceAbiOutput;

  @AddToRuleKey private final OutputPath rootOutputPath;
  @AddToRuleKey private final OutputPath pathToClassHashesOutputPath;
  @AddToRuleKey private final OutputPath annotationsOutputPath;

  @AddToRuleKey(stringify = true)
  @CustomFieldBehavior(DefaultFieldSerialization.class)
  private final BuildTarget buildTarget;

  @AddToRuleKey
  private final Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory;

  DefaultJavaLibraryBuildable(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      JarBuildStepsFactory jarBuildStepsFactory,
      UnusedDependenciesAction unusedDependenciesAction,
      Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory,
      @Nullable CalculateSourceAbi sourceAbi) {
    this.jarBuildStepsFactory = jarBuildStepsFactory;
    this.unusedDependenciesAction = unusedDependenciesAction;
    this.buildTarget = buildTarget;
    this.unusedDependenciesFinderFactory = unusedDependenciesFinderFactory;
    this.sourceAbiOutput =
        Optional.ofNullable(sourceAbi)
            .map(
                rule ->
                    new NonHashableSourcePathContainer(
                        Objects.requireNonNull(rule.getSourcePathToOutput())));

    CompilerOutputPaths outputPaths =
        CompilerOutputPaths.of(buildTarget, filesystem.getBuckPaths());

    RelPath pathToClassHashes = getPathToClassHashes(filesystem);
    this.pathToClassHashesOutputPath = new PublicOutputPath(pathToClassHashes);

    this.rootOutputPath = new PublicOutputPath(outputPaths.getOutputJarDirPath());
    this.annotationsOutputPath = new PublicOutputPath(outputPaths.getAnnotationPath());
  }

  RelPath getPathToClassHashes(ProjectFilesystem filesystem) {
    return JavaLibraryRules.getPathToClassHashes(buildTarget, filesystem);
  }

  public ImmutableSortedSet<SourcePath> getSources() {
    return jarBuildStepsFactory.getSources();
  }

  public ImmutableSortedSet<SourcePath> getResources() {
    return jarBuildStepsFactory.getResources();
  }

  public Optional<String> getResourcesRoot() {
    return jarBuildStepsFactory.getResourcesRoot();
  }

  public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    return jarBuildStepsFactory.getCompileTimeClasspathSourcePaths();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    jarBuildStepsFactory.addBuildStepsForLibraryJar(
        buildContext,
        filesystem,
        ModernBuildableSupport.getDerivedArtifactVerifier(buildTarget, filesystem, this),
        buildTarget,
        outputPathResolver.resolvePath(pathToClassHashesOutputPath),
        steps);

    maybeAddUnusedDependencyStepAndAddMakeMissingOutputStep(
        buildContext, outputPathResolver, filesystem, steps);

    return buildAndMaybeVerifySteps(steps, false);
  }

  @Override
  public ImmutableList<Step> getPipelinedBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      JavacPipelineState state,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    jarBuildStepsFactory.addPipelinedBuildStepsForLibraryJar(
        buildContext,
        filesystem,
        ModernBuildableSupport.getDerivedArtifactVerifier(buildTarget, filesystem, this),
        state,
        outputPathResolver.resolvePath(pathToClassHashesOutputPath),
        steps);

    maybeAddUnusedDependencyStepAndAddMakeMissingOutputStep(
        buildContext, outputPathResolver, filesystem, steps);

    return buildAndMaybeVerifySteps(steps, true);
  }

  private ImmutableList<Step> buildAndMaybeVerifySteps(
      ImmutableList.Builder<Step> steps, boolean isPipelineSteps) {
    ImmutableList<Step> stepList = steps.build();
    if (jarBuildStepsFactory.areAllStepsConvertedToIsolatedSteps()) {
      verifyThatStepsAreIsolated(stepList, isPipelineSteps);
    }
    return stepList;
  }

  private void verifyThatStepsAreIsolated(ImmutableList<Step> stepList, boolean isPipelineSteps) {
    Predicate<Step> isolatedStepPredicate = item -> item instanceof IsolatedStep;
    boolean allIsolatedSteps = stepList.stream().allMatch(isolatedStepPredicate);
    if (!allIsolatedSteps) {
      String notIsolatedSteps =
          stepList.stream()
              .filter(isolatedStepPredicate.negate())
              .map(step -> step.getClass().getSimpleName() + ": " + step.getShortName())
              .collect(Collectors.joining());
      throw new IllegalStateException(
          "Found not isolated steps: "
              + notIsolatedSteps
              + ". Is pipeline steps: "
              + isPipelineSteps);
    }
  }

  private void maybeAddUnusedDependencyStepAndAddMakeMissingOutputStep(
      BuildContext buildContext,
      OutputPathResolver outputPathResolver,
      ProjectFilesystem filesystem,
      ImmutableList.Builder<Step> steps) {

    unusedDependenciesFinderFactory.ifPresent(
        factory ->
            steps.add(
                getUnusedDependenciesStep(
                    factory,
                    filesystem,
                    buildContext.getSourcePathResolver(),
                    buildContext.getCellPathResolver())));
    steps.add(getMakeMissingOutputsStep(outputPathResolver));
  }

  private IsolatedStep getUnusedDependenciesStep(
      UnusedDependenciesFinderFactory factory,
      ProjectFilesystem filesystem,
      SourcePathResolverAdapter sourcePathResolver,
      CellPathResolver cellPathResolver) {
    return factory.create(
        buildTarget, filesystem, sourcePathResolver, unusedDependenciesAction, cellPathResolver);
  }

  private IsolatedStep getMakeMissingOutputsStep(OutputPathResolver outputPathResolver) {
    return new MakeMissingOutputsStep(
        outputPathResolver.resolvePath(rootOutputPath),
        outputPathResolver.resolvePath(pathToClassHashesOutputPath),
        outputPathResolver.resolvePath(annotationsOutputPath));
  }

  public boolean useDependencyFileRuleKeys() {
    return jarBuildStepsFactory.useDependencyFileRuleKeys();
  }

  public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathRuleFinder ruleFinder) {
    return jarBuildStepsFactory.getCoveredByDepFilePredicate(ruleFinder);
  }

  public Predicate<SourcePath> getExistenceOfInterestPredicate() {
    return jarBuildStepsFactory.getExistenceOfInterestPredicate();
  }

  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellPathResolver) {
    return jarBuildStepsFactory.getInputsAfterBuildingLocally(
        context, projectFilesystem, ruleFinder, cellPathResolver, buildTarget);
  }

  public boolean useRulePipelining() {
    return jarBuildStepsFactory.useRulePipelining();
  }

  public RulePipelineStateFactory<JavacPipelineState> getPipelineStateFactory() {
    return jarBuildStepsFactory;
  }

  public boolean hasAnnotationProcessing() {
    return jarBuildStepsFactory.hasAnnotationProcessing();
  }
}
