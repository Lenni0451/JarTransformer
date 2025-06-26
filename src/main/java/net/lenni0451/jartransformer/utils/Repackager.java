package net.lenni0451.jartransformer.utils;

import lombok.AllArgsConstructor;
import lombok.Builder;
import net.lenni0451.commons.asm.io.ClassIO;
import net.lenni0451.commons.asm.mappings.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

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

@Builder
@AllArgsConstructor
public class Repackager {

    private final Logger logger;
    private final FileSystem fileSystem;
    private final Set<String> whitelist;
    private final Set<String> blacklist;
    private final Map<String, String> relocations;
    private final boolean remapClasses;
    private final boolean moveFiles;
    private final boolean remapStrings;
    private final boolean remapServices;
    private final boolean remapManifest;
    private final boolean removeEmptyDirs;
    private final boolean remapLog4jPlugins;

    public void run() throws Throwable {
        PackageRemapper remapper = new PackageRemapper(this.relocations);
        if (!this.relocations.isEmpty()) this.remap(this.fileSystem, remapper);
        if (this.removeEmptyDirs) this.removeEmptyDirectories(this.fileSystem);
    }

    private void remap(final FileSystem fileSystem, final PackageRemapper remapper) throws IOException {
        try (Stream<Path> paths = Files.walk(fileSystem.getPath("/"))) {
            paths.forEach(path -> {
                try {
                    if (!Files.isRegularFile(path)) return;

                    String pathString = path.toString();
                    boolean slash = pathString.startsWith("/");
                    if (slash) pathString = pathString.substring(1);
                    if (!this.shouldProcess(pathString)) return;
                    if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class")) {
                        if (this.remapClasses) {
                            byte[] bytes = Files.readAllBytes(path);
                            ClassNode node = Remapper.remap(ClassIO.fromBytes(bytes), remapper);
                            if (this.remapStrings) {
                                ASMUtils.mutateStrings(node, s -> ASMUtils.remap(remapper, s));
                            }
                            Files.write(path, ClassIO.toStacklessBytes(node));
                            this.logger.debug("Remapped class: {}", path);
                        }
                    } else if (pathString.toLowerCase(Locale.ROOT).startsWith("meta-inf/")) {
                        if (this.remapServices && pathString.toLowerCase(Locale.ROOT).startsWith("meta-inf/services/")) {
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
                                this.logger.info("Remapped service implementations: {} -> {}", String.join(", ", serviceImpls), String.join(", ", remappedServiceImpls));
                            }
                            if (remappedServiceName != null && !serviceName.equals(remappedServiceName)) {
                                Path newPath = fileSystem.getPath((slash ? "/" : "") + "META-INF/services/" + remappedServiceName);
                                Files.move(path, newPath);
                                this.logger.info("Remapped service name: {} -> {}", serviceName, remappedServiceName);
                            }
                        } else if (this.remapManifest && path.getFileName().toString().equalsIgnoreCase("manifest.mf")) {
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
                                this.logger.info("Remapped manifest: {}", path);
                            }
                        } else if (this.remapLog4jPlugins && pathString.toLowerCase(Locale.ROOT).equals("meta-inf/org/apache/logging/log4j/core/config/plugins/log4j2plugins.dat")) {
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
                                        this.logger.debug("Remapped Log4J plugin class: {} -> {}", className, remappedName);
                                    }
                                }
                            }
                            if (modified) {
                                Files.write(path, pluginCache.serialize());
                                this.logger.info("Remapped Log4J plugins: {}", path);
                            }
                        }
                    }

                    if (this.moveFiles) {
                        if (pathString.toLowerCase(Locale.ROOT).startsWith("meta-inf/versions/")) {
                            String prefix = pathString.substring(0, pathString.indexOf('/', 18) + 1);
                            String suffix = pathString.substring(pathString.indexOf('/', 18) + 1);
                            String remappedSuffix = remapper.mapUnchecked(suffix);
                            if (remappedSuffix != null && !suffix.equals(remappedSuffix)) {
                                Path newPath = fileSystem.getPath((slash ? "/" : "") + prefix + remappedSuffix);
                                Files.createDirectories(newPath.getParent());
                                Files.move(path, newPath);
                                this.logger.debug("Remapped versioned file: {} -> {}", path, newPath);
                            }
                        } else {
                            String remappedPath = remapper.mapUnchecked(pathString);
                            if (remappedPath != null && !pathString.equals(remappedPath)) {
                                Path newPath = fileSystem.getPath((slash ? "/" : "") + remappedPath);
                                Files.createDirectories(newPath.getParent());
                                Files.move(path, newPath);
                                this.logger.debug("Remapped file: {} -> {}", path, newPath);
                            }
                        }
                    }
                } catch (Throwable t) {
                    throw new IllegalStateException("Failed to remap file: " + path, t);
                }
            });
        }
    }

    private boolean shouldProcess(final String pathString) {
        if (this.whitelist.isEmpty() && this.blacklist.isEmpty()) return true;
        if (!this.blacklist.isEmpty()) {
            for (String s : this.blacklist) {
                if (pathString.startsWith(s)) {
                    return false; //If any blacklist entry matches, don't process
                }
            }
        }
        if (!this.whitelist.isEmpty()) {
            for (String s : this.whitelist) {
                if (pathString.startsWith(s)) {
                    return true;
                }
            }
            return false; //If no whitelist entry matches, don't process
        }
        return true; //If the whitelist is empty, and it's not blacklisted, process the file
    }

    private void removeEmptyDirectories(final FileSystem fileSystem) throws IOException {
        try (Stream<Path> paths = Files.walk(fileSystem.getPath("/"))) {
            paths.sorted((p1, p2) -> p2.toString().length() - p1.toString().length()).forEach(path -> {
                try {
                    if (!Files.isDirectory(path)) return;
                    try (Stream<Path> dirStream = Files.list(path)) {
                        if (dirStream.anyMatch(p -> true)) return;
                        Files.delete(path);
                        this.logger.debug("Removed empty directory: {}", path);
                    }
                } catch (Throwable t) {
                    throw new IllegalStateException("Failed to remove empty directory: " + path, t);
                }
            });
        }
    }

}
