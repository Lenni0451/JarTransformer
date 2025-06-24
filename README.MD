# JarTransformer
Gradle plugin for transforming libraries and (output) jar files.

## Usage
Adding the plugin to your gradle project works like every other plugin:
```groovy
plugins {
    id "net.lenni0451.jartransformer" version "x.x.x"
}
```

After that you can use the `jarTransformer` extension to configure the plugin:
```groovy
jarTransformer { //The plugin extension
    jar { //Register a jar transformation
        inputFile = jar.archiveFile //The input jar file to transform
        outputFile = project.layout.buildDirectory.dir("libs").get().file("${project.name}-repackaged.jar") //The output jar file (optional, will be same as input if not set)
        repackage { //Register a repackage transformer
            relocations = [ //The relocations to apply
                    "net.raphimc": "repackaged.net.raphimc"
            ]
            removeEmptyDirs = true //Remove empty directories after the transformation
        }
    }
    dependency { //Register a dependency transformation
        configuration = configurations.repackageInclude //The configuration to transform the dependencies of
        repackage { //Register a repackage transformer
            relocations = [ //The relocations to apply
                    "net.lenni0451": "repackaged.net.lenni0451"
            ]
            removeEmptyDirs = true //Remove empty directories after the transformation
        }
    }
}
```
It is possible to register multiple jar and dependency transformations and each transformation can have multiple transformers.\
The order of the transformers is the order in which they are registered.
