/*
 * Copyright 2017 ThoughtWorks, Inc.
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

package com.thoughtworks.go.config;

import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.remote.FileConfigOrigin;
import com.thoughtworks.go.config.remote.RepoConfigOrigin;
import com.thoughtworks.go.domain.config.ConfigurationKey;
import com.thoughtworks.go.domain.config.ConfigurationProperty;
import com.thoughtworks.go.domain.config.ConfigurationValue;
import com.thoughtworks.go.domain.config.EncryptedConfigurationValue;
import com.thoughtworks.go.domain.packagerepository.ConfigurationPropertyMother;
import com.thoughtworks.go.helper.GoConfigMother;
import com.thoughtworks.go.helper.MaterialConfigsMother;
import com.thoughtworks.go.helper.PipelineConfigMother;
import com.thoughtworks.go.helper.StageConfigMother;
import com.thoughtworks.go.plugin.access.artifact.ArtifactMetadataStore;
import com.thoughtworks.go.plugin.api.info.PluginDescriptor;
import com.thoughtworks.go.plugin.domain.artifact.ArtifactPluginInfo;
import com.thoughtworks.go.plugin.domain.common.Metadata;
import com.thoughtworks.go.plugin.domain.common.PluggableInstanceSettings;
import com.thoughtworks.go.plugin.domain.common.PluginConfiguration;
import com.thoughtworks.go.security.GoCipher;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.hamcrest.core.Is;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

import static com.thoughtworks.go.domain.packagerepository.ConfigurationPropertyMother.create;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FetchPluggableArtifactTaskTest {
    private PipelineConfig downstream;
    private PipelineConfig upstream;
    private PipelineConfig uppestStream;
    private CruiseConfig config;
    private ArtifactPluginInfo artifactPluginInfo;

    @Before
    public void setUp() {
        config = GoConfigMother.configWithPipelines("random_pipeline", "uppest_lookalike", "uppest_stream", "upstreams_peer", "upstream", "downstream", "dummy");

        PipelineConfig randomPipeline = config.pipelineConfigByName(new CaseInsensitiveString("random_pipeline"));
        randomPipeline.add(StageConfigMother.stageConfig("random-stage1", new JobConfigs(new JobConfig("random-job1"))));

        PipelineConfig uppestLookalike = config.pipelineConfigByName(new CaseInsensitiveString("uppest_lookalike"));
        uppestLookalike.add(StageConfigMother.stageConfig("uppest-stage1", new JobConfigs(new JobConfig("uppest-job1"))));

        uppestStream = config.pipelineConfigByName(new CaseInsensitiveString("uppest_stream"));
        uppestStream.add(StageConfigMother.stageConfig("uppest-stage1", new JobConfigs(new JobConfig("uppest-job1"))));
        uppestStream.add(StageConfigMother.stageConfig("uppest-stage2", new JobConfigs(new JobConfig("uppest-job2"))));
        uppestStream.add(StageConfigMother.stageConfig("uppest-stage3", new JobConfigs(new JobConfig("uppest-job3"))));

        upstream = config.pipelineConfigByName(new CaseInsensitiveString("upstream"));
        upstream.setMaterialConfigs(new MaterialConfigs(MaterialConfigsMother.dependencyMaterialConfig("uppest_stream", "uppest-stage2")));
        upstream.add(StageConfigMother.stageConfig("up-stage1", new JobConfigs(new JobConfig("up-job1"))));
        upstream.add(StageConfigMother.stageConfig("up-stage2", new JobConfigs(new JobConfig("up-job2"))));

        downstream = config.pipelineConfigByName(new CaseInsensitiveString("downstream"));
        downstream.setMaterialConfigs(new MaterialConfigs(MaterialConfigsMother.dependencyMaterialConfig("upstream", "up-stage1")));
        downstream.get(0).getJobs().get(0).addTask(new FetchPluggableArtifactTask(new CaseInsensitiveString("foo"), new CaseInsensitiveString("bar"), new CaseInsensitiveString("baz"), "s3"));

        artifactPluginInfo = mock(ArtifactPluginInfo.class);
        PluginDescriptor pluginDescriptor = mock(PluginDescriptor.class);
        when(artifactPluginInfo.getDescriptor()).thenReturn(pluginDescriptor);
        when(pluginDescriptor.id()).thenReturn("cd.go.s3");
        ArtifactMetadataStore.instance().setPluginInfo(artifactPluginInfo);
    }

    @After
    public void clear() {
        ArtifactMetadataStore.instance().setPluginInfo(null);
    }

    @Test
    public void shouldImplementAbstractFetchArtifact() {
        assertTrue(new FetchPluggableArtifactTask() instanceof AbstractFetchTask);
    }

    @Test
    public void validate_shouldErrorWhenReferencingConfigRepositoryPipelineFromFilePipeline() {
        uppestStream.setOrigin(new RepoConfigOrigin());
        downstream.setOrigin(new FileConfigOrigin());

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("uppest_stream/upstream"), new CaseInsensitiveString("uppest-stage2"), new CaseInsensitiveString("uppest-job2"), "s3");

        StageConfig stage = downstream.getStage(new CaseInsensitiveString("stage"));
        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, stage, stage.getJobs().first()));

        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.ORIGIN), startsWith("\"downstream :: stage :: job\" tries to fetch artifact from job \"uppest_stream :: uppest-stage2 :: uppest-job2\" which is defined in"));
    }

    @Test
    public void validate_shouldNotErrorWhenReferencingFilePipelineFromConfigRepositoryPipeline() {
        uppestStream.getStage("uppest-stage2").jobConfigByConfigName("uppest-job2").artifactConfigs()
                .add(new PluggableArtifactConfig("s3", "cd.go.s3"));
        uppestStream.setOrigin(new FileConfigOrigin());
        downstream.setOrigin(new RepoConfigOrigin());

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("uppest_stream/upstream"), new CaseInsensitiveString("uppest-stage2"), new CaseInsensitiveString("uppest-job2"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage"))));

        assertThat(task.errors().isEmpty(), is(true));
    }

    @Test
    public void validate_shouldNotErrorWhenReferencingConfigRepositoryPipelineFromConfigRepositoryPipeline() {
        uppestStream.getStage("uppest-stage2").jobConfigByConfigName("uppest-job2").artifactConfigs()
                .add(new PluggableArtifactConfig("s3", "cd.go.s3"));
        uppestStream.setOrigin(new RepoConfigOrigin());
        downstream.setOrigin(new RepoConfigOrigin());
        config.setArtifactStores(new ArtifactStores(new ArtifactStore("s3", "foo.plugin")));

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("uppest_stream/upstream"), new CaseInsensitiveString("uppest-stage2"), new CaseInsensitiveString("uppest-job2"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage"))));

        assertThat(task.errors().isEmpty(), is(true));
    }

    @Test
    public void validate_shouldNotErrorWhenReferencingFilePipelineFromFilePipeline() {
        uppestStream.getStage("uppest-stage2").jobConfigByConfigName("uppest-job2").artifactConfigs()
                .add(new PluggableArtifactConfig("s3", "cd.go.s3"));

        uppestStream.setOrigin(new FileConfigOrigin());
        downstream.setOrigin(new FileConfigOrigin());

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("uppest_stream/upstream"), new CaseInsensitiveString("uppest-stage2"), new CaseInsensitiveString("uppest-job2"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage"))));

        assertThat(task.errors().isEmpty(), is(true));
    }

    @Test
    public void validate_shouldNotTryAndValidateWhenWithinTemplate() throws Exception {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("dummy"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new TemplatesConfig(), downstream.getStage(new CaseInsensitiveString("stage"))));

        assertThat(task.errors().isEmpty(), Is.is(true));
    }

    @Test
    public void validate_shouldValidatePresenceOfartifactId() {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("dummy"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "");

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage"))));

        assertFalse(task.errors().on("artifactId").isEmpty());
        assertThat(task.errors().on("artifactId"), is("Invalid fetch artifact artifactId name ''. This must be alphanumeric and can contain underscores and periods (however, it cannot start with a period). The maximum allowed length is 255 characters."));
    }

    @Test
    public void validate_shouldValidateNullartifactId() {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("dummy"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), null);

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage"))));

        assertFalse(task.errors().on("artifactId").isEmpty());
        assertThat(task.errors().on("artifactId"), is("Invalid fetch artifact artifactId name 'null'. This must be alphanumeric and can contain underscores and periods (however, it cannot start with a period). The maximum allowed length is 255 characters."));
    }

    @Test
    public void validate_shouldPopulateErrorIfArtifactIsNotPresentInConfig() {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("dummy"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");
        config.setArtifactStores(new ArtifactStores());

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage"))));

        assertFalse(task.errors().on("artifactId").isEmpty());
        assertThat(task.errors().on("artifactId"), is("Pluggable artifact with id `s3` does not exist in [dummy/stage/job]."));
    }

    @Test
    public void validate_shouldValidateFetchPluggableArtifactConfigurationUniqueness() {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("dummy"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3", create("Foo", false, "Bar"), create("Foo", false, "Bar"));

        task.validate(ConfigSaveValidationContext.forChain(config, new TemplatesConfig(), downstream.getStage(new CaseInsensitiveString("stage"))));

        assertTrue(task.getConfiguration().hasErrors());
        assertThat(task.getConfiguration().get(0).errors().on("configurationKey"), is("Duplicate key 'Foo' found for Fetch pluggable artifact"));
        assertThat(task.getConfiguration().get(1).errors().on("configurationKey"), is("Duplicate key 'Foo' found for Fetch pluggable artifact"));
    }

    @Test
    public void shouldPopulateErrorsIfFetchArtifactFromPipelineThatIsNotDependency() {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("dummy"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage"))));

        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.PIPELINE_NAME), is("Pipeline \"downstream\" tries to fetch artifact from pipeline "
                + "\"dummy\" which is not an upstream pipeline"));
    }

    @Test
    public void validate_shouldValidateBlankStageAndJobWhenWithinTemplate() throws Exception {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("dummy"), new CaseInsensitiveString(""), new CaseInsensitiveString(""), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new TemplatesConfig(), downstream.getStage(new CaseInsensitiveString("stage"))));

        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.STAGE), is("Stage is a required field."));
        assertThat(task.errors().on(FetchTask.JOB), is("Job is a required field."));
    }

    @Test
    public void shouldPopulateErrorsIfFetchArtifactDoesNotHaveStageAndOrJobDefined() {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString(""), new CaseInsensitiveString(""), new CaseInsensitiveString(""), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, new StageConfig(), new JobConfig()));

        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.STAGE), is("Stage is a required field."));
        assertThat(task.errors().on(FetchTask.JOB), is("Job is a required field."));
    }

    @Test
    public void shouldBeValidWhenFetchArtifactIsFromAnyAncestorStage_onTheUpstreamPipeline() {
        uppestStream.getStage("uppest-stage2").jobConfigByConfigName("uppest-job2").artifactConfigs()
                .add(new PluggableArtifactConfig("s3", "cd.go.s3"));

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("uppest_stream/upstream"), new CaseInsensitiveString("uppest-stage2"), new CaseInsensitiveString("uppest-job2"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage"))));

        assertThat(task.errors().isEmpty(), is(true));
    }

    @Test
    public void shouldBeValidWhenFetchArtifactIsFromAnyAncestorStage_s_predecessorStage__onTheUpstreamPipeline() {
        uppestStream.getStage("uppest-stage1").jobConfigByConfigName("uppest-job1").artifactConfigs()
                .add(new PluggableArtifactConfig("s3", "cd.go.s3"));

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("uppest_stream/upstream"), new CaseInsensitiveString("uppest-stage1"), new CaseInsensitiveString("uppest-job1"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage"))));

        assertThat(task.errors().isEmpty(), is(true));
    }

    @Test
    public void should_NOT_BeValidWhenFetchArtifactIsFromAnyAncestorStage_s_successorStage_onTheUpstreamPipeline() {
        uppestStream.getStage("uppest-stage3").jobConfigByConfigName("uppest-job3").artifactConfigs()
                .add(new PluggableArtifactConfig("s3", "cd.go.s3"));
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("uppest_stream/upstream"), new CaseInsensitiveString("uppest-stage3"), new CaseInsensitiveString("uppest-job3"), "s3");
        StageConfig stage = downstream.getStage(new CaseInsensitiveString("stage"));

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, stage, stage.getJobs().first()));

        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.STAGE), is("\"downstream :: stage :: job\" tries to fetch artifact from stage \"uppest_stream :: uppest-stage3\" which does not complete before \"downstream\" pipeline's dependencies."));
    }

    @Test
    public void should_NOT_BeValidWhen_pathFromAncestor_isInvalid_becauseRefferedPipelineIsNotAnAncestor() {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("random_pipeline/upstream"), new CaseInsensitiveString("random-stage1"), new CaseInsensitiveString("random-job1"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage"))));

        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.PIPELINE_NAME), is("Pipeline named 'random_pipeline' exists, but is not an ancestor of 'downstream' as declared in 'random_pipeline/upstream'."));
    }

    @Test
    public void should_NOT_BeValidWhen_NO_pathFromAncestorIsGiven_butAncestorPipelineIsBeingFetchedFrom() {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(null, new CaseInsensitiveString("uppest-stage3"), new CaseInsensitiveString("uppest-job3"), "s3");
        StageConfig stage = downstream.getStage(new CaseInsensitiveString("stage"));

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, stage, stage.getJobs().get(0)));

        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.STAGE), is("\"downstream :: stage :: job\" tries to fetch artifact from stage \"downstream :: uppest-stage3\" which does not exist."));
    }

    @Test
    public void should_BeValidWhen_hasAnAlternatePathToAncestor() {
        uppestStream.getStage("uppest-stage1").jobConfigByConfigName("uppest-job1").artifactConfigs()
                .add(new PluggableArtifactConfig("s3", "cd.go.s3"));

        PipelineConfig upstreamsPeer = config.pipelineConfigByName(new CaseInsensitiveString("upstreams_peer"));
        upstreamsPeer.setMaterialConfigs(new MaterialConfigs(MaterialConfigsMother.dependencyMaterialConfig("uppest_stream", "uppest-stage1")));
        upstreamsPeer.add(StageConfigMother.stageConfig("peer-stage", new JobConfigs(new JobConfig("peer-job"))));

        downstream = config.pipelineConfigByName(new CaseInsensitiveString("downstream"));
        downstream.setMaterialConfigs(new MaterialConfigs(MaterialConfigsMother.dependencyMaterialConfig("upstream", "up-stage1"), MaterialConfigsMother.dependencyMaterialConfig("upstreams_peer", "peer-stage")));

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("uppest_stream/upstream"), new CaseInsensitiveString("uppest-stage1"), new CaseInsensitiveString("uppest-job1"), "s3");
        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage"))));
        assertThat(task.errors().isEmpty(), is(true));

        task = new FetchPluggableArtifactTask(new CaseInsensitiveString("uppest_stream/upstreams_peer"), new CaseInsensitiveString("uppest-stage1"), new CaseInsensitiveString("uppest-job1"), "s3");
        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage"))));
        assertThat(task.errors().isEmpty(), is(true));
    }

    @Test
    public void should_NOT_BeValidWhen_ImmediateParentDeclaredInPathFromAncestor_isNotAParentPipeline() {
        uppestStream.getStage("uppest-stage1").jobConfigByConfigName("uppest-job1").artifactConfigs()
                .add(new PluggableArtifactConfig("s3", "cd.go.s3"));
        PipelineConfig upstreamsPeer = config.pipelineConfigByName(new CaseInsensitiveString("upstreams_peer"));
        upstreamsPeer.setMaterialConfigs(new MaterialConfigs(MaterialConfigsMother.dependencyMaterialConfig("uppest_stream", "uppest-stage1")));
        upstreamsPeer.add(StageConfigMother.stageConfig("peer-stage", new JobConfigs(new JobConfig("peer-job"))));

        downstream = config.pipelineConfigByName(new CaseInsensitiveString("downstream"));
        downstream.setMaterialConfigs(new MaterialConfigs(MaterialConfigsMother.dependencyMaterialConfig("upstream", "up-stage1"), MaterialConfigsMother.dependencyMaterialConfig("upstreams_peer", "peer-stage")));

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("upstream/uppest_stream"), new CaseInsensitiveString("up-stage1"), new CaseInsensitiveString("up-job1"), "s3");
        StageConfig stage = downstream.getStage(new CaseInsensitiveString("stage"));
        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, stage, stage.getJobs().get(0)));
        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.PIPELINE_NAME), is("Pipeline named 'uppest_stream' exists, but is not an ancestor of 'downstream' as declared in 'upstream/uppest_stream'."));
    }

    @Test
    public void should_NOT_BeValidWhen_ImmediateParentDeclaredInPathFromAncestor_isNotAParentPipeline_PipelineConfigValidationContext() {
        PipelineConfig upstreamsPeer = config.pipelineConfigByName(new CaseInsensitiveString("upstreams_peer"));
        upstreamsPeer.setMaterialConfigs(new MaterialConfigs(MaterialConfigsMother.dependencyMaterialConfig("uppest_stream", "uppest-stage1")));
        upstreamsPeer.add(StageConfigMother.stageConfig("peer-stage", new JobConfigs(new JobConfig("peer-job"))));

        downstream = config.pipelineConfigByName(new CaseInsensitiveString("downstream"));
        downstream.setMaterialConfigs(new MaterialConfigs(MaterialConfigsMother.dependencyMaterialConfig("upstream", "up-stage1"), MaterialConfigsMother.dependencyMaterialConfig("upstreams_peer", "peer-stage")));

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("upstream/uppest_stream"), new CaseInsensitiveString("up-stage1"), new CaseInsensitiveString("up-job1"), "s3");
        StageConfig stage = downstream.getStage(new CaseInsensitiveString("stage"));

        task.validateTree(PipelineConfigSaveValidationContext.forChain(true, "group", config, downstream, stage, stage.getJobs().get(0)));

        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.PIPELINE_NAME), is("Pipeline named 'uppest_stream' exists, but is not an ancestor of 'downstream' as declared in 'upstream/uppest_stream'."));
    }

    @Test
    public void should_NOT_BeValidWhen_stageMayNotHaveRunViaTheGivenPath_evenThoughItMayHaveActuallyRunAccordingToAnAlternatePath() {
        uppestStream.getStage("uppest-stage1").jobConfigByConfigName("uppest-job1").artifactConfigs()
                .add(new PluggableArtifactConfig("s3", "cd.go.s3"));
        PipelineConfig upstreamsPeer = config.pipelineConfigByName(new CaseInsensitiveString("upstreams_peer"));
        upstreamsPeer.setMaterialConfigs(new MaterialConfigs(MaterialConfigsMother.dependencyMaterialConfig("uppest_stream", "uppest-stage1")));
        upstreamsPeer.add(StageConfigMother.stageConfig("peer-stage", new JobConfigs(new JobConfig("peer-job"))));

        downstream = config.pipelineConfigByName(new CaseInsensitiveString("downstream"));
        downstream.setMaterialConfigs(new MaterialConfigs(MaterialConfigsMother.dependencyMaterialConfig("upstream", "up-stage1"), MaterialConfigsMother.dependencyMaterialConfig("upstreams_peer", "peer-stage")));

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("uppest_stream/upstreams_peer"), new CaseInsensitiveString("uppest-stage1"), new CaseInsensitiveString("uppest-job1"), "s3");
        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage")), downstream.getStage(new CaseInsensitiveString("stage")).getJobs().first()));

        assertThat(task.errors().isEmpty(), is(true));

        task = new FetchPluggableArtifactTask(new CaseInsensitiveString("uppest_stream/upstreams_peer"), new CaseInsensitiveString("uppest-stage2"), new CaseInsensitiveString("uppest-job2"), "s3");
        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage")), downstream.getStage(new CaseInsensitiveString("stage")).getJobs().first()));

        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.STAGE), is("\"downstream :: stage :: job\" tries to fetch artifact from stage \"uppest_stream :: uppest-stage2\" which does not complete before \"downstream\" pipeline's dependencies."));
    }

    @Test
    public void shouldFailWhenFetchArtifactIsFromAnyStage_AFTER_theDependencyStageOnTheUpstreamPipeline() {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("upstream"), new CaseInsensitiveString("up-stage2"), new CaseInsensitiveString("up-job2"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage")), downstream.getStage(new CaseInsensitiveString("stage")).getJobs().first()));

        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.STAGE), is("\"downstream :: stage :: job\" tries to fetch artifact from stage \"upstream :: up-stage2\" which does not complete before \"downstream\" pipeline's dependencies."));
    }

    @Test
    public void shouldPopulateErrorIfFetchArtifactFromDependentPipelineButStageDoesNotExist() {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("upstream"), new CaseInsensitiveString("stage-does-not-exist"), new CaseInsensitiveString("job"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, downstream.getStage(new CaseInsensitiveString("stage")), downstream.getStage(
                new CaseInsensitiveString("stage")), downstream.getStage(new CaseInsensitiveString("stage")).getJobs().get(0)));

        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.STAGE), is("\"downstream :: stage :: job\" tries to fetch artifact from stage "
                + "\"upstream :: stage-does-not-exist\" which does not exist."));
    }

    @Test
    public void shouldPopulateErrorIfFetchArtifactFromDependentPipelineButJobNotExist() {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("upstream"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job-does-not-exist"), "s3");
        StageConfig stage = downstream.getStage(new CaseInsensitiveString("stage"));

        task.validate(ConfigSaveValidationContext.forChain(config, new BasicPipelineConfigs(), downstream, stage, stage.getJobs().first()));

        assertThat(task.errors().isEmpty(), is(false));
        assertThat(task.errors().on(FetchTask.JOB), is("\"downstream :: stage :: job\" tries to fetch artifact from job "
                + "\"upstream :: stage :: job-does-not-exist\" which does not exist."));
    }

    @Test
    public void shouldBeValidIfFetchArtifactUsingADependantPipeline() {
        upstream.getStage("up-stage1").jobConfigByConfigName("up-job1").artifactConfigs()
                .add(new PluggableArtifactConfig("s3", "cd.go.s3"));

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("upstream"), new CaseInsensitiveString("up-stage1"), new CaseInsensitiveString("up-job1"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, downstream, downstream.getStage(new CaseInsensitiveString("stage"))));

        assertThat(task.errors().isEmpty(), is(true));
    }

    @Test
    public void shouldBeValidIfFetchArtifactUsingAStageBeforeCurrentInTheSamePipeline() {
        upstream.getStage("stage").jobConfigByConfigName("job").artifactConfigs()
                .add(new PluggableArtifactConfig("s3", "cd.go.s3"));

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("upstream"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, upstream, upstream.getStage(new CaseInsensitiveString("up-stage1"))));

        assertThat(task.errors().isEmpty(), is(true));
    }

    @Test
    public void shouldBeValidIfFetchArtifactDoesNotSpecifyPipeline() {
        upstream.getStage("stage").jobConfigByConfigName("job").artifactConfigs()
                .add(new PluggableArtifactConfig("s3", "cd.go.s3"));

        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, upstream, upstream.getStage(new CaseInsensitiveString("up-stage1"))));

        assertThat(task.errors().isEmpty(), is(true));
    }

    @Test
    public void validate_shouldSkipValidationOfPluggableArtifact_IsWithinTemplate() {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        task.validate(ConfigSaveValidationContext.forChain(config, new TemplatesConfig(), upstream.getStage(new CaseInsensitiveString("up-stage1"))));

        assertThat(task.errors().isEmpty(), is(true));
    }

    @Test
    public void encryptSecureProperties_shouldLeaveUserEnteredValuesAsIs() throws InvalidCipherTextException {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        ArrayList<ConfigurationProperty> configurationProperties = new ArrayList<>();
        configurationProperties.add(ConfigurationPropertyMother.create("plain", false, "plain"));
        configurationProperties.add(ConfigurationPropertyMother.create("secure", true, new GoCipher().encrypt("password")));

        task.addConfigurations(configurationProperties);

        assertThat(task.getConfiguration(), is(configurationProperties));
    }


    @Test
    public void encryptSecureProperties_shouldLeaveUserEnteredValuesAsIsIfPipelineIsNotInConfig() throws InvalidCipherTextException {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("pipeline"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        ArrayList<ConfigurationProperty> configurationProperties = new ArrayList<>();
        configurationProperties.add(ConfigurationPropertyMother.create("plain", false, "plain"));
        configurationProperties.add(ConfigurationPropertyMother.create("secure", true, new GoCipher().encrypt("password")));

        BasicCruiseConfig cruiseConfig = GoConfigMother.defaultCruiseConfig();
        task.addConfigurations(configurationProperties);
        task.encryptSecureProperties(cruiseConfig, PipelineConfigMother.pipelineConfig("p1"));

        assertThat(task.getConfiguration(), is(configurationProperties));
    }

    @Test
    public void encryptSecureProperties_shouldLeaveUserEnteredValuesAsIsIfStageDoesNotExist() throws InvalidCipherTextException {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("pipeline"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        ArrayList<ConfigurationProperty> configurationProperties = new ArrayList<>();
        configurationProperties.add(ConfigurationPropertyMother.create("plain", false, "plain"));
        configurationProperties.add(ConfigurationPropertyMother.create("secure", true, new GoCipher().encrypt("password")));

        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline", "s1", "j1");
        BasicCruiseConfig cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.addPipelineWithoutValidation("foo", pipelineConfig);

        task.addConfigurations(configurationProperties);
        task.encryptSecureProperties(cruiseConfig, pipelineConfig);

        assertThat(task.getConfiguration(), is(configurationProperties));
    }

    @Test
    public void encryptSecureProperties_shouldLeaveUserEnteredValuesAsIsIfJobDoesNotExist() throws InvalidCipherTextException {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("pipeline"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        ArrayList<ConfigurationProperty> configurationProperties = new ArrayList<>();
        configurationProperties.add(ConfigurationPropertyMother.create("plain", false, "plain"));
        configurationProperties.add(ConfigurationPropertyMother.create("secure", true, new GoCipher().encrypt("password")));

        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline", "stage", "j1");
        BasicCruiseConfig cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.addPipelineWithoutValidation("foo", pipelineConfig);

        task.addConfigurations(configurationProperties);
        task.encryptSecureProperties(cruiseConfig, pipelineConfig);

        assertThat(task.getConfiguration(), is(configurationProperties));
    }

    @Test
    public void encryptSecureProperties_shouldLeaveUserEnteredValuesAsIsIfArtifactDoesNotExist() throws InvalidCipherTextException {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("pipeline"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        ArrayList<ConfigurationProperty> configurationProperties = new ArrayList<>();
        configurationProperties.add(ConfigurationPropertyMother.create("plain", false, "plain"));
        configurationProperties.add(ConfigurationPropertyMother.create("secure", true, new GoCipher().encrypt("password")));

        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline", "stage", "job");
        BasicCruiseConfig cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.addPipelineWithoutValidation("foo", pipelineConfig);

        task.addConfigurations(configurationProperties);
        task.encryptSecureProperties(cruiseConfig, pipelineConfig);

        assertThat(task.getConfiguration(), is(configurationProperties));
    }

    @Test
    public void encryptSecureProperties_shouldLeaveUserEnteredValuesAsIsIfArtifactStoreIsMissing() throws InvalidCipherTextException {
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("pipeline"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        ArrayList<ConfigurationProperty> configurationProperties = new ArrayList<>();
        configurationProperties.add(ConfigurationPropertyMother.create("plain", false, "plain"));
        configurationProperties.add(ConfigurationPropertyMother.create("secure", true, new GoCipher().encrypt("password")));

        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline", "stage", "job");
        pipelineConfig.getStage("stage").jobConfigByConfigName("job").artifactConfigs().add(new PluggableArtifactConfig("s3", "aws"));

        BasicCruiseConfig cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.addPipelineWithoutValidation("foo", pipelineConfig);

        task.addConfigurations(configurationProperties);
        task.encryptSecureProperties(cruiseConfig, pipelineConfig);

        assertThat(task.getConfiguration(), is(configurationProperties));
    }

    @Test
    public void encryptSecureProperties_shouldLeaveUserEnteredValuesAsIsIfPluginIsMissing() throws InvalidCipherTextException {
        ArtifactMetadataStore.instance().remove("cd.go.s3");
        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("pipeline"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        ArrayList<ConfigurationProperty> configurationProperties = new ArrayList<>();
        configurationProperties.add(ConfigurationPropertyMother.create("plain", false, "plain"));
        configurationProperties.add(ConfigurationPropertyMother.create("secure", true, new GoCipher().encrypt("password")));

        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline", "stage", "job");
        PluggableArtifactConfig pluggableArtifactConfig = new PluggableArtifactConfig("s3", "aws");
        pluggableArtifactConfig.setArtifactStore(new ArtifactStore("aws", "cd.go.s3"));
        pipelineConfig.getStage("stage").jobConfigByConfigName("job").artifactConfigs().add(pluggableArtifactConfig);

        BasicCruiseConfig cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.addPipelineWithoutValidation("foo", pipelineConfig);

        task.addConfigurations(configurationProperties);
        task.encryptSecureProperties(cruiseConfig, pipelineConfig);

        assertThat(task.getConfiguration(), is(configurationProperties));
    }

    @Test
    public void encryptSecureProperties_shouldEncryptSecureProperties() throws InvalidCipherTextException {
        ArrayList<PluginConfiguration> pluginConfigurations = new ArrayList<>();

        pluginConfigurations.add(new PluginConfiguration("secure_property1", new Metadata(true, true)));
        pluginConfigurations.add(new PluginConfiguration("secure_property2", new Metadata(true, true)));
        pluginConfigurations.add(new PluginConfiguration("plain", new Metadata(true, false)));
        when(artifactPluginInfo.getFetchArtifactSettings()).thenReturn(new PluggableInstanceSettings(pluginConfigurations));

        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline", "stage", "job");
        PluggableArtifactConfig pluggableArtifactConfig = new PluggableArtifactConfig("s3", "aws");
        pluggableArtifactConfig.setArtifactStore(new ArtifactStore("aws", "cd.go.s3"));
        pipelineConfig.getStage("stage").jobConfigByConfigName("job").artifactConfigs().add(pluggableArtifactConfig);

        BasicCruiseConfig cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.addPipelineWithoutValidation("foo", pipelineConfig);


        FetchPluggableArtifactTask task = new FetchPluggableArtifactTask(new CaseInsensitiveString("pipeline"), new CaseInsensitiveString("stage"), new CaseInsensitiveString("job"), "s3");

        ArrayList<ConfigurationProperty> configurationProperties = new ArrayList<>();
        configurationProperties.add(new ConfigurationProperty(new ConfigurationKey("plain"), new ConfigurationValue("plain")));
        configurationProperties.add(new ConfigurationProperty(new ConfigurationKey("secure_property1"), new ConfigurationValue("password") ));
        configurationProperties.add(new ConfigurationProperty(new ConfigurationKey("secure_property2"), new EncryptedConfigurationValue(new GoCipher().encrypt("secret"))));

        ArrayList<ConfigurationProperty> expectedConfigurationProperties = new ArrayList<>();
        expectedConfigurationProperties.add(new ConfigurationProperty(new ConfigurationKey("plain"), new ConfigurationValue("plain")));
        expectedConfigurationProperties.add(new ConfigurationProperty(new ConfigurationKey("secure_property1"), new EncryptedConfigurationValue(new GoCipher().encrypt("password"))));
        expectedConfigurationProperties.add(new ConfigurationProperty(new ConfigurationKey("secure_property2"), new EncryptedConfigurationValue(new GoCipher().encrypt("secret"))));


        task.addConfigurations(configurationProperties);
        task.encryptSecureProperties(cruiseConfig, pipelineConfig);

        assertThat(task.getConfiguration().size(), is(3));
        assertThat(task.getConfiguration(), is(expectedConfigurationProperties));
    }
}