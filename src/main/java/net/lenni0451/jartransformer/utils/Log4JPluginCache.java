package net.lenni0451.jartransformer.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Log4JPluginCache {

    public static Log4JPluginCache deserialize(final byte[] data) throws IOException {
        Log4JPluginCache cache = new Log4JPluginCache();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        int categoryCount = dis.readInt();
        for (int i = 0; i < categoryCount; i++) {
            String category = dis.readUTF();
            Map<String, PluginEntry> plugins = cache.addCategory(category);
            int pluginCount = dis.readInt();
            for (int p = 0; p < pluginCount; p++) {
                plugins.put(
                        dis.readUTF(),
                        new PluginEntry(dis.readUTF(), dis.readUTF(), dis.readBoolean(), dis.readBoolean())
                );
            }
        }
        return cache;
    }


    private final Map<String, Map<String, PluginEntry>> cache = new HashMap<>();

    public Set<String> getCategories() {
        return this.cache.keySet();
    }

    public Map<String, PluginEntry> getCategory(final String category) {
        return this.cache.get(category);
    }

    public Map<String, PluginEntry> addCategory(final String category) {
        return this.cache.computeIfAbsent(category, k -> new HashMap<>());
    }

    public void merge(final Log4JPluginCache other) {
        for (Map.Entry<String, Map<String, PluginEntry>> category : other.cache.entrySet()) {
            if (!this.cache.containsKey(category.getKey())) {
                this.cache.put(category.getKey(), category.getValue());
            } else {
                Map<String, PluginEntry> categoryMap = this.cache.get(category.getKey());
                for (Map.Entry<String, PluginEntry> plugin : category.getValue().entrySet()) {
                    categoryMap.putIfAbsent(plugin.getKey(), plugin.getValue());
                }
            }
        }
    }

    public byte[] serialize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(this.cache.size());
        for (Map.Entry<String, Map<String, PluginEntry>> category : this.cache.entrySet()) {
            dos.writeUTF(category.getKey());
            dos.writeInt(category.getValue().size());
            for (Map.Entry<String, PluginEntry> entry : category.getValue().entrySet()) {
                dos.writeUTF(entry.getKey());
                dos.writeUTF(entry.getValue().getClassName());
                dos.writeUTF(entry.getValue().getName());
                dos.writeBoolean(entry.getValue().isPrintable());
                dos.writeBoolean(entry.getValue().isDefer());
            }
        }
        return baos.toByteArray();
    }


    @Getter
    @Setter
    @AllArgsConstructor
    public static class PluginEntry {
        private String className;
        private String name;
        private boolean printable;
        private boolean defer;
    }

}
