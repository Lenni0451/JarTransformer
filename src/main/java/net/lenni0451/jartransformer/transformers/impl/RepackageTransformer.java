package net.lenni0451.jartransformer.transformers.impl;

import net.lenni0451.commons.asm.io.ClassIO;
import net.lenni0451.commons.asm.mappings.Remapper;
import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.utils.ASMUtils;
import net.lenni0451.jartransformer.utils.Log4JPluginCache;
import net.lenni0451.jartransformer.utils.PackageRemapper;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

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
            PackageRemapper remapper = new PackageRemapper(this.getRelocations().get());
            this.remap(log, fileSystem, remapper);
        }
        if (this.getRemoveEmptyDirs().get()) {
            this.removeEmptyDirectories(log, fileSystem);
        }
    }

    private void remap(final Logger log, final FileSystem fileSystem, final PackageRemapper remapper) throws IOException {
        this.iterateFiles(fileSystem, path -> {
            try {
                String pathString = path.toString();
                boolean slash = pathString.startsWith("/");
                if (slash) pathString = pathString.substring(1);
                if (!this.shouldProcess(pathString)) return;
                if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class")) {
                    this.remapClass(log, path, remapper);
                } else if (pathString.toLowerCase(Locale.ROOT).startsWith("meta-inf/")) {
                    this.remapServices(log, fileSystem, path, pathString, slash, remapper);
                    this.remapManifest(log, path, remapper);
                    this.remapLog4jPlugins(log, path, pathString, remapper);
                }

                if (this.getMoveFiles().get()) {
                    if (pathString.toLowerCase(Locale.ROOT).startsWith("meta-inf/versions/")) {
                        String prefix = pathString.substring(0, pathString.indexOf('/', 18) + 1);
                        String suffix = pathString.substring(pathString.indexOf('/', 18) + 1);
                        String remappedSuffix = remapper.mapUnchecked(suffix);
                        if (remappedSuffix != null && !suffix.equals(remappedSuffix)) {
                            Path newPath = fileSystem.getPath((slash ? "/" : "") + prefix + remappedSuffix);
                            Files.createDirectories(newPath.getParent());
                            Files.move(path, newPath);
                            log.debug("Remapped versioned file: {} -> {}", path, newPath);
                        }
                    } else {
                        String remappedPath = remapper.mapUnchecked(pathString);
                        if (remappedPath != null && !pathString.equals(remappedPath)) {
                            Path newPath = fileSystem.getPath((slash ? "/" : "") + remappedPath);
                            Files.createDirectories(newPath.getParent());
                            Files.move(path, newPath);
                            log.debug("Remapped file: {} -> {}", path, newPath);
                        }
                    }
                }
            } catch (Throwable t) {
                log.error("Failed to remap file: {}", path, t);
                throw t;
            }
        });
    }

    private boolean shouldProcess(final String pathString) {
        if (this.getWhitelist().get().isEmpty() && this.getBlacklist().get().isEmpty()) return true;
        if (!this.getBlacklist().get().isEmpty()) {
            for (String s : this.getBlacklist().get()) {
                if (pathString.startsWith(s)) {
                    return false; //If any blacklist entry matches, don't process
                }
            }
        }
        if (!this.getWhitelist().get().isEmpty()) {
            for (String s : this.getWhitelist().get()) {
                if (pathString.startsWith(s)) {
                    return true;
                }
            }
            return false; //If no whitelist entry matches, don't process
        }
        return true; //If the whitelist is empty, and it's not blacklisted, process the file
    }

    private void remapClass(final Logger log, final Path path, final PackageRemapper remapper) throws IOException {
        if (!this.getRemapClasses().get()) return;

        byte[] bytes = Files.readAllBytes(path);
        ClassNode node = Remapper.remap(ClassIO.fromBytes(bytes), remapper);
        if (this.getRemapStrings().get()) {
            ASMUtils.mutateStrings(node, s -> ASMUtils.remap(remapper, s));
        }
        Files.write(path, ClassIO.toStacklessBytes(node));
        log.debug("Remapped class: {}", path);
    }

    private void remapServices(final Logger log, final FileSystem fileSystem, final Path path, final String pathString, final boolean slash, final PackageRemapper remapper) throws IOException {
        if (!this.getRemapServices().get()) return;
        if (!pathString.toLowerCase(Locale.ROOT).startsWith("meta-inf/services/")) return;

        String serviceName = path.toString().substring(19);
        String remappedServiceName = ASMUtils.remap(remapper, serviceName);
        String[] serviceImpls = Files.readAllLines(path, StandardCharsets.UTF_8).toArray(new String[0]);
        String[] remappedServiceImpls = new String[serviceImpls.length];
        boolean modified = false;
        for (int i = 0; i < serviceImpls.length; i++) {
            String serviceImpl = serviceImpls[i];
            String remappedServiceImpl = ASMUtils.remap(remapper, serviceImpl);
            if (serviceImpl.startsWith("#") || remappedServiceImpl == null) {
                remappedServiceImpls[i] = serviceImpl;
            } else {
                remappedServiceImpls[i] = remappedServiceImpl;
                modified |= !serviceImpl.equals(remappedServiceImpl);
            }
        }
        if (modified) {
            Files.writeString(path, String.join("\n", remappedServiceImpls));
            log.info("Remapped service implementations: {} -> {}", String.join(", ", serviceImpls), String.join(", ", remappedServiceImpls));
        }
        if (remappedServiceName != null && !serviceName.equals(remappedServiceName)) {
            Path newPath = fileSystem.getPath((slash ? "/" : "") + "META-INF/services/" + remappedServiceName);
            Files.move(path, newPath);
            log.info("Remapped service name: {} -> {}", serviceName, remappedServiceName);
        }
    }

    private void remapManifest(final Logger log, final Path path, final PackageRemapper remapper) throws IOException {
        if (!this.getRemapManifest().get()) return;
        if (!path.getFileName().toString().equalsIgnoreCase("manifest.mf")) return;

        Manifest manifest = new Manifest(Files.newInputStream(path));
        List<Attributes> allAttributes = new ArrayList<>();
        allAttributes.add(manifest.getMainAttributes());
        allAttributes.addAll(manifest.getEntries().values());

        boolean modified = false;
        for (Attributes attributes : allAttributes) {
            for (Map.Entry<Object, Object> entry : attributes.entrySet()) {
                String value = entry.getValue().toString();
                String remappedValue = ASMUtils.remap(remapper, value);
                if (remappedValue != null && !value.equals(remappedValue)) {
                    entry.setValue(remappedValue);
                    modified = true;
                }
            }
        }

        if (modified) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            manifest.write(baos);
            Files.write(path, baos.toByteArray());
            log.info("Remapped manifest: {}", path);
        }
    }

    private void remapLog4jPlugins(final Logger log, final Path path, final String pathString, final PackageRemapper remapper) throws IOException {
        if (!this.getRemapLog4jPlugins().get()) return;
        if (!pathString.toLowerCase(Locale.ROOT).equals("meta-inf/org/apache/logging/log4j/core/config/plugins/log4j2plugins.dat")) return;

        Log4JPluginCache pluginCache = Log4JPluginCache.deserialize(Files.readAllBytes(path));
        boolean modified = false;
        for (String category : pluginCache.getCategories()) {
            Map<String, Log4JPluginCache.PluginEntry> plugins = pluginCache.getCategory(category);
            for (Log4JPluginCache.PluginEntry plugin : plugins.values()) {
                String className = plugin.getClassName();
                String remappedName = ASMUtils.remap(remapper, className);
                if (remappedName != null && !className.equals(remappedName)) {
                    plugin.setClassName(remappedName);
                    modified = true;
                    log.debug("Remapped Log4J plugin class: {} -> {}", className, remappedName);
                }
            }
        }
        if (modified) {
            Files.write(path, pluginCache.serialize());
            log.info("Remapped Log4J plugins: {}", path);
        }
    }

    private void removeEmptyDirectories(final Logger log, final FileSystem fileSystem) throws IOException {
        try (Stream<Path> paths = Files.walk(fileSystem.getPath("/"))) {
            paths
                    .filter(Files::isDirectory)
                    .sorted((p1, p2) -> p2.toString().length() - p1.toString().length())
                    .forEach(path -> {
                        try {
                            try (Stream<Path> dirStream = Files.list(path)) {
                                if (dirStream.anyMatch(p -> true)) return;
                                Files.delete(path);
                                log.debug("Removed empty directory: {}", path);
                            }
                        } catch (Throwable t) {
                            throw new IllegalStateException("Failed to remove empty directory: " + path, t);
                        }
                    });
        }
    }

}
