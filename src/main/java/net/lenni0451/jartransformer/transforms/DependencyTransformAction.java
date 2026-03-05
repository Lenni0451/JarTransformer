package net.lenni0451.jartransformer.transforms;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.jartransformer.transformers.Transformer;
import org.gradle.api.artifacts.transform.*;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Slf4j
@CacheableTransform
public abstract class DependencyTransformAction implements TransformAction<DependencyTransformAction.Parameters> {

    @InputArtifact
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @Override
    @SneakyThrows
    public void transform(TransformOutputs outputs) {
        File input = this.getInputArtifact().get().getAsFile();
        File output = outputs.file(input.getName().replace(".jar", "-repackaged.jar"));
        Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Transformer.applyAll(log, output, this.getParameters().getTransformers().get());
    }


    public static abstract class Parameters implements TransformParameters {
        @Nested
        public abstract ListProperty<Transformer> getTransformers();
    }

}
