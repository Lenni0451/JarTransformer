package net.lenni0451.jartransformer.transformers.base;

import lombok.Getter;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;

import javax.inject.Inject;

@Getter
public abstract class JarTransformer extends BaseTransformer {

    private final Directory buildLibs;

    @Inject
    public JarTransformer(final String name, final ObjectFactory objectFactory, final Directory buildLibs) {
        super(objectFactory, name);
        this.buildLibs = buildLibs;
    }

    @InputFile
    public abstract RegularFileProperty getInputFile();

    @Optional
    @InputFile
    public abstract RegularFileProperty getOutputFile();

}
