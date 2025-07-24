package net.lenni0451.jartransformer.extensions;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Optional;

import javax.inject.Inject;
import java.util.Set;

public abstract class JarMergerExtension {

    @Inject
    public JarMergerExtension(final Project project) {
        this.getConfiguration().convention(project.getConfigurations().getByName("runtimeClasspath"));
        this.getDestinationDirectory().convention(project.getLayout().getBuildDirectory().dir("libs"));
        this.getDuplicatesStrategy().convention(DuplicatesStrategy.WARN);
        this.getExcludes().convention(Set.of("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class"));
        this.getMergeServices().convention(true);
        this.getMergeLog4jPlugins().convention(true);
    }

    public abstract Property<Configuration> getConfiguration();

    public abstract DirectoryProperty getDestinationDirectory();

    @Optional
    public abstract Property<String> getFileName();

    public abstract Property<DuplicatesStrategy> getDuplicatesStrategy();

    public abstract SetProperty<String> getExcludes();

    public abstract Property<Boolean> getMergeServices();

    public abstract Property<Boolean> getMergeLog4jPlugins();

}
