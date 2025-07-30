package net.lenni0451.jartransformer.tasks;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.jartransformer.utils.FileSystemUtils;
import net.lenni0451.jartransformer.utils.Log4JPluginCache;
import net.lenni0451.jartransformer.utils.PatternUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public abstract class JarMergerTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getProjectJar();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getInputFiles();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @Input
    public abstract Property<DuplicatesStrategy> getDuplicatesStrategy();

    @Input
    public abstract SetProperty<String> getExcludes();

    @Input
    public abstract Property<Boolean> getMergeServices();

    @Input
    public abstract Property<Boolean> getMergeLog4jPlugins();

    @TaskAction
    public void run() throws IOException, URISyntaxException {
        File outputFile = this.getOutputJar().get().getAsFile();
        log.info("Start merging jar files into {}", outputFile.getName());
        if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();
        Set<Pattern> excludes = this.getExcludes().get().stream().map(PatternUtils::globToRegex).collect(Collectors.toSet());

        try (FileSystem fileSystem = FileSystemUtils.openCreating(outputFile)) {
            JarEntryAccess fileSystemAccess = new JarEntryAccess() {
                @Override
                public void write(String name, byte[] content) throws IOException {
                    Path path = fileSystem.getPath(name);
                    Path parent = path.getParent();
                    if (parent != null) Files.createDirectories(path.getParent());
                    Files.write(path, content);
                }

                @Override
                public Optional<JarEntryContentSupplier> read(String name) {
                    Path path = fileSystem.getPath(name);
                    if (!Files.exists(path)) return Optional.empty();
                    return Optional.of(() -> Files.readAllBytes(path));
                }
            };
            this.processJarFile(this.getProjectJar().get().getAsFile(), fileSystemAccess, excludes);
            for (File inputFile : this.getInputFiles()) {
                this.processJarFile(inputFile, fileSystemAccess, excludes);
            }
        }

        log.info("Successfully created merged jar: {}", outputFile.getAbsolutePath());
    }

    private void iterateFiles(final File file, final JarEntryProcessor processor) throws IOException {
        try (JarFile jarFile = new JarFile(file)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                processor.process(entry, name, () -> {
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        return is.readAllBytes();
                    }
                });
            }
        }
    }

    private void processJarFile(final File inputFile, final JarEntryAccess access, final Set<Pattern> excludes) throws IOException {
        log.debug("Processing: {}", inputFile.getName());
        this.iterateFiles(inputFile, (entry, name, content) -> {
            if (excludes.stream().anyMatch(pattern -> pattern.matcher(name).matches())) {
                log.debug("Skipping excluded entry: {}", name);
                return;
            }
            if (this.getMergeServices().get() && this.processService(name, access, content)) {
                log.debug("Merged service entry: {}", name);
                return;
            }
            if (this.getMergeLog4jPlugins().get() && this.processLog4JPlugins(name, access, content)) {
                log.debug("Merged Log4J plugins entry: {}", name);
                return;
            }

            boolean isDuplicate = access.read(name).isPresent();
            if (isDuplicate) {
                switch (this.getDuplicatesStrategy().get()) {
                    case INCLUDE -> {
                        log.debug("Duplicate entry found in {}: {}. Overwriting with new content.", inputFile.getName(), name);
                        access.write(name, content.get());
                    }
                    case EXCLUDE -> log.debug("Duplicate entry found in {}: {}. Skipping this entry.", inputFile.getName(), name);
                    case WARN -> log.warn("Duplicate entry found in {}: {}. Skipping this entry.", inputFile.getName(), name);
                    case FAIL -> throw new IOException("Duplicate entry found in " + inputFile.getName() + ": " + name);
                    default -> throw new UnsupportedOperationException("Unsupported duplicates strategy: " + this.getDuplicatesStrategy().get());
                }
            } else {
                access.write(name, content.get());
            }
        });
    }

    private boolean processService(final String name, final JarEntryAccess access, final JarEntryContentSupplier content) throws IOException {
        if (!name.startsWith("META-INF/services/")) return false;

        byte[] newContent = content.get();
        byte[] existingContent = access.read(name).map(JarEntryContentSupplier::getOrThrow).orElse(null);
        if (existingContent != null) {
            byte[] mergedContent = new byte[existingContent.length + 1 + newContent.length];
            System.arraycopy(existingContent, 0, mergedContent, 0, existingContent.length);
            mergedContent[existingContent.length] = '\n'; // Add a newline to separate entries
            System.arraycopy(newContent, 0, mergedContent, existingContent.length + 1, newContent.length);
            access.write(name, mergedContent);
            return true;
        }
        return false;
    }

    private boolean processLog4JPlugins(final String name, final JarEntryAccess access, final JarEntryContentSupplier content) throws IOException {
        if (!name.equals("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat")) return false;

        byte[] newContent = content.get();
        byte[] existingContent = access.read(name).map(JarEntryContentSupplier::getOrThrow).orElse(null);
        if (existingContent != null) {
            Log4JPluginCache existingCache = Log4JPluginCache.deserialize(existingContent);
            Log4JPluginCache newCache = Log4JPluginCache.deserialize(newContent);
            existingCache.merge(newCache);
            access.write(name, existingCache.serialize());
            return true;
        }
        return false;
    }


    @FunctionalInterface
    private interface JarEntryProcessor {
        void process(final JarEntry entry, final String name, final JarEntryContentSupplier content) throws IOException;
    }

    @FunctionalInterface
    private interface JarEntryContentSupplier {
        byte[] get() throws IOException;

        @SneakyThrows
        default byte[] getOrThrow() {
            return this.get();
        }
    }

    private interface JarEntryAccess {
        void write(final String name, final byte[] content) throws IOException;

        Optional<JarEntryContentSupplier> read(final String name) throws IOException;
    }

}
