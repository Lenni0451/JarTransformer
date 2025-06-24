# JarTransformer
Gradle plugin for transforming libraries and (output) jar files.

## Usage
Adding the plugin to your gradle project works like every other plugin.

First, you need to add the plugin repository to your `settings.gradle` file:
```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}
```

Then, you can add the plugin to your `build.gradle` file:
```groovy
plugins {
    id "net.lenni0451.jartransformer" version "x.x.x"
}
```

After that you can use the `jarTransformer` extension to configure the plugin:
```groovy
jarTransformer { // The plugin extension
    jar { // Register a jar transformation
        inputFile = jar.archiveFile //The input jar file to transform
        outputFile = project.layout.buildDirectory.dir("libs").get().file("${project.name}-repackaged.jar") // The output jar file (optional, will be same as input if not set)
        repackage { // Register a transformer
            ... // See the Transformers section for a list of available transformers and their configuration
        }
        ... // Multiple transformers can be registered here
    }
    dependency { // Register a dependency transformation
        configuration = configurations.repackageInclude // The configuration to transform the dependencies of
        repackage { // Register a transformer
            ... // See the Transformers section for a list of available transformers and their configuration
        }
        ... // Multiple transformers can be registered here
    }
    ... // Multiple jar and dependency transformations can be registered here
}
```
It is possible to register multiple jar and dependency transformations and each transformation can have multiple transformers.\
The order of the transformers is the order in which they are registered.

## Transformers
### RepackageTransformer
This transformer can be used to remap packages in the jar file. It supports remapping of classes, strings, services and the manifest.
```groovy
repackage {
    // The relocations to apply
    relocations = [
        // Remap all classes in packages starting with "com.example" to "repackaged.com.example"
        // Example: "com.example.MyClass" -> "repackaged.com.example.MyClass", "com.examplepkg.MyOtherClass" -> "repackaged.com.examplepkg.MyOtherClass"
        "com.example": "repackaged.com.example",
        // Remap all classes in the package "com.example" to "repackaged.com.example"
        // Example: "com.example.MyClass" -> "repackaged.com.example.MyClass", "com.examplepkg.MyOtherClass" -> "com.examplepkg.MyOtherClass"
        "com.example.": "repackaged.com.example.",
    ]

    // Whether to remap classes in the jar file
    // Can be set to false if you only want to remap strings, services or the manifest
    remapClasses = true // Default: true

    // Whether to move files in the jar file to the new package structure
    // Should probably only be set to false if remapClasses is also false
    // Could be useful to remap selected strings after another repackage transformer already moved classes
    moveFiles = true // Default: true

    // Whether to remap strings inside classes
    // This works with strings in dot notation (e.g. "com.example.MyClass") and in slash notation (e.g. "com/example/MyClass")
    // Disabled by default, because it could easily break things
    remapStrings = false // Default: false

    // Whether to remap services in the jar file
    remapServices = true // Default: true

    // Whether to remap the manifest in the jar file
    remapManifest = true // Default: true

    // Whether to remove empty directories in the jar file
    // After remapping classes, some directories might be empty
    // Removing them is not required, but can help to reduce the size of the jar file and make it cleaner
    // This also removes empty directories which are not part of the relocations
    removeEmptyDirs = false // Default: false
}
```

### ExcludeTransformer
This transformer can be used to exclude files from the jar file.
```groovy
exclude {
    // The files to exclude from the jar file
    // A file is removed if its path starts with one of the specified paths
    // The paths are relative to the root of the jar file
    excludes = [
        // Exclude all files in the "META-INF" directory
        "META-INF",
        // Exclude all files in the "com/example" package
        "com/example",
    ]

    // A list of regex patterns to match files against
    // The entire path of the file needs to match the regex pattern
    regexExcludes = [
        // Exclude all files containing "Test" in their name
        ".*Test.*",
        // Exclude all files ending with ".class"
        ".*\\.class$",
    ]

    // Reverse the exclusion logic
    // If set to true, only files that do not match the excludes or regexExcludes will be kept
    reversed = false // Default: false
}
```
