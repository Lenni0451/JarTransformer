package net.lenni0451.jartransformer.transformers;

import org.gradle.api.Project;

import java.util.List;

public interface SpecializedTransformer<T extends SpecializedTransformer<T>> {

    /**
     * Apply the specialized transformer actions to the given project and list of transformers.<br>
     * This method is only called for the first registered transformer of each type.<br>
     * The code in this method should be independent of the transformer instance to prevent issues.
     *
     * @param project      The current project
     * @param transformers The list of registered transformers of this type
     */
    void applySpecialized(final Project project, final List<T> transformers);

}
