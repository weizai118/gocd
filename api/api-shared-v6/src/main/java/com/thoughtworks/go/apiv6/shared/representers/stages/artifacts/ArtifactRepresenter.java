/*
 * Copyright 2018 ThoughtWorks, Inc.
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

package com.thoughtworks.go.apiv6.shared.representers.stages.artifacts;

import com.thoughtworks.go.api.base.OutputWriter;
import com.thoughtworks.go.api.representers.ErrorGetter;
import com.thoughtworks.go.api.representers.JsonReader;
import com.thoughtworks.go.apiv6.shared.representers.stages.ConfigHelperOptions;
import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.exceptions.UnprocessableEntityException;

import java.util.HashMap;
import java.util.Map;

public class ArtifactRepresenter {

    public static void toJSON(OutputWriter jsonWriter, ArtifactConfig artifactConfig) {
        if (!artifactConfig.errors().isEmpty()) {
            jsonWriter.addChild("errors", errorWriter -> {
                HashMap<String, String> errorMapping = new HashMap<>();
                errorMapping.put("src", "source");
                errorMapping.put("dest", "destination");
                errorMapping.put("id", "artifact_id");
                errorMapping.put("storeId", "store_id");

                new ErrorGetter(errorMapping).toJSON(errorWriter, artifactConfig);
            });
        }

        jsonWriter.add("type", artifactConfig.getArtifactType().name());
        switch (artifactConfig.getArtifactType()) {
            case test:
            case build:
                BuiltinArtifactConfigRepresenter.toJSON(jsonWriter, (BuiltinArtifactConfig) artifactConfig);
                break;
            case external:
                ExternalArtifactConfigRepresenter.toJSON(jsonWriter, (PluggableArtifactConfig) artifactConfig);
        }

    }

    public static ArtifactConfig fromJSON(JsonReader jsonReader, ConfigHelperOptions options) {
        String type = jsonReader.getString("type");
        ArtifactConfig artifactConfig;
        switch (type) {
            case "build":
                artifactConfig = BuiltinArtifactConfigRepresenter.fromJSON(jsonReader, new BuildArtifactConfig());
                break;
            case "test":
                artifactConfig = BuiltinArtifactConfigRepresenter.fromJSON(jsonReader, new TestArtifactConfig());
                break;
            case "external":
                artifactConfig = ExternalArtifactConfigRepresenter.fromJSON(jsonReader, new PluggableArtifactConfig(), options);
                break;
            default:
                throw new UnprocessableEntityException(String.format("Invalid Artifact type: '%s'. It has to be one of %s.", type, String.join(",", "build", "test", "external")));
        }
        return artifactConfig;
    }
}
