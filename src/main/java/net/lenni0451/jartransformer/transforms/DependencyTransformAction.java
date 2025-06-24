package net.lenni0451.jartransformer.transforms;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.jartransformer.transformers.Transformer;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Slf4j
public abstract class DependencyTransformAction implements TransformAction<DependencyTransformAction.Parameters> {

    @InputArtifact
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
        @Input
        public abstract ListProperty<Transformer> getTransformers();
    }

}
