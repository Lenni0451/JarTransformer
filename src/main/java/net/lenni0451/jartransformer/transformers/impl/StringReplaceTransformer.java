package net.lenni0451.jartransformer.transformers.impl;

import net.lenni0451.commons.asm.io.ClassIO;
import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.utils.ASMUtils;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public abstract class StringReplaceTransformer extends Transformer {

    @Inject
    public StringReplaceTransformer(final String name) {
        super(name);
        this.getFileExtensions().convention(List.of(".class"));
    }

    @Input
    public abstract ListProperty<String> getFileExtensions();

    @Input
    public abstract MapProperty<String, Object> getReplacements();

    @Input
    public abstract MapProperty<String, Object> getRegexReplacements();

    @Override
    public void transform(Logger log, FileSystem fileSystem) throws Throwable {
        List<String> extensions = this.getFileExtensions().get();
        Map<String, Object> replacements = this.getReplacements().get();
        Map<String, Object> regexReplacements = this.getRegexReplacements().get();
        this.iterateFiles(fileSystem, path -> {
            byte[] modifiedBytes = null;
            for (String extension : extensions) {
                if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT))) {
                    byte[] bytes = Files.readAllBytes(path);
                    if (extension.equalsIgnoreCase(".class")) {
                        modifiedBytes = this.transformClass(bytes, replacements, regexReplacements);
                    } else {
                        modifiedBytes = this.transformText(bytes, replacements, regexReplacements);
                    }
                    break;
                }
            }
            if (modifiedBytes != null) {
                Files.write(path, modifiedBytes);
                log.debug("Processed string replace transformer for class: {}", path);
            }
        });
    }

    private byte[] transformClass(final byte[] bytes, final Map<String, Object> replacements, final Map<String, Object> regexReplacements) {
        ClassNode node = ClassIO.fromBytes(bytes);
        boolean modified = ASMUtils.mutateStrings(node, s -> {
            for (Map.Entry<String, Object> entry : replacements.entrySet()) {
                s = s.replace(entry.getKey(), String.valueOf(entry.getValue()));
            }
            for (Map.Entry<String, Object> entry : regexReplacements.entrySet()) {
                s = s.replaceAll(entry.getKey(), String.valueOf(entry.getValue()));
            }
            return s;
        });
        if (!modified) return null;
        return ClassIO.toStacklessBytes(node);
    }

    private byte[] transformText(final byte[] bytes, final Map<String, Object> replacements, final Map<String, Object> regexReplacements) {
        String content = new String(bytes);
        String newContent = content;
        for (Map.Entry<String, Object> entry : replacements.entrySet()) {
            newContent = newContent.replace(entry.getKey(), String.valueOf(entry.getValue()));
        }
        for (Map.Entry<String, Object> entry : regexReplacements.entrySet()) {
            newContent = newContent.replaceAll(entry.getKey(), String.valueOf(entry.getValue()));
        }
        if (newContent.equals(content)) return null;
        return newContent.getBytes();
    }

}
