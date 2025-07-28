package net.lenni0451.jartransformer.tasks;

import lombok.extern.slf4j.Slf4j;
import net.lenni0451.jartransformer.transformers.base.JarTransformer;
import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Slf4j
public abstract class JarTransformTask extends DefaultTask {

    @Input
    public abstract Property<JarTransformer> getJarTransformer();

    @TaskAction
    public void run() throws Throwable {
        JarTransformer jarTransformer = this.getJarTransformer().get();
        File inputFile = jarTransformer.getInputFile().get().getAsFile();
        File outputFile = jarTransformer.getOutputFile().isPresent() ? jarTransformer.getOutputFile().get().getAsFile() : null;
        if (!inputFile.exists()) {
            throw new IllegalArgumentException("Input file does not exist: " + inputFile.getAbsolutePath());
        }
        if (outputFile != null && outputFile.exists()) {
            log.warn("Output file already exists and will be overwritten: {}", outputFile.getAbsolutePath());
        }

        File file;
        if (inputFile.equals(outputFile) || outputFile == null) {
            //Repackaging in-place breaks the gradle jar cache
            //But it's still possible for convenience
            log.warn("Repackaging in-place is not recommended, consider changing the output file");
            file = inputFile;
        } else {
            if (outputFile.getParentFile() != null) {
                outputFile.getParentFile().mkdirs();
            }
            Files.copy(inputFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            file = outputFile;
        }
        jarTransformer.apply(log, file);
    }

}
