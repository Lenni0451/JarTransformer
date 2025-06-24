package net.lenni0451.jartransformer.utils;

import org.objectweb.asm.commons.SimpleRemapper;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

public class PackageRemapper extends SimpleRemapper {

    private final Map<String, String> mapping;

    public PackageRemapper(final Map<String, String> mapping) {
        super(Collections.emptyMap()); //The remapper should not remap anything itself
        this.mapping = mapping.entrySet().stream().collect(Collectors.toMap(entry -> entry.getKey().replace('.', '/'), e -> e.getValue().replace('.', '/')));
    }

    @Override
    public String map(String key) {
        if (key.contains(".")) return null; //Ignore fields and methods (class.name)
        return this.mapUnchecked(key);
    }

    public String mapUnchecked(final String key) {
        for (Map.Entry<String, String> entry : this.mapping.entrySet()) {
            if (key.startsWith(entry.getKey())) {
                return entry.getValue() + key.substring(entry.getKey().length());
            }
        }
        return null;
    }

}
