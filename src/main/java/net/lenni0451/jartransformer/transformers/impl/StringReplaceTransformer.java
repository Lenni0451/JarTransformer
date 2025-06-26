package net.lenni0451.jartransformer.transformers.impl;

import net.lenni0451.commons.asm.io.ClassIO;
import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.utils.ASMUtils;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;

public abstract class StringReplaceTransformer extends Transformer {

    @Inject
    public StringReplaceTransformer(final String name) {
        super(name);
    }

    @Input
    public abstract MapProperty<String, Object> getReplacements();

    @Override
    public void transform(Logger log, FileSystem fileSystem) throws Throwable {
        Map<String, Object> replacements = this.getReplacements().get();
        this.iterateFiles(fileSystem, path -> {
            if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class")) {
                byte[] bytes = Files.readAllBytes(path);
                ClassNode node = ClassIO.fromBytes(bytes);
                boolean modified = ASMUtils.mutateStrings(node, s -> {
                    for (Map.Entry<String, Object> entry : replacements.entrySet()) {
                        s = s.replace(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                    return s;
                });
                if (modified) {
                    Files.write(path, ClassIO.toStacklessBytes(node));
                    log.debug("Processed access transformer for class: {}", path);
                }
            }
        });
    }

}
