package net.lenni0451.jartransformer.transformers.impl;

import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.utils.Repackager;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.nio.file.FileSystem;
import java.util.Map;

public abstract class RepackageTransformer implements Transformer {

    @Inject
    public RepackageTransformer(final String name) {
        this.getName().set(name);
        this.getRelocations().convention(Map.of());
        this.getRemapClasses().convention(true);
        this.getMoveFiles().convention(true);
        this.getRemapStrings().convention(false);
        this.getRemapServices().convention(true);
        this.getRemapManifest().convention(true);
        this.getRemoveEmptyDirs().convention(false);
    }

    public abstract Property<String> getName();

    @Input
    public abstract MapProperty<String, String> getRelocations();

    @Input
    public abstract Property<Boolean> getRemapClasses();

    @Input
    public abstract Property<Boolean> getMoveFiles();

    @Input
    public abstract Property<Boolean> getRemapStrings();

    @Input
    public abstract Property<Boolean> getRemapServices();

    @Input
    public abstract Property<Boolean> getRemapManifest();

    @Input
    public abstract Property<Boolean> getRemoveEmptyDirs();

    @Override
    public void transform(Logger log, FileSystem fileSystem) throws Throwable {
        Repackager.builder()
                .logger(log)
                .fileSystem(fileSystem)
                .relocations(this.getRelocations().get())
                .remapClasses(this.getRemapClasses().get())
                .moveFiles(this.getMoveFiles().get())
                .remapStrings(this.getRemapStrings().get())
                .remapServices(this.getRemapServices().get())
                .remapManifest(this.getRemapManifest().get())
                .removeEmptyDirs(this.getRemoveEmptyDirs().get())
                .build().run();
    }

}
