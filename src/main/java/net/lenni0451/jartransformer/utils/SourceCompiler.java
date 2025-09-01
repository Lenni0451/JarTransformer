package net.lenni0451.jartransformer.utils;

import lombok.extern.slf4j.Slf4j;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.jetbrains.annotations.Nullable;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class SourceCompiler {

    @Nullable
    public static File compileSourceSet(final Project project, final FileCollection sourceFiles, final Configuration dependencies) {
        if (sourceFiles.getFiles().isEmpty()) return null;
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("No Java compiler found");
        File outputDir = new File(project.getLayout().getBuildDirectory().get().getAsFile(), "classTransform-classes");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new RuntimeException("Failed to create output directory: " + outputDir.getAbsolutePath());
        }

        String classpath = dependencies.getAsPath();
        List<String> options = new ArrayList<>();
        options.add("-classpath");
        options.add(classpath);
        options.add("-d");
        options.add(outputDir.getAbsolutePath());
        sourceFiles.getFiles().stream().map(File::getAbsolutePath).forEach(options::add);

        log.info("Compiling source files with options: {}", options);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int result = compiler.run(null, out, out, options.toArray(new String[0]));
        if (result != 0) {
            log.error("Failed to compile source files, exit code: {}", result);
            log.error("Compiler output:\n{}", out);
            throw new RuntimeException("Failed to compile source files, exit code: " + result);
        }

        return outputDir;
    }

}
