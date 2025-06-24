package net.lenni0451.jartransformer.transformers.impl;

import lombok.extern.slf4j.Slf4j;
import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.utils.DeletingFileVisitor;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
public abstract class ExcludeTransformer extends Transformer {

    @Inject
    public ExcludeTransformer(final String name) {
        super(name);
        this.getExcludes().convention(Set.of());
        this.getReversed().convention(false);
    }

    @Input
    public abstract SetProperty<String> getExcludes();

    @Input
    public abstract SetProperty<String> getRegexExcludes();

    @Input
    public abstract Property<Boolean> getReversed();

    @Override
    public void transform(Logger log, FileSystem fileSystem) throws Throwable {
        Set<String> excludes = this.getExcludes().get();
        Set<String> regexExcludes = this.getRegexExcludes().get();
        boolean reversed = this.getReversed().get();

        List<Path> toRemove;
        try (Stream<Path> paths = Files.walk(fileSystem.getPath("/"))) {
            toRemove = paths.filter(path -> {
                String pathString = path.toString().toLowerCase(Locale.ROOT);
                if (pathString.startsWith("/")) pathString = pathString.substring(1);
                for (String s : excludes) {
                    if (pathString.startsWith(s.toLowerCase(Locale.ROOT))) {
                        return !reversed;
                    }
                }
                for (String s : regexExcludes) {
                    if (pathString.matches(s)) {
                        return !reversed;
                    }
                }
                return reversed;
            }).toList();
        }
        for (Path path : toRemove) {
            try {
                if (!Files.exists(path)) continue;

                Files.walkFileTree(path, new DeletingFileVisitor());
                log.debug("Removed file: {}", path);
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to remove file: " + path, t);
            }
        }
    }

}
