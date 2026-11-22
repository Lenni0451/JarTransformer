package net.lenni0451.jartransformer.transformers.impl;

import net.lenni0451.commons.asm.io.ClassIO;
import net.lenni0451.commons.asm.mappings.Remapper;
import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.utils.PackageRemapper;
import net.lenni0451.jartransformer.utils.RemapUtils;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

public abstract class RepackageTransformer extends Transformer {

    @Inject
    public RepackageTransformer(final String name) {
        super(name);
        this.getWhitelist().convention(Set.of());
        this.getBlacklist().convention(Set.of());
        this.getRelocations().convention(Map.of());
        this.getRemapClasses().convention(true);
        this.getMoveFiles().convention(true);
        this.getRemapStrings().convention(false);
        this.getRemapServices().convention(true);
        this.getRemapManifest().convention(true);
        this.getRemoveEmptyDirs().convention(false);
        this.getRemapLog4jPlugins().convention(true);
    }

    @Input
    public abstract SetProperty<String> getWhitelist();

    @Input
    public abstract SetProperty<String> getBlacklist();

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

    @Input
    public abstract Property<Boolean> getRemapLog4jPlugins();

    @Override
    public void transform(Logger log, FileSystem fileSystem) throws Throwable {
        if (!this.getRelocations().get().isEmpty()) {
            if (this.getRemapClasses().get() && !this.getMoveFiles().get()) {
                log.warn("remapClasses is enabled but moveFiles is disabled; remapped classes will keep their old paths and the jar may be broken");
            }
            PackageRemapper remapper = new PackageRemapper(this.getRelocations().get());
            this.remap(log, fileSystem, remapper);
        }
        if (this.getRemoveEmptyDirs().get()) {
            RemapUtils.removeEmptyDirectories(log, fileSystem);
        }
    }

    private void remap(final Logger log, final FileSystem fileSystem, final PackageRemapper remapper) throws IOException {
        Function<String, String> remapFunction = s -> RemapUtils.remap(remapper, s);
        Function<String, String> pathRemap = this.getRemapServices().get() ? RemapUtils.servicePathRemap(remapper::mapUnchecked, remapFunction) : remapper::mapUnchecked;
        this.iterateFiles(fileSystem, path -> {
            try {
                String pathString = path.toString();
                boolean slash = pathString.startsWith("/");
                if (slash) pathString = pathString.substring(1);
                if (!RemapUtils.shouldProcess(pathString, this.getWhitelist().get(), this.getBlacklist().get())) return;
                if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class")) {
                    this.remapClass(log, path, remapper);
                    if (!this.getRemapClasses().get()) return; //Moving an unmapped class breaks its entry path
                } else if (pathString.toLowerCase(Locale.ROOT).startsWith("meta-inf/")) {
                    if (this.getRemapServices().get()) RemapUtils.remapServiceFile(log, path, pathString, remapFunction);
                    if (this.getRemapManifest().get()) RemapUtils.remapManifestFile(log, path, remapFunction);
                    if (this.getRemapLog4jPlugins().get()) RemapUtils.remapLog4jPluginsFile(log, path, pathString, remapFunction);
                }

                if (this.getMoveFiles().get()) {
                    RemapUtils.moveRemappedFile(log, fileSystem, path, pathString, slash, pathRemap);
                }
            } catch (Throwable t) {
                log.error("Failed to remap file: {}", path, t);
                throw t;
            }
        });
    }

    private void remapClass(final Logger log, final Path path, final PackageRemapper remapper) throws IOException {
        if (!this.getRemapClasses().get()) return;

        byte[] bytes = Files.readAllBytes(path);
        ClassNode node = Remapper.remap(ClassIO.fromBytes(bytes), remapper);
        if (this.getRemapStrings().get()) {
            RemapUtils.remapStrings(node, s -> RemapUtils.remap(remapper, s));
        }
        Files.write(path, ClassIO.toStacklessBytes(node));
        log.debug("Remapped class: {}", path);
    }

}
