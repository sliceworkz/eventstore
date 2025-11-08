# Contributing

## Building

Basic build: 

```bash
mvn package
```

Or with inclusion of javadoc and sources generation:

```bash
mvn install
```


## License Headers

This project uses LGPL-3.0. All Java source files must include the license header.

After creating or modifying Java files, run:
```bash
mvn license:format
```

To check if headers are present:
```bash
mvn license:check
```

