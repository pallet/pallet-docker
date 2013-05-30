# pallet-docker

A provider for [Pallet][palletops], to use [docker][docker].

## Pallet

[Pallet][palletops] is used to provision and maintain servers on cloud and
virtual machine infrastructure, and aims to solve the problem of providing a
consistently configured running image across a range of clouds.  It is designed
for use from the [Clojure][clojure] REPL, from clojure code, and from the
command line.

- reuse configuration in development, testing and production.
- store all your configuration in a source code management system (eg. git),
  including role assignments.
- configuration is re-used by compostion; just create new functions that call
  existing crates with new arguments. No copy and modify required.
- enable use of configuration crates (recipes) from versioned jar files.

[Documentation][docs] is available.

## Installation

Pallet-docker is distributed as a jar, and is available in the
[clojars repository][clojars].

Installation is with leiningen or your favourite maven repository aware build
tool.

### lein project.clj

```clojure
:dependencies [[com.palletops/pallet "0.8.0-SNAPSHOT"]
               [com.palletops/pallet-docker "0.8.0-SNAPSHOT"]]
```

### maven pom.xml

```xml
<dependencies>
  <dependency>
    <groupId>com.palletops</groupId>
    <artifactId>pallet</artifactId>
    <version>0.8.0-SNAPSHOT</version>
  </dependency>
  <dependency>
    <groupId>com.palletops</groupId>
    <artifactId>pallet-docker</artifactId>
    <version>0.8.0-SNAPSHOT</version>
  </dependency>
<dependencies>

<repositories>
  <repository>
    <id>clojars</id>
    <url>http://clojars.org/repo</url>
  </repository>
</repositories>
```

## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)

Copyright 2013 Hugo Duncan.

[palletops]: http://palletops.com "Pallet site"
[docs]: http://palletops.com/doc "Pallet Documentation"
[ml]: http://groups.google.com/group/pallet-clj "Pallet mailing list"
[clojure]: http://clojure.org "Clojure"
[docker]: http://docker.io/ DOCKER
