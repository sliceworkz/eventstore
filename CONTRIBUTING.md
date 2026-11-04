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


## Sign-off

Every commit must be signed off (`git commit -s`), certifying the
[Developer Certificate of Origin](DCO): that you wrote the change, or otherwise have the right to
submit it under the project's license (LGPL-3.0). The sign-off is a line at the end of the commit
message, made from your git name and email:

```
Signed-off-by: Jane Developer <jane@example.com>
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

