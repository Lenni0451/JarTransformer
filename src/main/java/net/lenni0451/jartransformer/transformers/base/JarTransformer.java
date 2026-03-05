package net.lenni0451.jartransformer.transformers.base;

import lombok.Getter;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.*;

import javax.inject.Inject;

@Getter
public abstract class JarTransformer extends BaseTransformer {

    @Internal
    private final Directory buildLibs;

    @Inject
    public JarTransformer(final String name, final ObjectFactory objectFactory, final Directory buildLibs) {
        super(objectFactory, name);
        this.buildLibs = buildLibs;
    }

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getInputFile();

    @Optional
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

}
